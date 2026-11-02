package com.landu.browser.nativecore;

/** Immutable location policy captured with the environment's launch snapshot. */
public final class EnvironmentGeolocationSettings {
    public static final int SYSTEM = 0;
    public static final int CUSTOM = 1;
    public static final int BLOCKED = 2;
    public final int mode;
    public final double latitude;
    public final double longitude;
    public final double accuracy;

    public EnvironmentGeolocationSettings(int mode, double latitude, double longitude, double accuracy) {
        if (mode < SYSTEM || mode > BLOCKED) throw new IllegalArgumentException("定位模式无效");
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(accuracy)) {
            throw new IllegalArgumentException("定位坐标和精度必须为有限数字");
        }
        if (mode == CUSTOM) {
            if (latitude < -90 || latitude > 90) throw new IllegalArgumentException("纬度须在 -90 到 90 之间");
            if (longitude < -180 || longitude > 180) throw new IllegalArgumentException("经度须在 -180 到 180 之间");
            if (accuracy < 1 || accuracy > 1000000) throw new IllegalArgumentException("定位精度须在 1 到 1000000 米之间");
        } else if (latitude != 0 || longitude != 0 || accuracy != 0) {
            throw new IllegalArgumentException("非自定义定位不应包含坐标");
        }
        this.mode = mode;
        this.latitude = latitude == 0 ? 0 : latitude;
        this.longitude = longitude == 0 ? 0 : longitude;
        this.accuracy = accuracy == 0 ? 0 : accuracy;
    }

    public static EnvironmentGeolocationSettings system() {
        return new EnvironmentGeolocationSettings(SYSTEM, 0, 0, 0);
    }

    public static EnvironmentGeolocationSettings blocked() {
        return new EnvironmentGeolocationSettings(BLOCKED, 0, 0, 0);
    }

    public static EnvironmentGeolocationSettings custom(String latitude, String longitude, String accuracy) {
        return new EnvironmentGeolocationSettings(CUSTOM, number(latitude, "纬度"),
                number(longitude, "经度"), number(accuracy, "定位精度"));
    }

    private static double number(String value, String label) {
        if (value == null || value.length() > 40
                || !value.trim().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException(label + "格式无效");
        }
        try { return Double.parseDouble(value.trim()); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(label + "格式无效", error); }
    }

    public void applyTo(BrowserCommandLineTarget target) {
        target.remove("landu-geolocation-mode");
        target.remove("landu-geolocation-position");
        if (mode == BLOCKED) target.set("landu-geolocation-mode", "blocked");
        if (mode == CUSTOM) {
            target.set("landu-geolocation-mode", "custom");
            target.set("landu-geolocation-position", Double.toString(latitude) + ","
                    + Double.toString(longitude) + "," + Double.toString(accuracy));
        }
    }

    public String summary() {
        if (mode == SYSTEM) return "跟随手机定位";
        if (mode == BLOCKED) return "阻止定位";
        return "自定义：" + latitude + ", " + longitude + "（" + accuracy + " 米）";
    }
}
