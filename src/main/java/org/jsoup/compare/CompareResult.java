package org.jsoup.compare;

import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 The result of comparing two subtrees with {@link SubtreeCompare#compare}.
 <p>
 Even when both fingerprints have identical digests, the comparison walks the canonical streams token by token
 before declaring the subtrees equivalent; see the <a href="package-summary.html#verification">package
 specification</a>.
 </p>
 * @since 1.24.1
 */
public final class CompareResult {
    private final boolean equivalent;
    private final Fingerprint expectedFingerprint;
    private final Fingerprint actualFingerprint;
    private final @Nullable Difference firstDifference;

    private CompareResult(boolean equivalent, Fingerprint expectedFingerprint, Fingerprint actualFingerprint,
                          @Nullable Difference firstDifference) {
        this.equivalent = equivalent;
        this.expectedFingerprint = expectedFingerprint;
        this.actualFingerprint = actualFingerprint;
        this.firstDifference = firstDifference;
    }

    static CompareResult equivalent(Fingerprint expected, Fingerprint actual) {
        return new CompareResult(true, expected, actual, null);
    }

    static CompareResult different(Fingerprint expected, Fingerprint actual, Difference firstDifference) {
        Validate.notNull(firstDifference);
        return new CompareResult(false, expected, actual, firstDifference);
    }

    /**
     Returns whether the two subtrees are equivalent under the configuration.
     @return true if equivalent
     */
    public boolean isEquivalent() {
        return equivalent;
    }

    /**
     Returns the fingerprint of the expected (first) subtree.
     @return the expected fingerprint
     */
    public Fingerprint expectedFingerprint() {
        return expectedFingerprint;
    }

    /**
     Returns the fingerprint of the actual (second) subtree.
     @return the actual fingerprint
     */
    public Fingerprint actualFingerprint() {
        return actualFingerprint;
    }

    /**
     Returns the first difference found, if any.
     @return the first difference, or empty if equivalent
     */
    public Optional<Difference> firstDifference() {
        return Optional.ofNullable(firstDifference);
    }

    @Override
    public String toString() {
        if (equivalent) return "CompareResult: equivalent";
        return "CompareResult: different - " + firstDifference;
    }
}
