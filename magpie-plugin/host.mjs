import { randomBytes, randomUUID, createPublicKey, timingSafeEqual, verify } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { lstatSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { hostname } from 'node:os';
import { dirname, join } from 'node:path';
import { exportPublicKey, exportSecretKey, generateKeyPair, importPublicKey, importSecretKey } from '@getpaseo/relay/e2ee';
import { connectRelay } from './relay.mjs';
import { deviceId, encodePairing, isBase64url, localRelay, proofBytes, version } from './protocol.mjs';

export function writePrivateJson(path, value) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  try {
    writeFileSync(temporary, JSON.stringify(value), { mode: 0o600, flag: 'wx' });
    renameSync(temporary, path);
  } finally {
    try { unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}

export function privateDirectory(path) {
  mkdirSync(path, { recursive: true, mode: 0o700 });
  const directory = lstatSync(path);
  if (!directory.isDirectory() || directory.uid !== process.getuid() || (directory.mode & 0o077) !== 0) {
    throw new Error('Phone service directory must be private');
  }
}

function readState(path) {
  try {
    const state = JSON.parse(readFileSync(path, 'utf8'));
    if (state.version !== version || !Array.isArray(state.devices)) throw new Error('Invalid vault');
    return state;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const keys = generateKeyPair();
    const state = {
      version,
      serverId: randomUUID(),
      daemonPublicKeyB64: exportPublicKey(keys.publicKey),
      daemonSecretKeyB64: exportSecretKey(keys.secretKey),
      devices: [],
    };
    writePrivateJson(path, state);
    return state;
  }
}

function displayName() {
  try {
    const name = execFileSync('/usr/sbin/scutil', ['--get', 'ComputerName'], {
      encoding: 'utf8', timeout: 1500, stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
    return name || hostname();
  } catch {
    return hostname();
  }
}

function freshToken() { return randomBytes(32).toString('base64url'); }

function validLabel(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 64 && !/[\p{Cc}\p{Cf}]/u.test(value);
}

function matchesTicket(candidate, current) {
  return current && Date.now() < current.expiresAt && isBase64url(candidate, 32) &&
    timingSafeEqual(Buffer.from(candidate), Buffer.from(current.ticket));
}

export function startHost(directory, socketPath) {
  privateDirectory(directory);
  const statePath = join(directory, 'devices.json');
  let state;
  let relay;
  let offer;
  let currentPairing;

  function saveDevices(devices) {
    const next = { ...state, devices };
    writePrivateJson(statePath, next);
    state = next;
  }

  function openSession() {
    const nonce = freshToken();
    let channel;
    let authDeadline;
    let grant;
    let attempted = false;

    function send(message) {
      void channel.send(JSON.stringify({ v: version, ...message })).catch(() => channel.close());
    }

    function authorize(request) {
      if (attempted) return { error: 'challenge_used' };
      attempted = true;
      const { type, publicKey, signature, ticket, label } = request;
      const enrolling = type === 'device.pair';
      if (!isBase64url(publicKey, 32) || !isBase64url(signature, 64)) return { error: 'invalid_proof' };
      if (enrolling && !matchesTicket(ticket, currentPairing)) return { error: 'invalid_pairing' };

      const key = createPublicKey({ key: { kty: 'OKP', crv: 'Ed25519', x: publicKey }, format: 'jwk' });
      if (!verify(null, proofBytes(offer.serverId, nonce, type, publicKey, enrolling ? ticket : ''),
        key, Buffer.from(signature, 'base64url'))) return { error: 'invalid_proof' };

      const id = deviceId(publicKey);
      const existing = state.devices.find((device) => device.id === id);
      if (enrolling) {
        if (!validLabel(label)) return { error: 'invalid_label' };
        if (!existing && state.devices.length >= 32) return { error: 'device_limit' };
        grant = { id, label, pairedAt: Date.now() };
        saveDevices([...state.devices.filter((device) => device.id !== id), grant]);
        currentPairing = undefined;
      } else if (!existing) {
        return { error: 'device_not_authorized' };
      } else {
        grant = existing;
      }
      clearTimeout(authDeadline);
      return { deviceId: id };
    }

    return {
      attach(peer) {
        channel = peer;
        authDeadline = setTimeout(() => peer.close(1008, 'Authorization timed out'), 15000);
        peer.onClose(() => clearTimeout(authDeadline));
        send({ type: 'challenge', serverId: offer.serverId, nonce });
      },
      receive(raw) {
        if (!channel || typeof raw !== 'string') return;
        let request;
        try { request = JSON.parse(raw); } catch { return; }
        if (request?.v !== version || typeof request.id !== 'string' || !/^[a-zA-Z0-9_-]{1,64}$/.test(request.id)) return;
        let result;
        try {
          switch (request.type) {
            case 'device.pair':
            case 'device.auth':
              result = authorize(request);
              break;
            case 'host.get':
              // A new pairing creates a new grant, including for a previously revoked key.
              result = grant && state.devices.includes(grant)
                ? { displayName: displayName(), hostId: offer.serverId, receipt: randomUUID() }
                : { error: 'device_not_authorized' };
              break;
            default:
              result = { error: 'unsupported_request' };
          }
        } catch {
          result = { error: 'operation_failed' };
        }
        send({ type: 'result', id: request.id, ...result });
      },
    };
  }

  const server = createServer(async (req, res) => {
    try {
      const request = await readJson(req);
      let result;
      switch (request.action) {
        case 'status':
          result = {
            status: relay?.isOnline() ? 'online' : 'connecting',
            devices: state.devices.map(({ id, label, pairedAt }) => ({ id, label, pairedAt })),
            pairingExpiresAt: currentPairing?.expiresAt,
          };
          break;
        case 'issue':
          if (!relay.isOnline()) { result = { error: 'relay_offline' }; break; }
          currentPairing = { ticket: freshToken(), expiresAt: Date.now() + 120000 };
          result = { link: encodePairing(offer, currentPairing.ticket), expiresAt: currentPairing.expiresAt };
          break;
        case 'cancel':
          currentPairing = undefined;
          result = { ok: true };
          break;
        case 'revoke': {
          const remaining = state.devices.filter(({ id }) => id !== request.id);
          if (remaining.length !== state.devices.length) saveDevices(remaining);
          result = { ok: true };
          break;
        }
        default:
          result = { error: 'unknown_action' };
      }
      sendJson(res, result);
    } catch {
      sendJson(res, { error: 'operation_failed' }, 400);
    }
  });
  // The macOS job owns this socket. The directory is private to this user.
  privateDirectory(dirname(socketPath));
  try { unlinkSync(socketPath); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  server.listen(socketPath, () => {
    state = readState(statePath);
    const { serverId, daemonPublicKeyB64, daemonSecretKeyB64 } = state;
    offer = { v: 2, serverId, daemonPublicKeyB64, relay: localRelay, minimumSecurityMode: 'protected' };
    relay = connectRelay(offer, {
      publicKey: importPublicKey(daemonPublicKeyB64),
      secretKey: importSecretKey(daemonSecretKeyB64),
    }, openSession);
  });
}

export async function readJson(req) {
  let length = 0;
  const chunks = [];
  for await (const chunk of req) {
    length += chunk.length;
    if (length > 4096) throw new Error('Request too large');
    chunks.push(chunk);
  }
  const value = JSON.parse(Buffer.concat(chunks));
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid request');
  return value;
}

export function sendJson(res, result, status = 200) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(result));
}
