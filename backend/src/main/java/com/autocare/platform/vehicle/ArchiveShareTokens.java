package com.autocare.platform.vehicle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Separate domain from authentication. Raw capabilities never enter database receipts. */
public final class ArchiveShareTokens {
    private final byte[] secret;
    public ArchiveShareTokens(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 bytes");
    }
    public String derive(String publicId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("autocare:archive-share:v1:" + publicId).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Share cryptography unavailable", e); }
    }
    public static boolean valid(String token) { return token != null && token.matches("[a-f0-9]{64}"); }
    public static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public boolean matches(String publicId, String storedHash) {
        return MessageDigest.isEqual(hash(derive(publicId)).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
