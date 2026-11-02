// Copyright 2026 The LanduBrowser Authors
// Use of this source code is governed by a BSD-style license.

package org.chromium.chrome.browser.user_education;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.GraphicsMode;

import org.chromium.base.Callback;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.browser.feature_engagement.TrackerFactory;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.components.browser_ui.widget.textbubble.TextBubble;
import org.chromium.components.feature_engagement.SnoozeAction;
import org.chromium.components.feature_engagement.Tracker;
import org.chromium.ui.widget.ChromePopupWindow;
import org.chromium.ui.widget.RectProvider;
import org.chromium.ui.widget.UiWidgetFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real bubble and helper failure lifecycle without bypassing show checks. */
@RunWith(BaseRobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LanduIphLifecycleTest {
    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Captor private ArgumentCaptor<Callback<Boolean>> mInitCallback;
    private Activity mActivity;
    private ChromePopupWindow mPopup;
    private AtomicBoolean mShowing;
    private AtomicReference<Drawable> mBackground;
    private AtomicReference<PopupWindow.OnDismissListener> mDismissListener;
    private FrameLayout mAnchor;
    private RectProvider mRect;

    @Before
    public void setUp() {
        mActivity = Robolectric.buildActivity(Activity.class).setup().get();
        mActivity.getTheme().applyStyle(
                org.chromium.components.browser_ui.widget.R.style.Theme_BrowserUI_DayNight, true);
        mAnchor = spy(new FrameLayout(mActivity));
        mActivity.setContentView(mAnchor);
        View decor = mActivity.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 1920);
        // Model a visible, attached window explicitly: the host has no real display frame.
        doReturn(mAnchor).when(mAnchor).getRootView();
        doReturn(true).when(mAnchor).isAttachedToWindow();
        doAnswer(invocation -> {
                    ((Rect) invocation.getArgument(0)).set(0, 0, 1080, 1920);
                    return null;
                })
                .when(mAnchor).getWindowVisibleDisplayFrame(any(Rect.class));
        mRect = spy(new RectProvider(new Rect(100, 200, 250, 250)));
        mPopup = mock(ChromePopupWindow.class);
        UiWidgetFactory factory = mock(UiWidgetFactory.class);
        when(factory.createPopupWindow(any())).thenReturn(mPopup);
        UiWidgetFactory.setInstance(factory);
        // Preserve the real bubble background and padding used by the layout calculator.
        mBackground = new AtomicReference<>();
        doAnswer(invocation -> {
                    mBackground.set(invocation.getArgument(0));
                    return null;
                })
                .when(mPopup).setBackgroundDrawable(any());
        when(mPopup.getBackground()).thenAnswer(invocation -> mBackground.get());
        when(mPopup.getContentView()).thenReturn(new FrameLayout(mActivity));
        mShowing = new AtomicBoolean();
        mDismissListener = new AtomicReference<>();
        doAnswer(invocation -> {
                    mDismissListener.set(invocation.getArgument(0));
                    return null;
                })
                .when(mPopup).setOnDismissListener(any());
        doAnswer(invocation -> {
                    if (mShowing.getAndSet(false) && mDismissListener.get() != null) {
                        mDismissListener.get().onDismiss();
                    }
                    return null;
                })
                .when(mPopup).dismiss();
        when(mPopup.isShowing()).thenAnswer(invocation -> mShowing.get());
        doAnswer(invocation -> { mShowing.set(true); return null; })
                .when(mPopup).showAtLocation(any(View.class), anyInt(), anyInt(), anyInt());
        TextBubble.setSkipShowCheckForTesting(false);
    }

    @After
    public void tearDown() {
        TextBubble.dismissBubbles();
        UiWidgetFactory.setInstance(null);
        mActivity.finish();
    }

    private TextBubble bubble(View anchor) {
        return new TextBubble.Builder(mActivity, anchor, mRect, "Pin shortcut", "Pin shortcut")
                .build();
    }

    @Test
    public void detachedAnchorIsCancelledWithoutRegisteringBubbleOrTimer() {
        TextBubble bubble = bubble(new FrameLayout(mActivity));
        bubble.setAutoDismissTimeout(100);
        assertFalse(bubble.tryShow());
        assertFalse(bubble.isShowing());
        assertEquals(0, (int) TextBubble.getCountSupplier().get());
        verify(mRect).stopObserving();
        verify(mPopup, never()).showAtLocation(any(View.class), anyInt(), anyInt(), anyInt());
        verify(mPopup).dismiss();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verify(mPopup).dismiss();
    }

    @Test
    public void expiredWindowTokenIsCancelled() {
        rejectWindowTokenAfterMarkingShowing();
        TextBubble bubble = bubble(mAnchor);
        assertFalse(bubble.tryShow());
        verify(mPopup).showAtLocation(any(View.class), anyInt(), anyInt(), anyInt());
        verify(mRect, atLeastOnce()).stopObserving();
        assertEquals(0, (int) TextBubble.getCountSupplier().get());
    }

    @Test
    public void normalBubbleStillShowsOnlyOnceAndIsRemovedOnDismiss() {
        TextBubble bubble = bubble(mAnchor);
        assertTrue(bubble.tryShow());
        assertTrue(bubble.tryShow());
        assertEquals(1, (int) TextBubble.getCountSupplier().get());
        verify(mPopup).showAtLocation(any(View.class), anyInt(), anyInt(), anyInt());
        ArgumentCaptor<PopupWindow.OnDismissListener> listener =
                ArgumentCaptor.forClass(PopupWindow.OnDismissListener.class);
        verify(mPopup).setOnDismissListener(listener.capture());
        mShowing.set(false);
        listener.getValue().onDismiss();
        assertEquals(0, (int) TextBubble.getCountSupplier().get());
        verify(mRect).stopObserving();
    }

    @Test
    public void strictShowStillAssertsForUnexpectedFailure() {
        TextBubble bubble = bubble(new FrameLayout(mActivity));
        assertThrows(AssertionError.class, bubble::show);
        verify(mRect).stopObserving();
    }

    private void rejectWindowTokenAfterMarkingShowing() {
        doAnswer(invocation -> {
                    mShowing.set(true);
                    throw new WindowManager.BadTokenException("expired QA token");
                })
                .when(mPopup).showAtLocation(any(View.class), anyInt(), anyInt(), anyInt());
    }

    private void verifyBlockedReservation(boolean snooze, View anchor) {
        Tracker tracker = mock(Tracker.class);
        Profile profile = mock(Profile.class);
        when(profile.getOriginalProfile()).thenReturn(profile);
        when(tracker.shouldTriggerHelpUi("LANDU_TEST")).thenReturn(true);
        TrackerFactory.setTrackerForTests(tracker);
        Runnable onBlocked = mock(Runnable.class);
        Runnable onShow = mock(Runnable.class);
        Runnable onDismiss = mock(Runnable.class);
        UserEducationHelper helper = new UserEducationHelper(mActivity, profile, new Handler());
        IphCommand command = new IphCommandBuilder(
                mActivity.getResources(), "LANDU_TEST", "Pin shortcut", "Pin shortcut")
                .setAnchorView(anchor)
                .setOnNotShownCallback(onBlocked)
                .setOnShowCallback(onShow)
                .setOnDismissCallback(onDismiss)
                .setEnableSnoozeMode(snooze)
                .build();
        helper.requestShowIph(command);
        verify(tracker).addOnInitializedCallback(mInitCallback.capture());
        mInitCallback.getValue().onResult(true);
        assertNull(helper.getTextBubbleForTesting());
        verify(onBlocked).run();
        verify(onShow, never()).run();
        verify(onDismiss, never()).run();
        if (snooze) {
            verify(tracker).dismissedWithSnooze("LANDU_TEST", SnoozeAction.SNOOZED);
            verify(tracker, never()).dismissed(any());
        } else {
            verify(tracker).dismissed("LANDU_TEST");
            verify(tracker, never()).dismissedWithSnooze(any(), anyInt());
        }
        helper.dismissTextBubble();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verify(onBlocked, times(1)).run();
        verify(onDismiss, never()).run();
    }

    @Test
    public void failedHelpUiReleasesTrackerAndReportsBlocked() {
        verifyBlockedReservation(false, new FrameLayout(mActivity));
    }

    @Test
    public void failedHelpUiReleasesSnoozeReservation() {
        verifyBlockedReservation(true, new FrameLayout(mActivity));
    }
    @Test
    public void rejectedWindowReportsBlockedWithoutDelayedDismissal() {
        rejectWindowTokenAfterMarkingShowing();
        verifyBlockedReservation(false, mAnchor);
        verify(mPopup).showAtLocation(any(View.class), anyInt(), anyInt(), anyInt());
    }

}
