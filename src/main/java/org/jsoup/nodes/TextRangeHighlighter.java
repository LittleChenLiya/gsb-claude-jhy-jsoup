package org.jsoup.nodes;

import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class TextRangeHighlighter {
    public static final String MARK_TAG = "mark";
    public static final String MARK_ID_ATTR = "data-mark-id";

    private TextRangeHighlighter() {
    }

    public static String textView(Document doc) {
        Validate.notNull(doc);
        StringBuilder sb = new StringBuilder();
        for (NodeIterator<TextNode> it = new NodeIterator<>(doc, TextNode.class); it.hasNext(); ) {
            TextNode tn = it.next();
            if (isInScriptOrStyle(tn)) continue;
            sb.append(tn.getWholeText());
        }
        return sb.toString();
    }

    public static UndoRecord highlight(Document doc, int start, int end, String markId) {
        Validate.notNull(doc);
        Validate.notNull(markId);

        List<TextNodeSpan> spans = new ArrayList<>();
        StringBuilder viewBuilder = new StringBuilder();
        int offset = 0;
        for (NodeIterator<TextNode> it = new NodeIterator<>(doc, TextNode.class); it.hasNext(); ) {
            TextNode tn = it.next();
            if (isInScriptOrStyle(tn)) continue;
            String text = tn.getWholeText();
            spans.add(new TextNodeSpan(tn, offset, offset + text.length()));
            viewBuilder.append(text);
            offset += text.length();
        }
        String view = viewBuilder.toString();
        int viewLength = view.length();

        if (start < 0 || end < start || end > viewLength)
            throw new IllegalArgumentException("Invalid range: [" + start + ", " + end + ") for text view of length " + viewLength);
        if (cutsSurrogatePair(view, start) || cutsSurrogatePair(view, end))
            throw new IllegalArgumentException("Invalid range: [" + start + ", " + end + ") cuts a surrogate pair");

        List<Segment> plan = new ArrayList<>();
        for (TextNodeSpan span : spans) {
            int overlapStart = Math.max(start, span.startOffset);
            int overlapEnd = Math.min(end, span.endOffset);
            if (overlapStart >= overlapEnd) continue;
            int localStart = overlapStart - span.startOffset;
            int localEnd = overlapEnd - span.startOffset;
            plan.add(new Segment(span.textNode, localStart, localEnd));
        }

        List<SegmentRecord> records = new ArrayList<>();
        for (Segment seg : plan) {
            records.add(applySegment(doc, seg, markId));
        }
        return new UndoRecord(records);
    }

    private static SegmentRecord applySegment(Document doc, Segment seg, String markId) {
        TextNode tn = seg.textNode;
        String originalText = tn.getWholeText();

        TextNode prefix = null;
        if (seg.localStart > 0) {
            prefix = tn;
            tn = tn.splitText(seg.localStart);
        }

        TextNode suffix = null;
        if (seg.localEnd < originalText.length()) {
            int splitOffset = seg.localEnd - seg.localStart;
            suffix = tn.splitText(splitOffset);
        }

        Element mark = doc.createElement(MARK_TAG);
        mark.attr(MARK_ID_ATTR, markId);
        tn.before(mark);
        mark.appendChild(tn);

        SegmentRecord rec = new SegmentRecord();
        rec.mark = mark;
        rec.markParent = mark.parent();
        rec.markIndex = mark.siblingIndex();
        rec.markText = getMarkText(mark);
        rec.markId = markId;
        rec.prefix = prefix;
        rec.prefixText = prefix != null ? prefix.getWholeText() : null;
        rec.suffix = suffix;
        rec.suffixText = suffix != null ? suffix.getWholeText() : null;
        return rec;
    }

    private static String getMarkText(Element mark) {
        StringBuilder sb = new StringBuilder();
        for (Node child : mark.childNodes()) {
            if (child instanceof TextNode)
                sb.append(((TextNode) child).getWholeText());
        }
        return sb.toString();
    }

    private static boolean cutsSurrogatePair(String view, int p) {
        return p > 0 && p < view.length()
            && Character.isHighSurrogate(view.charAt(p - 1))
            && Character.isLowSurrogate(view.charAt(p));
    }

    private static boolean isInScriptOrStyle(Node node) {
        for (Node p = node.parent(); p != null; p = p.parent()) {
            if (p instanceof Element) {
                String tag = ((Element) p).normalName();
                if ("script".equals(tag) || "style".equals(tag)) return true;
            }
        }
        return false;
    }

    private static final class TextNodeSpan {
        final TextNode textNode;
        final int startOffset;
        final int endOffset;
        TextNodeSpan(TextNode textNode, int startOffset, int endOffset) {
            this.textNode = textNode;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    private static final class Segment {
        final TextNode textNode;
        final int localStart;
        final int localEnd;
        Segment(TextNode textNode, int localStart, int localEnd) {
            this.textNode = textNode;
            this.localStart = localStart;
            this.localEnd = localEnd;
        }
    }

    private static final class SegmentRecord {
        @Nullable Element mark;
        @Nullable Node markParent;
        int markIndex;
        @Nullable String markText;
        @Nullable String markId;
        @Nullable TextNode prefix;
        @Nullable String prefixText;
        @Nullable TextNode suffix;
        @Nullable String suffixText;

        boolean isValid() {
            if (mark == null || markParent == null || markText == null || markId == null) return false;
            if (mark.parentNode() != markParent) return false;
            if (mark.siblingIndex() != markIndex) return false;
            if (!MARK_TAG.equals(mark.normalName())) return false;
            if (!mark.hasAttr(MARK_ID_ATTR) || !markId.equals(mark.attr(MARK_ID_ATTR))) return false;
            if (!markText.equals(getMarkText(mark))) return false;
            if (prefix != null) {
                if (prefix.parentNode() != markParent) return false;
                if (prefix.siblingIndex() != markIndex - 1) return false;
                if (!prefixText.equals(prefix.getWholeText())) return false;
            }
            if (suffix != null) {
                if (suffix.parentNode() != markParent) return false;
                if (suffix.siblingIndex() != markIndex + 1) return false;
                if (!suffixText.equals(suffix.getWholeText())) return false;
            }
            return true;
        }

        void undo() {
            if (mark == null || markParent == null) return;
            TextNode middle = null;
            for (Node child : mark.childNodes()) {
                if (child instanceof TextNode) {
                    middle = (TextNode) child;
                    break;
                }
            }
            if (middle == null) return;

            mark.replaceWith(middle);

            if (prefix != null || suffix != null) {
                StringBuilder combined = new StringBuilder();
                if (prefix != null) combined.append(prefix.getWholeText());
                combined.append(middle.getWholeText());
                if (suffix != null) combined.append(suffix.getWholeText());

                if (prefix != null) {
                    prefix.text(combined.toString());
                    middle.remove();
                    if (suffix != null) suffix.remove();
                } else {
                    middle.text(combined.toString());
                    if (suffix != null) suffix.remove();
                }
            }
        }
    }

    public static final class UndoRecord {
        private final List<SegmentRecord> records;
        private boolean undone = false;

        UndoRecord(List<SegmentRecord> records) {
            this.records = records;
        }

        public boolean isValid() {
            if (undone) return false;
            for (SegmentRecord rec : records) {
                if (!rec.isValid()) return false;
            }
            return true;
        }

        public void undo() {
            if (undone) return;
            if (!isValid())
                throw new IllegalStateException("Cannot undo: the document has been modified externally since the highlight was applied.");
            for (SegmentRecord rec : records) {
                rec.undo();
            }
            undone = true;
        }
    }
}
