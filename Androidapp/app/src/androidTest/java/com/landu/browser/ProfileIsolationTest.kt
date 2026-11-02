package com.landu.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landu.browser.browser.LocalBrowser
import com.landu.browser.data.Environment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProfileIsolationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "https://isolation.test.landu.invalid/"

    private fun environment(): Environment = Environment(UUID.randomUUID().toString(), "隔离验证", "测试", "about:blank", System.currentTimeMillis())

    private fun open(environment: Environment): WebView {
        val holder = AtomicReference<WebView>()
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            assertTrue("MULTI_PROFILE unavailable: this device must not be certified", LocalBrowser.supported())
            val view = LocalBrowser.create(instrumentation.targetContext, environment)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val body = "<!doctype html><meta charset='utf-8'><title>Landu isolation test</title>"
                    return WebResourceResponse("text/html", "UTF-8", body.byteInputStream())
                }
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            holder.set(view)
            view.loadUrl(origin)
        }
        assertTrue("Test document did not load", loaded.await(20, TimeUnit.SECONDS))
        return holder.get()
    }

    private fun evaluate(view: WebView, script: String): String {
        val finished = CountDownLatch(1)
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result.set(it); finished.countDown() } }
        assertTrue("JavaScript callback timed out", finished.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    private fun asyncResult(view: WebView, expression: String): String {
        evaluate(view, "window.testResult = null; ($expression).then(value => window.testResult = JSON.stringify(value)).catch(() => window.testResult = 'ERROR');")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val result = evaluate(view, "window.testResult")
            if (result != "null") { assertNotEquals("\"ERROR\"", result); return result }
            Thread.sleep(50)
        }
        throw AssertionError("Async storage operation timed out")
    }

    @Test fun cookiesLocalStorageIndexedDbAndCacheDoNotCrossProfiles() {
        val firstEnvironment = environment()
        val secondEnvironment = environment()
        val views = mutableListOf<WebView>()
        try {
            val first = open(firstEnvironment).also { views.add(it) }
            val second = open(secondEnvironment).also { views.add(it) }
            evaluate(first, "document.cookie='landu=A;path=/;max-age=3600;Secure';localStorage.setItem('landu','A');")
            assertTrue(evaluate(first, "document.cookie").contains("landu=A"))
            assertEquals("\"A\"", evaluate(first, "localStorage.getItem('landu')"))
            assertEquals("\"\"", evaluate(second, "document.cookie"))
            assertEquals("null", evaluate(second, "localStorage.getItem('landu')"))
            assertEquals("\"true\"", asyncResult(first, "new Promise((resolve,reject)=>{const request=indexedDB.open('landu',1);request.onupgradeneeded=()=>request.result.createObjectStore('values');request.onerror=reject;request.onsuccess=()=>{const db=request.result;const tx=db.transaction('values','readwrite');tx.objectStore('values').put('A','marker');tx.oncomplete=()=>{db.close();resolve(true)};tx.onerror=reject;};})"))
            val databaseNames = "indexedDB.databases().then(entries=>entries.map(entry=>entry.name))"
            assertTrue(asyncResult(first, databaseNames).contains("landu"))
            assertFalse(asyncResult(second, databaseNames).contains("landu"))
            assertEquals("\"true\"", asyncResult(first, "caches.open('landu').then(cache=>cache.put('/marker',new Response('A'))).then(()=>true)"))
            assertTrue(asyncResult(first, "caches.keys()").contains("landu"))
            assertFalse(asyncResult(second, "caches.keys()").contains("landu"))
            instrumentation.runOnMainSync { LocalBrowser.destroy(first) }
            views.remove(first)
            val reopened = open(firstEnvironment).also { views.add(it) }
            assertEquals("\"A\"", evaluate(reopened, "localStorage.getItem('landu')"))
            assertTrue(evaluate(reopened, "document.cookie").contains("landu=A"))
            assertTrue(asyncResult(reopened, databaseNames).contains("landu"))
        } finally {
            instrumentation.runOnMainSync {
                views.forEach { LocalBrowser.destroy(it) }
                listOf(firstEnvironment, secondEnvironment).forEach { runCatching { LocalBrowser.delete(it.profileName) } }
            }
        }
    }
}
