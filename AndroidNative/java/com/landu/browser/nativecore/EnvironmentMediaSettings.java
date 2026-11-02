package com.landu.browser.nativecore;

import java.nio.charset.StandardCharsets;

/** Physical media devices stay usable; identities and public labels follow the environment policy. */
public final class EnvironmentMediaSettings {
    public static final int REAL = 0;
    public static final int ISOLATED = 1;
    public static final int HIDDEN_LABELS = 2;
    private static final String[] SWITCHES = {"landu-media-mode", "landu-media-seed", "landu-block-media",
            "landu-media-audioinput", "landu-media-videoinput", "landu-media-audiooutput"};
    public final int mode;
    public final boolean blockCapture;
    private final String[] labels;

    public EnvironmentMediaSettings(int mode, boolean blockCapture, String... labels) {
        if (mode < REAL || mode > HIDDEN_LABELS) throw new IllegalArgumentException("媒体设备模式无效");
        if (labels == null || labels.length != 3) throw new IllegalArgumentException("媒体设备名称须包含三项");
        this.mode = mode;
        this.blockCapture = blockCapture;
        this.labels = new String[3];
        for (int i = 0; i < 3; ++i) {
            String label = labels[i];
            if (label == null || label.length() > 200) throw new IllegalArgumentException("媒体设备名称不得超过 200 字符");
            for (int offset = 0; offset < label.length();) {
                int cp = label.codePointAt(offset);
                if (Character.isISOControl(cp) || (cp >= 0xd800 && cp <= 0xdfff))
                    throw new IllegalArgumentException("媒体设备名称包含无效字符");
                offset += Character.charCount(cp);
            }
            this.labels[i] = label.trim();
        }
    }

    public static EnvironmentMediaSettings system() { return new EnvironmentMediaSettings(REAL, false, "", "", ""); }
    public static EnvironmentMediaSettings isolated() { return new EnvironmentMediaSettings(ISOLATED, false, "", "", ""); }
    public String[] labels() { return labels.clone(); }

    public void applyTo(BrowserCommandLineTarget target, String seed) {
        if (seed == null || !seed.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("媒体设备种子无效");
        for (String name : SWITCHES) target.remove(name);
        if (blockCapture) target.set("landu-block-media", "");
        if (mode == REAL) return;
        target.set("landu-media-mode", mode == ISOLATED ? "isolated" : "hidden-labels");
        target.set("landu-media-seed", seed);
        if (mode == ISOLATED) {
            for (int i = 0; i < 3; ++i) {
                if (labels[i].isEmpty()) continue;
                StringBuilder hex = new StringBuilder();
                for (byte value : labels[i].getBytes(StandardCharsets.UTF_8)) {
                    hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
                    hex.append(Character.forDigit(value & 15, 16));
                }
                target.set(SWITCHES[i + 3], hex.toString());
            }
        }
    }
}
