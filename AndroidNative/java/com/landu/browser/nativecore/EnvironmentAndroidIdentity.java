package com.landu.browser.nativecore;

/** Android web identity only; does not change the device OS or rendering hardware. */
public final class EnvironmentAndroidIdentity {
    public final String platformVersion;
    public final String model;

    public EnvironmentAndroidIdentity(String platformVersion, String model) {
        if (platformVersion == null || model == null) throw new IllegalArgumentException("浏览器身份设置不能为空");
        if (platformVersion.length() > 16 || model.length() > 80
                || hasControl(platformVersion) || hasControl(model)) {
            throw new IllegalArgumentException("Android 版本或设备型号格式不正确");
        }
        String version = platformVersion.trim();
        String selectedModel = model.trim();
        if (version.isEmpty()) {
            if (!selectedModel.isEmpty()) throw new IllegalArgumentException("自定义型号需同时填写 Android 版本");
            this.platformVersion = "";
            this.model = "";
            return;
        }
        if (!version.matches("[1-9][0-9](\\.(0|[1-9][0-9]{0,2})){0,2}")) {
            throw new IllegalArgumentException("Android 版本须为 10–99，可带两级小版本，例如 15 或 15.1.2");
        }
        if (!selectedModel.matches("[A-Za-z0-9 ._-]{0,80}")) {
            throw new IllegalArgumentException("设备型号仅支持英文字母、数字、空格、点、横线和下划线");
        }
        String[] parts = version.split("\\.");
        this.platformVersion = parts[0] + "." + (parts.length > 1 ? parts[1] : "0")
                + "." + (parts.length > 2 ? parts[2] : "0");
        this.model = selectedModel;
    }

    // Curated Android 15/16 models supported by the OEM update lists.
    // The selected literals use the existing persisted custom-identity format.
    private static final String[] RANDOM_MODELS = {
        "Pixel 7", "Pixel 7 Pro", "Pixel 8", "Pixel 8 Pro", "Pixel 9",
        "SM-S911B", "SM-S916B", "SM-S921B", "SM-S926B"
    };

    public static EnvironmentAndroidIdentity random(java.util.Random random, String previousModel) {
        java.util.Objects.requireNonNull(random, "random");
        int index = random.nextInt(RANDOM_MODELS.length);
        if (RANDOM_MODELS[index].equals(previousModel)) {
            index = (index + 1 + random.nextInt(RANDOM_MODELS.length - 1)) % RANDOM_MODELS.length;
        }
        return new EnvironmentAndroidIdentity(random.nextBoolean() ? "15" : "16", RANDOM_MODELS[index]);
    }

    public static EnvironmentAndroidIdentity system() { return new EnvironmentAndroidIdentity("", ""); }
    public boolean isSystem() { return platformVersion.isEmpty(); }

    public void applyTo(BrowserCommandLineTarget target) {
        target.remove("landu-android-version");
        target.remove("landu-android-model");
        if (!isSystem()) {
            target.set("landu-android-version", platformVersion);
            // Presence with an empty value explicitly suppresses the model.
            target.set("landu-android-model", model);
        }
    }

    private static boolean hasControl(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < 32 || text.charAt(i) == 127) return true;
        return false;
    }
}
