package org.jsoup.compare;

import org.jsoup.helper.Validate;
import org.jsoup.internal.Normalizer;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 Configuration for subtree comparison: which attributes to ignore, whether to ignore ordinary whitespace, which
 {@link DigestFunction} to use, and the fingerprint version the rules bind to.

 <h2>Fingerprint version binding</h2>
 The canonicalization rules are versioned. The current version is {@link #FINGERPRINT_VERSION}; it is embedded in
 every {@link Fingerprint} and mixed into the digest input, so fingerprints produced under different rule versions
 can never compare as equal. When the canonicalization rules change, this version must be incremented.
 * @since 1.24.1
 */
public final class CompareConfig {
    /** The version of the canonicalization rules implemented by this release. */
    public static final int FINGERPRINT_VERSION = 1;

    private final Set<String> ignoredAttributes;
    private final boolean ignoreWhitespace;
    private final DigestFunction digester;

    private CompareConfig(Builder builder) {
        Set<String> attrs = new LinkedHashSet<>();
        for (String attr : builder.ignoredAttributes) {
            if (attr == null) continue;
            attrs.add(Normalizer.asciiLowerCase(attr));
        }
        this.ignoredAttributes = Collections.unmodifiableSet(attrs);
        this.ignoreWhitespace = builder.ignoreWhitespace;
        this.digester = builder.digester;
    }

    /**
     Returns a new builder with the default settings: no ignored attributes, ordinary whitespace ignored, and
     SHA-256 digests.
     @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     Returns the default configuration: no ignored attributes, ordinary whitespace ignored, and SHA-256 digests.
     @return the default configuration
     */
    public static CompareConfig defaults() {
        return builder().build();
    }

    /**
     Returns a builder initialized with this configuration's values.
     @return a new builder
     */
    public Builder toBuilder() {
        return new Builder()
            .ignoreAttributes(ignoredAttributes)
            .ignoreWhitespace(ignoreWhitespace)
            .digester(digester);
    }

    /**
     Tests whether the supplied attribute name is ignored by this configuration. Attribute name comparison is
     case-insensitive (ASCII).
     @param key the attribute name
     @return true if ignored
     */
    public boolean isIgnoredAttribute(String key) {
        return key != null && ignoredAttributes.contains(Normalizer.asciiLowerCase(key));
    }

    /**
     Returns the unmodifiable set of ignored attribute names, lower-cased.
     @return ignored attribute names
     */
    public Set<String> ignoredAttributes() {
        return ignoredAttributes;
    }

    /**
     Returns whether ordinary (non-{@code pre}) whitespace is ignored.
     @return true if whitespace is ignored
     */
    public boolean ignoreWhitespace() {
        return ignoreWhitespace;
    }

    /**
     Returns the digest function used for fingerprints.
     @return the digest function
     */
    public DigestFunction digester() {
        return digester;
    }

    /**
     Returns the fingerprint version the rules bind to.
     @return the fingerprint version
     */
    public int fingerprintVersion() {
        return FINGERPRINT_VERSION;
    }

    /** A builder for {@link CompareConfig}. */
    public static final class Builder {
        private final Set<String> ignoredAttributes = new LinkedHashSet<>();
        private boolean ignoreWhitespace = true;
        private DigestFunction digester = DigestFunction.sha256();

        private Builder() {}

        /**
         Adds attribute names to ignore when comparing. Attribute name comparison is case-insensitive (ASCII).
         @param attributes attribute names to ignore
         @return this builder
         */
        public Builder ignoreAttributes(String... attributes) {
            Validate.notNull(attributes);
            return ignoreAttributes(Arrays.asList(attributes));
        }

        /**
         Adds attribute names to ignore when comparing. Attribute name comparison is case-insensitive (ASCII).
         @param attributes attribute names to ignore
         @return this builder
         */
        public Builder ignoreAttributes(Collection<String> attributes) {
            Validate.notNull(attributes);
            for (String attr : attributes) {
                if (attr != null) ignoredAttributes.add(attr);
            }
            return this;
        }

        /**
         Sets whether ordinary whitespace is ignored. When true (the default), whitespace runs in text nodes
         outside {@code pre} (and preformatted) elements are collapsed, leading and trailing whitespace is trimmed,
         and whitespace-only text nodes are skipped. Whitespace inside {@code pre} elements is always preserved.
         @param ignoreWhitespace true to ignore ordinary whitespace
         @return this builder
         */
        public Builder ignoreWhitespace(boolean ignoreWhitespace) {
            this.ignoreWhitespace = ignoreWhitespace;
            return this;
        }

        /**
         Sets the digest function used for fingerprints. Defaults to SHA-256.
         @param digester the digest function
         @return this builder
         */
        public Builder digester(DigestFunction digester) {
            Validate.notNull(digester);
            this.digester = digester;
            return this;
        }

        /**
         Builds the configuration.
         @return a new {@link CompareConfig}
         */
        public CompareConfig build() {
            return new CompareConfig(this);
        }
    }
}
