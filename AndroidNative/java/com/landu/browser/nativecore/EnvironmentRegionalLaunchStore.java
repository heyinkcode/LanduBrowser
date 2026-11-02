package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Private launch receipt, bound to identity, configuration and lease nonce; never exported as user configuration. */
public final class EnvironmentRegionalLaunchStore {
    private final Path root;
    public EnvironmentRegionalLaunchStore(Path controlRoot) throws IOException {
        Path folder = controlRoot.resolve("regional-launch");
        if (Files.isSymbolicLink(folder)) throw new IOException("地区启动目录无效");
        Files.createDirectories(folder); root=folder.toRealPath();
    }
    public void write(EnvironmentDefinition environment, EnvironmentLeaseStore.Ticket ticket,
            EnvironmentExitInfo measured, String failure) throws IOException {
        if (!environment.identity.equals(ticket.identity)) throw new IOException("地区配置归属不一致");
        if (measured != null && measured.ip.isEmpty()) throw new IOException("出口 IP 缺失");
        if (failure == null || failure.length()>200 || failure.indexOf('\0')>=0) throw new IOException("检测状态无效");
        if (environment.regionalPolicy.needsExitLookup() && measured == null) throw new IOException("自动地区设置需要有效出口检测");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4c445231); out.writeUTF(ticket.nonce); out.write(signature(environment));
            out.writeUTF(failure); out.writeBoolean(measured != null);
            if (measured != null) {
                out.writeUTF(measured.ip);out.writeUTF(measured.countryCode);out.writeUTF(measured.city);out.writeUTF(measured.timeZone);
                out.writeLong(measured.checkedAt);out.writeBoolean(measured.hasPosition());
                if(measured.hasPosition()){out.writeDouble(measured.latitude);out.writeDouble(measured.longitude);}
            }
        }
        Path destination = path(environment); check(destination);
        Path temp = Files.createTempFile(root,"regional-",".tmp");
        try {
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                ByteBuffer data=ByteBuffer.wrap(bytes.toByteArray());while(data.hasRemaining())channel.write(data);channel.force(true);
            }
            Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp);}
    }
    public Receipt read(EnvironmentDefinition environment, EnvironmentLeaseStore.Ticket ticket) throws IOException {
        Receipt receipt=read(environment);
        if(!environment.identity.equals(ticket.identity) || !receipt.nonce.equals(ticket.nonce)) throw new IOException("地区启动状态已失效，请重新启动环境");
        return receipt;
    }
    public Receipt read(EnvironmentDefinition environment) throws IOException {
        Path source=path(environment);check(source);
        if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)>4096)throw new IOException("请先检测环境出口");
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(source)))) {
            if(input.readInt()!=0x4c445231)throw new IOException("地区启动状态无效");
            String nonce=input.readUTF();byte[] hash=new byte[32];input.readFully(hash);
            if(!MessageDigest.isEqual(hash,signature(environment)))throw new IOException("环境配置已变化，请重新检测出口");
            String failure=input.readUTF(); EnvironmentExitInfo measured=null;
            if(input.readBoolean()) {
                String ip=input.readUTF();
                String country=input.readUTF();
                String city=input.readUTF();
                String zone=input.readUTF();
                long checked=input.readLong();
                Double latitude=null;
                Double longitude=null;
                if(input.readBoolean()){latitude=input.readDouble();longitude=input.readDouble();}
                measured=new EnvironmentExitInfo(ip,country,city,zone,latitude,longitude,checked);
                if(ip.isEmpty())throw new IOException("出口 IP 缺失");
            }
            if(input.available()!=0 || failure.length()>200)throw new IOException("地区启动状态无效");
            if(environment.regionalPolicy.needsExitLookup() && measured==null)throw new IOException("缺少自动地区检测结果");
            return new Receipt(nonce,measured,failure,EnvironmentRegionalResolver.resolve(environment.regionalPolicy,
                    environment.region,environment.geolocation,measured));
        } catch(IllegalArgumentException invalid){throw new IOException("地区启动状态无效",invalid);}
    }
    private Path path(EnvironmentDefinition environment) {return root.resolve(environment.identity.id()+".bin");}
    private static void check(Path path)throws IOException{if(Files.isSymbolicLink(path))throw new IOException("地区启动文件无效");}
    private static byte[] signature(EnvironmentDefinition e)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)) {
            out.writeUTF(e.identity.id());out.writeLong(e.createdAt);out.writeLong(e.updatedAt);
            out.writeUTF(e.network.proxyUri);out.writeBoolean(e.network.requiresAuthentication);out.writeUTF(e.network.credentialReference);
            out.writeUTF(e.regionalPolicy.languageMode.name());out.writeUTF(e.regionalPolicy.timeZoneMode.name());
            out.writeUTF(e.regionalPolicy.locationMode.name());out.writeUTF(e.regionalPolicy.preferredLanguages);
            out.writeUTF(e.region.languages);out.writeUTF(e.region.timeZone);out.writeInt(e.geolocation.mode);
            out.writeDouble(e.geolocation.latitude);out.writeDouble(e.geolocation.longitude);out.writeDouble(e.geolocation.accuracy);
        }
        try{return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static final class Receipt {
        public final String nonce; public final EnvironmentExitInfo measured; public final String failure;
        public final EnvironmentRegionalResolver.Result resolved;
        Receipt(String nonce,EnvironmentExitInfo measured,String failure,EnvironmentRegionalResolver.Result resolved) {
            this.nonce=nonce;this.measured=measured;this.failure=failure;this.resolved=resolved;
        }
    }
}
