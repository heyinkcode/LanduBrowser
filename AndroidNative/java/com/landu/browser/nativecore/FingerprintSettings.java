package com.landu.browser.nativecore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FingerprintSettings {
    public static final boolean NEW_ENVIRONMENT_FONT_ISOLATION = true;
    public static final boolean NEW_ENVIRONMENT_CANVAS_NOISE = true;
    public static final boolean NEW_ENVIRONMENT_AUDIO_NOISE = true;
    public static final boolean NEW_ENVIRONMENT_CLIENT_RECTS_NOISE = true;
    public static final int DNT_DEFAULT = 0;
    public static final int DNT_ENABLED = 1;
    public static final int DNT_DISABLED = 2;
    private static final int[] CPU_CHOICES = {0, 2, 4, 8, 12, 16};
    private static final int[] MEMORY_CHOICES = {0, 1, 2, 4, 8};
    public final String seed;
    public final int hardwareConcurrency;
    public final int deviceMemory;
    public final boolean regionalFontIsolation;
    public final boolean canvasNoise;
    public final int doNotTrack;
    public final EnvironmentGpuMetadata gpuMetadata;
    public final boolean audioNoise;
    public final boolean muteAudio;
    public final boolean clientRectsNoise;
    public final EnvironmentMediaSettings media;
    public final EnvironmentUserAgentSettings userAgent;
    public final AnimationFrameSettings animationFrames;

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory) {
        this(seed, hardwareConcurrency, deviceMemory, false);
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory, boolean regionalFontIsolation) {
        this(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, false);
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory, boolean regionalFontIsolation, boolean canvasNoise) {
        this(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise, DNT_DEFAULT);
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory,
            boolean regionalFontIsolation, boolean canvasNoise, int doNotTrack) {
        this(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise, doNotTrack,
                EnvironmentGpuMetadata.system());
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory,
            boolean regionalFontIsolation, boolean canvasNoise, int doNotTrack, EnvironmentGpuMetadata gpuMetadata) {
        this(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise,
                doNotTrack, gpuMetadata, false, false);
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory,
            boolean regionalFontIsolation, boolean canvasNoise, int doNotTrack, EnvironmentGpuMetadata gpuMetadata,
            boolean audioNoise, boolean muteAudio) {
        this(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise, doNotTrack,
                gpuMetadata, audioNoise, muteAudio, false);
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory,
            boolean regionalFontIsolation, boolean canvasNoise, int doNotTrack, EnvironmentGpuMetadata gpuMetadata,
            boolean audioNoise, boolean muteAudio, boolean clientRectsNoise) {
        this(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise, doNotTrack,
                gpuMetadata, audioNoise, muteAudio, clientRectsNoise, EnvironmentMediaSettings.system());
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory,
            boolean regionalFontIsolation, boolean canvasNoise, int doNotTrack, EnvironmentGpuMetadata gpuMetadata,
            boolean audioNoise, boolean muteAudio, boolean clientRectsNoise, EnvironmentMediaSettings media) {
        this(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise, doNotTrack,
                gpuMetadata, audioNoise, muteAudio, clientRectsNoise, media,
                EnvironmentUserAgentSettings.automatic(), AnimationFrameSettings.device());
    }

    public FingerprintSettings(String seed, int hardwareConcurrency, int deviceMemory,
            boolean regionalFontIsolation, boolean canvasNoise, int doNotTrack, EnvironmentGpuMetadata gpuMetadata,
            boolean audioNoise, boolean muteAudio, boolean clientRectsNoise, EnvironmentMediaSettings media,
            EnvironmentUserAgentSettings userAgent, AnimationFrameSettings animationFrames) {
        if (seed == null || !seed.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("指纹种子格式无效");
        requireChoice(hardwareConcurrency, CPU_CHOICES, "CPU 核心数");
        requireChoice(deviceMemory, MEMORY_CHOICES, "设备内存");
        if (doNotTrack < DNT_DEFAULT || doNotTrack > DNT_DISABLED) {
            throw new IllegalArgumentException("Do Not Track 设置无效");
        }
        this.seed = seed;
        this.hardwareConcurrency = hardwareConcurrency;
        this.deviceMemory = deviceMemory;
        this.regionalFontIsolation = regionalFontIsolation;
        this.canvasNoise = canvasNoise;
        this.doNotTrack = doNotTrack;
        if (gpuMetadata == null) throw new IllegalArgumentException("GPU 标识设置不能为空");
        this.gpuMetadata = gpuMetadata;
        this.audioNoise = audioNoise;
        this.muteAudio = muteAudio;
        this.clientRectsNoise = clientRectsNoise;
        if (media == null) throw new IllegalArgumentException("媒体设备设置不能为空");
        this.media = media;
        if (userAgent == null || animationFrames == null) throw new IllegalArgumentException("UA-CH 与 RAF 配置不能为空");
        this.userAgent = userAgent;
        this.animationFrames = animationFrames;
    }

    public static FingerprintSettings defaults(EnvironmentIdentity identity) {
        if (identity == null) throw new IllegalArgumentException("环境标识不能为空");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    ("landu-native-fingerprint-v1:" + identity.id()).getBytes(StandardCharsets.UTF_8));
            StringBuilder seed = new StringBuilder(64);
            for (byte value : hash) seed.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return new FingerprintSettings(seed.toString(), 0, 0);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static FingerprintSettings forNewEnvironment(EnvironmentIdentity identity) {
        return defaults(identity).withRegionalFontIsolation(NEW_ENVIRONMENT_FONT_ISOLATION)
                .withCanvasNoise(NEW_ENVIRONMENT_CANVAS_NOISE).withAudio(NEW_ENVIRONMENT_AUDIO_NOISE, false)
                .withClientRectsNoise(NEW_ENVIRONMENT_CLIENT_RECTS_NOISE).withMedia(EnvironmentMediaSettings.isolated());
    }

    public FingerprintSettings withHardware(int cpu, int memory) {
        return new FingerprintSettings(seed, cpu, memory, regionalFontIsolation, canvasNoise, doNotTrack, gpuMetadata, audioNoise, muteAudio, clientRectsNoise, media, userAgent, animationFrames);
    }

    public FingerprintSettings withRegionalFontIsolation(boolean enabled) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, enabled, canvasNoise, doNotTrack, gpuMetadata, audioNoise, muteAudio, clientRectsNoise, media, userAgent, animationFrames);
    }

    public FingerprintSettings withCanvasNoise(boolean enabled) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, enabled, doNotTrack, gpuMetadata, audioNoise, muteAudio, clientRectsNoise, media, userAgent, animationFrames);
    }

    public FingerprintSettings withDoNotTrack(int mode) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise, mode, gpuMetadata, audioNoise, muteAudio, clientRectsNoise, media, userAgent, animationFrames);
    }

    public FingerprintSettings withGpuMetadata(EnvironmentGpuMetadata metadata) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation, canvasNoise, doNotTrack, metadata, audioNoise, muteAudio, clientRectsNoise, media, userAgent, animationFrames);
    }

    public FingerprintSettings withAudio(boolean noise, boolean muted) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation,
                canvasNoise, doNotTrack, gpuMetadata, noise, muted, clientRectsNoise, media, userAgent, animationFrames);
    }

    public FingerprintSettings withClientRectsNoise(boolean noise) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation,
                canvasNoise, doNotTrack, gpuMetadata, audioNoise, muteAudio, noise, media, userAgent, animationFrames);
    }

    public FingerprintSettings withMedia(EnvironmentMediaSettings settings) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation,
                canvasNoise, doNotTrack, gpuMetadata, audioNoise, muteAudio, clientRectsNoise, settings, userAgent, animationFrames);
    }

    public FingerprintSettings withUserAgent(EnvironmentUserAgentSettings settings) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation,
                canvasNoise, doNotTrack, gpuMetadata, audioNoise, muteAudio, clientRectsNoise, media, settings, animationFrames);
    }

    public FingerprintSettings withAnimationFrames(AnimationFrameSettings settings) {
        return new FingerprintSettings(seed, hardwareConcurrency, deviceMemory, regionalFontIsolation,
                canvasNoise, doNotTrack, gpuMetadata, audioNoise, muteAudio, clientRectsNoise, media, userAgent, settings);
    }

    public void applyTo(BrowserCommandLineTarget target) {
        target.remove("landu-hardware-concurrency");
        target.remove("landu-device-memory");
        target.remove("landu-isolate-regional-fonts");
        target.remove("landu-canvas-seed");
        target.remove("landu-dnt-on");
        target.remove("landu-dnt-off");
        target.remove("landu-audio-seed");
        target.remove("mute-audio");
        target.remove("landu-client-rects-seed");
        if (hardwareConcurrency != 0) target.set("landu-hardware-concurrency", Integer.toString(hardwareConcurrency));
        if (deviceMemory != 0) target.set("landu-device-memory", Integer.toString(deviceMemory));
        if (regionalFontIsolation) target.set("landu-isolate-regional-fonts", "");
        if (canvasNoise) target.set("landu-canvas-seed", seed);
        if (audioNoise) target.set("landu-audio-seed", seed);
        if (muteAudio) target.set("mute-audio", "");
        if (clientRectsNoise) target.set("landu-client-rects-seed", seed);
        if (doNotTrack == DNT_ENABLED) target.set("landu-dnt-on", "");
        if (doNotTrack == DNT_DISABLED) target.set("landu-dnt-off", "");
        gpuMetadata.applyTo(target);
        media.applyTo(target, seed);
        userAgent.applyTo(target);
        animationFrames.applyTo(target);
    }

    public static int[] cpuChoices() { return CPU_CHOICES.clone(); }
    public static int[] memoryChoices() { return MEMORY_CHOICES.clone(); }

    private static void requireChoice(int value, int[] choices, String label) {
        for (int choice : choices) if (choice == value) return;
        throw new IllegalArgumentException(label + "不在支持范围内");
    }
}
