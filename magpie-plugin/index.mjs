import { randomBytes, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createServer, request } from 'node:http';
import qrcode from 'qrcode-generator';
import { readJson, sendJson } from './host.mjs';
import { macCompanion } from './launch-agent.mjs';

const defaultPort = 18647;

function callCompanion(socketPath, action, id, allowUsage) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ action, id, allowUsage });
    const req = request({
      socketPath, path: '/', method: 'POST', headers: { 'Content-Length': Buffer.byteLength(body) },
    }, (res) => {
      let content = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { content += chunk; });
      res.on('error', reject);
      res.on('end', () => {
        try { resolve(JSON.parse(content)); } catch (error) { reject(error); }
      });
    });
    req.setTimeout(2000, () => req.destroy(new Error('Local service timed out')));
    req.on('error', reject);
    req.end(body);
  });
}

export async function PageService(_input, options) {
  const port = options?.port ?? defaultPort;
  const authority = `127.0.0.1:${port}`;
  const ownOrigin = `http://${authority}`;
  const companion = macCompanion(process.cwd());
  const secret = randomBytes(32).toString('hex');
  const template = readFileSync(new URL('./service.html', import.meta.url), 'utf8');
  const css = readFileSync(new URL('./service.css', import.meta.url), 'utf8');
  const script = readFileSync(new URL('./service.js', import.meta.url), 'utf8');
  const icon = readFileSync(new URL('./icon.png', import.meta.url)).toString('base64');

  async function control({ token, action, id, allowUsage }) {
    if (typeof token !== 'string' || token.length !== secret.length ||
        Buffer.byteLength(token) !== secret.length ||
        !timingSafeEqual(Buffer.from(token), Buffer.from(secret))) return { error: 'forbidden' };
    if (action === 'start') {
      companion.start();
      return { ok: true };
    }
    if (action === 'stop') {
      companion.stop();
      return { ok: true };
    }

    let result;
    try {
      result = await callCompanion(companion.socketPath, action, id, allowUsage);
    } catch {
      if (action === 'status') {
        return { status: companion.configured() ? 'unavailable' : 'disabled', devices: [] };
      }
      return { error: 'service_unavailable' };
    }
    if (action === 'issue' && result.link) {
      const code = qrcode(0, 'M');
      code.addData(result.link);
      code.make();
      result.qr = code.createDataURL(4);
    }
    return result;
  }

  const server = createServer(async (req, res) => {
    const origin = req.headers.origin;
    if (req.headers.host !== authority || (origin && origin !== 'null' && origin !== ownOrigin)) {
      sendJson(res, { error: 'forbidden' }, 403);
      return;
    }
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (req.method === 'GET' && req.url === '/') {
      const nonce = randomBytes(18).toString('base64');
      res.setHeader('Content-Security-Policy',
        `default-src 'none'; frame-ancestors 'self' wails: http://127.0.0.1:*; base-uri 'none'; form-action 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; connect-src 'self' ${ownOrigin}; img-src data:`);
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.end(template.replaceAll('{{NONCE}}', nonce).replace('{{TOKEN}}', secret)
        .replaceAll('{{ICON}}', `data:image/png;base64,${icon}`)
        .replace('{{CSS}}', () => css).replace('{{SCRIPT}}', () => script));
      return;
    }
    if (req.method !== 'POST' || req.url !== '/control') {
      sendJson(res, { error: 'not_found' }, 404);
      return;
    }
    if (origin === 'null') res.setHeader('Access-Control-Allow-Origin', 'null');
    try {
      const result = await control(await readJson(req));
      sendJson(res, result, result.error === 'forbidden' ? 403 : 200);
    } catch {
      sendJson(res, { error: 'operation_failed' }, 400);
    }
  });
  server.headersTimeout = 5000;
  server.requestTimeout = 5000;
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  try {
    companion.publishUsageGateway(process.env.MAGPIE_ADDR);
  } catch (error) {
    server.close();
    throw error;
  }
  return {};
}
