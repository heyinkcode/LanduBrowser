package com.landu.browser.nativecore;

/** A ceiling on real web animation callbacks; never fabricates a timestamp. */
public final class AnimationFrameSettings {
    public final int maxFramesPerSecond;

    public AnimationFrameSettings(int maxFramesPerSecond) {
        if (maxFramesPerSecond < 0 || maxFramesPerSecond > 240) {
            throw new IllegalArgumentException("RAF 帧率须为 1–240，0 表示跟随设备");
        }
        this.maxFramesPerSecond = maxFramesPerSecond;
    }

    public static AnimationFrameSettings device() { return new AnimationFrameSettings(0); }

    public void applyTo(BrowserCommandLineTarget target) {
        target.remove("landu-raf-max-fps");
        if (maxFramesPerSecond != 0) target.set("landu-raf-max-fps", Integer.toString(maxFramesPerSecond));
    }
}
