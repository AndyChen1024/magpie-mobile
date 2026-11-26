import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { privateDirectory, writePrivateJson } from './host.mjs';
import { loopbackGateway, usageGatewayFile } from './usage.mjs';

const xml = (value) => value.replace(/[<>&"']/g, (char) => (
  { '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' }[char]
));

export function macCompanion(configDirectory) {
  const stateDirectory = resolve(configDirectory, 'connect-phone');
  const suffix = createHash('sha256').update(stateDirectory).digest('hex').slice(0, 16);
  const label = `app.magpie.connect-phone.${suffix}`;
  const domain = `gui/${process.getuid()}`;
  const target = `${domain}/${label}`;
  const agents = join(homedir(), 'Library', 'LaunchAgents');
  const plist = join(agents, `${label}.plist`);
  const socketDirectory = join(tmpdir(), `magpie-phone-${suffix}`);
  const socketPath = join(socketDirectory, 'control.sock');

  const launchctl = (...args) => execFileSync('/bin/launchctl', args, { stdio: 'ignore', timeout: 5000 });
  function loaded() {
    try {
      launchctl('print', target);
      return true;
    } catch (error) {
      if (error.status === 113) return false;
      throw error;
    }
  }

  return {
    socketPath,
    configured: () => existsSync(plist),
    publishUsageGateway(address) {
      privateDirectory(stateDirectory);
      // Retain only the address, and fail closed when Magpie supplies a non-loopback bind.
      const gateway = loopbackGateway(address);
      const host = gateway?.hostname === '::1' ? '[::1]' : gateway?.hostname;
      writePrivateJson(join(stateDirectory, usageGatewayFile), gateway ? `${host}:${gateway.port}` : null);
    },
    start() {
      if (loaded()) return;
      privateDirectory(socketDirectory);
      mkdirSync(agents, { recursive: true, mode: 0o700 });
      const script = fileURLToPath(new URL('./companion.mjs', import.meta.url));
      const args = [process.execPath, script, stateDirectory, socketPath];
      const contents = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>${xml(label)}</string>
<key>ProgramArguments</key><array>${args.map((arg) => `<string>${xml(arg)}</string>`).join('')}</array>
<key>KeepAlive</key><true/>
<key>ThrottleInterval</key><integer>3</integer>
<key>Umask</key><integer>63</integer>
</dict></plist>
`;
      const alreadyConfigured = existsSync(plist);
      const temporary = `${plist}.${process.pid}.tmp`;
      writeFileSync(temporary, contents, { mode: 0o600 });
      renameSync(temporary, plist);
      try {
        launchctl('bootstrap', domain, plist);
      } catch (error) {
        if (loaded()) return;
        if (!alreadyConfigured) unlinkSync(plist);
        throw error;
      }
    },
    stop() {
      if (loaded()) launchctl('bootout', target);
      try { unlinkSync(plist); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    },
  };
}
