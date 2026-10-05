package org.jsoup.compare;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Node;

import java.util.List;

/**
 Explicit subtree comparison for jsoup DOM trees.

 <p>Two subtrees are compared by reducing each to a <b>canonical token stream</b> under a {@link CompareConfig}
 rule set:</p>
 <ul>
 <li>attributes named in the rule set are ignored; remaining attributes are sorted, so attribute order never
 affects equivalence;</li>
 <li>ordinary whitespace (outside {@code pre} and other whitespace-preserving elements) is collapsed, and
 whitespace-only text nodes vanish; whitespace inside {@code pre} is kept verbatim and never collapsed;</li>
 <li>text content and node types (element, text, cdata, comment, data, doctype, ...) remain distinct.</li>
 </ul>

 <p>A versioned {@link Fingerprint} (a digest of the canonical stream, bound to the fingerprint version of the
 rule set) is computed for each side. A digest match is treated only as a hint: the comparer then walks both
 canonical streams token by token to verify actual equivalence, so an injected weak or constant hasher can never
 produce a false positive. On a mismatch, the structural path of the first differing node is reported.</p>

 <p>All methods are strictly read-only; the compared DOM is never modified.</p>
 */
public final class SubtreeComparer {
    private SubtreeComparer() {}

    /** Compare two subtrees under the default rules ({@link CompareConfig#defaults()}). */
    public static ComparisonResult compare(Node a, Node b) {
        return compare(a, b, CompareConfig.defaults());
    }

    /** Compare two subtrees under the given rule set. The DOMs are not modified. */
    public static ComparisonResult compare(Node a, Node b, CompareConfig config) {
        Validate.notNull(a);
        Validate.notNull(b);
        Validate.notNull(config);

        List<CanonicalForm.Token> tokensA = CanonicalForm.tokens(a, config);
        List<CanonicalForm.Token> tokensB = CanonicalForm.tokens(b, config);
        Fingerprint fpA = fingerprint(tokensA, config);
        Fingerprint fpB = fingerprint(tokensB, config);

        // Never trust the digest: verify actual equivalence along the canonical token stream.
        int shared = Math.min(tokensA.size(), tokensB.size());
        int i = 0;
        while (i < shared && tokensA.get(i).canonical.equals(tokensB.get(i).canonical))
            i++;

        if (i == shared && tokensA.size() == tokensB.size())
            return new ComparisonResult(true, null, "", fpA, fpB);

        String path;
        String summary;
        if (i < shared) {
            CanonicalForm.Token ta = tokensA.get(i);
            CanonicalForm.Token tb = tokensB.get(i);
            path = ta.path;
            summary = "canonical token differs: A=" + abbrev(ta.canonical) + " B=" + abbrev(tb.canonical);
            if (!ta.path.equals(tb.path))
                summary += " (path in B: " + tb.path + ")";
        } else {
            boolean aLonger = tokensA.size() > tokensB.size();
            CanonicalForm.Token extra = (aLonger ? tokensA : tokensB).get(i);
            path = extra.path;
            summary = (aLonger ? "A" : "B") + " has extra node: " + abbrev(extra.canonical);
        }
        return new ComparisonResult(false, path, summary, fpA, fpB);
    }

    /** Compute the versioned canonical fingerprint of a subtree under the default rules. */
    public static Fingerprint fingerprint(Node node) {
        return fingerprint(node, CompareConfig.defaults());
    }

    /** Compute the versioned canonical fingerprint of a subtree under the given rule set. The DOM is not modified. */
    public static Fingerprint fingerprint(Node node, CompareConfig config) {
        Validate.notNull(node);
        Validate.notNull(config);
        return fingerprint(CanonicalForm.tokens(node, config), config);
    }

    /** @return the canonical string form of a subtree under the given rule set; useful for debugging and tests. */
    public static String canonicalForm(Node node, CompareConfig config) {
        Validate.notNull(node);
        Validate.notNull(config);
        return CanonicalForm.canonicalString(CanonicalForm.tokens(node, config));
    }

    private static Fingerprint fingerprint(List<CanonicalForm.Token> tokens, CompareConfig config) {
        Hasher hasher = config.hasherFactory().get();
        Validate.notNull(hasher, "Hasher factory must not return null");
        hasher.update(CanonicalForm.canonicalString(tokens));
        return new Fingerprint(config.fingerprintVersion(), hasher.digestHex());
    }

    private static String abbrev(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
