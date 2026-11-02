package com.landu.browser.nativecore;

import java.util.Locale;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** A measured response; city/IP are never inferred from a manually selected preset. */
public final class EnvironmentExitInfo {
    public final String ip;
    public final String countryCode;
    public final String city;
    public final String timeZone;
    public final Double latitude;
    public final Double longitude;
    public final long checkedAt;

    public EnvironmentExitInfo(String ip, String countryCode, String city, String timeZone,
            Double latitude, Double longitude, long checkedAt) {
        this.ip = text(ip, 64);
        if (!this.ip.isEmpty() && !validIp(this.ip)) {
            throw new IllegalArgumentException("出口 IP 格式无效");
        }
        String country = text(countryCode, 2).toUpperCase(Locale.ROOT);
        if (!country.isEmpty() && !country.matches("[A-Z]{2}")) throw new IllegalArgumentException("出口国家格式无效");
        this.countryCode = country;
        this.city = text(city, 160);
        String zone = text(timeZone, 128);
        try { zone = new EnvironmentRegionSettings("", zone).timeZone; }
        catch (IllegalArgumentException invalidZone) { zone = ""; }
        this.timeZone = zone;
        if ((latitude == null) != (longitude == null)) throw new IllegalArgumentException("出口坐标不完整");
        if (latitude != null && (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("出口坐标无效");
        }
        if (checkedAt < 0) throw new IllegalArgumentException("出口检测时间无效");
        this.latitude = latitude;
        this.longitude = longitude;
        this.checkedAt = checkedAt;
    }

    public boolean hasPosition() { return latitude != null && longitude != null; }

    private static boolean validIp(String value) {
        if (value.indexOf(':') >= 0) {
            if (!value.matches("[0-9a-fA-F:.]+")) return false;
            try { InetAddress.getByName(value); return true; }
            catch (UnknownHostException invalidLiteral) { return false; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static String text(String value, int limit) {
        if (value == null || value.length() > limit) throw new IllegalArgumentException("出口信息长度无效");
        for (int i = 0; i < value.length(); ++i) {
            if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("出口信息包含控制字符");
        }
        return value.trim();
    }
}
