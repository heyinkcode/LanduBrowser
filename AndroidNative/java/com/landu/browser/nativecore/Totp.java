package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 client-side generation only. Never contacts an account or validation server. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Totp() {}

    public static Parameters parse(String input) {
        if (input == null || input.length() > 2048) throw invalid();
        String value = input.trim();
        String secret = value;
        String algorithm = "SHA1";
        int digits = 6;
        int period = 30;
        if (value.regionMatches(true, 0, "otpauth://", 0, 10)) {
            try {
                URI uri = new URI(value);
                if (!"totp".equalsIgnoreCase(uri.getHost()) || uri.getRawUserInfo() != null
                        || uri.getPort() != -1 || uri.getFragment() != null || uri.getPath() == null
                        || uri.getPath().length() < 2 || uri.getRawQuery() == null) throw invalid();
                Map<String, String> options = new HashMap<>();
                for (String item : uri.getRawQuery().split("&", -1)) {
                    int equals = item.indexOf('=');
                    if (equals < 1) throw invalid();
                    String name = URLDecoder.decode(item.substring(0, equals), StandardCharsets.UTF_8.name());
                    String content = URLDecoder.decode(item.substring(equals + 1), StandardCharsets.UTF_8.name());
                    if (!(name.equals("secret") || name.equals("issuer") || name.equals("algorithm")
                            || name.equals("digits") || name.equals("period")) || options.put(name, content) != null) throw invalid();
                }
                secret = options.get("secret");
                algorithm = options.getOrDefault("algorithm", "SHA1").toUpperCase(Locale.ROOT);
                String digitOption = options.getOrDefault("digits", "6");
                String periodOption = options.getOrDefault("period", "30");
                if (!digitOption.matches("6|8") || !periodOption.matches("[1-9][0-9]{0,2}")) throw invalid();
                digits = Integer.parseInt(digitOption);
                period = Integer.parseInt(periodOption);
            } catch (URISyntaxException | java.io.UnsupportedEncodingException | IllegalArgumentException error) {
                throw invalid();
            }
        }
        if (!(algorithm.equals("SHA1") || algorithm.equals("SHA256") || algorithm.equals("SHA512"))
                || period < 15 || period > 300) throw invalid();
        return new Parameters(decode(secret), algorithm, digits, period);
    }

    private static byte[] decode(String value) {
        if (value == null || value.length() > 2048 || !value.matches("[A-Za-z2-7= \\t\\r\\n-]+")) throw invalid();
        String text = value.replaceAll("[ \\t\\r\\n-]", "").toUpperCase(Locale.ROOT);
        int padding = text.indexOf('=');
        if (padding >= 0) {
            if (text.length() % 8 != 0 || !text.substring(padding).matches("=+")
                    || text.length() - padding > 6) throw invalid();
            text = text.substring(0, padding);
        }
        int residue = text.length() % 8;
        if (residue == 1 || residue == 3 || residue == 6 || text.length() < 16 || text.length() > 200) throw invalid();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) throw invalid();
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((buffer >> bits) & 255);
                buffer &= (1 << bits) - 1;
            }
        }
        if (buffer != 0 || bytes.size() < 10) throw invalid();
        return bytes.toByteArray();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("2FA 密钥无效：请填写 Base32 密钥或标准 otpauth://totp/ 链接");
    }

    public static final class Parameters {
        private final byte[] key;
        public final String algorithm;
        public final int digits;
        public final int periodSeconds;
        private Parameters(byte[] key, String algorithm, int digits, int periodSeconds) {
            this.key = key;
            this.algorithm = algorithm;
            this.digits = digits;
            this.periodSeconds = periodSeconds;
        }

        public String atUnixSeconds(long seconds) {
            if (seconds < 0) throw new IllegalArgumentException("手机时间无效，请检查系统时间");
            byte[] hash = null;
            try {
                Mac mac = Mac.getInstance("Hmac" + algorithm);
                mac.init(new SecretKeySpec(key, "Hmac" + algorithm));
                hash = mac.doFinal(ByteBuffer.allocate(8).putLong(seconds / periodSeconds).array());
                int offset = hash[hash.length - 1] & 15;
                int number = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                        | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
                int modulus = digits == 8 ? 100000000 : 1000000;
                return String.format(Locale.ROOT, "%0" + digits + "d", number % modulus);
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("此设备暂时无法生成验证码", error);
            } finally {
                if (hash != null) Arrays.fill(hash, (byte) 0);
            }
        }

        public int secondsRemaining(long seconds) {
            if (seconds < 0) throw new IllegalArgumentException("手机时间无效，请检查系统时间");
            return periodSeconds - (int) (seconds % periodSeconds);
        }

        @Override public String toString() { return "Totp.Parameters[redacted]"; }
    }
}
