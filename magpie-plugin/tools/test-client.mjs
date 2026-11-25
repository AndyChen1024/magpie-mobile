import { createPrivateKey, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { openClientSecureChannel } from '@getpaseo/relay/e2ee';
import { writePrivateJson } from '../host.mjs';
import { dialRelay, secureTransport } from '../relay.mjs';
import { parsePairing, proofBytes, version } from '../protocol.mjs';

export async function openPhone(identity) {
  const socket = dialRelay(identity.offer, 'client');
  const transport = secureTransport(socket);
  const pending = new Map();
  let channel;
  let nonce;
  let ready;
  let failed;

  function closePending() {
    failed(new Error('Protected connection unavailable'));
    for (const pendingRequest of pending.values()) pendingRequest.reject(new Error('Connection closed'));
    pending.clear();
  }

  const initial = new Promise((resolve, reject) => { ready = resolve; failed = reject; });
  const timeout = setTimeout(() => socket.terminate(), 12000);
  socket.on('close', closePending);
  socket.once('open', () => {
    openClientSecureChannel(transport, {
      daemonPublicKeyB64: identity.offer.daemonPublicKeyB64,
      minimumMode: 'protected',
      events: {
        onmessage(raw) {
          const reply = JSON.parse(raw);
          if (reply.v !== version) return;
          if (reply.type === 'challenge' && reply.serverId === identity.offer.serverId) {
            nonce = reply.nonce;
            ready();
          }
          if (reply.type === 'result') pending.get(reply.id)?.resolve(reply);
        },
        onerror: () => socket.terminate(),
      },
    }).then((secureChannel) => { channel = secureChannel; }).catch(() => socket.terminate());
  });
  try {
    await initial;
  } finally {
    clearTimeout(timeout);
  }

  async function request(type, fields = {}) {
    const id = randomUUID();
    let timer;
    if (socket.readyState !== 1) throw new Error('Connection closed');
    try {
      const response = new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject });
        timer = setTimeout(() => reject(new Error('Request timed out')), 8000);
      });
      try {
        await channel.send(JSON.stringify({ v: version, type, id, ...fields }));
      } catch {
        socket.terminate();
      }
      return await response;
    } finally {
      clearTimeout(timer);
      pending.delete(id);
    }
  }

  function authorize(operation, { ticket, label } = {}) {
    const publicKey = identity.key.x;
    const key = createPrivateKey({ key: identity.key, format: 'jwk' });
    const proof = proofBytes(identity.offer.serverId, nonce, operation, publicKey, ticket);
    return request(operation, {
      publicKey, signature: sign(null, proof, key).toString('base64url'), ticket, label,
    });
  }

  return {
    securityMode: channel.securityMode(),
    request,
    pair: (ticket, label) => authorize('device.pair', { ticket, label }),
    authenticate: () => authorize('device.auth'),
    close: () => socket.close(),
  };
}

export function readIdentity(path) { return JSON.parse(readFileSync(path, 'utf8')); }

export function identityFor(path, offer) {
  try {
    const identity = readIdentity(path);
    if (identity.offer.serverId !== offer.serverId ||
        identity.offer.daemonPublicKeyB64 !== offer.daemonPublicKeyB64) throw new Error('Identity belongs to another Mac');
    return identity;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    const identity = { offer, key: generateKeyPairSync('ed25519').privateKey.export({ format: 'jwk' }) };
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    writePrivateJson(path, identity);
    return identity;
  }
}

async function run() {
  const [profile, command, linkPath, label] = process.argv.slice(2);
  if (!profile || !['pair', 'read'].includes(command) || (command === 'pair' && (!linkPath || !label))) {
    console.error('Usage: node tools/test-client.mjs IDENTITY pair LINK_FILE LABEL | IDENTITY read [--show-name]');
    process.exitCode = 2;
    return;
  }
  const pairing = command === 'pair' ? parsePairing(readFileSync(linkPath, 'utf8')) : null;
  const identity = pairing ? identityFor(profile, pairing.offer) : readIdentity(profile);
  const phone = await openPhone(identity);
  try {
    const auth = pairing ? await phone.pair(pairing.ticket, label) : await phone.authenticate();
    const reply = await phone.request('host.get');
    const safe = { security: phone.securityMode, id: reply.id };
    if (reply.error) {
      console.log(JSON.stringify({ ...safe, authorization: auth.error ?? 'ok', error: reply.error }));
      process.exitCode = 1;
    } else {
      console.log(JSON.stringify({ ...safe, receipt: reply.receipt, displayNameReceived: Boolean(reply.displayName),
        ...(linkPath === '--show-name' ? { displayName: reply.displayName } : {}) }));
    }
  } finally {
    phone.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  run().catch(() => {
    console.error('Could not complete the reference client operation.');
    process.exitCode = 1;
  });
}
