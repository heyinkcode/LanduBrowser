package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EnvironmentCatalog {
    private static final int MAGIC = 0x4c444332;
    private static final int VERSION = 18;
    private static final int MAX_ENVIRONMENTS = 1000;
    private static final int MAX_GROUPS = 100;
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private static final Object LOCAL_LOCK = new Object();
    private final Path directory;
    private final Path catalog;

    public EnvironmentCatalog(Path privateControlDirectory) throws IOException {
        Files.createDirectories(privateControlDirectory);
        directory = privateControlDirectory.toRealPath();
        catalog = directory.resolve("environments.bin");
    }

    public Snapshot read() throws IOException {
        rejectLink(catalog);
        if (!Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(0, Collections.emptyList(), Collections.emptyList());
        }
        long size = Files.size(catalog);
        if (size < 20 || size > MAX_BYTES) throw new IOException("环境列表文件大小异常，已保留原文件");
        return decodeConfiguration(Files.readAllBytes(catalog));
    }

    /** Decode a bounded configuration backup. No filesystem or catalog mutation occurs. */
    public static Snapshot decodeConfiguration(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 20 || bytes.length > MAX_BYTES) {
            throw new IOException("配置文件大小无效或超过 16 MiB");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version < 1 || version > VERSION) {
                throw new IOException("环境列表版本无法识别，已保留原文件");
            }
            long revision = input.readLong();
            int count = input.readInt();
            if (revision < 1 || count < 0 || count > MAX_ENVIRONMENTS) throw new IOException("环境列表无效");
            List<EnvironmentDefinition> result = new ArrayList<>();
            Set<EnvironmentIdentity> identities = new HashSet<>();
            for (int i = 0; i < count; i++) {
                EnvironmentDefinition environment = new EnvironmentDefinition(
                        EnvironmentIdentity.parse(input.readUTF()), input.readUTF(), input.readUTF(),
                        input.readUTF(), input.readLong(), input.readLong());
                if (version >= 2) {
                    FingerprintSettings settings = new FingerprintSettings(input.readUTF(), input.readInt(), input.readInt());
                    if (version >= 3) {
                        int isolation = input.readUnsignedByte();
                        if (isolation > 1) throw new IOException("字体隔离设置无效，已保留原文件");
                        settings = settings.withRegionalFontIsolation(isolation == 1);
                    }
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt,
                            environment.updatedAt, settings);
                }
                if (version >= 4) {
                    String proxy = input.readUTF();
                    int restrict = input.readUnsignedByte();
                    if (restrict > 1) throw new IOException("WebRTC 设置无效，已保留原文件");
                    EnvironmentNetworkSettings network = new EnvironmentNetworkSettings(proxy, restrict == 1);
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt,
                            environment.updatedAt, environment.fingerprint, network);
                }
                if (version >= 5) {
                    EnvironmentRegionSettings region = new EnvironmentRegionSettings(input.readUTF(), input.readUTF());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt,
                            environment.updatedAt, environment.fingerprint, environment.network, region);
                }
                if (version >= 6) {
                    int canvas = input.readUnsignedByte();
                    if (canvas > 1) throw new IOException("Canvas 设置无效，已保留原文件");
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt,
                            environment.updatedAt, environment.fingerprint.withCanvasNoise(canvas == 1),
                            environment.network, environment.region);
                }
                if (version >= 7) {
                    int dnt = input.readUnsignedByte();
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt,
                            environment.updatedAt, environment.fingerprint.withDoNotTrack(dnt),
                            environment.network, environment.region);
                }
                if (version >= 8) {
                    EnvironmentAndroidIdentity android = new EnvironmentAndroidIdentity(input.readUTF(), input.readUTF());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt,
                            environment.updatedAt, environment.fingerprint, environment.network,
                            environment.region, android);
                }
                if (version >= 9) {
                    EnvironmentGpuMetadata gpu = new EnvironmentGpuMetadata(input.readUTF(), input.readUTF(), input.readUTF(),
                            input.readUTF(), input.readUTF(), input.readUTF());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt,
                            environment.updatedAt, environment.fingerprint.withGpuMetadata(gpu), environment.network,
                            environment.region, environment.androidIdentity);
                }
                EnvironmentOrganization organization = version >= 10
                        ? new EnvironmentOrganization(input.readInt(), input.readUTF(), input.readUTF())
                        : new EnvironmentOrganization(i + 1, "", "");
                environment = environment.withStoredOrganization(organization);
                if (version >= 11) {
                    EnvironmentGeolocationSettings geo = new EnvironmentGeolocationSettings(
                            input.readUnsignedByte(), input.readDouble(), input.readDouble(), input.readDouble());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt, environment.updatedAt,
                            environment.fingerprint, environment.network, environment.region,
                            environment.androidIdentity, environment.organization, geo);
                }
                if (version >= 12) {
                    int noise = input.readUnsignedByte();
                    int muted = input.readUnsignedByte();
                    if (noise > 1 || muted > 1) throw new IOException("音频设置无效，已保留原文件");
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt, environment.updatedAt,
                            environment.fingerprint.withAudio(noise == 1, muted == 1), environment.network,
                            environment.region, environment.androidIdentity, environment.organization, environment.geolocation);
                }
                if (version >= 13) {
                    int noise = input.readUnsignedByte();
                    if (noise > 1) throw new IOException("布局测量设置无效，已保留原文件");
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt, environment.updatedAt,
                            environment.fingerprint.withClientRectsNoise(noise == 1), environment.network,
                            environment.region, environment.androidIdentity, environment.organization, environment.geolocation);
                }
                if (!identities.add(environment.identity)) throw new IOException("环境标识重复");
                if (version >= 14) {
                    int mode = input.readUnsignedByte();
                    int blocked = input.readUnsignedByte();
                    if (blocked > 1) throw new IOException("媒体权限设置无效，已保留原文件");
                    EnvironmentMediaSettings media = new EnvironmentMediaSettings(mode, blocked == 1,
                            input.readUTF(), input.readUTF(), input.readUTF());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt, environment.updatedAt,
                            environment.fingerprint.withMedia(media), environment.network, environment.region,
                            environment.androidIdentity, environment.organization, environment.geolocation);
                }
                if (version >= 15) {
                    int authentication = input.readUnsignedByte();
                    if (authentication > 1) throw new IOException("代理认证设置无效，已保留原文件");
                    EnvironmentNetworkSettings network = new EnvironmentNetworkSettings(
                            environment.network.proxyUri, environment.network.restrictWebRtcUdp,
                            authentication == 1, input.readUTF());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt, environment.updatedAt,
                            environment.fingerprint, network, environment.region, environment.androidIdentity,
                            environment.organization, environment.geolocation);
                }
                if (version >= 17) {
                    EnvironmentRegionalPolicy policy = new EnvironmentRegionalPolicy(
                            EnvironmentRegionalPolicy.LanguageMode.valueOf(input.readUTF()),
                            EnvironmentRegionalPolicy.TimeZoneMode.valueOf(input.readUTF()),
                            EnvironmentRegionalPolicy.LocationMode.valueOf(input.readUTF()), input.readUTF());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt, environment.updatedAt,
                            environment.fingerprint, environment.network, environment.region, environment.androidIdentity,
                            environment.organization, environment.geolocation, policy);
                }
                if (version >= 18) {
                    int custom = input.readUnsignedByte();
                    int mobile = input.readUnsignedByte();
                    int wow64 = input.readUnsignedByte();
                    if (custom > 1 || mobile > 1 || wow64 > 1) throw new IOException("UA-CH 模式无效");
                    String[] fields = new String[EnvironmentUserAgentSettings.FIELD_COUNT];
                    for (int field = 0; field < fields.length; field++) fields[field] = input.readUTF();
                    EnvironmentUserAgentSettings userAgent = new EnvironmentUserAgentSettings(custom == 1, fields, mobile == 1, wow64 == 1);
                    AnimationFrameSettings frames = new AnimationFrameSettings(input.readInt());
                    environment = new EnvironmentDefinition(environment.identity, environment.name,
                            environment.startPage, environment.notes, environment.createdAt, environment.updatedAt,
                            environment.fingerprint.withUserAgent(userAgent).withAnimationFrames(frames), environment.network,
                            environment.region, environment.androidIdentity, environment.organization, environment.geolocation, environment.regionalPolicy);
                }
                result.add(environment);
            }
            List<EnvironmentGroup> groups = new ArrayList<>();
            if (version >= 10) {
                int groupCount = input.readInt();
                if (groupCount < 0 || groupCount > MAX_GROUPS) throw new IOException("分组数量无效");
                for (int i = 0; i < groupCount; i++) groups.add(new EnvironmentGroup(input.readUTF(), input.readUTF()));
            }
            Set<EnvironmentIdentity> recycled = new HashSet<>();
            if (version >= 16) {
                int recycledCount = input.readInt();
                if (recycledCount < 0 || recycledCount > count) throw new IOException("回收站数量无效");
                for (int i = 0; i < recycledCount; i++) {
                    EnvironmentIdentity identity = EnvironmentIdentity.parse(input.readUTF());
                    if (!identities.contains(identity) || !recycled.add(identity)) {
                        throw new IOException("回收站环境标识无效或重复");
                    }
                }
            }
            if (input.available() != 0) throw new IOException("环境列表包含无法识别的数据");
            Snapshot snapshot = new Snapshot(revision, result, groups, recycled);
            validate(snapshot);
            return snapshot;
        } catch (IllegalArgumentException error) {
            throw new IOException("环境列表损坏，已保留原文件", error);
        }
    }

    /** Caller owns the stream, including closing it on both success and failure. */
    public static Snapshot readConfiguration(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法打开配置文件");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (true) {
            int count = input.read(chunk);
            if (count == -1) break;
            if (count < 0) throw new IOException("配置文件读取异常");
            if (count == 0) {
                int single = input.read();
                if (single == -1) break;
                if (bytes.size() >= MAX_BYTES) throw new IOException("配置文件超过 16 MiB");
                bytes.write(single);
            } else {
                if ((long) bytes.size() + count > MAX_BYTES) throw new IOException("配置文件超过 16 MiB");
                bytes.write(chunk, 0, count);
            }
        }
        return decodeConfiguration(bytes.toByteArray());
    }

    public byte[] exportConfiguration() throws IOException { return encodeConfiguration(read(), false); }

    /** Merge configuration only; private browser files and the active process lease are untouched. */
    public ImportResult importConfiguration(long expectedRevision, Snapshot imported, boolean updateExisting) throws IOException {
        if (imported == null) throw new IllegalArgumentException("导入配置不能为空");
        validate(imported);
        int[] counts = new int[4];
        Snapshot saved = transact(expectedRevision, previous -> {
            List<EnvironmentGroup> groups = new ArrayList<>(previous.groups);
            Map<String, EnvironmentGroup> byId = new HashMap<>();
            Map<String, EnvironmentGroup> byName = new HashMap<>();
            for (EnvironmentGroup group : groups) { byId.put(group.id, group); byName.put(group.nameKey(), group); }
            Map<String, String> groupMapping = new HashMap<>();
            groupMapping.put("", "");
            for (EnvironmentGroup incoming : imported.groups) {
                EnvironmentGroup existing = byId.get(incoming.id);
                if (existing == null) existing = byName.get(incoming.nameKey());
                if (existing == null) {
                    groups.add(incoming); byId.put(incoming.id, incoming); byName.put(incoming.nameKey(), incoming);
                    existing = incoming; counts[3]++;
                }
                groupMapping.put(incoming.id, existing.id);
            }
            List<EnvironmentDefinition> entries = new ArrayList<>(previous.allEnvironments);
            Set<EnvironmentIdentity> recycled = new HashSet<>(previous.recycledIdentities);
            Map<EnvironmentIdentity, Integer> positions = new HashMap<>();
            Set<Integer> numbers = new HashSet<>();
            for (int i = 0; i < entries.size(); i++) {
                positions.put(entries.get(i).identity, i); numbers.add(entries.get(i).organization.number);
            }
            int nextNumber = 1;
            for (EnvironmentDefinition incoming : imported.allEnvironments) {
                Integer index = positions.get(incoming.identity);
                if (index != null && !updateExisting) { counts[2]++; continue; }
                if (index != null && previous.recycledIdentities.contains(incoming.identity)) {
                    throw new IOException("请先从回收站恢复同标识环境，再覆盖其配置");
                }
                String mappedGroup = groupMapping.get(incoming.organization.groupId);
                if (mappedGroup == null) throw new IOException("导入环境引用了不存在的分组");
                int number;
                if (index != null) {
                    EnvironmentDefinition existing = entries.get(index);
                    if (existing.createdAt != incoming.createdAt) {
                        throw new IOException("同标识环境的创建时间不一致，已取消整次导入");
                    }
                    number = existing.organization.number;
                } else {
                    while (numbers.contains(nextNumber)) nextNumber++;
                    number = nextNumber++; numbers.add(number);
                }
                EnvironmentOrganization organization = new EnvironmentOrganization(number, mappedGroup, incoming.organization.tags);
                long updatedAt = index == null ? incoming.updatedAt : Math.max(System.currentTimeMillis(),
                        Math.max(incoming.updatedAt, entries.get(index).updatedAt));
                EnvironmentDefinition replacement = new EnvironmentDefinition(incoming.identity, incoming.name,
                        incoming.startPage, incoming.notes, incoming.createdAt, updatedAt, incoming.fingerprint,
                        incoming.network.withoutLocalCredential(), incoming.region, incoming.androidIdentity, organization, incoming.geolocation,
                        incoming.regionalPolicy);
                if (index == null) {
                    entries.add(replacement); counts[0]++;
                    if (imported.recycledIdentities.contains(incoming.identity)) recycled.add(incoming.identity);
                }
                else { entries.set(index, replacement); counts[1]++; }
            }
            return new Snapshot(Math.addExact(previous.revision, 1), entries, groups, recycled);
        });
        return new ImportResult(saved, counts[0], counts[1], counts[2], counts[3]);
    }

    public Snapshot save(long expectedRevision, EnvironmentDefinition environment) throws IOException {
        if (environment == null) throw new IllegalArgumentException("环境不能为空");
        return transact(expectedRevision, previous -> {
            if (previous.recycledIdentities.contains(environment.identity)) {
                throw new IOException("请先从回收站恢复环境，再编辑配置");
            }
            List<EnvironmentDefinition> entries = new ArrayList<>(previous.allEnvironments);
            EnvironmentDefinition candidate = environment;
            boolean replaced = false;
            int highest = 0;
            for (int i = 0; i < entries.size(); i++) {
                EnvironmentDefinition existing = entries.get(i);
                highest = Math.max(highest, existing.organization.number);
                if (existing.identity.equals(environment.identity)) {
                    if (existing.createdAt != environment.createdAt) throw new IOException("不能修改环境创建时间");
                    if (environment.organization.number != 0
                            && environment.organization.number != existing.organization.number) {
                        throw new IOException("请使用重新编号操作调整编号");
                    }
                    candidate = environment.withStoredOrganization(
                            environment.organization.withNumber(existing.organization.number));
                    entries.set(i, candidate);
                    replaced = true;
                }
            }
            if (!replaced) {
                if (environment.organization.number != 0) throw new IOException("新环境编号由本机自动分配");
                candidate = environment.withStoredOrganization(environment.organization.withNumber(highest + 1));
                entries.add(candidate);
            }
            return next(previous, entries, previous.groups);
        });
    }

    public Snapshot saveGroup(long expectedRevision, EnvironmentGroup group) throws IOException {
        if (group == null) throw new IllegalArgumentException("分组不能为空");
        return transact(expectedRevision, previous -> {
            List<EnvironmentGroup> groups = new ArrayList<>(previous.groups);
            boolean replaced = false;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).id.equals(group.id)) { groups.set(i, group); replaced = true; break; }
            }
            if (!replaced) groups.add(group);
            return next(previous, previous.allEnvironments, groups);
        });
    }

    /** Remove only the group: all member environments and their data remain. */
    public Snapshot removeGroup(long expectedRevision, String groupId) throws IOException {
        return transact(expectedRevision, previous -> {
            List<EnvironmentGroup> groups = new ArrayList<>(previous.groups);
            if (!groups.removeIf(group -> group.id.equals(groupId))) throw new IOException("分组不存在或已被移除");
            List<EnvironmentDefinition> entries = new ArrayList<>();
            for (EnvironmentDefinition environment : previous.allEnvironments) {
                entries.add(environment.organization.groupId.equals(groupId)
                        ? environment.withOrganization(environment.organization.withGroup("")) : environment);
            }
            return next(previous, entries, groups);
        });
    }

    public Snapshot renumber(long expectedRevision) throws IOException {
        return transact(expectedRevision, previous -> {
            List<EnvironmentDefinition> entries = new ArrayList<>();
            for (EnvironmentDefinition environment : previous.allEnvironments) {
                entries.add(environment.withOrganization(environment.organization.withNumber(entries.size() + 1)));
            }
            return next(previous, entries, previous.groups);
        });
    }

    private static Snapshot next(Snapshot previous, List<EnvironmentDefinition> entries, List<EnvironmentGroup> groups) {
        return new Snapshot(Math.addExact(previous.revision, 1), entries, groups, previous.recycledIdentities);
    }

    /** Reversible catalog-only transition. Browser directories and credentials remain untouched. */
    public Snapshot recycle(long expectedRevision, EnvironmentIdentity identity) throws IOException {
        return setRecycled(expectedRevision, identity, true);
    }

    public Snapshot restore(long expectedRevision, EnvironmentIdentity identity) throws IOException {
        return setRecycled(expectedRevision, identity, false);
    }

    private Snapshot setRecycled(long expectedRevision, EnvironmentIdentity identity, boolean recycle) throws IOException {
        if (identity == null) throw new IllegalArgumentException("环境标识不能为空");
        try {
            return new EnvironmentLeaseStore(directory).whileIdle(() -> transact(expectedRevision, previous -> {
                boolean exists = false;
                for (EnvironmentDefinition environment : previous.allEnvironments) {
                    if (environment.identity.equals(identity)) { exists = true; break; }
                }
                if (!exists) throw new IOException("环境不存在");
                Set<EnvironmentIdentity> recycled = new HashSet<>(previous.recycledIdentities);
                boolean changed = recycle ? recycled.add(identity) : recycled.remove(identity);
                if (!changed) throw new IOException(recycle ? "环境已在回收站中" : "环境不在回收站中");
                return new Snapshot(Math.addExact(previous.revision, 1), previous.allEnvironments, previous.groups, recycled);
            }));
        } catch (EnvironmentLeaseStore.BusyException busy) {
            throw new IOException("请先关闭运行中的环境，或取消待启动的环境，再进行回收或恢复", busy);
        }
    }

    private interface Mutation { Snapshot apply(Snapshot previous) throws IOException; }
    private interface LockedOperation<T> { T apply(Snapshot snapshot) throws IOException; }

    public interface ActiveOperation<T> { T run(EnvironmentDefinition environment) throws IOException; }

    /** Prevent archive/import transitions during a local owner-bound account operation. */
    public <T> T accessActive(EnvironmentIdentity identity, long createdAt, ActiveOperation<T> operation) throws IOException {
        if (identity == null || operation == null) throw new IllegalArgumentException("缺少环境账号操作");
        return locked(previous -> {
            EnvironmentDefinition environment = previous.find(identity);
            if (environment.createdAt != createdAt) throw new IOException("环境信息已变化，请重新打开账号窗口");
            return operation.run(environment);
        });
    }

    private Snapshot transact(long expectedRevision, Mutation mutation) throws IOException {
        return locked(previous -> {
            if (previous.revision != expectedRevision) {
                throw new IOException("环境列表已更新，请重新打开编辑页面");
            }
            Snapshot next = mutation.apply(previous);
            validate(next);
            write(next);
            return next;
        });
    }

    private <T> T locked(LockedOperation<T> operation) throws IOException {
        synchronized (LOCAL_LOCK) {
            Path lockPath = directory.resolve("catalog.lock");
            rejectLink(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock lock = channel.lock()) {
                return operation.apply(read());
            }
        }
    }

    private static byte[] encodeConfiguration(Snapshot snapshot, boolean includeLocalReferences) throws IOException {
        validate(snapshot);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(Math.max(1, snapshot.revision));
            output.writeInt(snapshot.allEnvironments.size());
            for (EnvironmentDefinition environment : snapshot.allEnvironments) {
                output.writeUTF(environment.identity.id());
                output.writeUTF(environment.name);
                output.writeUTF(environment.startPage);
                output.writeUTF(environment.notes);
                output.writeLong(environment.createdAt);
                output.writeLong(environment.updatedAt);
                output.writeUTF(environment.fingerprint.seed);
                output.writeInt(environment.fingerprint.hardwareConcurrency);
                output.writeInt(environment.fingerprint.deviceMemory);
                output.writeByte(environment.fingerprint.regionalFontIsolation ? 1 : 0);
                output.writeUTF(environment.network.proxyUri);
                output.writeByte(environment.network.restrictWebRtcUdp ? 1 : 0);
                output.writeUTF(environment.region.languages);
                output.writeUTF(environment.region.timeZone);
                output.writeByte(environment.fingerprint.canvasNoise ? 1 : 0);
                output.writeByte(environment.fingerprint.doNotTrack);
                output.writeUTF(environment.androidIdentity.platformVersion);
                output.writeUTF(environment.androidIdentity.model);
                for (String value : environment.fingerprint.gpuMetadata.values()) output.writeUTF(value);
                output.writeInt(environment.organization.number);
                output.writeUTF(environment.organization.groupId);
                output.writeUTF(environment.organization.tags);
                output.writeByte(environment.geolocation.mode);
                output.writeDouble(environment.geolocation.latitude);
                output.writeDouble(environment.geolocation.longitude);
                output.writeDouble(environment.geolocation.accuracy);
                output.writeByte(environment.fingerprint.audioNoise ? 1 : 0);
                output.writeByte(environment.fingerprint.muteAudio ? 1 : 0);
                output.writeByte(environment.fingerprint.clientRectsNoise ? 1 : 0);
                output.writeByte(environment.fingerprint.media.mode);
                output.writeByte(environment.fingerprint.media.blockCapture ? 1 : 0);
                for (String label : environment.fingerprint.media.labels()) output.writeUTF(label);
                output.writeByte(environment.network.requiresAuthentication ? 1 : 0);
                output.writeUTF(includeLocalReferences ? environment.network.credentialReference : "");
                output.writeUTF(environment.regionalPolicy.languageMode.name());
                output.writeUTF(environment.regionalPolicy.timeZoneMode.name());
                output.writeUTF(environment.regionalPolicy.locationMode.name());
                output.writeUTF(environment.regionalPolicy.preferredLanguages);
                output.writeByte(environment.fingerprint.userAgent.custom ? 1 : 0);
                output.writeByte(environment.fingerprint.userAgent.mobile ? 1 : 0);
                output.writeByte(environment.fingerprint.userAgent.wow64 ? 1 : 0);
                for (String value : environment.fingerprint.userAgent.values()) output.writeUTF(value);
                output.writeInt(environment.fingerprint.animationFrames.maxFramesPerSecond);
            }
            output.writeInt(snapshot.groups.size());
            for (EnvironmentGroup group : snapshot.groups) {
                output.writeUTF(group.id);
                output.writeUTF(group.name);
            }
            output.writeInt(snapshot.recycledEnvironments.size());
            for (EnvironmentDefinition environment : snapshot.recycledEnvironments) {
                output.writeUTF(environment.identity.id());
            }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("环境列表文件超过容量限制");
        return bytes.toByteArray();
    }

    private void write(Snapshot snapshot) throws IOException {
        rejectLink(catalog);
        byte[] bytes = encodeConfiguration(snapshot, true);
        Path temporary = Files.createTempFile(directory, "catalog-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, catalog, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("环境配置路径不允许使用符号链接");
    }

    private static void validate(Snapshot snapshot) throws IOException {
        if (snapshot.allEnvironments.size() > MAX_ENVIRONMENTS) throw new IOException("环境数量（含回收站）已达到本机管理上限");
        if (snapshot.groups.size() > MAX_GROUPS) throw new IOException("分组数量已达到本机管理上限");
        Set<String> groupIds = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (EnvironmentGroup group : snapshot.groups) {
            if (!groupIds.add(group.id) || !names.add(group.nameKey())) {
                throw new IOException("分组名称或标识重复");
            }
        }
        Set<Integer> numbers = new HashSet<>();
        Set<EnvironmentIdentity> identities = new HashSet<>();
        for (EnvironmentDefinition environment : snapshot.allEnvironments) {
            if (!identities.add(environment.identity)) throw new IOException("环境标识重复");
            EnvironmentOrganization organization = environment.organization;
            if (organization.number < 1 || !numbers.add(organization.number)) throw new IOException("环境编号无效或重复");
            if (!organization.groupId.isEmpty() && !groupIds.contains(organization.groupId)) throw new IOException("环境所属分组不存在");
        }
    }

    public static final class ImportResult {
        public final Snapshot snapshot;
        public final int added;
        public final int updated;
        public final int skipped;
        public final int groupsAdded;
        private ImportResult(Snapshot snapshot, int added, int updated, int skipped, int groupsAdded) {
            this.snapshot = snapshot; this.added = added; this.updated = updated;
            this.skipped = skipped; this.groupsAdded = groupsAdded;
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final List<EnvironmentDefinition> environments;
        public final List<EnvironmentDefinition> allEnvironments;
        public final List<EnvironmentDefinition> recycledEnvironments;
        public final List<EnvironmentGroup> groups;
        private final Set<EnvironmentIdentity> recycledIdentities;

        private Snapshot(long revision, List<EnvironmentDefinition> environments, List<EnvironmentGroup> groups) {
            this(revision, environments, groups, Collections.emptySet());
        }

        private Snapshot(long revision, List<EnvironmentDefinition> all, List<EnvironmentGroup> groups,
                Set<EnvironmentIdentity> recycled) {
            this.revision = revision;
            this.allEnvironments = Collections.unmodifiableList(new ArrayList<>(all));
            this.recycledIdentities = Collections.unmodifiableSet(new HashSet<>(recycled));
            List<EnvironmentDefinition> active = new ArrayList<>();
            List<EnvironmentDefinition> inactive = new ArrayList<>();
            for (EnvironmentDefinition environment : all) {
                (recycled.contains(environment.identity) ? inactive : active).add(environment);
            }
            this.environments = Collections.unmodifiableList(active);
            this.recycledEnvironments = Collections.unmodifiableList(inactive);
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
        }

        public String groupName(String id) {
            if (id.isEmpty()) return "未分组";
            for (EnvironmentGroup group : groups) if (group.id.equals(id)) return group.name;
            return "分组已移除";
        }

        /** null group selects all; empty string selects ungrouped. */
        public List<EnvironmentDefinition> filter(String groupId, String query) {
            String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            List<EnvironmentDefinition> result = new ArrayList<>();
            for (EnvironmentDefinition environment : environments) {
                if (groupId != null && !groupId.equals(environment.organization.groupId)) continue;
                if (needle.isEmpty()) { result.add(environment); continue; }
                String haystack = environment.organization.number + " " + environment.name + " "
                        + environment.notes + " " + environment.organization.tags + " "
                        + groupName(environment.organization.groupId) + " " + environment.startPage;
                if (haystack.toLowerCase(Locale.ROOT).contains(needle)) result.add(environment);
            }
            return Collections.unmodifiableList(result);
        }

        public EnvironmentDefinition find(EnvironmentIdentity identity) throws IOException {
            for (EnvironmentDefinition environment : environments) {
                if (environment.identity.equals(identity)) return environment;
            }
            throw new IOException("环境不存在或已被移除");
        }
    }
}
