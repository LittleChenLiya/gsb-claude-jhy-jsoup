package org.jsoup.nodes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 A SourceEditPlan is a validated, immutable set of {@link SourceEdit}s that can be committed against the original
 source of a parsed Document. The plan never re-serializes the DOM; instead it splices each edit's replacement text
 into the original source at the edit's UTF-16 offsets, so unmodified regions (including quote styles, entity usage,
 letter case, and whitespace) are preserved exactly.
 <p>Edits are applied internally in descending offset order (so earlier offsets stay valid), while {@link #edits()}
 reports them in original source (ascending offset) order.</p>
 <p>Before applying, {@link #commit(String)} verifies that the supplied source still matches the SHA-256 digest
 captured when the plan was created, and rejects the commit if the original text has changed.</p>
 @see SourceEditor
 @since 1.24.1
 */
public final class SourceEditPlan {
    private final String source;
    private final String sourceDigest;
    private final List<SourceEdit> edits;        // ascending source offset order (reporting order)
    private final List<SourceEdit> applyOrder;   // descending source offset order (application order)

    SourceEditPlan(String source, String sourceDigest, List<SourceEdit> edits, List<SourceEdit> applyOrder) {
        this.source = source;
        this.sourceDigest = sourceDigest;
        this.edits = edits;
        this.applyOrder = applyOrder;
    }

    /**
     Gets the edits in this plan, in original source (ascending offset) order.
     @return the edits, in source order.
     */
    public List<SourceEdit> edits() {
        return edits;
    }

    /**
     Gets the edits in the order they are applied during {@link #commit()}: descending source offset order, so that
     applying one edit never shifts the offsets of edits yet to be applied.
     @return the edits, in application (reverse offset) order.
     */
    public List<SourceEdit> editsInApplyOrder() {
        return applyOrder;
    }

    /**
     Gets the SHA-256 digest (lowercase hex) of the original source this plan was created against. The digest is
     verified on {@link #commit(String)}.
     @return the source digest.
     */
    public String sourceDigest() {
        return sourceDigest;
    }

    /**
     Applies this plan to the original source captured when the plan was created.
     @return the edited source.
     */
    public String commit() {
        return commit(source);
    }

    /**
     Verifies that {@code originalSource} matches the digest captured when this plan was created, then applies the
     edits (in descending offset order) and returns the edited source. Unmodified regions keep their original
     characters exactly; the DOM is never re-serialized.
     @param originalSource the original source text to edit
     @return the edited source
     @throws SourceEditException if the supplied source does not match the expected digest
     */
    public String commit(String originalSource) {
        String actual = digest(originalSource);
        if (!actual.equals(sourceDigest))
            throw new SourceEditException(
                "The original source has changed since this plan was created (expected sha-256 " + sourceDigest +
                    " but got " + actual + "); re-parse and re-plan the edits.");

        StringBuilder sb = new StringBuilder(originalSource);
        for (SourceEdit edit : applyOrder) {
            sb.replace(edit.startPos(), edit.endPos(), edit.replacement());
        }
        return sb.toString();
    }

    /**
     Computes the SHA-256 digest (lowercase hex) of a source string, over its UTF-8 bytes.
     */
    static String digest(String source) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] hash = md.digest(source.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash)
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }
}
