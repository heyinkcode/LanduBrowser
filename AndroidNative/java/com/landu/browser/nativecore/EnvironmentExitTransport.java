package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Bounded HTTPS query through exactly the configured route. No global authenticator or direct retry. */
public final class EnvironmentExitTransport {
    private static final int LIMIT = 32768;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "landu-exit-deadline"); thread.setDaemon(true); return thread;
    });
    private final SSLSocketFactory tls;
    private final Resolver resolver;
    public EnvironmentExitTransport() { this((SSLSocketFactory) SSLSocketFactory.getDefault()); }
    EnvironmentExitTransport(SSLSocketFactory tls) { this(tls, InetAddress::getAllByName); }
    EnvironmentExitTransport(SSLSocketFactory tls, Resolver resolver) { this.tls = tls; this.resolver = resolver; }
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }

    public String get(URI endpoint, EnvironmentNetworkSettings network, ProxyCredentials credentials) throws IOException {
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("出口检测必须使用 HTTPS");
        }
        if (network.requiresAuthentication && credentials == null) throw new IOException("请先更新代理账号密码");
        if (network.requiresAuthentication) credentials.validateFor(network);
        String host = endpoint.getHost(); int port = endpoint.getPort() < 0 ? 443 : endpoint.getPort();
        AtomicReference<Socket> owned = new AtomicReference<>();
        long expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        ScheduledFuture<?> deadline = TIMER.schedule(() -> close(owned.get()), 12, TimeUnit.SECONDS);
        try {
            Socket socket;
            if (network.proxyUri.isEmpty()) {
                socket = connect(host, port, owned, expires);
            } else {
                URI proxy = URI.create(network.proxyUri);
                String proxyHost = proxy.getHost();
                if (proxyHost.startsWith("[")) proxyHost = proxyHost.substring(1, proxyHost.length()-1);
                socket = connect(proxyHost, proxy.getPort(), owned, expires);
                if (proxy.getScheme().equals("socks5")) {
                    socksTunnel(socket, host, port, network.requiresAuthentication ? credentials : null);
                } else {
                    if (proxy.getScheme().equals("https")) {
                        socket = secure(socket, proxyHost, proxy.getPort()); owned.set(socket);
                    }
                    String authority = host + ":" + port;
                    String authorization = "";
                    if (network.requiresAuthentication) {
                        authorization = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(
                                (credentials.username + ":" + credentials.password).getBytes(StandardCharsets.UTF_8)) + "\r\n";
                    }
                    write(socket.getOutputStream(), "CONNECT " + authority + " HTTP/1.1\r\nHost: "
                            + authority + "\r\n" + authorization + "\r\n");
                    Headers response = headers(socket.getInputStream());
                    if (response.status != 200) throw new IOException("代理未建立出口检测连接（HTTP " + response.status + "）");
                }
            }
            socket = secure(socket, host, port); owned.set(socket);
            String path = endpoint.getRawPath(); if (path == null || path.isEmpty()) path = "/";
            if (endpoint.getRawQuery() != null) path += "?" + endpoint.getRawQuery();
            write(socket.getOutputStream(), "GET " + path + " HTTP/1.1\r\nHost: " + host
                    + (port == 443 ? "" : ":" + port)
                    + "\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nConnection: close\r\nUser-Agent: LanduBrowser-ExitCheck/1\r\n\r\n");
            InputStream input = socket.getInputStream(); Headers response = headers(input);
            if (response.status != 200) throw new IOException("出口查询暂不可用（HTTP " + response.status + "）");
            if (response.values.containsKey("content-encoding")
                    && !response.values.get("content-encoding").equalsIgnoreCase("identity")) throw new IOException("出口响应编码不支持");
            return new String(body(input, response.values), StandardCharsets.UTF_8);
        } finally { deadline.cancel(false); close(owned.get()); }
    }

    private Socket connect(String host, int port, AtomicReference<Socket> owned, long expires) throws IOException {
        IOException failure = new IOException("出口主机没有可用地址");
        // Resolve the destination only for direct mode; a proxy resolves its own tunnel target.
        for (InetAddress address : resolver.resolve(host)) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(expires - System.nanoTime());
            if (remaining <= 0) throw new SocketTimeoutException("出口检测超时");
            Socket candidate = new Socket(java.net.Proxy.NO_PROXY);
            close(owned.getAndSet(candidate));
            candidate.setSoTimeout(5000);
            try {
                candidate.connect(new InetSocketAddress(address, port), (int)Math.min(2500, remaining));
                return candidate;
            } catch (IOException unavailable) { failure = unavailable; close(candidate); }
        }
        throw failure;
    }

    private Socket secure(Socket socket, String host, int port) throws IOException {
        SSLSocket encrypted = (SSLSocket) tls.createSocket(socket, host, port, true);
        encrypted.setSoTimeout(5000);
        SSLParameters parameters = encrypted.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS"); encrypted.setSSLParameters(parameters);
        try { encrypted.startHandshake(); return encrypted; }
        catch (IOException failure) { close(encrypted); throw failure; }
    }
    static void socksTunnel(Socket socket, String host, int port, ProxyCredentials credentials) throws IOException {
        InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
        // Offer only the configured method; an authenticated environment cannot downgrade.
        int method = credentials == null ? 0 : 2;
        byte[] username = credentials == null ? new byte[0] : credentials.username.getBytes(StandardCharsets.UTF_8);
        byte[] password = credentials == null ? new byte[0] : credentials.password.getBytes(StandardCharsets.UTF_8);
        if (credentials != null && (username.length < 1 || username.length > 255 || password.length < 1 || password.length > 255)) {
            throw new IOException("SOCKS5 账号或密码长度无效");
        }
        try {
            output.write(new byte[]{5,1,(byte)method}); output.flush();
            if (read(input) != 5 || read(input) != method) throw new IOException("SOCKS5 不接受所选认证方式");
            if (credentials != null) {
                output.write(1); output.write(username.length); output.write(username);
                output.write(password.length); output.write(password); output.flush();
                if (read(input) != 1 || read(input) != 0) throw new IOException("SOCKS5 账号密码认证失败，请检查后重试");
            }
        } finally {
            java.util.Arrays.fill(username, (byte)0); java.util.Arrays.fill(password, (byte)0);
        }
        byte[] name = java.net.IDN.toASCII(host).getBytes(StandardCharsets.US_ASCII);
        if (name.length == 0 || name.length > 255) throw new IOException("出口主机无效");
        output.write(new byte[]{5,1,0,3,(byte)name.length}); output.write(name);
        output.write(port >> 8); output.write(port & 255); output.flush();
        if (read(input) != 5 || read(input) != 0 || read(input) != 0) throw new IOException("SOCKS5 出口检测连接失败");
        int kind = read(input);
        int length;
        if (kind == 1) length = 4; else if (kind == 4) length = 16;
        else if (kind == 3) length = read(input); else throw new IOException("SOCKS5 响应地址无效");
        for (int i=0; i<length+2; i++) read(input);
    }
    private static Headers headers(InputStream input) throws IOException {
        int[] budget = {16384}; String status = line(input, budget);
        if (!status.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw new IOException("出口 HTTP 响应无效");
        Map<String,String> values = new HashMap<>();
        while (true) {
            String line = line(input, budget); if (line.isEmpty()) break;
            int colon = line.indexOf(':'); if (colon <= 0) throw new IOException("出口响应头无效");
            String key = line.substring(0,colon).toLowerCase(Locale.ROOT);
            if (!key.matches("[a-z0-9-]+")) throw new IOException("出口响应头无效");
            String value = line.substring(colon+1).trim();
            if (values.put(key,value) != null && (key.equals("content-length") || key.equals("transfer-encoding"))) {
                throw new IOException("出口响应长度重复");
            }
        }
        return new Headers(Integer.parseInt(status.substring(9,12)),values);
    }
    private static byte[] body(InputStream input, Map<String,String> headers) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String encoding = headers.get("transfer-encoding");
        String length = headers.get("content-length");
        if (encoding != null) {
            if (length != null || !encoding.equalsIgnoreCase("chunked")) throw new IOException("出口响应分块无效");
            int[] budget = {16384};
            while (true) {
                String line = line(input,budget); int extension = line.indexOf(';');
                String size = extension < 0 ? line : line.substring(0,extension);
                if (!size.matches("[0-9A-Fa-f]{1,8}")) throw new IOException("出口分块长度无效");
                long count = Long.parseLong(size,16);
                if (count > LIMIT-bytes.size()) throw new IOException("出口响应过大");
                if (count == 0) { while (!line(input,budget).isEmpty()) { } break; }
                copy(input,bytes,(int)count);
                if (read(input) != 13 || read(input) != 10) throw new IOException("出口分块结束无效");
            }
        } else if (length != null) {
            if (!length.matches("[0-9]{1,8}") || Long.parseLong(length)>LIMIT) throw new IOException("出口响应长度无效");
            copy(input,bytes,Integer.parseInt(length));
        } else {
            int value;
            while ((value=input.read()) >= 0) { if (bytes.size()==LIMIT) throw new IOException("出口响应过大"); bytes.write(value); }
        }
        return bytes.toByteArray();
    }
    private static String line(InputStream input,int[] budget) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (--budget[0]>=0) {
            int value=read(input);
            if (value==13) { if (--budget[0]<0 || read(input)!=10) throw new IOException("出口响应换行无效"); return bytes.toString("ISO-8859-1"); }
            if (value==10) throw new IOException("出口响应换行无效"); bytes.write(value);
        }
        throw new IOException("出口响应头过大");
    }
    private static void copy(InputStream input,OutputStream output,int count) throws IOException {
        byte[] buffer=new byte[4096];
        while(count>0) { int n=input.read(buffer,0,Math.min(count,buffer.length)); if(n<0)throw new EOFException(); output.write(buffer,0,n);count-=n; }
    }
    private static int read(InputStream input)throws IOException { int value=input.read(); if(value<0)throw new EOFException();return value; }
    private static void write(OutputStream output,String value)throws IOException { output.write(value.getBytes(StandardCharsets.ISO_8859_1));output.flush(); }
    private static void close(Socket socket) { if(socket!=null)try{socket.close();}catch(IOException ignored){} }
    private static final class Headers { final int status; final Map<String,String> values; Headers(int s,Map<String,String> v){status=s;values=v;} }
}
