package com.landu.browser.nativecore;

/** Public GPU identity strings only. Rendering features and limits remain native. */
public final class EnvironmentGpuMetadata {
    private static final String[] SWITCHES = {"landu-webgl-vendor", "landu-webgl-renderer",
            "landu-webgpu-vendor", "landu-webgpu-architecture", "landu-webgpu-device", "landu-webgpu-description"};
    private static final String[] LABELS = {"WebGL 厂商", "WebGL 渲染器", "WebGPU 厂商",
            "WebGPU 架构", "WebGPU 设备编号", "WebGPU 描述"};
    private static final int[] LIMITS = {128, 512, 64, 128, 64, 512};
    private final String[] fields;

    public EnvironmentGpuMetadata(String... values) {
        if (values == null || values.length != 6) throw new IllegalArgumentException("GPU 标识须包含六个字段");
        fields = new String[6];
        boolean any = false;
        for (int i = 0; i < fields.length; i++) {
            String value = values[i];
            if (value == null || value.length() > LIMITS[i]) throw new IllegalArgumentException(LABELS[i] + "长度无效");
            for (int j = 0; j < value.length(); j++) {
                if (value.charAt(j) < 32 || value.charAt(j) > 126) {
                    throw new IllegalArgumentException(LABELS[i] + "仅支持可打印的英文字符，不允许换行或控制符");
                }
            }
            fields[i] = value.trim();
            any |= !fields[i].isEmpty();
        }
        if (any) {
            for (int i = 0; i < 3; i++) {
                if (fields[i].isEmpty()) throw new IllegalArgumentException("请填写" + LABELS[i]);
            }
        }
    }

    public static EnvironmentGpuMetadata system() { return new EnvironmentGpuMetadata("", "", "", "", "", ""); }
    public boolean isSystem() { return fields[0].isEmpty(); }
    public String[] values() { return fields.clone(); }
    public static String label(int index) { return LABELS[index]; }
    public static int limit(int index) { return LIMITS[index]; }

    public void applyTo(BrowserCommandLineTarget target) {
        for (String name : SWITCHES) target.remove(name);
        if (!isSystem()) {
            // Empty optional fields are present explicitly, never filled from the real GPU.
            for (int i = 0; i < SWITCHES.length; i++) target.set(SWITCHES[i], fields[i]);
        }
    }
}
