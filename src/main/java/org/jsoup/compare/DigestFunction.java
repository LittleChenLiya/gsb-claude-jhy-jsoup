package org.jsoup.compare;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 A pluggable digest function used by {@link SubtreeCompare} to compute subtree fingerprints.
 <p>
 The digest is computed over the <em>canonical stream</em> of a subtree (see {@link Fingerprint} and the
 <a href="package-summary.html#canonical-stream">package specification</a>). Because fingerprints are only a
 fast-path hint, {@link SubtreeCompare} always verifies a digest match by walking both canonical streams token
 by token; an injected constant (or otherwise broken) digest therefore cannot make a genuinely different subtree
 compare as equivalent.
 </p>
 * @since 1.24.1
 */
@FunctionalInterface
public interface DigestFunction {
    /**
     Computes a digest of the supplied canonical-stream bytes.
     @param input the canonical stream bytes
     @return the digest bytes
     */
    byte[] digest(byte[] input);

    /**
     Returns the name of the digest algorithm, for reporting. Custom implementations should override this.
     @return the algorithm name; defaults to {@code "custom"}
     */
    default String algorithm() {
        return "custom";
    }

    /**
     Returns the default digest function: SHA-256.
     @return a SHA-256 digest function
     */
    static DigestFunction sha256() {
        return new DigestFunction() {
            @Override public byte[] digest(byte[] input) {
                try {
                    return MessageDigest.getInstance("SHA-256").digest(input);
                } catch (NoSuchAlgorithmException e) {
                    // SHA-256 is guaranteed to exist on every Java platform
                    throw new IllegalStateException("SHA-256 MessageDigest is not available", e);
                }
            }

            @Override public String algorithm() {
                return "SHA-256";
            }
        };
    }
}
