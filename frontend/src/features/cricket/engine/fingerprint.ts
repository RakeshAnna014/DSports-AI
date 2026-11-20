import { sha256Bytes, toHex } from './sha256';

/** What can appear in a state: the engine's states hold only these. */
export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

/**
 * The canonical text of a state, byte for byte what the Java vector writer produces: object keys sorted alphabetically at every level, no spaces, numbers as
 * plain integers, strings that are plain ASCII identifiers (ids, enum names). It throws on anything else rather than produce text that might differ from Java's.
 */
export function canonicalJson(value: Json): string {
  if (value === null) {
    return 'null';
  }
  switch (typeof value) {
    case 'boolean':
      return value ? 'true' : 'false';
    case 'number':
      if (!Number.isSafeInteger(value)) {
        throw new Error(`Only integers can be fingerprinted, got ${value}`);
      }
      return String(value);
    case 'string':
      if (!/^[\x20-\x7e]*$/.test(value) || value.includes('"') || value.includes('\\')) {
        throw new Error(`Only plain ASCII text can be fingerprinted, got ${JSON.stringify(value)}`);
      }
      return `"${value}"`;
    default:
      if (Array.isArray(value)) {
        return `[${value.map(canonicalJson).join(',')}]`;
      }
      return `{${Object.keys(value)
        .sort()
        .map((key) => `${canonicalJson(key)}:${canonicalJson(value[key])}`)
        .join(',')}}`;
  }
}

/** The first 8 bytes of the SHA-256 of the canonical text, as 16 hex characters: the fingerprint recorded in the vectors and compared with the server's. */
export const fingerprint = (state: Json): string => toHex(sha256Bytes(canonicalJson(state)), 8);
