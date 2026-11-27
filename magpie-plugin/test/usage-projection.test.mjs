import assert from 'node:assert/strict';
import test from 'node:test';
import { version } from '../protocol.mjs';
import { projectUsage } from '../usage.mjs';

const id = 'x'.repeat(64);
const summaryQuery = { type: 'usage.summary', period: '30d' };
const requestQuery = { type: 'usage.requests', period: '30d', offset: 0, limit: 50 };
const summary = { calls: 2, errors: 0, input: 100, output: 12, unpriced: 0, cost: 1.5 };
const t = '2026-11-01T01:00:00.123456789-07:00';
const row = { t, agent: 'codex', model: 'router/gpt', status: 200, priced: false };
const resultBytes = (usage) => Buffer.byteLength(JSON.stringify({ v: version, type: 'result', id, usage }));

function projectRows(rows) {
  return projectUsage({ offset: 0, total: rows.length, rows }, requestQuery, id);
}

test('keeps all 31 buckets and both ranked top fives within the UTF-8 result envelope', () => {
  const count = Number.MAX_SAFE_INTEGER;
  const series = Array.from({ length: 31 }, (_, index) => ({
    time: `2026-10-${String(index + 1).padStart(2, '0')}${t.slice(10)}`,
    input: index === 0 ? 0 : count, output: index === 0 ? 0 : count,
  }));
  const label = '模型😀"\\'.repeat(500);
  const agents = Array.from({ length: 6 }, (_, index) => ({
    id: `${index}-${label}`, calls: count, input: count - index, output: count, host: 'private-host',
  }));
  const models = agents.map(({ calls, input, output }, index) => ({
    name: `${index}-${label}`, id: 'model-id @ private-host', provider: 'private-provider', calls, input, output,
    sub: 'private-sub', account: 'private-account',
  }));
  const usage = projectUsage({
    ...summary, bucket: 'day', series: series.map((point) => ({ ...point, label: 'private-label', by: { private: true } })),
    agents, models, cache_read: 0, cache_write: 9, reasoning: 2, path: '/private-path',
  }, summaryQuery, id);
  assert.deepEqual(usage.series, series);
  assert.equal(usage.bucket, 'day');
  assert.equal(usage.cache_read, 0);
  assert.equal(usage.cache_write, 9);
  assert.equal(usage.reasoning, 2);
  assert.equal('cache_unknown' in usage, false);
  assert.equal(usage.agents.length, 5);
  assert.equal(usage.models.length, 5);
  for (let index = 0; index < 5; index++) {
    assert.ok(usage.agents[index].name.startsWith(`${index}-`));
    assert.ok(usage.models[index].name.startsWith(`${index}-`));
    assert.equal(usage.models[index].input, count - index);
    for (const label of [usage.agents[index].name, usage.models[index].name]) {
      assert.ok(Buffer.byteLength(JSON.stringify(label)) - 2 <= 96);
      assert.ok(!label.includes('\uFFFD'));
    }
  }
  assert.ok(!JSON.stringify(usage).includes('private'));
  assert.ok(resultBytes(usage) <= 6120);
});

test('ranks models after combining the same model across routes', () => {
  const usage = projectUsage({ ...summary, models: [
    { name: 'single', calls: 2, input: 8, output: 9 },
    { model: 'shared', provider: 'one', calls: 2, input: 5, output: 4 },
    { name: 'shared', provider: 'two', calls: 2, input: 4, output: 4 },
    { name: 'shared', host: 'private-host', calls: 1, input: 2, output: 1 },
  ] }, summaryQuery, id);
  assert.deepEqual(usage.models, [
    { name: 'shared', calls: 5, input: 11, output: 9 },
    { name: 'single', calls: 2, input: 8, output: 9 },
  ]);
});

test('preserves old summaries and drops malformed optional values and incomplete trends', () => {
  assert.deepEqual(projectUsage(summary, summaryQuery, id), summary);
  assert.equal(projectUsage({ ...summary, input: '100' }, summaryQuery, id), null);
  const point = { time: t, input: 0, output: 0 };
  for (const series of [[point, { time: t, input: 1 }], [point, { ...point, time: '2026-02-30T00:00:00Z' }], Array(32).fill(point)]) {
    const usage = projectUsage({
      ...summary, series, bucket: {}, cache_read: '3', cache_write: -1, cache_unknown: 0, reasoning: false,
      agents: [{ id: 'agent', calls: 1, input: 0, output: 0 }],
      models: [{ id: 'private-host', calls: 1, input: 0, output: 0 }, { model: 'model', calls: 1, input: 0, output: 0 }],
    }, summaryQuery, id);
    assert.deepEqual(usage, {
      ...summary, cache_unknown: 0, agents: [{ name: 'agent', calls: 1, input: 0, output: 0 }],
      models: [{ name: 'model', calls: 1, input: 0, output: 0 }],
    });
  }
});

test('allowlists request details and gives cancellation precedence over every failure signal', () => {
  const rows = [
    { ...row, providerName: 'Display', provider: 'id', req: 'requested', served: 'served', in: 0, out: 8,
      cache_read: 3, cache_write: 0, reasoning: 1, ms: 23, ttft_ms: 5, err: 'private error',
      host: 'private host', client_ip: 'private IP', account: 'private account', path: 'private path',
      session: 'private session', callerKeyId: 'private key', request: { body: 'private prompt' } },
    { ...row, status: 499, canceled: true, failed: true, err: 'private canceled', providerName: ' ', provider: 'id', cache_unknown: true },
    { ...row, status: 503 }, { ...row, failed: true },
    { ...row, in: -1, out: '2', ms: 0, ttft_ms: 0.5, err: {}, canceled: 'true', failed: 'true' },
  ];
  assert.deepEqual(projectRows(rows).rows, [
    { ...row, providerName: 'Display', req: 'requested', served: 'served', in: 0, out: 8,
      cache_read: 3, cache_write: 0, reasoning: 1, ms: 23, ttft_ms: 5, failed: true },
    { ...row, status: 499, canceled: true, provider: 'id', cache_unknown: true },
    { ...row, status: 503, failed: true }, { ...row, failed: true }, row,
  ]);
  assert.equal(projectRows([{ ...row, priced: true }]), null);
  assert.equal(projectRows([{ ...row, t: 'invalid' }]), null);
});

test('pages by the actual row count and keeps a base row that exactly fills the frame', () => {
  const raw = Array.from({ length: 57 }, (_, index) => ({
    ...row, model: `model-${index}`, req: '😀模型'.repeat(128), served: '服'.repeat(128),
    providerName: '"名\\'.repeat(128), in: index, out: 10,
  }));
  let offset = 0;
  while (offset < raw.length) {
    const usage = projectUsage({ offset, total: raw.length, rows: raw.slice(offset, offset + 50) }, { ...requestQuery, offset }, id);
    assert.ok(resultBytes(usage) <= 6120);
    assert.equal(usage.offset, offset);
    assert.equal(usage.total, raw.length);
    assert.ok(usage.rows.length > 0 && usage.rows.length < 50);
    for (const [index, result] of usage.rows.entries()) {
      assert.equal(result.model, `model-${offset + index}`);
      assert.equal(result.in, offset + index);
      assert.ok(result.req.startsWith('😀模型'));
    }
    offset += usage.rows.length;
  }
  const base = { ...row, model: '界'.repeat(1800) };
  const envelope = { offset: 0, total: 1, rows: [base] };
  base.model += 'q'.repeat(6120 - resultBytes(envelope));
  const usage = projectRows([{ ...base, req: 'x', out: 2, ttft_ms: 1 }]);
  assert.deepEqual(usage, envelope);
  assert.equal(resultBytes(usage), 6120);
  const canceled = projectRows([{ ...base, status: 499, canceled: true, err: 'private cancel' }]);
  assert.equal(canceled.rows[0].status, 499);
  assert.equal(canceled.rows[0].canceled, true);
  assert.equal(canceled.rows[0].failed, undefined);
  assert.ok(canceled.rows[0].model.endsWith('…'));
  assert.ok(resultBytes(canceled) <= 6120);
  assert.deepEqual(projectRows([base, row, { ...row, priced: true }]), { ...envelope, total: 3 });
});
