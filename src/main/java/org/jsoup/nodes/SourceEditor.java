package org.jsoup.nodes;

import org.jsoup.helper.Validate;
import org.jsoup.internal.QuietAppendable;
import org.jsoup.internal.StringUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 A SourceEditor plans minimal, offset-based text edits against the original source of a parsed {@link Document},
 without re-serializing the DOM. Regions that are not edited keep their original character sequence exactly,
 including quote style, attribute name case, entities, and whitespace.
 <p>To use:</p>
 <ol>
  <li>Parse with position tracking enabled: {@code new Parser(htmlParser).setTrackPosition(true)} (see
  {@link org.jsoup.parser.Parser#setTrackPosition(boolean)}).</li>
  <li>Create an editor with {@link #on(String, Document)}, passing the exact original source and the parsed
  document.</li>
  <li>Plan edits against the original DOM nodes: {@link #replaceText(TextNode, String)},
  {@link #setAttribute(Element, String, String)}, {@link #removeAttribute(Element, String)},
  {@link #remove(Node)}.</li>
  <li>Inspect the planned {@link #edits()} (reported in original source order), then {@link #commit()} to apply
  them. Edits are applied in descending offset order so that earlier offsets stay valid.</li>
 </ol>
 <p>All offsets are Java UTF-16 character positions into the original source string (matching
 {@link Range#startPos()} / {@link Range#endPos()}). New content introduced by an edit is escaped appropriately
 for its context (text or attribute value). Edits that overlap, or that target nodes without a reliable tracked
 source position (for example implicitly created elements), are rejected with a {@link SourceEditException}.
 {@link #commit(String)} verifies a digest of the original source before applying anything, so a changed source
 is never silently patched.</p>
 @see Node#sourceRange()
 @see Element#endSourceRange()
 @see Attributes#sourceRange(String)
 @since 1.24.1
 */
public class SourceEditor {
    private final String original;
    private final String digest;
    private final Document.OutputSettings outputSettings;
    private final ArrayList<Edit> edits = new ArrayList<>();
    private int sequence = 0;

    private SourceEditor(String original, Document.OutputSettings outputSettings) {
        this.original = original;
        this.digest = digest(original);
        this.outputSettings = outputSettings;
    }

    /**
     Create a SourceEditor over the original source that produced {@code doc}.
     @param original the exact source text the document was parsed from
     @param doc a document parsed from {@code original} with position tracking enabled
     @return a new SourceEditor
     */
    public static SourceEditor on(String original, Document doc) {
        Validate.notNull(original);
        Validate.notNull(doc);
        return new SourceEditor(original, doc.outputSettings().clone());
    }

    /**
     Plan replacing the text of a {@link TextNode} with {@code newText}. The new text is escaped for a text
     context (e.g. {@code &}, {@code <}, {@code >} become entities).
     @param node a text node from the original document, with a tracked, non-implicit source range
     @param newText the replacement text (unescaped)
     @return this editor, for chaining
     */
    public SourceEditor replaceText(TextNode node, String newText) {
        Validate.notNull(node);
        Validate.notNull(newText);
        Range range = requireRange(node.sourceRange(), "text node");
        return add(new Edit(range.startPos(), range.endPos(), escape(newText, Entities.ForText)));
    }

    /**
     Plan setting an attribute on an element. If the attribute exists in the source, its name and value are
     replaced in place; otherwise a new {@code key="value"} attribute is inserted at the end of the element's
     start tag. The value is escaped for an attribute context.
     @param el an element from the original document, with a tracked, non-implicit source range
     @param key the attribute name
     @param value the new attribute value (unescaped)
     @return this editor, for chaining
     */
    public SourceEditor setAttribute(Element el, String key, String value) {
        Validate.notNull(el);
        Validate.notNull(key);
        Validate.notNull(value);
        String validKey = Attribute.getValidKey(key, outputSettings.syntax());
        String replacement = validKey + "=\"" + escape(value, Entities.ForAttribute) + "\"";

        Range.AttributeRange attrRange = el.attributes().sourceRange(key);
        if (attrRange.isTracked()) {
            Range name = attrRange.nameRange();
            Range val = attrRange.valueRange();
            int start = name.startPos();
            int end = val.isImplicit() ? name.endPos() : valueEndExclusive(val);
            return add(new Edit(start, end, replacement));
        }

        // attribute not present in source; insert before the close of the start tag
        Range tag = requireRange(el.sourceRange(), "element");
        int insert = tag.endPos() - 1; // just before '>'
        if (insert > tag.startPos() && original.charAt(insert - 1) == '/') insert--; // keep self-closing '/'
        return add(new Edit(insert, insert, " " + replacement));
    }

    /**
     Plan removing an attribute from an element, including the whitespace preceding it. If the attribute is not
     present in the source, this is a no-op.
     @param el an element from the original document
     @param key the attribute name to remove
     @return this editor, for chaining
     */
    public SourceEditor removeAttribute(Element el, String key) {
        Validate.notNull(el);
        Validate.notNull(key);
        Range.AttributeRange attrRange = el.attributes().sourceRange(key);
        if (!attrRange.isTracked()) return this; // nothing to remove

        Range name = attrRange.nameRange();
        Range val = attrRange.valueRange();
        int start = name.startPos();
        int end = val.isImplicit() ? name.endPos() : valueEndExclusive(val);
        while (start > 0 && isWhitespace(original.charAt(start - 1))) start--; // absorb preceding tag whitespace
        return add(new Edit(start, end, ""));
    }

    /**
     Plan removing a node from the source. For an element with an explicit end tag, the whole element (start tag
     through end tag) is removed; for a void or childless element, or a leaf node, its own source range is
     removed. Elements with children but no reliable end tag position (implicit close) are rejected.
     @param node a node from the original document, with a tracked, non-implicit source range
     @return this editor, for chaining
     */
    public SourceEditor remove(Node node) {
        Validate.notNull(node);
        Range range = requireRange(node.sourceRange(), "node");
        int start = range.startPos();
        int end = range.endPos();
        if (node instanceof Element) {
            Element el = (Element) node;
            Range endTag = el.endSourceRange();
            if (endTag.isTracked() && !endTag.isImplicit()) {
                end = endTag.endPos();
            } else if (el.childNodeSize() != 0) {
                throw new SourceEditException("Cannot remove element " + el.tagName()
                    + ": no reliable end tag source position (implicit close at " + endTag + ")");
            }
        }
        return add(new Edit(start, end, ""));
    }

    /**
     Get the planned edits, in original source order (ascending start offset).
     @return an unmodifiable view of the planned edits
     */
    public List<Edit> edits() {
        ArrayList<Edit> copy = new ArrayList<>(edits);
        copy.sort(BySourceOrder);
        return Collections.unmodifiableList(copy);
    }

    /**
     Get the digest (SHA-256, hex encoded) of the original source this editor was created with. The digest is
     verified again on {@link #commit(String)}.
     @return the expected source digest
     */
    public String digest() {
        return digest;
    }

    /**
     Apply the planned edits to the original source this editor was created with.
     @return the edited source
     @see #commit(String)
     */
    public String commit() {
        return commit(original);
    }

    /**
     Apply the planned edits to {@code source}, after verifying that it matches the digest of the original
     source. Edits are applied in descending offset order, so offsets of not-yet-applied edits remain valid; the
     DOM is never re-serialized.
     @param source the original source text
     @return the edited source
     @throws SourceEditException if {@code source} does not match the original digest
     */
    public String commit(String source) {
        Validate.notNull(source);
        if (!digest(source).equals(digest))
            throw new SourceEditException("Source text does not match the original digest; refusing to apply edits");
        StringBuilder sb = new StringBuilder(source);
        for (Edit edit : editsDescending())
            sb.replace(edit.startPos, edit.endPos, edit.replacement);
        return sb.toString();
    }

    /** Edits in application order: descending offsets, replacements before same-position inserts, first-planned first. */
    List<Edit> editsDescending() {
        ArrayList<Edit> copy = new ArrayList<>(edits);
        copy.sort(ByApplicationOrder);
        return Collections.unmodifiableList(copy);
    }

    private SourceEditor add(Edit edit) {
        if (edit.endPos > original.length())
            throw new SourceEditException("Edit " + edit + " exceeds the original source length " + original.length());
        for (Edit existing : edits) {
            if (overlaps(existing, edit))
                throw new SourceEditException(
                    "Edit " + edit + " conflicts with overlapping edit " + existing + "; refusing to plan overlapping edits");
        }
        edit.sequence = sequence++;
        edits.add(edit);
        return this;
    }

    private static boolean overlaps(Edit a, Edit b) {
        if (a.startPos == a.endPos && b.startPos == b.endPos) return false; // inserts may share a position
        if (a.startPos == a.endPos) return a.startPos > b.startPos && a.startPos < b.endPos;
        if (b.startPos == b.endPos) return b.startPos > a.startPos && b.startPos < a.endPos;
        return a.startPos < b.endPos && b.startPos < a.endPos;
    }

    private Range requireRange(Range range, String what) {
        if (!range.isTracked())
            throw new SourceEditException("Cannot edit " + what +
                ": no tracked source position. Parse with Parser.setTrackPosition(true)");
        if (range.isImplicit())
            throw new SourceEditException("Cannot edit " + what + ": source position is implicit (" + range + ")");
        return range;
    }

    /** End of an attribute value in source, including a closing quote if present. */
    private int valueEndExclusive(Range val) {
        int end = val.endPos();
        if (end < original.length()) {
            char c = original.charAt(end);
            if (c == '"' || c == '\'') end++;
        }
        return end;
    }

    private String escape(String content, int options) {
        StringBuilder sb = StringUtil.borrowBuilder();
        Entities.escape(QuietAppendable.wrap(sb), content, outputSettings, options);
        return StringUtil.releaseBuilder(sb);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\f' || c == '\r';
    }

    static String digest(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Comparator<Edit> BySourceOrder = (a, b) -> {
        if (a.startPos != b.startPos) return Integer.compare(a.startPos, b.startPos);
        if (a.endPos != b.endPos) return Integer.compare(a.endPos, b.endPos);
        return Integer.compare(a.sequence, b.sequence);
    };

    private static final Comparator<Edit> ByApplicationOrder = (a, b) -> {
        if (a.startPos != b.startPos) return Integer.compare(b.startPos, a.startPos);
        if (a.endPos != b.endPos) return Integer.compare(b.endPos, a.endPos); // replacements before same-pos inserts
        return Integer.compare(b.sequence, a.sequence); // later-planned insert applies first, so output keeps plan order
    };

    /**
     A single planned source edit: replace the original source in {@code [startPos, endPos)} with
     {@code replacement}. Offsets are Java UTF-16 character positions into the original source.
     */
    public static class Edit {
        private final int startPos;
        private final int endPos;
        private final String replacement;
        private int sequence;

        private Edit(int startPos, int endPos, String replacement) {
            this.startPos = startPos;
            this.endPos = endPos;
            this.replacement = replacement;
        }

        /** The 0-based start offset (inclusive) in the original source. */
        public int startPos() {
            return startPos;
        }

        /** The 0-based end offset (exclusive) in the original source. */
        public int endPos() {
            return endPos;
        }

        /** The escaped replacement text that will be spliced into the source. */
        public String replacement() {
            return replacement;
        }

        @Override
        public String toString() {
            return "[" + startPos + ", " + endPos + ") -> \"" + replacement + "\"";
        }
    }

    /**
     Thrown when an edit cannot be planned or committed: overlapping edits, missing or implicit source positions,
     or a source that no longer matches the original digest.
     */
    public static class SourceEditException extends IllegalArgumentException {
        public SourceEditException(String message) {
            super(message);
        }
    }
}
