package com.landu.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.landu.browser.browser.LocalBrowser
import com.landu.browser.data.BrowserOptions
import com.landu.browser.data.Environment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserSettingsTest {
    @Test fun savedOptionsActuallyReachWebViewSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertTrue("MULTI_PROFILE required", WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            val options = BrowserOptions(userAgent = "Landu-Test/2", wideViewport = true, textZoom = 150, fontSize = 22,
                fontFamily = "serif", javaScript = false, domStorage = false, images = false, thirdPartyCookies = true)
            val environment = Environment(UUID.randomUUID().toString(), "设置验证", "", "about:blank", 1, options = options)
            val browser = LocalBrowser.create(instrumentation.targetContext, environment)
            try {
                assertEquals(options.userAgent, browser.settings.userAgentString)
                assertEquals(150, browser.settings.textZoom)
                assertEquals(22, browser.settings.defaultFontSize)
                assertEquals("serif", browser.settings.standardFontFamily)
                assertTrue(browser.settings.useWideViewPort)
                assertFalse(browser.settings.javaScriptEnabled)
                assertFalse(browser.settings.domStorageEnabled)
                assertFalse(browser.settings.loadsImagesAutomatically)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    assertTrue(WebViewCompat.getProfile(browser).cookieManager.acceptThirdPartyCookies(browser))
                    assertEquals(environment.profileName, WebViewCompat.getProfile(browser).name)
                }
                assertFalse(browser.settings.allowFileAccess)
                assertFalse(browser.settings.allowContentAccess)
            } finally { LocalBrowser.destroy(browser) }
        }
    }
}
