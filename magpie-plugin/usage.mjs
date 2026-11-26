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
const isCount = (value) => Number.isSafeInteger(value) && value >= 0;
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
  if (query.type === 'usage.summary') {
    if (!summaryCounts.every((key) => isCount(raw[key])) || !Number.isFinite(raw.cost)) return null;
    return Object.fromEntries([...summaryCounts, 'cost'].map((key) => [key, raw[key]]));
  }
  const { offset, total, rows } = raw;
  if (!isCount(offset) || !isCount(total) || offset > total || !Array.isArray(rows) ||
      rows.length > query.limit || rows.length > total - offset || offset !== Math.min(query.offset, total) ||
      (rows.length === 0 && offset < total)) return null;

  const usage = { offset, total, rows: [] };
  let size = Buffer.byteLength(JSON.stringify({ v: version, type: 'result', id, usage }));
  for (const source of rows) {
    const row = projectRow(source);
    if (!row) return null;
    size += Buffer.byteLength(JSON.stringify(row)) + Number(usage.rows.length > 0);
    if (size > maxResultBytes) return usage.rows.length ? usage : null;
    usage.rows.push(row);
  }
  return usage;
}

function projectRow(raw) {
  if (!isObject(raw) || typeof raw.t !== 'string' ||
      !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)$/.test(raw.t) ||
      !Number.isFinite(Date.parse(raw.t))) return null;
  const row = { t: raw.t };
  for (const key of ['agent', 'agentName', 'model']) {
    if (typeof raw[key] === 'string') row[key] = raw[key];
  }
  if (Number.isInteger(raw.status) && raw.status >= 100 && raw.status <= 599) row.status = raw.status;
  if (raw.priced === true) {
    if (!Number.isFinite(raw.cost)) return null;
    row.priced = true;
    row.cost = raw.cost;
  } else if (raw.priced === false) {
    row.priced = false;
  }
  return row;
}
