package com.example.payments.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * HMAC-SHA256 over {@code timestamp.body}. The timestamp is part of the signed payload, so a
 * captured signature cannot be replayed once it falls outside the allowed clock window.
 */
@Component
public class CallbackSigner {

    public static final String TIMESTAMP_HEADER = "X-Provider-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Provider-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = ".";
    private static final HexFormat HEX = HexFormat.of();

    private final CallbackAuthProperties properties;
    private final byte[] secret;

    public CallbackSigner(CallbackAuthProperties properties) {
        if (properties.isEnabled() && !StringUtils.hasText(properties.getSecret())) {
            throw new IllegalStateException(
                    "app.callback.auth.secret must be set when callback authentication is enabled");
        }
        if (properties.getMaxAge().isZero() || properties.getMaxAge().isNegative()) {
            throw new IllegalStateException("app.callback.auth.max-age must be positive");
        }
        this.properties = properties;
        this.secret = properties.getSecret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** Lowercase hex HMAC-SHA256 of {@code timestampSeconds + "." + body}. */
    public String sign(long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] digest = mac.doFinal(
                    (timestampSeconds + SEPARATOR + body).getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /**
     * Checks the timestamp window first, then compares signatures in constant time. Returns
     * false for anything malformed rather than throwing, so callers decide the HTTP response.
     */
    public boolean verify(String timestampHeader, String providedSignature, String body, Instant now) {
        if (!isEnabled() || timestampHeader == null || providedSignature == null) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }

        long maxAgeSeconds = properties.getMaxAge().toSeconds();
        long nowSeconds = now.getEpochSecond();
        if (timestamp > nowSeconds + maxAgeSeconds || timestamp < nowSeconds - maxAgeSeconds) {
            return false;
        }

        byte[] expected = sign(timestamp, body).getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedSignature.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }
}
