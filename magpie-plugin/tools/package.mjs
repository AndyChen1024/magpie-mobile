import { cpSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = new URL('../', import.meta.url);
const dist = fileURLToPath(new URL('../dist', import.meta.url));
const stage = `${dist}.${process.pid}.tmp`;
const files = [
  'index.mjs', 'launch-agent.mjs', 'companion.mjs', 'host.mjs', 'protocol.mjs', 'relay.mjs',
  'page.html', 'page.js', 'service.html', 'service.css', 'service.js', 'icon.png',
];
const manifest = JSON.parse(readFileSync(new URL('package.json', root)));
delete manifest.scripts;
mkdirSync(stage, { recursive: true });
try {
  for (const file of [...files, 'node_modules']) cpSync(new URL(file, root), `${stage}/${file}`, { recursive: true });
  writeFileSync(`${stage}/package.json`, JSON.stringify(manifest, null, 2) + '\n');
  rmSync(dist, { recursive: true, force: true });
  renameSync(stage, dist);
} finally {
  rmSync(stage, { recursive: true, force: true });
}
