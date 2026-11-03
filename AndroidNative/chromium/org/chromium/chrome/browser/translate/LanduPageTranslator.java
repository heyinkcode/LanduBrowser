// Copyright 2026 Landu Browser contributors. All rights reserved.
// Use of this source code is governed by the repository license.
package org.chromium.chrome.browser.translate;

import android.app.Activity;
import android.app.AlertDialog;

import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.ContextUtils;
import org.chromium.base.Callback;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabObserver;
import org.chromium.chrome.browser.ui.messages.snackbar.Snackbar;
import org.chromium.chrome.browser.ui.messages.snackbar.SnackbarManager;
import org.chromium.chrome.browser.ui.messages.snackbar.SnackbarManagerProvider;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.common.IsolatedWorldIds;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.widget.Toast;
import org.chromium.url.GURL;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** User-triggered, on-device translation of the current document's text nodes. */
final class LanduPageTranslator implements TabObserver, SnackbarManager.SnackbarController {
    // Reserved for Chrome Translate in chrome/common/chrome_isolated_world_ids.h.
    private static final int TRANSLATION_WORLD = IsolatedWorldIds.ISOLATED_WORLD_ID_CONTENT_END + 1;
    private static final Map<Tab, LanduPageTranslator> SESSIONS = new HashMap<>();
    private final Tab mTab;
    private final WebContents mContents;
    private final RenderFrameHost mFrame;
    private final String mKey = "__landuTranslation_" + UUID.randomUUID().toString().replace("-", "");
    private Translator mTranslator;
    private SnackbarManager mSnackbar;
    private boolean mClosed;
    private boolean mTruncated;
    private int mTranslated;

    static void translate(Tab tab) {
        LanduPageTranslator current = SESSIONS.get(tab);
        if (current != null) {
            current.restore();
            return;
        }
        if (tab.getWebContents() == null || tab.isDestroyed()) return;
        LanduPageTranslator session = new LanduPageTranslator(tab);
        SESSIONS.put(tab, session);
        tab.addObserver(session);
        session.begin();
    }

    private LanduPageTranslator(Tab tab) {
        mTab = tab;
        mContents = tab.getWebContents();
        mFrame = mContents.getMainFrame();
        WindowAndroid window = tab.getWindowAndroid();
        if (window != null) mSnackbar = SnackbarManagerProvider.from(window);
    }

    private boolean active() {
        return !mClosed && !mTab.isDestroyed() && !mContents.isDestroyed()
                && mTab.getWebContents() == mContents && mContents.getMainFrame() == mFrame
                && mFrame.isRenderFrameLive();
    }

    private void begin() {
        try (InputStream input = ContextUtils.getApplicationContext().getAssets()
                        .open("landu/page-translation.js");
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String source = output.toString(StandardCharsets.UTF_8.name());
            if (!active()) { close(); return; }
            mFrame.executeJavaScriptInIsolatedWorld(
                    source + "(" + JSONObject.quote(mKey) + ")", TRANSLATION_WORLD, result -> {
                if (!active()) return;
                try {
                    JSONObject page = new JSONObject(result);
                    mTruncated = page.optBoolean("truncated");
                    if (page.optInt("count") == 0) {
                        fail("当前页面没有可翻译的文字");
                        return;
                    }
                    String language = TranslateBridge.getCurrentLanguage(mTab);
                    if (language.isEmpty() || "und".equals(language)) {
                        language = page.optString("language");
                    }
                    prepare(language);
                } catch (JSONException error) {
                    fail("页面已变化，请重新点击翻译");
                }
            });
        } catch (IOException error) {
            fail("翻译组件读取失败");
        }
    }

    private void prepare(String language) {
        String source = TranslateLanguage.fromLanguageTag(language);
        if (source == null) {
            WindowAndroid window = mTab.getWindowAndroid();
            Activity activity = window == null ? null : window.getActivity().get();
            if (activity == null || activity.isFinishing()) {
                fail("尚未识别网页语言，请稍后重试");
                return;
            }
            new AlertDialog.Builder(activity).setTitle("选择原文语言")
                    .setItems(new String[] {"英语", "日语", "韩语", "德语", "法语", "西班牙语"},
                            (dialog, which) -> prepare(new String[] {"en", "ja", "ko", "de", "fr", "es"}[which]))
                    .setOnCancelListener(dialog -> restore()).show();
            return;
        }
        if (TranslateLanguage.CHINESE.equals(source)) {
            fail("当前页面已是中文");
            return;
        }
        try {
            MlKitContext.initializeIfNeeded(ContextUtils.getApplicationContext());
            mTranslator = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(source).setTargetLanguage(TranslateLanguage.CHINESE).build());
            status("正在准备本地翻译 · 首次使用需下载语言模型", "取消");
            mTranslator.downloadModelIfNeeded()
                    .addOnSuccessListener(unused -> { if (active()) nextBatch(); })
                    .addOnFailureListener(error -> {
                        if (active()) fail("语言模型下载失败，请检查手机网络后重试");
                    });
        } catch (RuntimeException error) {
            fail("本地翻译组件初始化失败");
        }
    }

    private void nextBatch() {
        evaluate("next()", result -> {
            try {
                JSONArray batch = new JSONArray(result);
                if (batch.length() == 0) {
                    releaseTranslator();
                    status(mTranslated == 0 ? "没有需要翻译的文字" :
                            (mTruncated ? "已翻译部分内容 · Google Translate" : "已译为中文 · Google Translate"), "原文");
                    return;
                }
                status("正在原页翻译 · Google Translate", "取消");
                translateItem(batch, 0, new JSONArray());
            } catch (JSONException error) {
                close();
            }
        });
    }

    private void translateItem(JSONArray batch, int index, JSONArray translated) {
        if (!active()) return;
        if (index == batch.length()) {
            evaluate("apply(" + translated + ")", result -> {
                try { mTranslated += Integer.parseInt(result); }
                catch (NumberFormatException error) { close(); return; }
                nextBatch();
            });
            return;
        }
        JSONObject item = batch.optJSONObject(index);
        if (item == null) { close(); return; }
        translateText(item.optString("text"), "", translatedText -> {
            try {
                translated.put(new JSONObject().put("id", item.getInt("id")).put("text", translatedText));
                translateItem(batch, index + 1, translated);
            } catch (JSONException error) {
                close();
            }
        });
    }

    private void translateText(String remaining, String prefix, Callback<String> callback) {
        if (!active() || mTranslator == null) return;
        int end = Math.min(remaining.length(), 2000);
        if (end < remaining.length() && Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
        String chunk = remaining.substring(0, end);
        String rest = remaining.substring(end);
        mTranslator.translate(chunk).addOnSuccessListener(text -> {
            if (!active()) return;
            String value = prefix + text;
            if (rest.isEmpty()) callback.onResult(value);
            else translateText(rest, value, callback);
        }).addOnFailureListener(error -> {
            if (active()) fail("翻译中断，可重新点击翻译重试");
        });
    }

    private void evaluate(String operation, Callback<String> callback) {
        if (!active()) return;
        String state = "globalThis[" + JSONObject.quote(mKey) + "]";
        mFrame.executeJavaScriptInIsolatedWorld(
                "(" + state + " ? " + state + "." + operation + " : null)", TRANSLATION_WORLD,
                value -> {
                    if (!active()) return;
                    if ("null".equals(value)) close();
                    else callback.onResult(value);
                });
    }

    private void status(String text, String action) {
        if (mSnackbar == null) return;
        mSnackbar.dismissSnackbars(this);
        mSnackbar.showSnackbar(Snackbar.make(text, this, Snackbar.TYPE_PERSISTENT,
                Snackbar.UMA_UNKNOWN).setAction(action, null));
    }

    private void fail(String message) {
        restore();
        Toast.makeText(ContextUtils.getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    private void restore() {
        evaluate("restore()", unused -> {});
        close();
    }

    private void releaseTranslator() {
        if (mTranslator != null) { mTranslator.close(); mTranslator = null; }
    }

    private void close() {
        if (mClosed) return;
        mClosed = true;
        releaseTranslator();
        mTab.removeObserver(this);
        SESSIONS.remove(mTab);
        if (mSnackbar != null) mSnackbar.dismissSnackbars(this);
    }

    @Override
    public void onAction(Object data) { restore(); }

    @Override
    public void onPageLoadStarted(Tab tab, GURL url) { close(); }

    @Override
    public void onDestroyed(Tab tab) { close(); }

    @Override
    public void onHidden(Tab tab, int type) { restore(); }
}
