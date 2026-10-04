package org.jsoup.nodes;

import org.jsoup.helper.Validate;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 Highlights a half-open text range [start, end) of a subtree's text view by wrapping the affected text
 segments in mark elements, without disturbing the existing element structure (b, a, etc.
 stay in place; only text nodes are split).

 Text view definition:
 The text view of a root element is the concatenation of the whole text of every TextNode under it, in document
 order, excluding any text nodes that descend from a script or style element. Offsets are
 UTF-16 code unit offsets into that concatenation (i.e. plain Java String indexes).

 Range rules:
 - The range is validated against the text view before any mutation; an invalid range (negative, reversed, or
   past the end of the view) throws IllegalArgumentException and leaves the DOM completely unchanged.
 - A boundary that would split a Unicode surrogate pair is expanded outwards so the pair stays whole.
 - An empty range is a valid no-op.

 Plan then apply:
 Highlighting is computed in two phases: first an immutable plan of (TextNode, from, to) segments is derived
 from the text view; only after the whole plan is known to be valid is it applied to the DOM. Planning failures
 therefore never leave a partially highlighted document.

 Undo:
 highlight() returns a Highlight undo record. undo() first re-validates that every mark it created,
 and every adjacent text node produced by splitting, still matches the recorded baseline (same parent, same
 sibling sequence, same text). If the user (or other code) has since edited, moved, or deleted any of those
 nodes, the undo is refused and the DOM is left untouched, so later edits are never silently discarded.

 Example:
   Document doc = Jsoup.parse("<p>A<b>BC</b>D</p>");
   // text view is "ABCD"; [1, 4) covers "BC" (inside b) and "D"
   TextHighlighter.Highlight hl = TextHighlighter.highlight(doc.body(), 1, 4, "m1");
   // body is now: A<b><mark class="jsoup-hl" data-hl-id="m1">BC</mark></b><mark class="jsoup-hl" data-hl-id="m1">D</mark>
   hl.undo(); // restores: A<b>BC</b>D
 */
public final class TextHighlighter {
    /** The tag used for highlight elements ("mark"). */
    public static final String MarkTag = "mark";
    /** The class set on every highlight element ("jsoup-hl"). */
    public static final String MarkClass = "jsoup-hl";
    /** The attribute carrying the caller supplied mark ID ("data-hl-id"). */
    public static final String MarkIdAttr = "data-hl-id";

    private TextHighlighter() {}

    /**
     Returns the text view of root: the concatenation of the whole text of all descendant TextNodes in
     document order, excluding text inside script and style elements. UTF-16 offsets used by
     highlight() index into this string.
     @param root the subtree to read
     @return the concatenated text view
     */
    public static String textView(Element root) {
        Validate.notNull(root);
        StringBuilder sb = new StringBuilder();
        for (TextNode node : textNodes(root))
            sb.append(node.getWholeText());
        return sb.toString();
    }

    /**
     Highlights the half-open UTF-16 range [start, end) of root's text view (see textView) by wrapping each
     affected text segment in a mark element (class jsoup-hl, attribute data-hl-id). Existing elements are
     preserved; only text nodes are split. Range boundaries that fall inside a surrogate pair are expanded
     outwards to keep the pair intact.
     The whole range is planned and validated before the DOM is touched; an invalid range throws and leaves
     the DOM unchanged.
     @param root the subtree to highlight within
     @param start start UTF-16 offset (inclusive) into the text view
     @param end end UTF-16 offset (exclusive) into the text view
     @param markId caller supplied ID recorded on each created mark via MarkIdAttr
     @return an undo record for this highlight
     @throws IllegalArgumentException if the range is negative, reversed, or beyond the text view
     */
    public static Highlight highlight(Element root, int start, int end, String markId) {
        Validate.notNull(root);
        Validate.notNull(markId);

        // ---- Plan phase: build the text view, validate, snap, and compute segments without touching the DOM ----
        List<TextNode> nodes = textNodes(root);
        StringBuilder viewBuilder = new StringBuilder();
        int[] nodeStarts = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            nodeStarts[i] = viewBuilder.length();
            viewBuilder.append(nodes.get(i).getWholeText());
        }
        String view = viewBuilder.toString();
        int total = view.length();
        Validate.isTrue(start >= 0 && end >= start && end <= total,
            "Range [" + start + ", " + end + ") is out of bounds for text view of length " + total);

        // Expand boundaries that would cut a surrogate pair
        if (start > 0 && start < total
            && Character.isHighSurrogate(view.charAt(start - 1)) && Character.isLowSurrogate(view.charAt(start)))
            start--;
        if (end > 0 && end < total
            && Character.isHighSurrogate(view.charAt(end - 1)) && Character.isLowSurrogate(view.charAt(end)))
            end++;

        List<Segment> plan = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            int ns = nodeStarts[i], ne = ns + nodes.get(i).getWholeText().length();
            int s = Math.max(start, ns), e = Math.min(end, ne);
            if (s < e) {
                TextNode node = nodes.get(i);
                int from = s - ns, to = e - ns;
                // Defensive plan validation: never apply a segment that does not fit its node
                Validate.isTrue(from >= 0 && to > from && to <= node.getWholeText().length(),
                    "Planned segment does not fit its text node");
                plan.add(new Segment(node, from, to));
            }
        }

        // ---- Apply phase: the plan is fully validated, so the DOM can be mutated safely ----
        List<Element> marks = new ArrayList<>();
        List<Group> groups = new ArrayList<>();
        for (Segment seg : plan) {
            TextNode node = seg.node;
            String original = node.getWholeText();
            Node parent = node.parent();
            int originalIndex = node.siblingIndex();

            if (seg.to < original.length())
                node.splitText(seg.to); // trailing split node keeps the tail
            TextNode mid = seg.from > 0 ? node.splitText(seg.from) : node;

            Element mark = new Element(MarkTag);
            mark.attr("class", MarkClass);
            mark.attr(MarkIdAttr, markId);
            mid.before(mark);
            mark.appendChild(mid);
            marks.add(mark);

            // Record the baseline run of sibling nodes that replaced the original text node, for undo validation
            List<Node> run = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            List<Node> siblings = parent.childNodes();
            int runLength = 1 + (seg.from > 0 ? 1 : 0) + (seg.to < original.length() ? 1 : 0);
            for (int i = originalIndex; i < originalIndex + runLength; i++) {
                Node n = siblings.get(i);
                run.add(n);
                expected.add(n instanceof TextNode ? ((TextNode) n).getWholeText() : mid.getWholeText());
            }
            groups.add(new Group(parent, original, run, expected));
        }
        return new Highlight(markId, start, end, marks, groups);
    }

    /** All text nodes of the text view, in document order, excluding script/style descendants. */
    private static List<TextNode> textNodes(Element root) {
        final List<TextNode> out = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override public void head(Node node, int depth) {
                if (node instanceof TextNode && !inExcludedElement(node))
                    out.add((TextNode) node);
            }
            @Override public void tail(Node node, int depth) {}
        }, root);
        return out;
    }

    private static boolean inExcludedElement(Node node) {
        Node p = node.parent();
        while (p != null) {
            if (p instanceof Element) {
                String name = ((Element) p).normalName();
                if (name.equals("script") || name.equals("style"))
                    return true;
            }
            p = p.parent();
        }
        return false;
    }

    private static final class Segment {
        final TextNode node;
        final int from, to;
        Segment(TextNode node, int from, int to) {
            this.node = node; this.from = from; this.to = to;
        }
    }

    /** Baseline of one original text node: the run of sibling nodes that replaced it, and their expected text. */
    private static final class Group {
        final Node parent;
        final String originalText;
        final List<Node> run;
        final List<String> expected;
        Group(Node parent, String originalText, List<Node> run, List<String> expected) {
            this.parent = parent; this.originalText = originalText; this.run = run; this.expected = expected;
        }
    }

    /**
     An undo record for one highlight() call. Holds the created mark elements and the baseline needed to
     safely restore the original text nodes.
     */
    public static final class Highlight {
        private final String markId;
        private final int start, end; // effective range, after surrogate snapping
        private final List<Element> marks;
        private final List<Group> groups;
        private boolean undone = false;
        private String refusalReason = null;

        Highlight(String markId, int start, int end, List<Element> marks, List<Group> groups) {
            this.markId = markId;
            this.start = start;
            this.end = end;
            this.marks = marks;
            this.groups = groups;
        }

        /** The mark ID supplied by the caller. */
        public String markId() { return markId; }

        /** The effective start offset actually highlighted (after surrogate-pair snapping). */
        public int start() { return start; }

        /** The effective end offset actually highlighted (after surrogate-pair snapping). */
        public int end() { return end; }

        /** The mark elements created by this highlight, in document order. */
        public List<Element> marks() { return Collections.unmodifiableList(marks); }

        /** Whether this highlight has been successfully undone. */
        public boolean isUndone() { return undone; }

        /** If the last undo() call was refused, a human-readable reason; otherwise null. */
        public String refusalReason() { return refusalReason; }

        /**
         Attempts to restore the original text nodes and remove this highlight's mark elements.
         Before changing anything, every mark and every adjacent split text node is checked against the
         baseline recorded at highlight time. If any of them has been edited, moved, or removed externally,
         the undo is refused: nothing in the DOM is changed, this method returns false, and refusalReason()
         describes the conflict. A successful undo returns true and restores the original text and node order.
         @return true if the highlight was undone; false if the undo was refused
         */
        public boolean undo() {
            if (undone) {
                refusalReason = "This highlight has already been undone.";
                return false;
            }
            // Validate every group against its baseline before mutating anything
            for (Group group : groups) {
                String problem = validate(group);
                if (problem != null) {
                    refusalReason = problem;
                    return false;
                }
            }
            // All groups match the baseline; safe to restore
            for (Group group : groups) {
                TextNode restored = new TextNode(group.originalText);
                group.run.get(0).before(restored);
                for (Node n : group.run)
                    n.remove();
            }
            undone = true;
            refusalReason = null;
            return true;
        }

        private String validate(Group group) {
            Node first = group.run.get(0);
            if (first.parent() != group.parent)
                return "Refusing to undo mark " + markId + ": the highlighted region was moved or removed.";
            List<Node> children = group.parent.childNodes();
            int index = first.siblingIndex();
            if (index + group.run.size() > children.size())
                return "Refusing to undo mark " + markId + ": siblings of the highlighted region changed.";
            for (int i = 0; i < group.run.size(); i++) {
                Node expected = group.run.get(i);
                if (children.get(index + i) != expected)
                    return "Refusing to undo mark " + markId + ": nodes around the highlighted region changed.";
                String want = group.expected.get(i);
                if (expected instanceof TextNode) {
                    if (!((TextNode) expected).getWholeText().equals(want))
                        return "Refusing to undo mark " + markId + ": an adjacent text node was edited.";
                } else {
                    Element mark = (Element) expected;
                    if (!mark.normalName().equals(MarkTag) || !markId.equals(mark.attr(MarkIdAttr)))
                        return "Refusing to undo mark " + markId + ": a mark element was altered.";
                    if (mark.childNodeSize() != 1 || !(mark.childNode(0) instanceof TextNode)
                        || !((TextNode) mark.childNode(0)).getWholeText().equals(want))
                        return "Refusing to undo mark " + markId + ": the content of a mark was edited.";
                }
            }
            return null;
        }
    }
}
