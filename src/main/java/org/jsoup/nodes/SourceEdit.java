package org.jsoup.nodes;

/**
 A SourceEdit describes a single minimal, source-offset based edit to the original input source of a parsed Document.
 Edits are created by a {@link SourceEditor} and applied by a {@link SourceEditPlan}, which splices the replacement
 text into the original source at the edit's offsets, leaving all unmodified regions byte-for-byte (character-for-character)
 identical to the original input.
 <p>Offsets are 0-based UTF-16 character positions into the original source, matching Java {@link String} indexing
 (a supplementary Unicode character such as an emoji occupies two positions).</p>
 @see SourceEditor
 @see SourceEditPlan
 @since 1.24.1
 */
public final class SourceEdit {
    /**
     The kind of source edit.
     */
    public enum Kind {
        /** Replaces the source text of a {@link TextNode}. */
        ReplaceText,
        /** Sets (or adds) an attribute on an {@link Element}'s start tag. */
        SetAttribute,
        /** Removes a node (and, for elements with an explicit end tag, its contents) from the source. */
        RemoveNode
    }

    private final Kind kind;
    private final int startPos;
    private final int endPos;
    private final String replacement;
    private final String description;

    SourceEdit(Kind kind, int startPos, int endPos, String replacement, String description) {
        this.kind = kind;
        this.startPos = startPos;
        this.endPos = endPos;
        this.replacement = replacement;
        this.description = description;
    }

    /** The kind of this edit. */
    public Kind kind() {
        return kind;
    }

    /** The 0-based UTF-16 start offset (inclusive) of the source region this edit replaces. */
    public int startPos() {
        return startPos;
    }

    /** The 0-based UTF-16 end offset (exclusive) of the source region this edit replaces. */
    public int endPos() {
        return endPos;
    }

    /** The (already source-escaped) replacement text that will be spliced in at this edit's offsets. */
    public String replacement() {
        return replacement;
    }

    /** A human-readable description of this edit's target, useful for logging and conflict reports. */
    public String description() {
        return description;
    }

    /** Tests if this edit is a zero-length insertion. */
    boolean isInsert() {
        return startPos == endPos;
    }

    @Override
    public String toString() {
        return kind + "[" + startPos + ", " + endPos + ") <- \"" + replacement + "\" (" + description + ")";
    }
}
