package moe.tachyon.shadowed.route

import at.favre.lib.crypto.bcrypt.BCrypt

private val hasher = BCrypt.with(BCrypt.Version.VERSION_2B)
private val verifier = BCrypt.verifyer(BCrypt.Version.VERSION_2B)

fun encryptPassword(password: String): String = hasher.hashToString(12, password.toCharArray())

fun verifyPassword(password: String, hash: String): Boolean = verifier.verify(password.toCharArray(), hash).verified

// Constant-time dummy bcrypt hash (VERSION_2B, cost 12) used by login /
// resetPassword to keep the response time of "user not found" code paths
// indistinguishable from "wrong password" code paths. Any change to the cost
// factor or version MUST keep this dummy in sync with encryptPassword above.
internal val DUMMY_BCRYPT_HASH: String = hasher.hashToString(12, "dummy-password".toCharArray())
