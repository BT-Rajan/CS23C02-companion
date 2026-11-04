package app.cs23c02.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConfigTest {
    @Test
    fun serverAddressIsAlwaysHttpsWithNoPathOrTrailingSlash() {
        assertTrue(ApiConfig.BASE_URL.startsWith("https://"))
        assertFalse(ApiConfig.BASE_URL.removePrefix("https://").contains("/"))
        assertTrue(ApiConfig.HOST.isNotEmpty())
    }

    @Test
    fun pinsAreParsedAsNonEmptyEntries() = assertTrue(ApiConfig.CERT_PINS.all { it.isNotBlank() })
}
