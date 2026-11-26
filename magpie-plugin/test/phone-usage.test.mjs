import assert from 'node:assert/strict';
import { generateKeyPairSync, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, request } from 'node:http';
import { join } from 'node:path';
import { test } from 'node:test';
import { startHost } from '../host.mjs';
import { macCompanion } from '../launch-agent.mjs';
import { parsePairing } from '../protocol.mjs';
import { loopbackGateway, usageGatewayFile } from '../usage.mjs';
import { openPhone } from '../tools/test-client.mjs';
import { WebSocketServer } from 'ws';

const summary = { calls: 2, errors: 1, input: 90, output: 20, unpriced: 1, cost: 0.05 };
const row = { t: '2026-11-25T10:01:05.123456789Z', model: 'test-model', agentName: 'Test agent', agent: 'test', status: 200, priced: true, cost: 0.05 };

async function startStack(t, usageSource) {
  const root = mkdtempSync('/tmp/magpie-usage-');
  const directory = join(root, 'connect-phone');
  const socketPath = join(root, 'control.sock');
  const relay = await startFakeRelay();
  const options = { relay: { endpoint: relay.endpoint, useTls: false }, usageSource };
  let host = startHost(directory, socketPath, options);
  const phones = [];
  t.after(() => {
    for (const phone of phones) phone.close();
    host.stop();
    relay.stop();
    rmSync(root, { recursive: true, force: true });
  });

  const call = (action, extra = {}) => new Promise((resolve, reject) => {
    const body = JSON.stringify({ action, ...extra });
    const req = request({ socketPath, method: 'POST', headers: { 'Content-Length': Buffer.byteLength(body) } }, (res) => {
      let content = '';
      res.on('data', (chunk) => { content += chunk; });
      res.on('end', () => resolve(JSON.parse(content)));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.end(body);
  });
  const online = () => until(async () => (await call('status').catch(() => ({}))).status === 'online');
  await online();
  const connect = async (identity) => {
    const phone = await openPhone(identity);
    phones.push(phone);
    return phone;
  };
  const identityFor = (offer) => ({ offer, key: generateKeyPairSync('ed25519').privateKey.export({ format: 'jwk' }) });
  return {
    root, directory, call, connect, identityFor,
    async restart() {
      host.stop();
      await new Promise((resolve) => setImmediate(resolve));
      host = startHost(directory, socketPath, options);
      await online();
    },
    async pair({ allowUsage, identity } = {}) {
      const pairing = parsePairing((await call('issue', { allowUsage })).link);
      identity ??= identityFor(pairing.offer);
      const phone = await connect(identity);
      const { deviceId, error } = await phone.pair(pairing.ticket, 'Test phone');
      assert.equal(error, undefined);
      return { phone, identity, deviceId };
    },
  };
}

const summaryResult = (phone) => phone.request('usage.summary', { period: 'today' });
const pageResult = (phone, offset = 0) => phone.request('usage.requests', { period: '7d', offset, limit: 50 });

test('host authorization, per-device permissions, re-pairing, persistence and validation', async (t) => {
  const queries = [];
  const stack = await startStack(t, (query) => {
    queries.push(query);
    if (query.period === '30d') throw new Error('private upstream detail');
    return { ...summary, path: '/private/example', byAccount: ['private-account'] };
  });
  const first = await stack.pair();
  await stack.restart();
  first.phone = await stack.connect(first.identity);
  assert.equal((await first.phone.authenticate()).deviceId, first.deviceId);
  const second = await stack.pair({ allowUsage: true });
  const third = await stack.pair();
  const stranger = await stack.connect(stack.identityFor(first.identity.offer));
  assert.equal((await summaryResult(stranger)).error, 'device_not_authorized');
  assert.equal((await summaryResult(first.phone)).error, 'usage_permission_denied');
  assert.equal((await summaryResult(third.phone)).error, 'usage_permission_denied');
  assert.equal((await first.phone.request('usage.set', { allowUsage: true })).error, 'unsupported_request');
  assert.deepEqual(queries, []);

  assert.equal((await stack.call('usage.set', { id: first.deviceId, allowUsage: 'true' })).error, 'invalid_request');
  assert.deepEqual(await stack.call('usage.set', { id: first.deviceId, allowUsage: true }), { ok: true });
  assert.deepEqual((await summaryResult(first.phone)).usage, summary);
  assert.deepEqual((await summaryResult(second.phone)).usage, summary);
  const failed = await first.phone.request('usage.summary', { period: '30d' });
  assert.equal(failed.error, 'usage_unavailable');
  assert.deepEqual(Object.keys(failed).sort(), ['error', 'id', 'type', 'v']);
  assert.equal((await first.phone.request('usage.summary', { params: { period: 'today' } })).error, 'invalid_request');
  assert.equal((await first.phone.request('usage.summary', { period: 'all' })).error, 'invalid_request');
  for (const [offset, limit] of [[-1, 50], ['0', 50], [1.2, 50], [0, 0], [0, 51], [0, '50']]) {
    assert.equal((await first.phone.request('usage.requests', { period: '7d', offset, limit })).error, 'invalid_request');
  }
  assert.deepEqual(queries, [{ type: 'usage.summary', period: 'today' }, { type: 'usage.summary', period: 'today' }, { type: 'usage.summary', period: '30d' }]);
  assert.deepEqual((await stack.call('status')).devices.map(({ allowUsage }) => allowUsage), [true, true, false]);

  const repaired = await stack.pair({ identity: first.identity, allowUsage: false });
  const repairedDenied = await stack.pair({ identity: third.identity, allowUsage: true });
  assert.equal((await summaryResult(first.phone)).error, 'device_not_authorized');
  assert.deepEqual((await summaryResult(repaired.phone)).usage, summary);
  assert.equal((await summaryResult(repairedDenied.phone)).error, 'usage_permission_denied');
  await stack.restart();
  const reconnected = await stack.connect(first.identity);
  assert.equal((await reconnected.authenticate()).deviceId, first.deviceId);
  assert.deepEqual((await summaryResult(reconnected)).usage, summary);
  await stack.call('revoke', { id: first.deviceId });
  assert.equal((await summaryResult(reconnected)).error, 'device_not_authorized');
  const pairedAgain = await stack.pair({ identity: first.identity });
  assert.equal((await summaryResult(pairedAgain.phone)).error, 'usage_permission_denied');
});

test('disabling then re-enabling rejects in-flight usage, and revoke invalidates the session', async (t) => {
  const waiting = [];
  const stack = await startStack(t, () => new Promise((resolve) => waiting.push(resolve)));
  const { phone, deviceId } = await stack.pair({ allowUsage: true });
  const beforeDisable = summaryResult(phone);
  await until(() => waiting.length === 1);
  await stack.call('usage.set', { id: deviceId, allowUsage: false });
  assert.equal((await summaryResult(phone)).error, 'usage_permission_denied');
  await stack.call('usage.set', { id: deviceId, allowUsage: true });
  waiting.shift()(summary);
  assert.equal((await beforeDisable).error, 'usage_permission_denied');
  const afterEnable = summaryResult(phone);
  await until(() => waiting.length === 1);
  waiting.shift()(summary);
  assert.deepEqual((await afterEnable).usage, summary);

  const beforeRevoke = summaryResult(phone);
  await until(() => waiting.length === 1);
  await stack.call('revoke', { id: deviceId });
  waiting.shift()(summary);
  assert.equal((await beforeRevoke).error, 'device_not_authorized');
});

test('host sends fixed credential-free GETs and pages through projected rows within the protected frame', async (t) => {
  const stack = await startStack(t);
  const calls = [];
  const longModel = '测试模型'.repeat(14);
  const rows = Array.from({ length: 57 }, (_, i) => ({
    ...row, model: `${longModel}-${i}`, client_ip: 'hidden-ip', account: 'hidden-account', trace: { prompt: 'hidden-prompt' },
  }));
  let mode = 'normal';
  const gateway = createServer((req, res) => {
    calls.push({ method: req.method, url: req.url, headers: req.headers });
    const url = new URL(req.url, 'http://example.invalid');
    if (mode === 'redirect') {
      res.writeHead(302, { Location: '/other' }).end();
    } else if (mode === 'malformed') {
      res.end('private non-JSON response');
    } else if (url.pathname.endsWith('/requests')) {
      const offset = Math.min(Number(url.searchParams.get('offset')), rows.length);
      res.end(JSON.stringify({ offset, total: rows.length, rows: rows.slice(offset, offset + 50), callerKeys: ['hidden-key'] }));
    } else {
      res.end(JSON.stringify({ ...summary, path: '/hidden-path' }));
    }
  });
  gateway.listen(0, '127.0.0.1');
  await once(gateway, 'listening');
  t.after(() => { gateway.close(); });
  const address = `127.0.0.1:${gateway.address().port}`;
  macCompanion(stack.root).publishUsageGateway(address);
  const { phone } = await stack.pair({ allowUsage: true });

  assert.deepEqual((await phone.request('usage.summary', { period: '30d', url: 'http://example.invalid' })).usage, summary);
  let offset = 0;
  const projected = [];
  while (offset < rows.length) {
    const reply = await pageResult(phone, offset);
    assert.ok(Buffer.byteLength(JSON.stringify(reply)) <= 6120);
    assert.equal(reply.usage.offset, offset);
    assert.equal(reply.usage.total, rows.length);
    assert.ok(reply.usage.rows.length > 0 && reply.usage.rows.length < 50);
    const requestCall = calls.at(-1);
    assert.equal(requestCall.url, `/v1/magpie/usage/requests?period=7d&offset=${offset}&limit=50`);
    projected.push(...reply.usage.rows);
    offset += reply.usage.rows.length;
  }
  assert.deepEqual(projected, rows.map(({ model }) => ({ ...row, model })));
  assert.deepEqual((await pageResult(phone, 99)).usage, { offset: 57, total: 57, rows: [] });
  assert.equal(calls[0].url, '/v1/magpie/usage?period=30d');
  assert.ok(calls.every(({ method, headers }) => method === 'GET' && !headers.authorization && !headers['x-api-key'] && !headers.cookie));

  mode = 'redirect';
  const beforeRedirect = calls.length;
  assert.equal((await summaryResult(phone)).error, 'usage_unavailable');
  assert.equal(calls.length, beforeRedirect + 1);
  mode = 'malformed';
  assert.equal((await summaryResult(phone)).error, 'usage_unavailable');
  macCompanion(stack.root).publishUsageGateway(`192.0.2.1:${gateway.address().port}`);
  const beforeInvalid = calls.length;
  assert.equal((await summaryResult(phone)).error, 'usage_unavailable');
  writeFileSync(join(stack.directory, usageGatewayFile), JSON.stringify(`http://${address}`));
  assert.equal((await summaryResult(phone)).error, 'usage_unavailable');
  assert.equal(calls.length, beforeInvalid);
});

test('only numeric loopback addresses with valid ports are accepted', () => {
  assert.deepEqual(loopbackGateway(), { hostname: '127.0.0.1', port: 3425 });
  assert.deepEqual(loopbackGateway('127.0.0.2:1234'), { hostname: '127.0.0.2', port: 1234 });
  assert.deepEqual(loopbackGateway('[::1]:65535'), { hostname: '::1', port: 65535 });
  for (const input of [null, '', 'localhost:3425', '0.0.0.0:3425', '127.0.0.1:3425\n', '127.00.0.1:3425', '127.0.0.1:65536', '127.0.0.1:3425/foo']) {
    assert.equal(loopbackGateway(input), null, String(input));
  }
});

async function until(check, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await check();
    if (value) return value;
    if (Date.now() > deadline) throw new Error('Condition not reached in time');
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

/**
 * Test stand-in for the Paseo v2 relay routes the plugin uses: one control
 * socket per server and one data socket per phone connection. It forwards
 * opaque frames, so the endpoints still run the real protected handshake.
 */
async function startFakeRelay() {
  const server = createServer();
  const wss = new WebSocketServer({ server });
  const connections = new Set();
  server.on('connection', (socket) => {
    connections.add(socket);
    socket.once('close', () => connections.delete(socket));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const hosts = new Map();

  const hostFor = (serverId) => {
    if (!hosts.has(serverId)) hosts.set(serverId, { control: null, links: new Map() });
    return hosts.get(serverId);
  };

  wss.on('connection', (socket, request) => {
    const url = new URL(request.url, 'http://relay');
    const host = hostFor(url.searchParams.get('serverId'));
    const role = url.searchParams.get('role');
    const connectionId = url.searchParams.get('connectionId');
    socket.on('error', () => {});

    if (role === 'server' && !connectionId) {
      host.control = socket;
      socket.send(JSON.stringify({ type: 'sync', connectionIds: [...host.links.keys()] }));
      socket.on('close', () => { if (host.control === socket) host.control = null; });
      return;
    }

    if (role === 'client') {
      const id = randomUUID();
      const link = { client: socket, server: null, backlog: [] };
      host.links.set(id, link);
      host.control?.send(JSON.stringify({ type: 'connected', connectionId: id }));
      socket.on('message', (data, isBinary) => {
        if (link.server) link.server.send(data, { binary: isBinary });
        else link.backlog.push([data, isBinary]);
      });
      socket.on('close', () => {
        host.links.delete(id);
        link.server?.close();
        host.control?.send(JSON.stringify({ type: 'disconnected', connectionId: id }));
      });
      return;
    }

    const link = host.links.get(connectionId);
    if (!link) { socket.close(); return; }
    link.server = socket;
    for (const [data, isBinary] of link.backlog.splice(0)) socket.send(data, { binary: isBinary });
    socket.on('message', (data, isBinary) => {
      if (link.client.readyState === 1) link.client.send(data, { binary: isBinary });
    });
    socket.on('close', () => link.client.close());
  });

  return {
    endpoint: `127.0.0.1:${wss.address().port}`,
    stop() {
      wss.close();
      server.close();
      for (const socket of wss.clients) socket.terminate();
      for (const socket of connections) socket.destroy();
    },
  };
}
