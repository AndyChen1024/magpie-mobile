import { buildRelayWebSocketUrl } from '@getpaseo/protocol/daemon-endpoints';
import { openDaemonSecureChannel } from '@getpaseo/relay/e2ee';
import WebSocket from 'ws';

const MAX_FRAME_BYTES = 8192;
const socketOptions = { handshakeTimeout: 8000, maxPayload: MAX_FRAME_BYTES, perMessageDeflate: false };

export function dialRelay({ serverId, relay }, role, connectionId) {
  return new WebSocket(buildRelayWebSocketUrl({ ...relay, serverId, role, connectionId, version: 2 }), socketOptions);
}

export function secureTransport(socket) {
  const transport = {
    send: (data) => new Promise((resolve, reject) => {
      socket.send(data, (error) => error ? reject(error) : resolve());
    }),
    close: (code, reason) => socket.close(code, reason),
    onmessage: null,
    onclose: null,
    onerror: null,
  };
  socket.on('message', (data, isBinary) => transport.onmessage?.({
    data: isBinary ? Uint8Array.from(data).buffer : data.toString('utf8'),
    isBinary,
  }));
  socket.on('close', (code, reason) => transport.onclose?.(code, reason.toString()));
  socket.on('error', (error) => transport.onerror?.(error));
  return transport;
}

export function connectRelay(offer, daemonKeyPair, servePeer) {
  const peers = new Map();
  let control;
  let online = false;
  let lastPong = 0;
  let retryMs = 1000;
  let retry;
  let stopped = false;

  function openPeer(id) {
    if (typeof id !== 'string' || id.length === 0 || id.length > 128 || peers.has(id) || peers.size >= 32) return;
    const socket = dialRelay(offer, 'server', id);
    peers.set(id, socket);
    const timeout = setTimeout(() => socket.terminate(), 15000);
    const transport = secureTransport(socket);
    const session = servePeer();
    socket.on('close', () => {
      clearTimeout(timeout);
      if (peers.get(id) === socket) peers.delete(id);
    });
    openDaemonSecureChannel(transport, {
      daemonKeyPair,
      minimumMode: 'protected',
      events: { onmessage: session.receive, onerror: () => socket.terminate() },
    }).then((channel) => {
      clearTimeout(timeout);
      session.attach(channel);
    }).catch(() => socket.terminate());
  }

  function connect() {
    control = dialRelay(offer, 'server');
    const readyTimeout = setTimeout(() => control.terminate(), 8000);
    const heartbeat = setInterval(() => {
      if (Date.now() - lastPong > 30000) {
        control.terminate();
      } else if (control.readyState === WebSocket.OPEN) {
        control.ping();
      }
    }, 10000);
    control.on('open', () => {
      lastPong = Date.now();
      control.ping();
    });
    control.on('pong', () => { lastPong = Date.now(); });
    control.on('error', () => control.terminate());
    control.on('message', (data) => {
      let message;
      try { message = JSON.parse(data.toString()); } catch { return; }
      if (message?.type === 'sync' && Array.isArray(message.connectionIds)) {
        clearTimeout(readyTimeout);
        online = true;
        retryMs = 1000;
        for (const id of message.connectionIds) openPeer(id);
      } else if (message?.type === 'connected') {
        openPeer(message.connectionId);
      } else if (message?.type === 'disconnected') {
        peers.get(message.connectionId)?.terminate();
      } else if (message?.type === 'ping') {
        control.send('{"type":"pong"}');
      }
    });
    control.on('close', () => {
      clearTimeout(readyTimeout);
      clearInterval(heartbeat);
      online = false;
      for (const socket of peers.values()) socket.terminate();
      peers.clear();
      if (!stopped) {
        retry = setTimeout(connect, retryMs);
        retryMs = Math.min(retryMs * 2, 10000);
      }
    });
  }

  connect();
  return {
    isOnline: () => online,
    stop() {
      stopped = true;
      clearTimeout(retry);
      control.terminate();
    },
  };
}
