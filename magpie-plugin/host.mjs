import { randomBytes, randomUUID, createPublicKey, timingSafeEqual, verify } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { lstatSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { hostname } from 'node:os';
import { dirname, join } from 'node:path';
import { exportPublicKey, exportSecretKey, generateKeyPair, importPublicKey, importSecretKey } from '@getpaseo/relay/e2ee';
import { connectRelay } from './relay.mjs';
import { deviceId, encodePairing, isBase64url, localRelay, proofBytes, version } from './protocol.mjs';
import { gatewayUsageSource, projectUsage, usageGatewayFile, usageQuery } from './usage.mjs';

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

export function startHost(directory, socketPath, options = {}) {
  privateDirectory(directory);
  const statePath = join(directory, 'devices.json');
  const readUsage = options.usageSource ?? gatewayUsageSource(join(directory, usageGatewayFile));
  let state;
  let relay;
  let offer;
  let currentPairing;
  // A replacement grant for the same key starts with its own live sessions.
  const connectedGrants = new WeakMap();

  function saveState(patch) {
    const next = { ...state, ...patch };
    writePrivateJson(statePath, next);
    state = next;
  }

  const usagePermission = (id) => state.usagePermissions?.[id];

  function openSession() {
    const nonce = freshToken();
    let channel;
    let authDeadline;
    let grant;
    let attempted = false;

    function send(message) {
      void channel.send(JSON.stringify({ v: version, ...message })).catch(() => channel.close());
    }

    const authorized = () => Boolean(grant && state.devices.includes(grant));

    async function readUsageResult(query, id) {
      try {
        const usage = projectUsage(await readUsage(query), query, id);
        return usage ? { usage } : { error: 'usage_unavailable' };
      } catch {
        return { error: 'usage_unavailable' };
      }
    }

    function handleUsage(request) {
      const reply = (result) => send({ type: 'result', id: request.id, ...result });
      if (!authorized()) return reply({ error: 'device_not_authorized' });
      const permission = usagePermission(grant.id);
      if (!permission) return reply({ error: 'usage_permission_denied' });
      const query = usageQuery(request);
      if (!query) return reply({ error: 'invalid_request' });
      void readUsageResult(query, request.id).then((result) => {
        if (!authorized()) return reply({ error: 'device_not_authorized' });
        if (usagePermission(grant.id) !== permission) return reply({ error: 'usage_permission_denied' });
        reply(result);
      });
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
        const devices = [...state.devices.filter((device) => device.id !== id), grant];
        if (!existing && currentPairing.allowUsage) {
          saveState({ devices, usagePermissions: { ...state.usagePermissions, [id]: randomUUID() } });
        } else {
          saveState({ devices });
        }
        currentPairing = undefined;
      } else if (!existing) {
        return { error: 'device_not_authorized' };
      } else {
        grant = existing;
      }
      clearTimeout(authDeadline);
      connectedGrants.set(grant, (connectedGrants.get(grant) ?? 0) + 1);
      return { deviceId: id };
    }

    return {
      attach(peer) {
        channel = peer;
        authDeadline = setTimeout(() => peer.close(1008, 'Authorization timed out'), 15000);
        peer.onClose(() => {
          clearTimeout(authDeadline);
          const sessions = connectedGrants.get(grant);
          if (sessions === 1) connectedGrants.delete(grant);
          if (sessions > 1) connectedGrants.set(grant, sessions - 1);
        });
        send({ type: 'challenge', serverId: offer.serverId, nonce });
      },
      receive(raw) {
        if (!channel || typeof raw !== 'string') return;
        let request;
        try { request = JSON.parse(raw); } catch { return; }
        if (request?.v !== version || typeof request.id !== 'string' || !/^[a-zA-Z0-9_-]{1,64}$/.test(request.id)) return;
        if (request.type === 'usage.summary' || request.type === 'usage.requests') {
          handleUsage(request);
          return;
        }
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
        case 'status': {
          const relayOnline = relay?.isOnline() ?? false;
          result = {
            status: relayOnline ? 'online' : 'connecting',
            devices: state.devices.map((device) => ({
              id: device.id, label: device.label, pairedAt: device.pairedAt,
              allowUsage: Boolean(usagePermission(device.id)),
              connected: relayOnline && connectedGrants.has(device),
            })),
            pairingExpiresAt: currentPairing?.expiresAt,
          };
          break;
        }
        case 'usage.set': {
          const { id, allowUsage } = request;
          if (!state.devices.some((device) => device.id === id)) { result = { error: 'device_not_authorized' }; break; }
          if (typeof allowUsage !== 'boolean') { result = { error: 'invalid_request' }; break; }
          if (allowUsage !== Boolean(usagePermission(id))) {
            // A fresh permission identity invalidates reads that began before a disable/re-enable.
            const usagePermissions = { ...state.usagePermissions };
            if (allowUsage) usagePermissions[id] = randomUUID();
            else delete usagePermissions[id];
            saveState({ usagePermissions });
          }
          result = { ok: true };
          break;
        }
        case 'issue':
          if (!relay.isOnline()) { result = { error: 'relay_offline' }; break; }
          currentPairing = { ticket: freshToken(), expiresAt: Date.now() + 120000, allowUsage: request.allowUsage === true };
          result = { link: encodePairing(offer, currentPairing.ticket), expiresAt: currentPairing.expiresAt };
          break;
        case 'cancel':
          currentPairing = undefined;
          result = { ok: true };
          break;
        case 'revoke': {
          const remaining = state.devices.filter(({ id }) => id !== request.id);
          if (remaining.length !== state.devices.length) {
            const usagePermissions = { ...state.usagePermissions };
            delete usagePermissions[request.id];
            saveState({ devices: remaining, usagePermissions });
          }
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
    offer = { v: 2, serverId, daemonPublicKeyB64, relay: options.relay ?? localRelay, minimumSecurityMode: 'protected' };
    relay = connectRelay(offer, {
      publicKey: importPublicKey(daemonPublicKeyB64),
      secretKey: importSecretKey(daemonSecretKeyB64),
    }, openSession);
  });
  return {
    stop() {
      relay?.stop();
      server.close();
    },
  };
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
