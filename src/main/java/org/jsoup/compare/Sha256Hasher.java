package org.jsoup.compare;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The default {@link Hasher}: SHA-256 over the UTF-8 bytes of the canonical stream. */
public final class Sha256Hasher implements Hasher {
    private final MessageDigest md;

    public Sha256Hasher() {
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override public void update(String data) {
        md.update(data.getBytes(StandardCharsets.UTF_8));
    }

    @Override public String digestHex() {
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest)
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
