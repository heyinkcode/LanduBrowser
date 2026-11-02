package com.landu.browser.data

import org.junit.Assert.*
import org.junit.Test

class BrowserOptionsTest {
    @Test fun defaultsAreConservativeAndUseSystemNetwork() {
        val options = BrowserOptions()
        assertEquals("", options.proxyUrl)
        assertEquals("", options.userAgent)
        assertEquals(100, options.textZoom)
        assertFalse(options.thirdPartyCookies)
    }

    @Test fun acceptedProxySchemesRequireExplicitPort() {
        listOf("http", "https", "socks5").forEach { scheme ->
            assertEquals("$scheme://proxy.example:8080", ProxyAddress.normalize(" $scheme://PROXY.example:8080 "))
        }
        assertEquals("http://[::1]:8080", ProxyAddress.normalize("http://[::1]:8080"))
    }

    @Test fun unsafeProxyAddressesAreRejected() {
        listOf("proxy.example:8080", "http://proxy.example", "http://host:0", "http://host:65536", "http://user:pass@host:80",
            "http://host:80/path", "http://host:80?query", "http://host:80#fragment", "socks4://host:80", "direct://host:80",
            "http://ho st:80", "http://host:80\nInjected: true").forEach { address ->
            assertThrows(address, IllegalArgumentException::class.java) { ProxyAddress.normalize(address) }
        }
    }

    @Test fun proxyLimitationsMustBeAcknowledged() {
        assertThrows(IllegalArgumentException::class.java) { BrowserOptions(proxyUrl = "http://proxy.example:8080") }
        assertEquals("http://proxy.example:8080", BrowserOptions("http://proxy.example:8080", true).proxyUrl)
    }

    @Test fun userAgentRejectsHeaderInjectionAndOversizedValues() {
        listOf("UA\r\nAuthorization: secret", "UA\u0000", "a".repeat(1025), "中文UA").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { BrowserOptions(userAgent = value) }
        }
        assertEquals("Landu/1.0 (Android)", BrowserOptions(userAgent = "Landu/1.0 (Android)").userAgent)
    }

    @Test fun displaySettingsAreBounded() {
        listOf(0, 74, 201).forEach { zoom -> assertThrows(IllegalArgumentException::class.java) { BrowserOptions(textZoom = zoom) } }
        listOf(0, 11, 33).forEach { size -> assertThrows(IllegalArgumentException::class.java) { BrowserOptions(fontSize = size) } }
        assertThrows(IllegalArgumentException::class.java) { BrowserOptions(fontFamily = "arbitrary-font") }
        assertEquals(200, BrowserOptions(textZoom = 200, fontSize = 32).textZoom)
    }
}
