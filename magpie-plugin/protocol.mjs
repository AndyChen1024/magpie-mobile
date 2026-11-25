import { createHash } from 'node:crypto';
import { ConnectionOfferV2Schema } from '@getpaseo/protocol/connection-offer';

export const version = 1;
export const localRelay = { endpoint: '127.0.0.1:18400', useTls: false };

export function encodePairing(offer, ticket) {
  const encoded = Buffer.from(JSON.stringify(offer)).toString('base64url');
  return `magpie-mobile://pair#offer=${encoded}&ticket=${ticket}`;
}

export function parsePairing(link) {
  const url = new URL(link.trim());
  const fragment = new URLSearchParams(url.hash.slice(1));
  const offer = ConnectionOfferV2Schema.parse(
    JSON.parse(Buffer.from(fragment.get('offer') ?? '', 'base64url').toString('utf8')),
  );
  const ticket = fragment.get('ticket');
  if (url.protocol !== 'magpie-mobile:' || url.hostname !== 'pair' ||
      offer.minimumSecurityMode !== 'protected' || !isBase64url(ticket, 32)) {
    throw new Error('Invalid Magpie phone pairing link');
  }
  return { offer, ticket };
}

export function isBase64url(value, bytes) {
  if (typeof value !== 'string' || value.length !== Math.ceil(bytes * 8 / 6)) return false;
  return Buffer.from(value, 'base64url').toString('base64url') === value;
}

export function proofBytes(serverId, nonce, operation, publicKey, ticket = '') {
  return Buffer.from(['magpie-connect-phone/v1', serverId, nonce, operation, publicKey, ticket].join('\n'));
}

export function deviceId(publicKey) {
  return createHash('sha256').update(Buffer.from(publicKey, 'base64url')).digest('base64url');
}
