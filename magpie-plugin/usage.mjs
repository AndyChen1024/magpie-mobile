import { readFileSync } from 'node:fs';
import { get } from 'node:http';
import { isIP } from 'node:net';
import { version } from './protocol.mjs';

export const usageGatewayFile = 'usage-gateway.json';
const defaultAddress = '127.0.0.1:3425';
const summaryPath = '/v1/magpie/usage';
const maxBodyBytes = 4 * 1024 * 1024;
// Android accepts 8192 base64 bytes, including the protected record's 24-byte overhead.
const maxResultBytes = 6120;
const periods = new Set(['today', '7d', '30d']);
const summaryCounts = ['calls', 'errors', 'input', 'output', 'unpriced'];
const breakdownCounts = ['cache_read', 'cache_write', 'reasoning'];
const groupCounts = ['calls', 'input', 'output'];
const rowLabels = ['agent', 'agentName', 'model'];
const buckets = new Set(['hour', 'day', 'week']);
const isCount = (value) => Number.isSafeInteger(value) && value >= 0;
const isText = (value) => typeof value === 'string' && value.trim().length > 0;
const resultBytes = (usage, id) => Buffer.byteLength(JSON.stringify({ v: version, type: 'result', id, usage }));
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

export function loopbackGateway(value = defaultAddress) {
  if (typeof value !== 'string') return null;
  const match = /^(127(?:\.\d{1,3}){3}|\[::1\]):([1-9]\d{0,4})$/.exec(value);
  if (!match || match[0] !== value || Number(match[2]) > 65535) return null;
  const hostname = match[1] === '[::1]' ? '::1' : match[1];
  if (!isIP(hostname)) return null;
  return { hostname, port: Number(match[2]) };
}

export function usageQuery({ type, period, offset, limit }) {
  if (!periods.has(period)) return null;
  if (type === 'usage.summary') return { type, period };
  if (!isCount(offset) || !Number.isSafeInteger(limit) || limit < 1 || limit > 50) return null;
  return { type, period, offset, limit };
}

export function gatewayUsageSource(addressFile) {
  return ({ type, period, offset, limit }) => {
    let address;
    try {
      address = JSON.parse(readFileSync(addressFile, 'utf8'));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    const gateway = loopbackGateway(address);
    if (!gateway) throw new Error('Invalid gateway address');
    const params = new URLSearchParams({ period });
    let path = summaryPath;
    if (type === 'usage.requests') {
      path += '/requests';
      params.set('offset', String(offset));
      params.set('limit', String(limit));
    }
    // The direct HTTP request has no redirect handling, proxy, or credentials.
    return new Promise((resolve, reject) => {
      const request = get({ ...gateway, path: `${path}?${params}`, agent: false, signal: AbortSignal.timeout(5000) }, async (response) => {
        try {
          if (response.statusCode !== 200) throw new Error('Gateway usage request failed');
          const chunks = [];
          let size = 0;
          for await (const chunk of response) {
            size += chunk.length;
            if (size > maxBodyBytes) throw new Error('Gateway usage response too large');
            chunks.push(chunk);
          }
          resolve(JSON.parse(Buffer.concat(chunks)));
        } catch (error) {
          response.destroy();
          reject(error);
        }
      });
      request.on('error', reject);
    });
  };
}

export function projectUsage(raw, query, id) {
  if (!isObject(raw)) return null;
  if (query.type === 'usage.summary') return projectSummary(raw, id);
  const { offset, total, rows } = raw;
  if (!isCount(offset) || !isCount(total) || offset > total || !Array.isArray(rows) ||
      rows.length > query.limit || rows.length > total - offset || offset !== Math.min(query.offset, total) ||
      (rows.length === 0 && offset < total)) return null;

  const usage = { offset, total, rows: [] };
  let size = resultBytes(usage, id);
  if (size > maxResultBytes) return null;
  for (const source of rows) {
    const base = projectRow(source);
    if (!base) return null;
    const details = projectRowDetails(source);
    const row = { ...base, ...details };
    const additionalBytes = Buffer.byteLength(JSON.stringify(row)) + Number(usage.rows.length > 0);
    if (size + additionalBytes > maxResultBytes) {
      if (usage.rows.length > 0) break;
      // Prefer preserving the first row's outcome over unusually long display labels.
      usage.rows.push(base);
      if (resultBytes(usage, id) > maxResultBytes) {
        for (const key of rowLabels) {
          if (base[key] !== undefined) base[key] = boundedLabel(base[key], 1024);
        }
        if (resultBytes(usage, id) > maxResultBytes) return null;
      }
      for (const [key, value] of Object.entries(details)) {
        base[key] = value;
        if (resultBytes(usage, id) > maxResultBytes) delete base[key];
      }
      break;
    }
    size += additionalBytes;
    usage.rows.push(row);
  }
  return usage;
}

function projectSummary(raw, id) {
  if (!summaryCounts.every((key) => isCount(raw[key])) || !Number.isFinite(raw.cost)) return null;
  const usage = Object.fromEntries([...summaryCounts, 'cost'].map((key) => [key, raw[key]]));
  if (resultBytes(usage, id) > maxResultBytes) return null;
  const include = (key, value) => {
    const previous = usage[key];
    usage[key] = value;
    if (resultBytes(usage, id) <= maxResultBytes) return;
    if (previous === undefined) delete usage[key];
    else usage[key] = previous;
  };
  if (buckets.has(raw.bucket)) include('bucket', raw.bucket);
  const series = projectSeries(raw.series);
  if (series) include('series', series);
  for (const key of [...breakdownCounts, 'cache_unknown']) {
    if (isCount(raw[key])) include(key, raw[key]);
  }

  const grouped = [['agents', projectGroups(raw.agents, 'id')], ['models', projectModels(raw.models)]];
  // Preserve both ranked prefixes when the groups compete for space.
  for (let count = 1; count <= 5; count++) {
    for (const [key, groups] of grouped) {
      if (groups && (count === 1 || (groups.length >= count && usage[key]?.length === count - 1))) {
        include(key, groups.slice(0, count));
      }
    }
  }
  return usage;
}

function projectSeries(raw) {
  if (!Array.isArray(raw) || raw.length > 31) return null;
  const series = [];
  for (const point of raw) {
    if (!isObject(point) || !isTimestamp(point.time) || !isCount(point.input) || !isCount(point.output)) return null;
    series.push({ time: point.time, input: point.input, output: point.output });
  }
  return series;
}

function* readGroups(raw, fallback) {
  for (const group of raw) {
    if (!isObject(group) || !groupCounts.every((key) => isCount(group[key]))) continue;
    const name = isText(group.name) ? group.name : group[fallback];
    if (!isText(name)) continue;
    yield { name, calls: group.calls, input: group.input, output: group.output };
  }
}

function projectGroups(raw, fallback) {
  if (!Array.isArray(raw)) return null;
  const groups = [];
  for (const group of readGroups(raw, fallback)) {
    groups.push({ ...group, name: boundedLabel(group.name) });
    if (groups.length === 5) break;
  }
  return groups;
}

function projectModels(raw) {
  if (!Array.isArray(raw)) return null;
  // The host groups by model, provider and route. The phone ranks models across routes.
  const byName = new Map();
  for (const group of readGroups(raw, 'model')) {
    const previous = byName.get(group.name);
    if (previous === null) continue;
    if (previous) {
      for (const key of groupCounts) group[key] += previous[key];
    }
    byName.set(group.name, groupCounts.every((key) => isCount(group[key])) ? group : null);
  }
  const ranked = [...byName.values()].filter(Boolean).sort((a, b) =>
    (b.input - a.input) + (b.output - a.output) || b.calls - a.calls);
  const groups = [];
  const labels = new Set();
  for (const group of ranked) {
    const name = boundedLabel(group.name);
    if (labels.has(name)) continue;
    labels.add(name);
    groups.push({ ...group, name });
    if (groups.length === 5) break;
  }
  return groups;
}

function boundedLabel(value, limit = 96) {
  if (!isText(value)) return undefined;
  const trimmed = value.trim();
  if (Buffer.byteLength(JSON.stringify(trimmed)) - 2 <= limit) return trimmed;
  let label = '';
  let bytes = Buffer.byteLength('…');
  // Bound serialized bytes and keep whole Unicode code points.
  for (const char of trimmed) {
    bytes += Buffer.byteLength(JSON.stringify(char)) - 2;
    if (bytes > limit) break;
    label += char;
  }
  return `${label.trimEnd()}…`;
}

function isTimestamp(value) {
  if (typeof value !== 'string') return false;
  const match = /^(\d{4}-\d\d-\d\d)T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)$/.exec(value);
  if (match?.[0] !== value || !Number.isFinite(Date.parse(value))) return false;
  return new Date(`${match[1]}T00:00:00Z`).toISOString().startsWith(match[1]);
}

function projectRow(raw) {
  if (!isObject(raw) || !isTimestamp(raw.t)) return null;
  const row = { t: raw.t };
  for (const key of rowLabels) {
    if (typeof raw[key] === 'string') row[key] = raw[key];
  }
  if (Number.isInteger(raw.status) && raw.status >= 100 && raw.status <= 599) row.status = raw.status;
  if (raw.canceled === true) row.canceled = true;
  else if (raw.failed === true || (isCount(raw.status) && raw.status >= 400) || isText(raw.err)) row.failed = true;
  if (raw.cache_unknown === true) row.cache_unknown = true;
  if (raw.priced === true) {
    if (!Number.isFinite(raw.cost)) return null;
    row.priced = true;
    row.cost = raw.cost;
  } else if (raw.priced === false) {
    row.priced = false;
  }
  return row;
}

function projectRowDetails(raw) {
  const details = {};
  for (const key of ['in', 'out', ...breakdownCounts]) {
    if (isCount(raw[key])) details[key] = raw[key];
  }
  for (const key of ['ms', 'ttft_ms']) {
    if (isCount(raw[key]) && raw[key] > 0) details[key] = raw[key];
  }
  const providerName = boundedLabel(raw.providerName);
  const provider = providerName ? undefined : boundedLabel(raw.provider);
  if (providerName) details.providerName = providerName;
  if (provider) details.provider = provider;
  for (const key of ['req', 'served']) {
    const label = boundedLabel(raw[key]);
    if (label) details[key] = label;
  }
  return details;
}
