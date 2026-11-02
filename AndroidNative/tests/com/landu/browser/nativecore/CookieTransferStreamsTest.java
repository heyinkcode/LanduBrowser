package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class CookieTransferStreamsTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        String unicode = "[{\"name\":\"own\",\"value\":\"汉字😀\"}]";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CookieTransferStreams.write(output, unicode);
        check(CookieTransferStreams.read(new ByteArrayInputStream(output.toByteArray())).equals(unicode));
        InputStream fragmented = new FilterInputStream(new ByteArrayInputStream(output.toByteArray())) {
            @Override public int read(byte[] data, int offset, int length) throws IOException {
                return super.read(data, offset, Math.min(length, 1));
            }
        };
        check(CookieTransferStreams.read(fragmented).equals(unicode));
        check(CookieTransferStreams.read(new ByteArrayInputStream(("\ufeff" + unicode).getBytes(StandardCharsets.UTF_8))).equals(unicode));
        String maximum = "x".repeat(CookieTransferStreams.MAX_BYTES);
        output.reset();
        CookieTransferStreams.write(output, maximum);
        check(output.size() == CookieTransferStreams.MAX_BYTES);
        check(CookieTransferStreams.read(new ByteArrayInputStream(output.toByteArray())).equals(maximum));
        fails(() -> CookieTransferStreams.read(new ByteArrayInputStream(new byte[CookieTransferStreams.MAX_BYTES + 1])));
        fails(() -> CookieTransferStreams.write(new ByteArrayOutputStream(), maximum + "x"));
        fails(() -> CookieTransferStreams.write(new ByteArrayOutputStream(), "汉".repeat(166667)));
        fails(() -> CookieTransferStreams.read(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28})));
        fails(() -> CookieTransferStreams.read(new ByteArrayInputStream(new byte[] {(byte) 0xf0, (byte) 0x9f})));
        fails(() -> CookieTransferStreams.write(new ByteArrayOutputStream(), "\ud800"));
        fails(() -> CookieTransferStreams.write(new ByteArrayOutputStream(), "\udc00"));
        fails(() -> CookieTransferStreams.read(new ByteArrayInputStream(new byte[0])));
        fails(() -> CookieTransferStreams.read(null));
        fails(() -> CookieTransferStreams.write(null, "[]"));
        fails(() -> CookieTransferStreams.write(new ByteArrayOutputStream(), ""));
        fails(() -> CookieTransferStreams.write(new ByteArrayOutputStream(), null));
        fails(() -> CookieTransferStreams.read(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("synthetic input failure"); }
        }));
        fails(() -> CookieTransferStreams.write(new OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("synthetic output failure"); }
        }, unicode));
        fails(() -> CookieTransferStreams.write(new ByteArrayOutputStream() {
            @Override public void flush() throws IOException { throw new IOException("synthetic flush failure"); }
        }, unicode));
        // Exercise a real bounded pipe with a payload far larger than its buffer.
        try (PipedInputStream input = new PipedInputStream(4096);
                PipedOutputStream pipe = new PipedOutputStream(input)) {
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try (PipedOutputStream stream = pipe) { CookieTransferStreams.write(stream, maximum); }
                catch (IOException error) { throw new RuntimeException(error); }
            });
            check(CookieTransferStreams.read(input).equals(maximum));
            writer.get(5, TimeUnit.SECONDS);
            check(writer.isDone() && !writer.isCompletedExceptionally());
        }
        System.out.println("{\"suite\":\"CookieTransferStreamsTest\",\"assertions\":" + assertions + ",\"passed\":true}");
    }
    private interface Operation { void run() throws Exception; }
    private static void fails(Operation action) throws Exception {
        try { action.run(); } catch (IOException expected) { assertions++; return; }
        throw new AssertionError("Expected bounded transport failure");
    }
    private static void check(boolean result) { assertions++; if (!result) throw new AssertionError(); }
}
