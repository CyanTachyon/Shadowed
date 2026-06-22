-- V3__pbkdf2_params.sql - Per-user PBKDF2 parameters (progressive hardening, C-3)
--
-- Background: the previous KDF used PBKDF2-HMAC-SHA-256 with iterations=100000
-- and salt=username. Usernames are enumerable, so an attacker with a DB dump
-- could pre-compute rainbow tables keyed by known usernames. OWASP (2023+)
-- recommends >= 600000 iterations for PBKDF2-SHA-256.
--
-- Progressive migration strategy (chosen by user):
--   * Existing rows (salt = NULL): clients keep using username || 100000.
--     Next time the user changes their password, the client generates a random
--     16-byte salt, derives the new key with 600000 iterations, and writes both
--     values back via /resetPassword.
--   * New registrations: the client always supplies a random base64 salt and
--     iterations >= 600000. The server only validates and stores.
--
-- Salt is base64-encoded 16 bytes (~24 chars); column width 64 leaves room for
-- larger salts if we ever want them.

ALTER TABLE users ADD COLUMN pbkdf2_salt VARCHAR(64);
ALTER TABLE users ADD COLUMN pbkdf2_iterations INTEGER NOT NULL DEFAULT 100000;
