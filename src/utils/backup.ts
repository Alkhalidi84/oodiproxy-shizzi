const ITERATIONS = 310_000;
const FORMAT = 'nexalink-encrypted-backup';
const MAX_BACKUP_BYTES = 5 * 1024 * 1024;

function asArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

function toBase64(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((value) => { binary += String.fromCharCode(value); });
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array {
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    throw new Error('The backup file contains invalid encoded data.');
  }
  const binary = atob(value);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function deriveKey(password: string, salt: Uint8Array): Promise<CryptoKey> {
  const material = await globalThis.crypto.subtle.importKey(
    'raw',
    asArrayBuffer(new TextEncoder().encode(password)),
    'PBKDF2',
    false,
    ['deriveKey'],
  );
  return globalThis.crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt: asArrayBuffer(salt), iterations: ITERATIONS, hash: 'SHA-256' },
    material,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

export async function encryptBackup(payload: unknown, password: string): Promise<Blob> {
  if (password.length < 10) throw new Error('Use a backup password with at least 10 characters.');
  if (!globalThis.crypto?.subtle) throw new Error('Secure encryption is unavailable in this browser.');
  const salt = globalThis.crypto.getRandomValues(new Uint8Array(16));
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(password, salt);
  const plaintext = new TextEncoder().encode(JSON.stringify(payload));
  const ciphertext = await globalThis.crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: asArrayBuffer(iv) },
    key,
    asArrayBuffer(plaintext),
  );
  const envelope = {
    format: FORMAT,
    version: 1,
    kdf: 'PBKDF2-SHA256',
    iterations: ITERATIONS,
    cipher: 'AES-256-GCM',
    salt: toBase64(salt),
    iv: toBase64(iv),
    ciphertext: toBase64(new Uint8Array(ciphertext)),
  };
  return new Blob([JSON.stringify(envelope, null, 2)], { type: 'application/json' });
}

export async function decryptBackup(file: File, password: string): Promise<unknown> {
  if (file.size > MAX_BACKUP_BYTES) throw new Error('Backup files must be 5 MB or smaller.');
  if (!globalThis.crypto?.subtle) throw new Error('Secure encryption is unavailable in this browser.');
  if (password.length < 10) throw new Error('Use the password that was created for this backup.');
  let envelope: Record<string, unknown>;
  try {
    envelope = JSON.parse(await file.text()) as Record<string, unknown>;
  } catch {
    throw new Error('The backup file is not valid JSON.');
  }
  if (envelope.format !== FORMAT || envelope.version !== 1 ||
      envelope.kdf !== 'PBKDF2-SHA256' || envelope.cipher !== 'AES-256-GCM' ||
      envelope.iterations !== ITERATIONS) {
    throw new Error('This is not a supported NexaLink encrypted backup.');
  }
  const salt = fromBase64(String(envelope.salt || ''));
  const iv = fromBase64(String(envelope.iv || ''));
  const ciphertext = fromBase64(String(envelope.ciphertext || ''));
  if (salt.length !== 16 || iv.length !== 12 || ciphertext.length < 16) {
    throw new Error('The backup file is damaged or incomplete.');
  }
  const key = await deriveKey(password, salt);
  let plaintext: ArrayBuffer;
  try {
    plaintext = await globalThis.crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: asArrayBuffer(iv) },
      key,
      asArrayBuffer(ciphertext),
    );
  } catch {
    throw new Error('The password is incorrect or the backup was modified.');
  }
  try {
    return JSON.parse(new TextDecoder().decode(plaintext));
  } catch {
    throw new Error('The decrypted backup payload is invalid.');
  }
}
