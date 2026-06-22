package moe.tachyon.shadowed.route

import at.favre.lib.crypto.bcrypt.BCrypt

private val hasher = BCrypt.with(BCrypt.Version.VERSION_2B)
private val verifier = BCrypt.verifyer(BCrypt.Version.VERSION_2B)

fun encryptPassword(password: String): String = hasher.hashToString(12, password.toCharArray())

fun verifyPassword(password: String, hash: String): Boolean = verifier.verify(password.toCharArray(), hash).verified
