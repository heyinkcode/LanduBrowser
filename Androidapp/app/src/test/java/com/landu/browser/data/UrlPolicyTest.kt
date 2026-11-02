package com.landu.browser.data

import org.junit.Assert.*
import org.junit.Test

class UrlPolicyTest {
    @Test fun blankIsLocalAndDoesNotContactASearchService() {
        assertEquals("about:blank", UrlPolicy.normalize(" "))
    }

    @Test fun bareDomainUsesHttps() {
        assertEquals("https://example.com/path?q=1", UrlPolicy.normalize(" example.com/path?q=1 "))
    }

    @Test fun explicitHttpsAndIpAddressesWork() {
        assertEquals("https://example.com:8443", UrlPolicy.normalize("HTTPS://example.com:8443"))
        assertTrue(UrlPolicy.canNavigate("https://127.0.0.1:8443/"))
        assertTrue(UrlPolicy.canNavigate("https://[::1]/"))
    }

    @Test fun dangerousAndUnsupportedSchemesAreBlocked() {
        listOf("javascript:alert(1)", "file:///data/data/private", "content://private/file", "intent://launch", "data:text/html,test", "http://example.com", "about:config").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { UrlPolicy.normalize(url) }
            assertFalse(url, UrlPolicy.canNavigate(url))
        }
    }

    @Test fun embeddedCredentialsAndBadPortsAreBlocked() {
        listOf("https://user:pass@example.com", "https://example.com:0", "https://example.com:70000", "https://example.com:-2").forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { UrlPolicy.normalize(url) }
        }
    }

    @Test fun ControlsWhitespaceAndOversizedInputAreRejected() {
        listOf("https://exam ple.com", "https://example.com/\npath", "example.com/" + "a".repeat(2048)).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { UrlPolicy.normalize(url) }
        }
    }
}
