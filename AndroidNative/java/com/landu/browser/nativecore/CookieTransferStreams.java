package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded UTF-8 pipe/document transport; Cookie contents never enter a Bundle. */
public final class CookieTransferStreams {
    public static final int MAX_BYTES = 500000;
    private CookieTransferStreams() {}

    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法读取 Cookie 文件");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (count == 0) continue;
            if (bytes.size() > MAX_BYTES - count) throw new IOException("Cookie 文件不能超过 500000 字节");
            bytes.write(buffer, 0, count);
        }
        if (bytes.size() == 0) throw new IOException("Cookie 文件为空或传输中断");
        try {
            String result = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return result.startsWith("\ufeff") ? result.substring(1) : result;
        } catch (CharacterCodingException failure) {
            throw new IOException("Cookie 文件必须使用 UTF-8 编码");
        }
    }

    public static void write(OutputStream output, String json) throws IOException {
        if (output == null || json == null || json.isEmpty()) throw new IOException("Cookie 结果为空");
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(json));
            if (encoded.remaining() > MAX_BYTES) throw new IOException("Cookie 结果超过 500000 字节");
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException failure) {
            throw new IOException("Cookie 结果编码无效");
        }
        try { output.write(bytes); output.flush(); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
}
