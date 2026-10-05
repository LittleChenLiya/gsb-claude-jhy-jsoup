package org.jsoup.compare;

import org.jsoup.helper.Validate;

/**
 A versioned canonical fingerprint of a DOM subtree, in the form {@code jsoup-subtree-v<version>:<hex>}.

 <p>The version identifies the rule set (canonicalization + digest format) the fingerprint was generated under;
 two fingerprints are only comparable when their versions match. A matching fingerprint is a hint, not a verdict:
 {@link SubtreeComparer} always confirms equivalence by walking the canonical token stream.</p>
 */
public final class Fingerprint {
    /** The algorithm identifier prefixing every fingerprint string. */
    public static final String AlgorithmId = "jsoup-subtree";

    private final int version;
    private final String hex;

    Fingerprint(int version, String hex) {
        this.version = version;
        this.hex = hex;
    }

    /** @return the fingerprint version of the rule set that produced this fingerprint. */
    public int version() {
        return version;
    }

    /** @return the lowercase hex digest of the canonical token stream. */
    public String hex() {
        return hex;
    }

    /** @return the canonical string form {@code jsoup-subtree-v<version>:<hex>}. */
    @Override public String toString() {
        return AlgorithmId + "-v" + version + ":" + hex;
    }

    /** Parse a fingerprint produced by {@link #toString()}.
     @throws IllegalArgumentException if the string is not a well-formed fingerprint. */
    public static Fingerprint parse(String s) {
        Validate.notNull(s);
        String prefix = AlgorithmId + "-v";
        if (!s.startsWith(prefix))
            throw new IllegalArgumentException("Not a " + AlgorithmId + " fingerprint: " + s);
        int colon = s.indexOf(':', prefix.length());
        if (colon <= prefix.length() || colon >= s.length() - 1)
            throw new IllegalArgumentException("Malformed fingerprint: " + s);
        int version;
        try {
            version = Integer.parseInt(s.substring(prefix.length(), colon));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed fingerprint version: " + s, e);
        }
        if (version < 1)
            throw new IllegalArgumentException("Fingerprint version must be >= 1: " + s);
        return new Fingerprint(version, s.substring(colon + 1));
    }

    /** Fingerprints are equal only when both the version and the digest match. */
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Fingerprint)) return false;
        Fingerprint other = (Fingerprint) o;
        return version == other.version && hex.equals(other.hex);
    }

    @Override public int hashCode() {
        return 31 * version + hex.hashCode();
    }
}
