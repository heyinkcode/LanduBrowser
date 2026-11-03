// Copyright 2026 Landu Browser contributors. All rights reserved.
// Use of this source code is governed by the repository license.
package org.chromium.chrome.browser.translate;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

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
final class LanduPageTranslator implements TabObserver {
    // Reserved for Chrome Translate in chrome/common/chrome_isolated_world_ids.h.
    private static final int TRANSLATION_WORLD = IsolatedWorldIds.ISOLATED_WORLD_ID_CONTENT_END + 1;
    private static final Map<Tab, LanduPageTranslator> SESSIONS = new HashMap<>();
    private final Tab mTab;
    private final WebContents mContents;
    private final RenderFrameHost mFrame;
    private final String mKey = "__landuTranslation_" + UUID.randomUUID().toString().replace("-", "");
    private Translator mTranslator;
    private TranslationBubble mBubble;
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
        View anchor = mTab.getView();
        if (anchor == null || !anchor.isAttachedToWindow()) return;
        if (mBubble == null) mBubble = new TranslationBubble(anchor, this::restore);
        mBubble.update(text, action);
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
        if (mBubble != null) { mBubble.dismiss(); mBubble = null; }
    }

    /** A native, edge-docked control whose touch area is limited to the visible control. */
    private static final class TranslationBubble {
        private static final long PANEL_DURATION_MS = 300;
        private static final long BUTTON_DURATION_MS = 180;
        private static final long COLLAPSE_DELAY_MS = 5000;
        private final View mAnchor;
        private final Runnable mRestore;
        private final PopupWindow mPopup;
        private final LinearLayout mRoot;
        private final TextView mButton;
        private final LinearLayout mDetails;
        private final TextView mStatus;
        private final TextView mAction;
        private final Rect mBounds = new Rect();
        private final Runnable mCollapse = () -> setExpanded(false);
        private final View.OnLayoutChangeListener mLayoutListener =
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> position();
        private final View.OnAttachStateChangeListener mAttachListener = new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {}

            @Override
            public void onViewDetachedFromWindow(View view) { mRestore.run(); }
        };
        private final int mTextColor;
        private ValueAnimator mDockAnimator;
        private boolean mExpanded;
        private boolean mDockRight = true;
        private boolean mDragging;
        private boolean mDismissed;
        private float mVerticalFraction = 0.55f;
        private float mDownX;
        private float mDownY;
        private int mDragStartX;
        private int mDragStartY;
        private int mX;
        private int mY;

        TranslationBubble(View anchor, Runnable restore) {
            mAnchor = anchor;
            mRestore = restore;
            boolean dark = (anchor.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            mTextColor = dark ? 0xffd7e6ff : 0xff315e99;
            mRoot = new LinearLayout(anchor.getContext());
            mRoot.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable background = new GradientDrawable();
            background.setColor(dark ? 0xff26364a : 0xfff4f8ff);
            background.setCornerRadius(dp(16));
            background.setStroke(dp(1), dark ? 0xff526784 : 0xffdce4f2);
            mRoot.setBackground(background);
            mRoot.setClipToOutline(true);
            mButton = button("译");
            mButton.setTextSize(18);
            mButton.setOnClickListener(view -> setExpanded(true));
            mButton.setOnTouchListener(this::drag);
            mDetails = new LinearLayout(anchor.getContext());
            mDetails.setOrientation(LinearLayout.VERTICAL);
            mDetails.setPadding(dp(12), dp(10), dp(12), dp(4));
            mStatus = new TextView(anchor.getContext());
            mStatus.setTextSize(13);
            mStatus.setTextColor(mTextColor);
            mStatus.setMaxLines(4);
            mDetails.addView(mStatus);
            LinearLayout actions = new LinearLayout(anchor.getContext());
            mAction = button("");
            mAction.setOnClickListener(view -> mRestore.run());
            TextView collapse = button("收起");
            collapse.setOnClickListener(view -> setExpanded(false));
            actions.addView(mAction, new LinearLayout.LayoutParams(0, dp(44), 1));
            actions.addView(collapse, new LinearLayout.LayoutParams(0, dp(44), 1));
            mDetails.addView(actions);
            mRoot.addView(mButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
            mRoot.addView(mDetails);
            mDetails.setVisibility(View.GONE);
            mPopup = new PopupWindow(mRoot, dp(44), dp(44), false);
            mPopup.setElevation(dp(4));
            mPopup.setOutsideTouchable(false);
            mPopup.setTouchModal(false);
            mPopup.setIsLaidOutInScreen(true);
            mPopup.setIsClippedToScreen(true);
            anchor.addOnLayoutChangeListener(mLayoutListener);
            anchor.addOnAttachStateChangeListener(mAttachListener);
        }

        private int dp(int value) {
            return Math.round(value * mAnchor.getResources().getDisplayMetrics().density);
        }

        private TextView button(String text) {
            TextView view = new TextView(mAnchor.getContext());
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            view.setTextColor(mTextColor);
            view.setGravity(Gravity.CENTER);
            view.setMinHeight(dp(44));
            view.setMinWidth(dp(44));
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22315e99), null, null));
            view.setFocusable(true);
            return view;
        }

        void update(String text, String action) {
            mStatus.setText(text);
            mAction.setText(action);
            mButton.setContentDescription(text + "，点按展开翻译操作，可拖动到屏幕边缘");
            position();
        }

        private void setExpanded(boolean expanded) {
            if (mDismissed) return;
            mAnchor.removeCallbacks(mCollapse);
            mExpanded = expanded;
            mButton.setVisibility(expanded ? View.GONE : View.VISIBLE);
            mDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
            position();
            // 贴边展开与收回沿同一路径，只改变透明度和位移。
            mRoot.animate().cancel();
            mRoot.setAlpha(0.5f);
            mRoot.setTranslationX(dp(8) * (mDockRight ? 1 : -1));
            mRoot.animate().alpha(1).translationX(0)
                    .setDuration(ValueAnimator.areAnimatorsEnabled()
                            ? (expanded ? PANEL_DURATION_MS : BUTTON_DURATION_MS) : 0)
                    .setInterpolator(new PathInterpolator(0, 0, 0.2f, 1)).start();
            if (expanded) mAnchor.postDelayed(mCollapse, COLLAPSE_DELAY_MS);
        }

        private boolean measureBounds() {
            Rect display = new Rect();
            mAnchor.getWindowVisibleDisplayFrame(display);
            if (!mAnchor.getGlobalVisibleRect(mBounds) || !mBounds.intersect(display)) return false;
            mBounds.inset(dp(8), dp(48));
            return mBounds.width() >= dp(44) && mBounds.height() >= dp(44);
        }

        private void position() {
            if (mDismissed || !mAnchor.isAttachedToWindow()) return;
            if (!measureBounds()) { mPopup.dismiss(); return; }
            int width = mExpanded ? Math.min(dp(260), mBounds.width()) : dp(44);
            mRoot.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(mBounds.height(), View.MeasureSpec.AT_MOST));
            int height = mExpanded ? mRoot.getMeasuredHeight() : dp(44);
            mX = mDockRight ? mBounds.right - width : mBounds.left;
            mY = mBounds.top + Math.round((mBounds.height() - height) * mVerticalFraction);
            if (mPopup.isShowing()) {
                mPopup.update(mX, mY, width, height);
            } else {
                mPopup.setWidth(width);
                mPopup.setHeight(height);
                mPopup.showAtLocation(mAnchor, Gravity.TOP | Gravity.LEFT, mX, mY);
            }
        }

        private boolean drag(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (mDockAnimator != null) mDockAnimator.cancel();
                    mRoot.animate().cancel();
                    view.setPressed(true);
                    mRoot.setTranslationX(0);
                    mRoot.setScaleX(0.97f);
                    mRoot.setScaleY(0.97f);
                    mDownX = event.getRawX();
                    mDownY = event.getRawY();
                    mDragStartX = mX;
                    mDragStartY = mY;
                    mDragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - mDownX;
                    float dy = event.getRawY() - mDownY;
                    mDragging |= Math.hypot(dx, dy) > ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                    if (mDragging && measureBounds()) {
                        mX = Math.max(mBounds.left, Math.min(mBounds.right - dp(44), mDragStartX + Math.round(dx)));
                        mY = Math.max(mBounds.top, Math.min(mBounds.bottom - dp(44), mDragStartY + Math.round(dy)));
                        mPopup.update(mX, mY, -1, -1);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    mRoot.setScaleX(1);
                    mRoot.setScaleY(1);
                    if (mDragging && measureBounds()) {
                        mDockRight = mX + dp(22) >= mBounds.centerX();
                        mVerticalFraction = (mY - mBounds.top) / (float) Math.max(1, mBounds.height() - dp(44));
                        int oldX = mX;
                        position();
                        mDockAnimator = ValueAnimator.ofInt(oldX, mX);
                        mDockAnimator.setDuration(ValueAnimator.areAnimatorsEnabled() ? BUTTON_DURATION_MS : 0);
                        mDockAnimator.setInterpolator(new PathInterpolator(0, 0, 0.2f, 1));
                        mDockAnimator.addUpdateListener(animation -> {
                            mX = (Integer) animation.getAnimatedValue();
                            mPopup.update(mX, mY, -1, -1);
                        });
                        mDockAnimator.start();
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        view.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        }

        void dismiss() {
            mDismissed = true;
            mAnchor.removeCallbacks(mCollapse);
            mAnchor.removeOnLayoutChangeListener(mLayoutListener);
            mAnchor.removeOnAttachStateChangeListener(mAttachListener);
            if (mDockAnimator != null) mDockAnimator.cancel();
            mRoot.animate().cancel();
            mPopup.dismiss();
        }
    }

    @Override
    public void onPageLoadStarted(Tab tab, GURL url) { close(); }

    @Override
    public void onContentChanged(Tab tab) { close(); }

    @Override
    public void onActivityAttachmentChanged(Tab tab, WindowAndroid window) { restore(); }

    @Override
    public void onDestroyed(Tab tab) { close(); }

    @Override
    public void onHidden(Tab tab, int type) { restore(); }
}
