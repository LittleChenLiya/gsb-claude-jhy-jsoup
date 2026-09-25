package org.jsoup.nodes;

import org.jsoup.internal.QuietAppendable;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 A SourceEditor plans minimal, source-offset based edits to the original input source of a parsed HTML document.
 Instead of re-serializing the DOM (which normalizes quote styles, entity usage, letter case, and whitespace), the
 editor records {@link SourceEdit}s against the original source text, and a {@link SourceEditPlan} splices only the
 edited regions back into that source. Every unmodified region keeps its original characters exactly.
 <p>Usage:</p>
 <pre>{@code
 SourceEditor editor = SourceEditor.parse(html); // parses with position tracking enabled
 Document doc = editor.document();
 editor.setAttribute(doc.selectFirst("a"), "href", "https://example.com/");
 editor.replaceText(doc.selectFirst("p").textNodes().get(0), "New text");
 SourceEditPlan plan = editor.plan();            // validates positions and rejects overlapping edits
 String editedHtml = plan.commit(html);          // verifies the source digest, then splices the edits
 }</pre>
 <p>All offsets are 0-based UTF-16 character positions into the original source, matching Java {@link String}
 indexing (a supplementary character such as an emoji occupies two positions).</p>
 <p>Requests are rejected with a {@link SourceEditException} when they cannot be applied safely: edits whose source
 ranges overlap (for example removing a parent element while changing one of its children), nodes without reliable
 tracked positions (position tracking disabled, or implicitly created nodes such as an inferred {@code <body>}), or
 attributes that appear more than once in the same start tag.</p>
 @see SourceEdit
 @see SourceEditPlan
 @see org.jsoup.parser.Parser#setTrackPosition(boolean)
 @since 1.24.1
 */
public final class SourceEditor {
    private static final Comparator<SourceEdit> BySourceOrder =
        Comparator.comparingInt(SourceEdit::startPos).thenComparingInt(SourceEdit::endPos);

    private final Document document;
    private final String source;
    private final String sourceDigest;
    private final List<SourceEdit> edits = new ArrayList<>();

    /**
     Parses {@code html} into a Document with source position tracking enabled, and creates a SourceEditor bound to
     that document and source.
     @param html the original source HTML
     @return a SourceEditor ready to accept edit requests
     */
    public static SourceEditor parse(String html) {
        Parser parser = Parser.htmlParser().setTrackPosition(true);
        Document document = parser.parseInput(html, "");
        return new SourceEditor(document, html);
    }

    /**
     Creates a SourceEditor for a Document that was parsed from {@code originalSource} with position tracking
     enabled (see {@link Parser#setTrackPosition(boolean)}). The document must not have been produced by a different
     source.
     @param document the parsed document
     @param originalSource the exact source the document was parsed from
     */
    public SourceEditor(Document document, String originalSource) {
        this.document = document;
        this.source = originalSource;
        this.sourceDigest = SourceEditPlan.digest(originalSource);
    }

    /**
     Gets the document this editor plans edits against. Select target nodes from this document.
     @return the parsed document
     */
    public Document document() {
        return document;
    }

    /**
     Gets the original source this editor is bound to.
     @return the original source
     */
    public String source() {
        return source;
    }

    /**
     Plans to replace the source text of {@code node} with {@code newText}. The new text is escaped appropriately for
     HTML text content; the original source outside the node's range is untouched.
     @param node the text node to replace; must have a tracked, explicit source range
     @param newText the replacement text (unescaped; will be escaped for you)
     @return this editor, for chaining
     @throws SourceEditException if the node has no reliable tracked source range
     */
    public SourceEditor replaceText(TextNode node, String newText) {
        checkOwnership(node);
        Range range = requireRange(node.sourceRange(), node);
        String escaped = escape(newText, Entities.ForText);
        edits.add(new SourceEdit(SourceEdit.Kind.ReplaceText, range.startPos(), range.endPos(), escaped,
            "text of " + node.getClass().getSimpleName() + " \"" + abbreviate(node.getWholeText()) + "\""));
        return this;
    }

    /**
     Plans to set attribute {@code key} to {@code value} on {@code element}'s start tag. If the attribute exists in
     the source, only its value span is replaced (preserving the original quote style and the rest of the tag);
     otherwise a new {@code key="value"} attribute is inserted at the end of the start tag. The value is escaped
     appropriately for an HTML attribute value.
     @param element the element to modify; must have a tracked, explicit source range
     @param key the attribute name
     @param value the new attribute value (unescaped; will be escaped for you)
     @return this editor, for chaining
     @throws SourceEditException if the element has no reliable tracked source range, if the attribute appears more
     than once in the start tag, or if {@code key} is not a valid attribute name for source insertion
     */
    public SourceEditor setAttribute(Element element, String key, String value) {
        checkOwnership(element);
        Range tagRange = requireRange(element.sourceRange(), element);
        String escaped = escape(value, Entities.ForAttribute);

        Range.AttributeRange attrRange = element.attributes().sourceRange(key);
        if (attrRange.isTracked()) {
            Range nameRange = attrRange.nameRange();
            Range valueRange = attrRange.valueRange();
            rejectDuplicateAttribute(tagRange, key);

            if (valueRange.startPos() < valueRange.endPos()) {
                // replace just the value span; the original quotes (or lack thereof) are preserved
                edits.add(new SourceEdit(SourceEdit.Kind.SetAttribute, valueRange.startPos(), valueRange.endPos(), escaped,
                    "value of attribute " + key + " on <" + element.tagName() + ">"));
            } else {
                // empty value in source: either a valueless attribute (hidden) or empty quotes (alt="")
                int insertStart = valueRange.startPos();
                int insertEnd = valueRange.endPos();
                String replacement;
                int cursor = nameRange.endPos();
                cursor = skipWhitespace(cursor);
                if (cursor < source.length() && source.charAt(cursor) == '=') {
                    // alt="" or alt= form: insert the new value between the existing quotes
                    cursor = skipWhitespace(cursor + 1);
                    if (cursor < source.length() && (source.charAt(cursor) == '"' || source.charAt(cursor) == '\'')) {
                        insertStart = cursor + 1;
                        insertEnd = insertStart;
                        replacement = escaped;
                    } else { // alt= with no value: append the value directly
                        insertStart = cursor;
                        insertEnd = cursor;
                        replacement = escaped;
                    }
                } else { // valueless attribute: add ="value" after the name
                    insertStart = nameRange.endPos();
                    insertEnd = insertStart;
                    replacement = "=\"" + escaped + "\"";
                }
                edits.add(new SourceEdit(SourceEdit.Kind.SetAttribute, insertStart, insertEnd, replacement,
                    "value of attribute " + key + " on <" + element.tagName() + ">"));
            }
        } else {
            // new attribute: insert before the close of the start tag, preserving any self-closing slash
            validateNewAttributeName(key);
            int insertAt = tagRange.endPos() - 1; // before '>'
            if (insertAt > tagRange.startPos() && source.charAt(insertAt - 1) == '/')
                insertAt--; // keep <br/> form
            edits.add(new SourceEdit(SourceEdit.Kind.SetAttribute, insertAt, insertAt,
                " " + key + "=\"" + escaped + "\"",
                "new attribute " + key + " on <" + element.tagName() + ">"));
        }
        return this;
    }

    /**
     Plans to remove {@code node} from the source. For an element with an explicit end tag, the whole element
     (start tag, contents, and end tag) is removed; for a void or childless element, only its start tag range is
     removed. Other node types (text, comment, etc.) are removed over their own source range.
     @param node the node to remove; must have a tracked, explicit source range
     @return this editor, for chaining
     @throws SourceEditException if the node has no reliable tracked source range, or is an element with children but
     only an implicit (or untracked) end tag
     */
    public SourceEditor remove(Node node) {
        checkOwnership(node);
        Range range = requireRange(node.sourceRange(), node);

        int endPos = range.endPos();
        if (node instanceof Element) {
            Element element = (Element) node;
            Range endRange = element.endSourceRange();
            if (endRange.isTracked() && !endRange.isImplicit()) {
                endPos = endRange.endPos();
            } else if (element.childNodeSize() != 0) {
                throw new SourceEditException(
                    "Cannot remove <" + element.tagName() + "> at " + range + ": its end tag is implicit or untracked, " +
                        "so the source span covering its children cannot be determined reliably.");
            }
        }
        edits.add(new SourceEdit(SourceEdit.Kind.RemoveNode, range.startPos(), endPos, "",
            "removal of " + node.nodeName()));
        return this;
    }

    /**
     Validates the requested edits and returns an immutable plan. Edits are reported by the plan in original source
     (ascending offset) order, and are applied on commit in descending offset order.
     @return the validated edit plan
     @throws SourceEditException if any two edits overlap; the exception message points out each conflicting range
     */
    public SourceEditPlan plan() {
        List<SourceEdit> sourceOrder = new ArrayList<>(edits);
        sourceOrder.sort(BySourceOrder);

        List<String> conflicts = new ArrayList<>();
        for (int i = 0; i < sourceOrder.size(); i++) {
            SourceEdit a = sourceOrder.get(i);
            for (int j = i + 1; j < sourceOrder.size(); j++) {
                SourceEdit b = sourceOrder.get(j);
                if (b.startPos() > a.endPos()) break; // sorted; no later edit can overlap a (inserts at a.endPos() are ok)
                if (overlaps(a, b))
                    conflicts.add("  - [" + a.startPos() + ", " + a.endPos() + ") " + a.description() +
                        "  conflicts with  [" + b.startPos() + ", " + b.endPos() + ") " + b.description());
            }
        }
        if (!conflicts.isEmpty())
            throw new SourceEditException("Overlapping source edits were requested:\n" + String.join("\n", conflicts));

        List<SourceEdit> applyOrder = new ArrayList<>(sourceOrder);
        applyOrder.sort(BySourceOrder.reversed());

        return new SourceEditPlan(source, sourceDigest,
            java.util.Collections.unmodifiableList(sourceOrder),
            java.util.Collections.unmodifiableList(applyOrder));
    }

    /**
     Tests if two edits overlap. Zero-length insertions conflict with any edit whose range contains the insertion
     point, and with another insertion at the same point.
     */
    private static boolean overlaps(SourceEdit a, SourceEdit b) {
        if (a.isInsert() && b.isInsert())
            return a.startPos() == b.startPos();
        if (a.isInsert())
            return b.startPos() <= a.startPos() && a.startPos() < b.endPos();
        if (b.isInsert())
            return a.startPos() <= b.startPos() && b.startPos() < a.endPos();
        return a.startPos() < b.endPos() && b.startPos() < a.endPos();
    }

    private void checkOwnership(Node node) {
        if (node == null)
            throw new SourceEditException("Node must not be null.");
        Document owner = node instanceof Document ? (Document) node : node.ownerDocument();
        if (owner != document)
            throw new SourceEditException("Node " + node.nodeName() + " does not belong to this editor's document.");
    }

    private Range requireRange(Range range, Node node) {
        if (!range.isTracked())
            throw new SourceEditException(
                "Node " + node.nodeName() + " has no tracked source position; parse with Parser.setTrackPosition(true) " +
                    "(or use SourceEditor.parse) to enable position tracking.");
        if (range.isImplicit())
            throw new SourceEditException(
                "Node " + node.nodeName() + " was implicitly created by the parser and has no reliable source position " +
                    "(implicit range " + range + ").");
        return range;
    }

    /**
     Rejects the request if the attribute name appears more than once in the element's start tag source: the DOM
     keeps only the first occurrence, so editing a duplicated attribute cannot be mapped to a single reliable span.
     */
    private void rejectDuplicateAttribute(Range tagRange, String key) {
        String tag = source.substring(tagRange.startPos(), tagRange.endPos());
        int count = 0;
        int i = 1; // after '<'
        int n = tag.length();
        while (i < n && !isWhitespace(tag.charAt(i)) && tag.charAt(i) != '>' && tag.charAt(i) != '/') i++; // tag name
        while (i < n) {
            while (i < n && isWhitespace(tag.charAt(i))) i++;
            if (i >= n || tag.charAt(i) == '>' || tag.charAt(i) == '/') break;
            int nameStart = i;
            while (i < n && !isWhitespace(tag.charAt(i)) && tag.charAt(i) != '=' && tag.charAt(i) != '>' && tag.charAt(i) != '/') i++;
            if (tag.substring(nameStart, i).equalsIgnoreCase(key)) count++;
            while (i < n && isWhitespace(tag.charAt(i))) i++;
            if (i < n && tag.charAt(i) == '=') {
                i++;
                while (i < n && isWhitespace(tag.charAt(i))) i++;
                if (i < n && (tag.charAt(i) == '"' || tag.charAt(i) == '\'')) {
                    char quote = tag.charAt(i++);
                    while (i < n && tag.charAt(i) != quote) i++;
                    if (i < n) i++;
                } else {
                    while (i < n && !isWhitespace(tag.charAt(i)) && tag.charAt(i) != '>') i++;
                }
            }
        }
        if (count > 1)
            throw new SourceEditException(
                "Attribute \"" + key + "\" appears " + count + " times in the start tag of <" + tagNameOf(tagRange) +
                    ">; the source span to edit is ambiguous, so the request was rejected.");
    }

    private String tagNameOf(Range tagRange) {
        String tag = source.substring(tagRange.startPos(), tagRange.endPos());
        int i = 1;
        while (i < tag.length() && !isWhitespace(tag.charAt(i)) && tag.charAt(i) != '>' && tag.charAt(i) != '/') i++;
        return tag.substring(1, i);
    }

    private int skipWhitespace(int cursor) {
        while (cursor < source.length() && isWhitespace(source.charAt(cursor))) cursor++;
        return cursor;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    private static void validateNewAttributeName(String key) {
        if (key.isEmpty())
            throw new SourceEditException("Attribute name must not be empty.");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (isWhitespace(c) || c == '=' || c == '"' || c == '\'' || c == '<' || c == '>' || c == '/')
                throw new SourceEditException("Attribute name \"" + key + "\" is not safe to insert into source.");
        }
    }

    private String escape(String data, int options) {
        Document.OutputSettings out = document.outputSettings();
        StringBuilder sb = new StringBuilder(data.length() + 16);
        Entities.escape(QuietAppendable.wrap(sb), data, out, options);
        return sb.toString();
    }

    private static String abbreviate(String text) {
        return text.length() <= 20 ? text : text.substring(0, 20) + "...";
    }
}
