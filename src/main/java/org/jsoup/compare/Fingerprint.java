package org.jsoup.compare;

import org.jsoup.helper.Validate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 A canonical fingerprint of a subtree: the rule version the fingerprint binds to, the digest algorithm, the digest
 bytes, and the canonical stream of tokens the digest was computed over.
 <p>
 The canonical stream is a depth-first sequence of typed tokens:
 </p>
 <ul>
 <li>{@code E} element start, followed by the element's normal name and its sorted, filtered attributes;</li>
 <li>{@code /E} element end;</li>
 <li>{@code T} text node (whitespace normalized unless inside {@code pre});</li>
 <li>{@code D} data node (e.g. {@code script}, {@code style} contents);</li>
 <li>{@code A} CDATA section;</li>
 <li>{@code C} comment;</li>
 <li>{@code X} XML/markup declaration;</li>
 <li>{@code P} processing instruction;</li>
 <li>{@code !} document type.</li>
 </ul>
 <p>
 Two subtrees with the same fingerprint version and digest are <em>candidates</em> for equivalence;
 {@link SubtreeCompare} always verifies equivalence by walking both canonical streams token by token.
 </p>
 * @since 1.24.1
 */
public final class Fingerprint {
    static final byte ELEMENT = 'E';
    static final byte ELEMENT_END = '/';
    static final byte TEXT = 'T';
    static final byte DATA = 'D';
    static final byte CDATA = 'A';
    static final byte COMMENT = 'C';
    static final byte DECLARATION = 'X';
    static final byte PROCESSING_INSTRUCTION = 'P';
    static final byte DOCUMENT_TYPE = '!';

    static final byte[] MAGIC = "jsoup-subtree-fingerprint".getBytes(StandardCharsets.UTF_8);

    private final int version;
    private final String algorithm;
    private final byte[] digest;
    private final List<Token> tokens;

    Fingerprint(int version, String algorithm, byte[] digest, List<Token> tokens) {
        Validate.notNull(algorithm);
        Validate.notNull(digest);
        Validate.notNull(tokens);
        this.version = version;
        this.algorithm = algorithm;
        this.digest = digest.clone();
        this.tokens = new ArrayList<>(tokens);
    }

    /**
     Returns the version of the canonicalization rules this fingerprint binds to.
     @return the fingerprint version
     */
    public int version() {
        return version;
    }

    /**
     Returns the name of the digest algorithm used.
     @return the algorithm name
     */
    public String algorithm() {
        return algorithm;
    }

    /**
     Returns a copy of the digest bytes.
     @return the digest bytes
     */
    public byte[] digest() {
        return digest.clone();
    }

    /**
     Returns the digest as a lower-case hex string.
     @return the digest in hex
     */
    public String digestHex() {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     Returns the number of tokens in the canonical stream.
     @return the token count
     */
    public int tokenCount() {
        return tokens.size();
    }

    /**
     Returns a human-readable rendering of the canonical stream, for debugging.
     @return the canonical stream as a string
     */
    public String canonicalStream() {
        StringBuilder sb = new StringBuilder();
        for (Token token : tokens) {
            sb.append(token).append('\n');
        }
        return sb.toString();
    }

    List<Token> tokens() {
        return tokens;
    }

    @Override
    public String toString() {
        return "Fingerprint[v" + version + ", " + algorithm + ", " + digestHex() + ", " + tokens.size() + " tokens]";
    }

    /** A single canonical-stream token: a type byte, structured fields, and the node path. */
    static final class Token {
        final byte type;
        final List<String> fields;
        final NodePath path;

        Token(byte type, List<String> fields, NodePath path) {
            this.type = type;
            this.fields = fields;
            this.path = path;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append((char) type);
            for (String field : fields) {
                sb.append(' ').append(escape(field));
            }
            sb.append("  @").append(path);
            return sb.toString();
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("\n", "\n").replace("\r", "\r").replace("\t", "\t");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Fingerprint)) return false;
        Fingerprint that = (Fingerprint) o;
        return version == that.version
            && algorithm.equals(that.algorithm)
            && Arrays.equals(digest, that.digest);
    }

    @Override
    public int hashCode() {
        int result = version;
        result = 31 * result + algorithm.hashCode();
        result = 31 * result + Arrays.hashCode(digest);
        return result;
    }
}
