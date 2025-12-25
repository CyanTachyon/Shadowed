package moe.tachyon.shadowed.route

import at.favre.lib.crypto.bcrypt.BCrypt

internal const val SERVER_AUTH_KEY = "shadowed_auth_key_v1"

private val hasher = BCrypt.with(BCrypt.Version.VERSION_2B)
private val verifier = BCrypt.verifyer(BCrypt.Version.VERSION_2B)

fun encryptPassword(password: String): String = hasher.hashToString(12, password.toCharArray())

fun verifyPassword(password: String, hash: String): Boolean = verifier.verify(password.toCharArray(), hash).verified
