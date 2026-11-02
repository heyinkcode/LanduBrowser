package com.landu.browser.nativecore;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;

public final class EnvironmentDefinition {
    public final EnvironmentIdentity identity;
    public final String name;
    public final String startPage;
    public final String notes;
    public final long createdAt;
    public final long updatedAt;
    public final FingerprintSettings fingerprint;
    public final EnvironmentNetworkSettings network;
    public final EnvironmentRegionSettings region;
    public final EnvironmentAndroidIdentity androidIdentity;
    public final EnvironmentOrganization organization;
    public final EnvironmentGeolocationSettings geolocation;
    public final EnvironmentRegionalPolicy regionalPolicy;

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt) {
        this(identity, name, startPage, notes, createdAt, updatedAt, FingerprintSettings.defaults(identity));
    }

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt, FingerprintSettings fingerprint) {
        this(identity, name, startPage, notes, createdAt, updatedAt, fingerprint, EnvironmentNetworkSettings.system());
    }

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt, FingerprintSettings fingerprint,
            EnvironmentNetworkSettings network) {
        this(identity, name, startPage, notes, createdAt, updatedAt, fingerprint, network, EnvironmentRegionSettings.system());
    }

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt, FingerprintSettings fingerprint,
            EnvironmentNetworkSettings network, EnvironmentRegionSettings region) {
        this(identity, name, startPage, notes, createdAt, updatedAt, fingerprint, network, region,
                EnvironmentAndroidIdentity.system());
    }

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt, FingerprintSettings fingerprint,
            EnvironmentNetworkSettings network, EnvironmentRegionSettings region,
            EnvironmentAndroidIdentity androidIdentity) {
        this(identity, name, startPage, notes, createdAt, updatedAt, fingerprint, network, region,
                androidIdentity, EnvironmentOrganization.unassigned());
    }

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt, FingerprintSettings fingerprint,
            EnvironmentNetworkSettings network, EnvironmentRegionSettings region,
            EnvironmentAndroidIdentity androidIdentity, EnvironmentOrganization organization) {
        this(identity, name, startPage, notes, createdAt, updatedAt, fingerprint, network,
                region, androidIdentity, organization, EnvironmentGeolocationSettings.system());
    }

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt, FingerprintSettings fingerprint,
            EnvironmentNetworkSettings network, EnvironmentRegionSettings region,
            EnvironmentAndroidIdentity androidIdentity, EnvironmentOrganization organization,
            EnvironmentGeolocationSettings geolocation) {
        this(identity, name, startPage, notes, createdAt, updatedAt, fingerprint, network, region,
                androidIdentity, organization, geolocation, EnvironmentRegionalPolicy.fromLegacy(region, geolocation));
    }

    public EnvironmentDefinition(EnvironmentIdentity identity, String name, String startPage,
            String notes, long createdAt, long updatedAt, FingerprintSettings fingerprint,
            EnvironmentNetworkSettings network, EnvironmentRegionSettings region,
            EnvironmentAndroidIdentity androidIdentity, EnvironmentOrganization organization,
            EnvironmentGeolocationSettings geolocation, EnvironmentRegionalPolicy regionalPolicy) {
        if (identity == null) throw new IllegalArgumentException("环境标识不能为空");
        this.identity = identity;
        this.name = text(name, 80, "环境名称");
        if (this.name.isEmpty()) throw new IllegalArgumentException("请输入环境名称");
        this.notes = text(notes, 2000, "备注");
        this.startPage = validateStartPage(startPage);
        if (createdAt < 0 || updatedAt < createdAt) throw new IllegalArgumentException("环境时间无效");
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        if (fingerprint == null) throw new IllegalArgumentException("指纹配置不能为空");
        this.fingerprint = fingerprint;
        if (network == null) throw new IllegalArgumentException("网络配置不能为空");
        this.network = network;
        if (region == null) throw new IllegalArgumentException("区域配置不能为空");
        this.region = region;
        if (androidIdentity == null) throw new IllegalArgumentException("浏览器身份配置不能为空");
        this.androidIdentity = androidIdentity;
        if (organization == null) throw new IllegalArgumentException("环境分类不能为空");
        this.organization = organization;
        if (geolocation == null) throw new IllegalArgumentException("定位配置不能为空");
        this.geolocation = geolocation;
        if (regionalPolicy == null) throw new IllegalArgumentException("地区策略不能为空");
        if (regionalPolicy.languageMode == EnvironmentRegionalPolicy.LanguageMode.FIXED && region.languages.isEmpty()) {
            throw new IllegalArgumentException("固定网页语言不能为空");
        }
        if (regionalPolicy.timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM && region.timeZone.isEmpty()) {
            throw new IllegalArgumentException("自定义时区不能为空");
        }
        if (regionalPolicy.locationMode == EnvironmentRegionalPolicy.LocationMode.CUSTOM
                && geolocation.mode != EnvironmentGeolocationSettings.CUSTOM) {
            throw new IllegalArgumentException("自定义定位须填写坐标");
        }
        this.regionalPolicy = regionalPolicy;
    }

    public static EnvironmentDefinition create(String name, String startPage, String notes) {
        long now = System.currentTimeMillis();
        EnvironmentIdentity identity = EnvironmentIdentity.parse(UUID.randomUUID().toString());
        return new EnvironmentDefinition(identity, name, startPage, notes, now, now,
                FingerprintSettings.forNewEnvironment(identity));
    }

    public EnvironmentDefinition edit(String name, String startPage, String notes) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), fingerprint, network, region, androidIdentity, organization, geolocation, regionalPolicy);
    }

    public EnvironmentDefinition withFingerprint(FingerprintSettings settings) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), settings, network, region, androidIdentity, organization, geolocation, regionalPolicy);
    }

    public EnvironmentDefinition withNetwork(EnvironmentNetworkSettings settings) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), fingerprint, settings, region, androidIdentity, organization, geolocation, regionalPolicy);
    }

    public EnvironmentDefinition withRegion(EnvironmentRegionSettings settings) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), fingerprint, network, settings, androidIdentity, organization, geolocation,
                regionalPolicy.withManualRegion(settings));
    }

    public EnvironmentDefinition withAndroidIdentity(EnvironmentAndroidIdentity settings) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), fingerprint, network, region, settings, organization, geolocation, regionalPolicy);
    }

    public EnvironmentDefinition withOrganization(EnvironmentOrganization settings) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), fingerprint, network, region, androidIdentity, settings, geolocation, regionalPolicy);
    }

    public EnvironmentDefinition withGeolocation(EnvironmentGeolocationSettings settings) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), fingerprint, network, region,
                androidIdentity, organization, settings, regionalPolicy.withManualLocation(settings));
    }

    EnvironmentDefinition withStoredOrganization(EnvironmentOrganization settings) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt, updatedAt,
                fingerprint, network, region, androidIdentity, settings, geolocation, regionalPolicy);
    }

    public EnvironmentDefinition withRegionalPolicy(EnvironmentRegionalPolicy policy) {
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt,
                Math.max(updatedAt, System.currentTimeMillis()), fingerprint, network, region,
                androidIdentity, organization, geolocation, policy);
    }

    public EnvironmentDefinition withResolvedRegionalSettings(EnvironmentRegionalResolver.Result resolved) {
        if (resolved == null) throw new IllegalArgumentException("地区解析结果不能为空");
        return new EnvironmentDefinition(identity, name, startPage, notes, createdAt, updatedAt,
                fingerprint, network, resolved.region, androidIdentity, organization,
                resolved.geolocation, regionalPolicy);
    }

    private static String text(String value, int limit, String label) {
        if (value == null || value.length() > limit || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + "长度或格式不正确");
        }
        return value.trim();
    }

    public static String validateStartPage(String input) {
        String value = text(input, 4096, "启动地址");
        if (value.isEmpty() || value.equals("about:blank")) return "about:blank";
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException("启动地址须为完整的 http:// 或 https:// 网址");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("启动地址格式不正确", error);
        }
    }
}
