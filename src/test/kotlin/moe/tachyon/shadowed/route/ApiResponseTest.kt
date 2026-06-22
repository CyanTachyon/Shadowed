package moe.tachyon.shadowed.route

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiResponseTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    @Test
    fun successWithDataSerializesToExpectedEnvelope() {
        val response = ApiResponse<String>(success = true, data = "hello")
        val encoded = json.encodeToString<ApiResponse<String>>(response)
        assertEquals("""{"success":true,"data":"hello","error":null}""", encoded)
    }

    @Test
    fun errorEnvelopeSerializesWithNullData() {
        val response = ApiResponse<String>(success = false, error = "boom")
        val encoded = json.encodeToString<ApiResponse<String>>(response)
        assertEquals("""{"success":false,"data":null,"error":"boom"}""", encoded)
    }

    @Test
    fun roundTripPreservesFields() {
        val original = ApiResponse(success = true, data = "payload", error = null)
        val encoded = json.encodeToString<ApiResponse<String>>(original)
        val decoded = json.decodeFromString<ApiResponse<String>>(encoded)
        assertEquals(original, decoded)
    }
}
