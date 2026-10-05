package org.jsoup.compare;

import org.jsoup.helper.Validate;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 The rule set governing a subtree comparison: which attributes are ignored, whether ordinary (non-{@code pre})
 whitespace is collapsed, which fingerprint version the rules are bound to, and which {@link Hasher} backs the
 fingerprint.

 <p>Every rule set is bound to a <b>fingerprint version</b> (default {@link #CURRENT_FINGERPRINT_VERSION}). The
 version is embedded into each {@link Fingerprint} it produces, and fingerprints of different versions never
 compare equal &mdash; a digest is only meaningful under the exact rules that generated it.</p>

 <p>Instances are immutable; use {@link #builder()} to configure.</p>
 */
public final class CompareConfig {
    /** The fingerprint version produced by this jsoup release. */
    public static final int CURRENT_FINGERPRINT_VERSION = 1;

    private static final CompareConfig Defaults = new Builder().build();

    private final Set<String> ignoredAttributes;
    private final boolean collapseWhitespace;
    private final int fingerprintVersion;
    private final Supplier<Hasher> hasherFactory;

    private CompareConfig(Builder b) {
        this.ignoredAttributes = Collections.unmodifiableSet(new LinkedHashSet<>(b.ignoredAttributes));
        this.collapseWhitespace = b.collapseWhitespace;
        this.fingerprintVersion = b.fingerprintVersion;
        this.hasherFactory = b.hasherFactory;
    }

    /** @return the default rule set: no ignored attributes, ordinary whitespace collapsed, current version, SHA-256. */
    public static CompareConfig defaults() {
        return Defaults;
    }

    /** @return a new builder pre-set with the defaults, ready to customize. */
    public static Builder builder() {
        return new Builder();
    }

    /** @return attribute names (lower-cased) excluded from comparison and fingerprinting. */
    public Set<String> ignoredAttributes() {
        return ignoredAttributes;
    }

    /** @return true if ordinary whitespace (outside whitespace-preserving elements such as {@code pre}) is collapsed. */
    public boolean collapseWhitespace() {
        return collapseWhitespace;
    }

    /** @return the fingerprint version this rule set is bound to. */
    public int fingerprintVersion() {
        return fingerprintVersion;
    }

    /** @return a factory for the hasher backing each fingerprint. */
    public Supplier<Hasher> hasherFactory() {
        return hasherFactory;
    }

    /** Builder for {@link CompareConfig}. */
    public static final class Builder {
        private final Set<String> ignoredAttributes = new LinkedHashSet<>();
        private boolean collapseWhitespace = true;
        private int fingerprintVersion = CURRENT_FINGERPRINT_VERSION;
        private Supplier<Hasher> hasherFactory = Sha256Hasher::new;

        private Builder() {}

        /** Ignore the named attributes (case-insensitive) wherever they appear in the compared subtrees. */
        public Builder ignoreAttributes(String... names) {
            Validate.notNull(names);
            for (String name : names) {
                Validate.notEmpty(name, "Attribute name must not be empty");
                ignoredAttributes.add(name.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /** Set whether ordinary whitespace is collapsed (default true). Whitespace inside {@code pre} and other
         whitespace-preserving elements is always kept verbatim, regardless of this setting. */
        public Builder collapseWhitespace(boolean collapse) {
            this.collapseWhitespace = collapse;
            return this;
        }

        /** Bind this rule set to a fingerprint version. Fingerprints of different versions never compare equal. */
        public Builder fingerprintVersion(int version) {
            Validate.isTrue(version >= 1, "Fingerprint version must be >= 1");
            this.fingerprintVersion = version;
            return this;
        }

        /** Supply a custom hasher. The comparer still verifies equivalence along the canonical token stream, so a
         weak or constant hasher cannot produce false positives. */
        public Builder hasherFactory(Supplier<Hasher> factory) {
            Validate.notNull(factory);
            this.hasherFactory = factory;
            return this;
        }

        public CompareConfig build() {
            return new CompareConfig(this);
        }
    }
}
