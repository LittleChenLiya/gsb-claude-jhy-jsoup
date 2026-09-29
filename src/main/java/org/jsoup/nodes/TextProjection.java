package org.jsoup.nodes;

import org.jsoup.helper.Validate;
import org.jsoup.internal.StringUtil;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 A TextProjection is the {@linkplain Element#text() plain text} of an Element, together with a projection of every
 character back to the {@link TextNode} it originated from. It is intended for use cases such as search-term
 highlighting, where a match found in the normalized plain text must be applied back onto the original DOM nodes.
 <h3>Text rules</h3>
 The projected text follows the same rules as {@link Element#text()}, with one explicit option for
 whitespace-preserving elements:
 <ol>
  <li><b>Whitespace collapsing.</b> Runs of whitespace characters (space, {@code \t}, {@code \n}, {@code \f},
      {@code \r}, and non-breaking space U+00A0) are collapsed to a single space, and leading and trailing whitespace
      is trimmed. Invisible formatting characters (zero-width space U+200B and soft hyphen U+00AD) are omitted.
      Other characters, including supplementary code points such as emoji, are emitted unchanged.</li>
  <li><b>Block boundaries.</b> Block and other text-boundary elements (such as {@code <div>}, {@code <p>}, and
      {@code <br>}) insert a separating space between adjacent content when it would otherwise run together; no
      newline is inserted. Newline characters inside text nodes are collapsed per the whitespace rule above. This is
      exactly the behaviour of {@link Element#text()}.</li>
  <li><b>Script and style.</b> The contents of {@code <script>} and {@code <style>} elements (and other data nodes)
      are never included.</li>
  <li><b>Preformatted elements.</b> Inside whitespace-preserving elements (such as {@code <pre>}, {@code <textarea>},
      and {@code <listing>}), text is emitted verbatim by default, as with {@link Element#text()}. When
      {@code preservePreWhitespace} is set to {@code false}, the whitespace in those elements is collapsed using the
      same rule as for ordinary elements. {@link CDataNode} content is always emitted verbatim.</li>
 </ol>
 <h3>Source mapping</h3>
 Each character of {@link #text()} that comes from a TextNode can be mapped back to a range within that node's
 {@link TextNode#getWholeText() whole (decoded) text}. Separating spaces introduced solely by the block-boundary rule
 do not come from any TextNode and are not represented in {@link #sources()} or {@link #mapRange(int, int)}. When
 whitespace is collapsed, the emitted space maps to the first whitespace character of the original run.
 <p>All offsets are Java {@code char} offsets (UTF-16 code units), consistent with {@link String} indexing; a
 supplementary code point such as an emoji therefore occupies two offsets.</p>

 @see TextSource
 @see Element#textProjection()
 @see Element#textProjection(boolean)
 @since 1.24.1
 */
public class TextProjection {
    private final String text;
    private final List<Mark> marks;

    private TextProjection(Element element, boolean preservePreWhitespace) {
        StringBuilder accum = StringUtil.borrowBuilder();
        List<Mark> rawMarks = new ArrayList<>();
        new Projector(accum, rawMarks, preservePreWhitespace).traverse(element);

        int rawLength = accum.length();
        int start = 0;
        while (start < rawLength && accum.charAt(start) <= ' ') start++;
        int end = rawLength;
        while (end > start && accum.charAt(end - 1) <= ' ') end--;

        this.text = accum.substring(start, end);
        StringUtil.releaseBuilder(accum);
        this.marks = new ArrayList<>(this.text.length());
        for (int i = start; i < end; i++) {
            marks.add(rawMarks.get(i));
        }
    }

    static TextProjection of(Element element, boolean preservePreWhitespace) {
        return new TextProjection(element, preservePreWhitespace);
    }

    /**
     Get the projected plain text. This is equal to {@link Element#text()} for the default option.
     @return the normalized plain text
     */
    public String text() {
        return text;
    }

    /**
     Get the length of the projected plain text, in Java {@code char}s.
     @return {@link #text()}.length()
     */
    public int length() {
        return text.length();
    }

    /**
     Get the TextNode source of the character at the given offset, if any.
     @param index the 0-based char offset within {@link #text()}
     @return the source of that character, or {@code null} if it was synthesized by a block-boundary rule
     */
    public TextSource sourceAt(int index) {
        Validate.isTrue(index >= 0 && index < text.length(), "Index out of bounds");
        Mark mark = marks.get(index);
        return mark == null ? null : new TextSource(mark.node, mark.start, mark.end);
    }

    /**
     Get every distinct TextNode range that contributes to the projected text, in projected-text order. Characters
     synthesized by block-boundary rules are not represented.
     @return an unmodifiable list of source ranges
     */
    public List<TextSource> sources() {
        return mapRange(0, text.length());
    }

    /**
     Map a range of the projected plain text back to the originating TextNode ranges. The returned ranges are in
     projected-text order, and concatenating their projected characters reproduces the requested range, apart from
     any block-boundary separator spaces that have no source node. Adjacent characters from the same TextNode are
     coalesced into one range; a match that crosses TextNodes (such as a search highlight spanning inline markup) is
     therefore returned as one range per node involved.
     @param start the start offset, inclusive
     @param end the end offset, exclusive; may equal {@code start} for an empty range, which returns an empty list
     @return an unmodifiable, non-null list of source ranges
     */
    public List<TextSource> mapRange(int start, int end) {
        Validate.isTrue(start >= 0 && end >= start && end <= text.length(), "Range out of bounds");
        if (start == end) return Collections.emptyList();

        List<TextSource> sources = new ArrayList<>();
        int lastMarkIndex = -2;
        for (int i = start; i < end; i++) {
            Mark mark = marks.get(i);
            if (mark == null) {
                continue;
            }
            if (i == lastMarkIndex + 1 && !sources.isEmpty()) {
                TextSource last = sources.get(sources.size() - 1);
                if (last.node() == mark.node && last.end() == mark.start) {
                    sources.set(sources.size() - 1, new TextSource(mark.node, last.start(), mark.end));
                    lastMarkIndex = i;
                    continue;
                }
            }
            sources.add(new TextSource(mark.node, mark.start, mark.end));
            lastMarkIndex = i;
        }
        return Collections.unmodifiableList(sources);
    }

    @Override public String toString() {
        return text;
    }

    /**
     Records the TextNode origin of a single projected char (or char of a supplementary code point).
     */
    private static final class Mark {
        private final TextNode node;
        private final int start;
        private final int end;

        Mark(TextNode node, int start, int end) {
            this.node = node;
            this.end = end;
            this.start = start;
        }
    }

    /**
     Traverses the DOM mirroring Element.text(), while recording the source of every emitted character.
     */
    private static final class Projector implements NodeVisitor {
        private final StringBuilder accum;
        private final List<Mark> marks;
        private final boolean preservePreWhitespace;

        Projector(StringBuilder accum, List<Mark> marks, boolean preservePreWhitespace) {
            this.accum = accum;
            this.marks = marks;
            this.preservePreWhitespace = preservePreWhitespace;
        }

        @Override public void head(Node node, int depth) {
            if (node instanceof TextNode) {
                appendText((TextNode) node);
            } else if (node instanceof Element) {
                Element element = (Element) node;
                if (accum.length() > 0 && Element.needsLeadingTextSeparator(element) &&
                    !TextNode.lastCharIsWhitespace(accum)) {
                    appendSeparator();
                }
            }
        }

        @Override public void tail(Node node, int depth) {
            if (node instanceof Element) {
                Element element = (Element) node;
                Node next = node.nextSibling();
                if (Element.needsTrailingTextSeparator(element) &&
                    (next instanceof TextNode || next instanceof Element && ((Element) next).tag.isInline()) &&
                    !TextNode.lastCharIsWhitespace(accum)) {
                    appendSeparator();
                }
            }
        }

        private void appendSeparator() {
            accum.append(' ');
            marks.add(null);
        }

        private void appendText(TextNode textNode) {
            String text = textNode.getWholeText();
            if (textNode instanceof CDataNode || (preservePreWhitespace &&
                Element.preserveWhitespace(textNode.parentNode()))) {
                appendRawText(textNode, text);
            } else {
                appendNormalisedText(textNode, text, TextNode.lastCharIsWhitespace(accum));
            }
        }

        private void appendRawText(TextNode textNode, String text) {
            accum.append(text);
            for (int i = 0; i < text.length(); i++) {
                marks.add(new Mark(textNode, i, i + 1));
            }
        }

        /**
         Mirrors StringUtil.appendNormalisedWhitespace, recording source offsets as characters are emitted.
         */
        private void appendNormalisedText(TextNode textNode, String text, boolean stripLeading) {
            boolean lastWasWhite = false;
            boolean reachedNonWhite = false;
            int len = text.length();
            int c = 0;
            for (int i = 0; i < len; i += Character.charCount(c)) {
                c = text.codePointAt(i);
                int charCount = Character.charCount(c);
                if (StringUtil.isActuallyWhitespace(c)) {
                    if ((stripLeading && !reachedNonWhite) || lastWasWhite)
                        continue;
                    accum.append(' ');
                    marks.add(new Mark(textNode, i, i + charCount));
                    lastWasWhite = true;
                } else if (!StringUtil.isInvisibleChar(c)) {
                    accum.appendCodePoint(c);
                    for (int k = 0; k < charCount; k++) {
                        marks.add(new Mark(textNode, i + k, i + k + 1));
                    }
                    lastWasWhite = false;
                    reachedNonWhite = true;
                }
            }
        }
    }
}
