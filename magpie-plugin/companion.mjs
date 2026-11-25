import { startHost } from './host.mjs';

const [directory, socket] = process.argv.slice(2);
if (!directory || !socket) process.exit(1);
process.umask(0o077);
startHost(directory, socket);
