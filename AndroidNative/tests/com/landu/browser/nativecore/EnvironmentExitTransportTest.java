package com.landu.browser.nativecore;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** Real local TLS origin and HTTP/HTTPS/SOCKS5 proxies; no external IP service or phone. */
public final class EnvironmentExitTransportTest {
    private static int assertions;
    private static final AtomicInteger accepted=new AtomicInteger();
    public static void main(String[] args)throws Exception {
        KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(Path.of(args[1]))){store.load(in,"landu-qa-only".toCharArray());}
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,"landu-qa-only".toCharArray());
        TrustManagerFactory trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        SSLContext context=SSLContext.getInstance("TLS");context.init(keys.getKeyManagers(),trust.getTrustManagers(),null);
        EnvironmentExitTransport transport=new EnvironmentExitTransport(context.getSocketFactory());
        ExecutorService worker=Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});
        SSLServerSocket origin=(SSLServerSocket)context.getServerSocketFactory().createServerSocket(0,10,InetAddress.getLoopbackAddress());
        AtomicReference<String> response=new AtomicReference<>("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");
        AtomicReference<String> originRequest=new AtomicReference<>();
        worker.submit(()->{while(!origin.isClosed())try{Socket socket=origin.accept();accepted.incrementAndGet();worker.submit(()->{try(socket){originRequest.set(readHeaders(socket.getInputStream()));socket.getOutputStream().write(response.get().getBytes(StandardCharsets.US_ASCII));}catch(IOException ignored){}});}catch(IOException ignored){};});
        URI endpoint=URI.create("https://localhost:"+origin.getLocalPort()+"/test?fields=ip");
        try {
            check(transport.get(endpoint,EnvironmentNetworkSettings.system(),null).equals("{}"),"Direct TLS origin");
            AtomicInteger resolvedHosts = new AtomicInteger();
            EnvironmentExitTransport dualStack = new EnvironmentExitTransport(context.getSocketFactory(), host -> {
                resolvedHosts.incrementAndGet();
                return new InetAddress[] {InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")};
            });
            check(dualStack.get(endpoint,EnvironmentNetworkSettings.system(),null).equals("{}")
                    && resolvedHosts.get()==1,"Unavailable IPv6 retries IPv4 of the same routed host");
            response.set("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n1\r\n}\r\n0\r\nX-QA: yes\r\n\r\n");
            check(transport.get(endpoint,EnvironmentNetworkSettings.system(),null).equals("{}"),"Chunked body and trailers");
            response.set("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");
            for(String scheme:new String[]{"http","https","socks5","socks5-auth"}) {
                URI proxiedEndpoint=URI.create("https://exit-test.invalid:"+origin.getLocalPort()+"/test");
                EnvironmentExitTransport proxyTransport=new EnvironmentExitTransport(context.getSocketFactory(), host->{
                    checkWorker(host.equals("localhost"));
                    return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                });
                ServerSocket proxy=scheme.equals("https")?context.getServerSocketFactory().createServerSocket(0,10,InetAddress.getLoopbackAddress()):new ServerSocket(0,10,InetAddress.getLoopbackAddress());
                AtomicReference<String> observed=new AtomicReference<>();
                Future<?> proxyJob=worker.submit(()->{try(proxy;Socket client=proxy.accept()){
                    if(scheme.startsWith("socks5")) {
                        DataInputStream in=new DataInputStream(client.getInputStream());OutputStream out=client.getOutputStream();
                        boolean authenticated = scheme.equals("socks5-auth");
                        int method = authenticated ? 2 : 0;
                        checkWorker(in.readUnsignedByte()==5&&in.readUnsignedByte()==1&&in.readUnsignedByte()==method);
                        out.write(new byte[]{5,(byte)method});out.flush();
                        if(authenticated) {
                            checkWorker(in.readUnsignedByte()==1);
                            byte[] user=new byte[in.readUnsignedByte()];in.readFully(user);
                            byte[] pass=new byte[in.readUnsignedByte()];in.readFully(pass);
                            checkWorker(new String(user,StandardCharsets.UTF_8).equals("qa-user"));
                            checkWorker(new String(pass,StandardCharsets.UTF_8).equals("qa-pass"));
                            out.write(new byte[]{1,0});out.flush();
                        }
                        checkWorker(in.readUnsignedByte()==5&&in.readUnsignedByte()==1&&in.readUnsignedByte()==0&&in.readUnsignedByte()==3);
                        byte[] host=new byte[in.readUnsignedByte()];in.readFully(host);int port=in.readUnsignedShort();observed.set(new String(host,StandardCharsets.US_ASCII)+":"+port);
                        out.write(new byte[]{5,0,0,1,127,0,0,1,0,0});out.flush();
                    } else {observed.set(readHeaders(client.getInputStream()));client.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();}
                    try(Socket target=new Socket(InetAddress.getLoopbackAddress(),origin.getLocalPort())){
                        Future<?> back=worker.submit(()->pump(target,client));pump(client,target);back.get(6,TimeUnit.SECONDS);
                    }
                }catch(Exception failure){throw new RuntimeException(failure);}});
                boolean auth=!scheme.equals("socks5");
                EnvironmentNetworkSettings network=new EnvironmentNetworkSettings(scheme.replace("-auth", "")+"://localhost:"+proxy.getLocalPort(),true,auth,"");
                check(proxyTransport.get(proxiedEndpoint,network,auth?new ProxyCredentials("qa-user","qa-pass"):null).equals("{}"),scheme+" routes target without resolving target DNS locally");
                proxyJob.get(8,TimeUnit.SECONDS);
                check(!originRequest.get().contains("Proxy-Authorization")&&originRequest.get().contains("Host: exit-test.invalid:"),"Proxy credentials never reach the origin request");
                if(auth&&!scheme.startsWith("socks5"))check(observed.get().contains("Proxy-Authorization: Basic cWEtdXNlcjpxYS1wYXNz"),"Credentials only sent to configured proxy");
                else check(observed.get().equals("exit-test.invalid:"+origin.getLocalPort()),"SOCKS target name sent to proxy, not resolved locally");
            }
            response.set("HTTP/1.1 302 Found\r\nLocation: http://localhost/\r\nContent-Length: 0\r\n\r\n");rejects(()->transport.get(endpoint,EnvironmentNetworkSettings.system(),null));
            response.set("HTTP/1.1 200 OK\r\nContent-Length: 32769\r\n\r\n");rejects(()->transport.get(endpoint,EnvironmentNetworkSettings.system(),null));
            response.set("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}");rejects(()->transport.get(endpoint,EnvironmentNetworkSettings.system(),null));
            response.set("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");
            rejects(()->transport.get(URI.create("https://127.0.0.1:"+origin.getLocalPort()+"/"),EnvironmentNetworkSettings.system(),null));
            try(ServerSocket denied=new ServerSocket(0,10,InetAddress.getLoopbackAddress())) {
                Future<?> job=worker.submit(()->{try(Socket client=denied.accept()){readHeaders(client.getInputStream());client.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".getBytes(StandardCharsets.US_ASCII));}catch(IOException failure){throw new RuntimeException(failure);}});
                int before=accepted.get();EnvironmentNetworkSettings network=new EnvironmentNetworkSettings("http://localhost:"+denied.getLocalPort(),true);
                rejects(()->transport.get(endpoint,network,null));job.get(6,TimeUnit.SECONDS);
                check(accepted.get()==before,"Failed proxy never retries directly against available origin");
            }
            System.out.println("{\"suite\":\"routed-exit-https-transport\",\"assertions\":"+assertions+",\"passed\":true,\"externalServiceUsed\":false}");
        } finally {origin.close();worker.shutdownNow();}
    }
    private static void pump(Socket from,Socket to){try{from.getInputStream().transferTo(to.getOutputStream());to.shutdownOutput();}catch(IOException ignored){}}
    private static String readHeaders(InputStream in)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int matched=0;byte[] end={13,10,13,10};while(b.size()<16384){int c=in.read();if(c<0)throw new EOFException();b.write(c);matched=c==end[matched]?matched+1:c==13?1:0;if(matched==4)return b.toString("US-ASCII");}throw new IOException("header too large");}
    interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{assertions++;try{action.run();}catch(IOException expected){return;}throw new AssertionError("Invalid network response accepted");}
    private static void checkWorker(boolean ok){if(!ok)throw new AssertionError("Invalid SOCKS handshake");}
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
}
