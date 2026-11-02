package com.landu.browser.nativecore;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;

/** Called only by the manager worker, before Chromium binds its immutable environment. */
public final class AndroidExitLookup {
    private static final URI ENDPOINT = URI.create("https://ipwho.is/?fields=success,ip,country_code,city,timezone.id,latitude,longitude");
    private AndroidExitLookup() { }
    public static EnvironmentExitInfo lookup(EnvironmentDefinition environment, ProxyCredentials credentials) throws IOException {
        String json = new EnvironmentExitTransport().get(ENDPOINT, environment.network, credentials);
        return parse(json, System.currentTimeMillis());
    }
    static EnvironmentExitInfo parse(String json, long checkedAt) throws IOException {
        try {
            JSONObject response = new JSONObject(json);
            if (!response.getBoolean("success")) throw new IOException("出口服务未返回有效位置，请稍后重试");
            String ip = response.getString("ip");
            if (ip.isEmpty()) throw new IOException("出口服务没有返回 IP");
            JSONObject zone = response.optJSONObject("timezone");
            Double latitude = response.has("latitude") && !response.isNull("latitude") ? response.getDouble("latitude") : null;
            Double longitude = response.has("longitude") && !response.isNull("longitude") ? response.getDouble("longitude") : null;
            return new EnvironmentExitInfo(ip, response.optString("country_code", ""),
                    response.optString("city", ""), zone == null ? "" : zone.optString("id", ""),
                    latitude, longitude, checkedAt);
        } catch (JSONException | IllegalArgumentException malformed) {
            throw new IOException("出口服务响应无效，请稍后重试", malformed);
        }
    }
}
