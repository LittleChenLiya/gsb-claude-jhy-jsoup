package org.jsoup.nodes;

import org.jsoup.helper.Validate;

/**
 A TextSource maps a range within the plain text produced by a {@link TextProjection} back to the originating
 {@link TextNode} in the parsed DOM.
 <p>
 A TextSource may cover only part of a TextNode, as the text projection can drop or collapse characters while
 normalizing whitespace (for example, {@code "a &nbsp;&nbsp;b"} is projected as {@code "a b"}, and the projected
 {@code ' '} maps to the first whitespace character in the original node).</p>
 <p>
 Offsets are Java {@code char} offsets (UTF-16 code units), consistent with {@link String#charAt(int)}. Supplementary
 code points (such as many emoji) occupy two {@code char}s in both the projected text and the source text.</p>

 @see TextProjection
 @see Element#textProjection(boolean)
 @since 1.24.1
 */
public class TextSource {
    private final TextNode node;
    private final int start;
    private final int end;

    TextSource(TextNode node, int start, int end) {
        Validate.isTrue(start >= 0 && end >= start, "Invalid source offsets");
        this.node = node;
        this.start = start;
        this.end = end;
    }

    /**
     Get the TextNode that this range of projected text came from.
     @return the originating TextNode
     */
    public TextNode node() {
        return node;
    }

    /**
     Get the start offset (inclusive) of this projected text within the node's
     {@link TextNode#getWholeText() whole text}.
     @return the 0-based start char offset
     */
    public int start() {
        return start;
    }

    /**
     Get the end offset (exclusive) of this projected text within the node's
     {@link TextNode#getWholeText() whole text}.
     @return the 0-based end char offset
     */
    public int end() {
        return end;
    }

    /**
     Get the original, un-normalized substring of the source TextNode covered by this TextSource.
     @return the source substring
     */
    public String text() {
        return node.getWholeText().substring(start, end);
    }

    /**
     Get the length of this source range in Java {@code char}s.
     @return {@link #end()} - {@link #start()}
     */
    public int length() {
        return end - start;
    }

    /**
     Test if this is an empty source range (a zero-length projection point sitting inside a TextNode).
     @return true if {@link #start()} equals {@link #end()}
     */
    public boolean isEmpty() {
        return start == end;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextSource)) return false;
        TextSource that = (TextSource) o;
        return start == that.start && end == that.end && node.equals(that.node);
    }

    @Override public int hashCode() {
        int result = node.hashCode();
        result = 31 * result + start;
        result = 31 * result + end;
        return result;
    }

    @Override public String toString() {
        return node.nodeName() + '[' + start + ',' + end + "]: '" + text() + '\'';
    }
}
