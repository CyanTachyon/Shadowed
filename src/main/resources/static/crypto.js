const cryptoObj = window.crypto || window.msCrypto;
const subtle = cryptoObj.subtle;

/**
 * Generates an RSA-OAEP key pair for encryption and decryption.
 * @returns {Promise<CryptoKeyPair>} The generated key pair.
 */
async function generateKeyPair()
{
    return await subtle.generateKey(
        {
            name: "RSA-OAEP",
            modulusLength: 2048,
            publicExponent: new Uint8Array([1, 0, 1]),
            hash: "SHA-256"
        },
        true,
        ["encrypt", "decrypt"]
    );
}

/**
 * Derives a key from a password using PBKDF2.
 * @param {string} password - The user's password.
 * @param {string} username - Used as salt.
 * @returns {Promise<CryptoKey>} The derived key for wrapping/unwrapping the private key.
 */
async function deriveKeyFromPassword(password, username)
{
    const enc = new TextEncoder();
    const keyMaterial = await subtle.importKey(
        "raw",
        enc.encode(password),
        "PBKDF2",
        false,
        ["deriveKey"]
    );

    return await subtle.deriveKey(
        {
            name: "PBKDF2",
            salt: enc.encode(username),
            iterations: 100000,
            hash: "SHA-256"
        },
        keyMaterial,
        { name: "AES-GCM", length: 256 },
        true,
        ["encrypt", "decrypt"]
    );
}

/**
 * Encrypts the private key with the derived password key.
 * @param {CryptoKey} privateKey - The private key to encrypt.
 * @param {CryptoKey} passwordKey - The key derived from the password.
 * @returns {Promise<string>} The encrypted private key (base64 encoded JSON).
 */
async function encryptPrivateKey(privateKey, passwordKey)
{
    const exportedKey = await subtle.exportKey("jwk", privateKey);
    const enc = new TextEncoder();
    const data = enc.encode(JSON.stringify(exportedKey));

    // 12 bytes IV for AES-GCM
    const iv = cryptoObj.getRandomValues(new Uint8Array(12));

    const encryptedContent = await subtle.encrypt(
        {
            name: "AES-GCM",
            iv: iv
        },
        passwordKey,
        data
    );

    const result = {
        iv: Array.from(iv),
        data: Array.from(new Uint8Array(encryptedContent))
    };

    return JSON.stringify(result);
}

/**
 * Decrypts the private key using the derived password key.
 * @param {string} encryptedPrivateKeyStr - The encrypted private key string.
 * @param {CryptoKey} passwordKey - The key derived from the password.
 * @returns {Promise<CryptoKey>} The decrypted private key.
 */
async function decryptPrivateKey(encryptedPrivateKeyStr, passwordKey)
{
    try
    {
        const encryptedObj = JSON.parse(encryptedPrivateKeyStr);
        const iv = new Uint8Array(encryptedObj.iv);
        const data = new Uint8Array(encryptedObj.data);

        const decryptedData = await subtle.decrypt(
            {
                name: "AES-GCM",
                iv: iv
            },
            passwordKey,
            data
        );

        const enc = new TextDecoder();
        const jwk = JSON.parse(enc.decode(decryptedData));

        return await subtle.importKey(
            "jwk",
            jwk,
            {
                name: "RSA-OAEP",
                hash: "SHA-256"
            },
            true,
            ["decrypt"]
        );
    }
    catch (e)
    {
        console.error("Failed to decrypt private key", e);
        return null;
    }
}

/**
 * Exports the public key to JWK format for sharing.
 * @param {CryptoKey} publicKey - The public key.
 * @returns {Promise<string>} The public key string.
 */
async function exportPublicKey(publicKey)
{
    const exported = await subtle.exportKey("jwk", publicKey);
    return JSON.stringify(exported);
}

/**
 * Imports a public key from JWK string.
 * @param {string} publicKeyStr - The public key string.
 * @returns {Promise<CryptoKey>} The public key.
 */
async function importPublicKey(publicKeyStr)
{
    const jwk = JSON.parse(publicKeyStr);
    return await subtle.importKey(
        "jwk",
        jwk,
        {
            name: "RSA-OAEP",
            hash: "SHA-256"
        },
        true,
        ["encrypt"]
    );
}

/**
 * Hashes the password using HMAC-SHA256 with a server-provided key.
 * @param {string} password - The user's password.
 * @param {string} serverKey - The server provided auth key.
 * @returns {Promise<string>} Hex string of the hash.
 */
async function hashPasswordWithServerKey(password, serverKey)
{
    const enc = new TextEncoder();
    const keyData = enc.encode(serverKey);
    const passwordData = enc.encode(password);

    const key = await subtle.importKey(
        "raw",
        keyData,
        { name: "HMAC", hash: "SHA-256" },
        false,
        ["sign"]
    );

    const signature = await subtle.sign(
        "HMAC",
        key,
        passwordData
    );

    const hashArray = Array.from(new Uint8Array(signature));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Generates an AES-GCM symmetric key.
 * @returns {Promise<CryptoKey>} The generated key.
 */
async function generateSymmetricKey()
{
    return await subtle.generateKey(
        {
            name: "AES-GCM",
            length: 256
        },
        true,
        ["encrypt", "decrypt"]
    );
}

/**
 * Encrypts (wraps) a symmetric key with a public key (RSA-OAEP).
 * @param {CryptoKey} symmetricKey - The key to wrap.
 * @param {CryptoKey} publicKey - The wrapping key.
 * @returns {Promise<string>} Base64 encoded encrypted key.
 */
async function encryptSymmetricKey(symmetricKey, publicKey)
{
    const rawData = await subtle.exportKey("raw", symmetricKey);
    const encryptedData = await subtle.encrypt(
        {
            name: "RSA-OAEP"
        },
        publicKey,
        rawData
    );
    return arrayBufferToBase64(encryptedData);
}

/**
 * Decrypts (unwraps) a symmetric key with a private key.
 * @param {string} encryptedKeyStr - Base64 encoded encrypted key.
 * @param {CryptoKey} privateKey - The unwrapping key.
 * @returns {Promise<CryptoKey>} The unwrapped AES-GCM key.
 */
async function decryptSymmetricKey(encryptedKeyStr, privateKey)
{
    try
    {
        const encryptedData = base64ToArrayBuffer(encryptedKeyStr);
        const rawKey = await subtle.decrypt(
            {
                name: "RSA-OAEP"
            },
            privateKey,
            encryptedData
        );
        return await subtle.importKey(
            "raw",
            rawKey,
            {
                name: "AES-GCM",
                length: 256
            },
            true,
            ["encrypt", "decrypt"]
        );
    }
    catch (e)
    {
        console.error("Failed to decrypt symmetric key", e);
        return null;
    }
}

/**
 * Encrypts a message using AES-GCM.
 * @param {string} message - Plaintext.
 * @param {CryptoKey} key - AES-GCM key.
 * @returns {Promise<string>} Base64 encoded JSON {iv, data}.
 */
async function encryptMessageString(message, key)
{
    const data = new TextEncoder().encode(message);
    return await encryptMessageBytes(data, key);
}

async function encryptMessageBytes(dataBytes, key)
{
    const iv = cryptoObj.getRandomValues(new Uint8Array(12));
    const encryptedContent = await subtle.encrypt(
        {
            name: "AES-GCM",
            iv: iv
        },
        key,
        dataBytes
    );
    const ivString = bytesToBase64(iv);
    const dataString = arrayBufferToBase64(encryptedContent);
    return ivString + "." + dataString;
}

/**
 * Decrypts a message using AES-GCM.
 * @param {string} encryptedMessageStr - Base64 encoded JSON {iv, data}.
 * @param {CryptoKey} key - AES-GCM key.
 * @returns {Promise<string>} Plaintext.
 */
async function decryptMessageString(encryptedMessageStr, key)
{
    return new TextDecoder().decode(await decryptMessageBytes(encryptedMessageStr, key));
}

async function decryptMessageBytes(encryptedMessageStr, key)
{
    const split = encryptedMessageStr.split(".", 2);
    const iv = base64ToBytes(split[0]);
    const data = base64ToBytes(split[1]);
    return await subtle.decrypt(
        {
            name: "AES-GCM",
            iv: iv
        },
        key,
        data
    );
}

function arrayBufferToBase64(buffer)
{
    return bytesToBase64(new Uint8Array(buffer));
}

function bytesToBase64(bytes)
{
    let binary = '';
    const len = bytes.length;
    for (let i = 0; i < len; i++)
        binary += String.fromCharCode(bytes[i]);
    return btoa(binary);
}

function base64ToArrayBuffer(base64)
{
    const binary_string = atob(base64);
    const len = binary_string.length;
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++)
        bytes[i] = binary_string.charCodeAt(i);
    return bytes.buffer;
}


function base64ToBytes(base64)
{
    return new Uint8Array(base64ToArrayBuffer(base64));
}