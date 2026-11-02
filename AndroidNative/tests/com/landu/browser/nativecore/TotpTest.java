package com.landu.browser.nativecore;

/** Public RFC 6238 Appendix B and RFC 4226 Appendix D interoperability vectors. */
public final class TotpTest {
    private static int assertions;
    private static final String SHA1_KEY = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    public static void main(String[] args) {
        String[] algorithms = {"SHA1", "SHA256", "SHA512"};
        String[] keys = {SHA1_KEY, "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA",
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA"};
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[][] codes = {{"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"},
                {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"},
                {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"}};
        for (int algorithm = 0; algorithm < algorithms.length; algorithm++) {
            Totp.Parameters parameters = Totp.parse("otpauth://totp/RFC:fixture?secret=" + keys[algorithm]
                    + "&algorithm=" + algorithms[algorithm] + "&digits=8&period=30");
            for (int time = 0; time < times.length; time++) {
                check(parameters.atUnixSeconds(times[time]).equals(codes[algorithm][time]), "RFC 6238 vector mismatch");
            }
        }
        Totp.Parameters defaults = Totp.parse(SHA1_KEY);
        check(defaults.digits == 6 && defaults.periodSeconds == 30 && defaults.algorithm.equals("SHA1"), "Raw Base32 defaults");
        String[] hotp = {"755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"};
        for (int counter = 0; counter < hotp.length; counter++) {
            check(defaults.atUnixSeconds(counter * 30L).equals(hotp[counter]), "RFC 4226 moving-counter vector mismatch");
        }
        check(defaults.secondsRemaining(0) == 30 && defaults.secondsRemaining(29) == 1
                && defaults.secondsRemaining(30) == 30, "Countdown boundary is exact");
        check(defaults.atUnixSeconds(29).equals(defaults.atUnixSeconds(0))
                && !defaults.atUnixSeconds(30).equals(defaults.atUnixSeconds(29)), "Code changes only at the period boundary");
        check(Totp.parse(SHA1_KEY.toLowerCase(java.util.Locale.ROOT)).atUnixSeconds(59).equals("287082"), "Lower-case key accepted");
        check(Totp.parse("GEZD-GNBV GY3TQOJQ GEZDGNBVGY3TQOJQ").atUnixSeconds(59).equals("287082"), "Grouped Base32 accepted");
        check(Totp.parse("otpauth://totp/Example%20Co:alice%40example.test?secret=" + keys[1]
                + "%3D%3D%3D%3D&issuer=Example%20Co&algorithm=SHA256&digits=8&period=30").atUnixSeconds(59).equals("46119246"), "Encoded padding and label accepted");
        Totp.Parameters custom = Totp.parse("otpauth://totp/x?secret=" + SHA1_KEY + "&period=60");
        check(custom.atUnixSeconds(60).equals(defaults.atUnixSeconds(30)) && custom.secondsRemaining(59) == 1, "Custom period is honored");
        for (String invalid : new String[] {"", "ABC", "0123456789012345", SHA1_KEY + "A", "ß".repeat(16),
                SHA1_KEY + "=", keys[1].substring(0, keys[1].length() - 1) + "B", "A".repeat(221),
                "otpauth://hotp/x?secret=" + SHA1_KEY, "otpauth://totp/x?issuer=only", "otpauth://totp/?secret=" + SHA1_KEY,
                "otpauth://u@totp/x?secret=" + SHA1_KEY, "otpauth://totp:80/x?secret=" + SHA1_KEY,
                "otpauth://totp/x?secret=" + SHA1_KEY + "#fragment", "otpauth://totp/x?secret=%ZZ",
                "otpauth://totp/x?secret=" + SHA1_KEY + "&secret=" + SHA1_KEY,
                "otpauth://totp/x?secret=" + SHA1_KEY + "&digits=7", "otpauth://totp/x?secret=" + SHA1_KEY + "&period=14",
                "otpauth://totp/x?secret=" + SHA1_KEY + "&period=301", "otpauth://totp/x?secret=" + SHA1_KEY + "&period=0",
                "otpauth://totp/x?secret=" + SHA1_KEY + "&algorithm=MD5", "otpauth://totp/x?secret=" + SHA1_KEY + "&counter=0"}) {
            rejects(() -> Totp.parse(invalid));
        }
        rejects(() -> Totp.parse(null));
        rejects(() -> defaults.atUnixSeconds(-1));
        rejects(() -> defaults.secondsRemaining(-1));
        check(!defaults.toString().contains(SHA1_KEY), "Debug output is redacted");
        System.out.println("{\"suite\":\"totp-public-rfc-vectors\",\"assertions\":" + assertions
                + ",\"rfc6238Vectors\":18,\"rfc4226Vectors\":10,\"passed\":true,\"androidDeviceTested\":false}");
    }
    private static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private static void rejects(Action action) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) {
            check(!expected.getMessage().contains(SHA1_KEY), "Errors never include secret input");
            return;
        }
        throw new AssertionError("Expected invalid account token input rejection");
    }
    private interface Action { void run(); }
}
