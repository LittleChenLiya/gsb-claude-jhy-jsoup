package org.jsoup.nodes;

import org.jsoup.internal.StringUtil;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.jsoup.nodes.TextNode.lastCharIsWhitespace;

/**
 A {@code TextProjection} is a {@linkplain #text() plain-text rendering} of an {@link Element} (built by
 {@link Element#textProject()} or {@link Element#textProject(boolean)}), together with a map of every emitted
 character back to the {@link TextNode} and the character offset within that node from which it came. It is intended
 for use cases such as search-term highlighting, where a match is found in the rendered text and then mapped back
 onto the original DOM nodes.

 <h2>Fixed projection rules</h2>
 <p>The projected text follows the same fixed rules as {@link Element#text()}:
 <ol>
  <li>Text nodes are visited in document order. {@code <script>} and {@code <style>} contents, comments, and other
      data nodes are never emitted, because they are not visible text.</li>
  <li>Outside of whitespace-preserving context, whitespace runs (spaces, tabs, newlines, carriage returns, form
      feeds, and non-breaking spaces {@code U+00A0}) collapse to a single {@code ' '}.</li>
  <li>The zero-width space ({@code U+200B}) and soft hyphen ({@code U+00AD}) are dropped.</li>
  <li>Block elements and other readable text boundaries (such as headings, paragraphs, and table cells) are
      separated by a single {@code ' '} when visible text would otherwise be joined. The rendered text is trimmed of
      leading and trailing whitespace.</li>
  <li>Whitespace inside a {@code <pre>} block (or any other tag that preserves whitespace) is retained verbatim by
      default, matching {@link Element#text()}. Pass {@code false} to {@link Element#textProject(boolean)} to
      normalize that whitespace the same way as ordinary text.</li>
 </ol>

 <p>All offsets are 0-based UTF-16 indices (identical to {@link String#charAt(int)}); supplementary characters such
 as emoji occupy two offsets, exactly as they do in the rendered {@link String}.

 <h2>Mapping a search hit back to the DOM</h2>
 <p>A range located in {@link #text()} (for example with {@link String#indexOf(String)}) is resolved with
 {@link #segments(int, int)}. The returned {@link Segment} objects point at the original {@link TextNode}s and the
 offsets within them. A synthetic boundary space emitted between block elements does not originate from any text
 node, so a range may span multiple segments, or resolve to none at all when it covers only such a synthetic space.

 @see Element#textProject()
 @see Element#textProject(boolean)
 @since 1.24.1
 */
public class TextProjection {
    private final Element element;
    private final boolean preservePreWhitespace;

    private final StringBuilder raw = new StringBuilder();
    /** For each UTF-16 unit in {@link #raw}: source node (or null for a synthetic boundary space). */
    private final List<TextNode> charNodes = new ArrayList<>();
    /** For each UTF-16 unit in {@link #raw}: source offset in {@link TextNode#getWholeText()}, or -1. */
    private final List<Integer> charOffsets = new ArrayList<>();

    private int trimStart = -1;
    private int trimEnd = -1;

    private TextProjection(Element element, boolean preservePreWhitespace) {
        this.element = element;
        this.preservePreWhitespace = preservePreWhitespace;
    }

    static TextProjection of(Element element, boolean preservePreWhitespace) {
        TextProjection projection = new TextProjection(element, preservePreWhitespace);
        new Projector(projection).traverse(element);
        return projection;
    }

    /**
     Get the projected plain text.
     @return normalized, decoded, trimmed plain text; possibly empty
     */
    public String text() {
        resolveTrim();
        return raw.substring(trimStart, trimEnd);
    }

    /**
     Get the element this projection was built from.
     @return the source element
     */
    public Element element() {
        return element;
    }

    /**
     Test if whitespace inside {@code <pre>} and other whitespace-preserving tags was retained verbatim.
     @return {@code true} (the default, matching {@link Element#text()}) if pre whitespace was retained
     */
    public boolean preservePreWhitespace() {
        return preservePreWhitespace;
    }

    /**
     Map a half-open range in {@link #text()} back to the originating text nodes.
     <p>For example, given {@code 你<b>好</b> 世界}, searching the projected text for {@code "好 世"} resolves to one
     segment in the {@code <b>} node ({@code 好}) and one in the following text node ({@code 世}).
     @param startOffset 0-based start offset in the projected text, inclusive
     @param endOffset 0-based end offset in the projected text, exclusive; may equal {@code startOffset}
     @return the source segments in document order; an empty list is returned for an empty range, or a range that
      covers only a synthetic boundary space
     */
    public List<Segment> segments(int startOffset, int endOffset) {
        resolveTrim();
        int textLength = trimEnd - trimStart;
        if (startOffset < 0 || endOffset < startOffset || endOffset > textLength) {
            throw new IndexOutOfBoundsException(
                String.format("Range [%d, %d) is outside of the projected text of length %d",
                    startOffset, endOffset, textLength));
        }
        if (startOffset == endOffset) return Collections.emptyList();

        List<Segment> out = new ArrayList<>();
        int absStart = trimStart + startOffset;
        int absEnd = trimStart + endOffset;

        TextNode runNode = null;
        int runSourceStart = -1;
        int runTextStart = -1;

        for (int pos = absStart; pos <= absEnd; pos++) {
            boolean ending = pos == absEnd;
            TextNode node = ending ? null : charNodes.get(pos);
            boolean mapped = node != null;
            boolean continuesRun = false;
            if (mapped && runNode == node && pos > absStart) {
                int offset = charOffsets.get(pos);
                int prevOffset = charOffsets.get(pos - 1);
                int expectedOffset;
                if (Character.isHighSurrogate(raw.charAt(pos - 1))) {
                    // the low surrogate of a supplementary code point shares the high surrogate offset
                    expectedOffset = prevOffset;
                } else {
                    // advance by the UTF-16 width of the source char that emitted the previous unit
                    String whole = runNode.getWholeText();
                    int width = prevOffset < whole.length() && Character.isHighSurrogate(whole.charAt(prevOffset)) ? 2 : 1;
                    expectedOffset = prevOffset + width;
                }
                continuesRun = offset == expectedOffset;
            }
            boolean runEnds = ending || !continuesRun;
            if (runNode != null && runEnds) {
                int runEnd = pos;
                int lastUnit = runEnd - 1;
                int lastOffset = charOffsets.get(lastUnit);
                int runSourceEnd = lastOffset + (Character.isLowSurrogate(raw.charAt(lastUnit)) ? 2 : 1);
                out.add(new Segment(runNode,
                    runSourceStart, runSourceEnd,
                    runTextStart, runEnd - trimStart));
                runNode = null;
            }
            if (mapped) {
                if (!continuesRun) {
                    runNode = node;
                    runSourceStart = charOffsets.get(pos);
                    runTextStart = pos - trimStart;
                }
            }
        }
        return out;
    }

    /**
     Map a single offset in {@link #text()} to the segment covering it.
     @param offset 0-based offset in the projected text
     @return the matching segment, or {@code null} if the offset is a synthetic boundary space (or lies past the end
      of the text)
     @see #segments(int, int)
     */
    public Segment segment(int offset) {
        resolveTrim();
        int textLength = trimEnd - trimStart;
        if (offset < 0 || offset >= textLength) return null;
        List<Segment> segments = segments(offset, offset + 1);
        return segments.isEmpty() ? null : segments.get(0);
    }

    /**
     Get the complete projection map: every mapped span of the projected text, in document order.
     @return an unmodifiable list of segments, skipping synthetic boundary spaces
     */
    public List<Segment> segments() {
        resolveTrim();
        int length = trimEnd - trimStart;
        return length == 0 ? Collections.emptyList() : Collections.unmodifiableList(segments(0, length));
    }

    @Override public String toString() {
        return text();
    }

    private void resolveTrim() {
        if (trimStart != -1) return;
        int start = 0;
        int end = raw.length();
        while (start < end && raw.charAt(start) == ' ') start++;
        while (end > start && raw.charAt(end - 1) == ' ') end--;
        trimStart = start;
        trimEnd = end;
    }

    private void appendText(TextNode textNode, String sourceText, boolean normalize) {
        if (normalize) {
            appendNormalised(textNode, sourceText, lastCharIsWhitespace(raw));
        } else {
            int len = sourceText.length();
            for (int i = 0; i < len; i++) {
                raw.append(sourceText.charAt(i));
                charNodes.add(textNode);
                charOffsets.add(i);
            }
        }
    }

    private void appendNormalised(TextNode textNode, String string, boolean stripLeading) {
        boolean lastWasWhite = false;
        boolean reachedNonWhite = false;
        int len = string.length();
        int c;
        for (int i = 0; i < len; i += Character.charCount(c)) {
            c = string.codePointAt(i);
            if (StringUtil.isActuallyWhitespace(c)) {
                if ((stripLeading && !reachedNonWhite) || lastWasWhite)
                    continue;
                raw.append(' ');
                charNodes.add(textNode);
                charOffsets.add(i);
                lastWasWhite = true;
            } else if (!StringUtil.isInvisibleChar(c)) {
                int charCount = Character.charCount(c);
                raw.appendCodePoint(c);
                for (int unit = 0; unit < charCount; unit++) {
                    charNodes.add(textNode);
                    charOffsets.add(i); // both units of a surrogate pair point at the code point offset
                }
                lastWasWhite = false;
                reachedNonWhite = true;
            }
        }
    }

    private void appendSyntheticSpace() {
        raw.append(' ');
        charNodes.add(null);
        charOffsets.add(-1);
    }

    /**
     A contiguous span of projected text that came from a single {@link TextNode}.
     */
    public static class Segment {
        private final TextNode textNode;
        private final int sourceStart;
        private final int sourceEnd;
        private final int textStart;
        private final int textEnd;

        private Segment(TextNode textNode, int sourceStart, int sourceEnd, int textStart, int textEnd) {
            this.textNode = textNode;
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.textStart = textStart;
            this.textEnd = textEnd;
        }

        /** @return the text node this projected text came from */
        public TextNode textNode() {
            return textNode;
        }

        /** @return 0-based start offset within {@link TextNode#getWholeText()}, inclusive */
        public int sourceStart() {
            return sourceStart;
        }

        /** @return 0-based end offset within {@link TextNode#getWholeText()}, exclusive */
        public int sourceEnd() {
            return sourceEnd;
        }

        /** @return the matched source substring of the text node */
        public String sourceText() {
            return textNode.getWholeText().substring(sourceStart, sourceEnd);
        }

        /** @return 0-based start offset in {@link TextProjection#text()}, inclusive */
        public int textStart() {
            return textStart;
        }

        /** @return 0-based end offset in {@link TextProjection#text()}, exclusive */
        public int textEnd() {
            return textEnd;
        }

        @Override public String toString() {
            return textNode.nodeName() + '[' + sourceStart + ".." + sourceEnd + "]=\"" + sourceText() + '"';
        }
    }

    private static final class Projector implements NodeVisitor {
        private final TextProjection projection;

        Projector(TextProjection projection) {
            this.projection = projection;
        }

        @Override public void head(Node node, int depth) {
            if (node instanceof TextNode) {
                TextNode textNode = (TextNode) node;
                boolean preserve = textNode instanceof CDataNode
                    || projection.preservePreWhitespace && Element.preserveWhitespace(textNode.parentNode);
                projection.appendText(textNode, textNode.getWholeText(), !preserve);
            } else if (node instanceof Element) {
                Element element = (Element) node;
                if (projection.raw.length() > 0
                    && Element.needsLeadingTextSeparator(element)
                    && !lastCharIsWhitespace(projection.raw))
                    projection.appendSyntheticSpace();
            }
        }

        @Override public void tail(Node node, int depth) {
            if (node instanceof Element) {
                Element element = (Element) node;
                Node next = node.nextSibling();
                if (Element.needsTrailingTextSeparator(element)
                    && (next instanceof TextNode
                        || next instanceof Element && ((Element) next).tag.isInline())
                    && !lastCharIsWhitespace(projection.raw))
                    projection.appendSyntheticSpace();
            }
        }
    }
}
