package moe.tachyon.shadowed.route

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteUtilsTest {
    @Test
    fun encryptedPasswordVerifiesSuccessfully() {
        val password = "correct horse battery staple"
        val hash = encryptPassword(password)
        assertTrue(verifyPassword(password, hash), "verify must accept the original password")
    }

    @Test
    fun verifyRejectsWrongPassword() {
        val hash = encryptPassword("the-right-one")
        assertFalse(verifyPassword("the-wrong-one", hash), "verify must reject a wrong password")
    }

    @Test
    fun encryptionProducesDifferentHashesForSameInput() {
        // bcrypt salt is random per call; same input should yield different hashes.
        val a = encryptPassword("repeated")
        val b = encryptPassword("repeated")
        assertNotEquals(a, b, "bcrypt must produce different hashes for the same password")
    }
}
