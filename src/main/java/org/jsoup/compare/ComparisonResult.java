package org.jsoup.compare;

import org.jspecify.annotations.Nullable;

/**
 The outcome of a {@link SubtreeComparer} comparison. Equality is always decided by walking the canonical token
 streams of both subtrees; the fingerprints are reported alongside as an informational digest and are never
 trusted on their own.
 */
public final class ComparisonResult {
    private final boolean equal;
    @Nullable private final String firstDifferencePath;
    private final String differenceSummary;
    private final Fingerprint fingerprintA;
    private final Fingerprint fingerprintB;

    ComparisonResult(boolean equal, @Nullable String firstDifferencePath, String differenceSummary,
                     Fingerprint fingerprintA, Fingerprint fingerprintB) {
        this.equal = equal;
        this.firstDifferencePath = firstDifferencePath;
        this.differenceSummary = differenceSummary;
        this.fingerprintA = fingerprintA;
        this.fingerprintB = fingerprintB;
    }

    /** @return true if the two subtrees are equivalent under the comparison rules (verified along the canonical
     token stream, not merely by digest). */
    public boolean equal() {
        return equal;
    }

    /** @return the structural path of the first differing node (e.g. {@code /html[1]/body[1]/div[1]/p[2]}), or
     null when the subtrees are equal. */
    public @Nullable String firstDifferencePath() {
        return firstDifferencePath;
    }

    /** @return a human-readable description of the first difference, or an empty string when equal. */
    public String differenceSummary() {
        return differenceSummary;
    }

    /** @return the fingerprint of the first subtree. */
    public Fingerprint fingerprintA() {
        return fingerprintA;
    }

    /** @return the fingerprint of the second subtree. */
    public Fingerprint fingerprintB() {
        return fingerprintB;
    }

    /** @return true if the two fingerprints match. Informational only: a match does not by itself establish
     equivalence (a colliding or constant hasher will match on different content), and {@link #equal()} is always
     decided by canonical-stream verification. */
    public boolean fingerprintsMatch() {
        return fingerprintA.equals(fingerprintB);
    }

    @Override public String toString() {
        return equal ?
            "equal(" + fingerprintA + ")" :
            "different at " + firstDifferencePath + ": " + differenceSummary;
    }
}
