package org.jsoup.nodes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.jsoup.Jsoup.parse;
import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for the {@link TextProjection} plain-text mapping API.
 */
class TextProjectionTest {

    private static TextProjection project(String html) {
        return parse(html).body().textProject();
    }

    private static void assertSegment(TextProjection.Segment segment, TextNode node,
                                      String sourceText, int textStart, int textEnd) {
        assertSame(node, segment.textNode());
        assertEquals(sourceText, segment.sourceText());
        assertEquals(node.getWholeText().substring(segment.sourceStart(), segment.sourceEnd()), sourceText);
        assertEquals(textStart, segment.textStart());
        assertEquals(textEnd, segment.textEnd());
    }

    @Test void projectsSimpleTextWithSourceMapping() {
        Element body = parse("<p>Hello</p>").body();
        TextProjection projection = body.textProject();

        assertEquals("Hello", projection.text());
        assertEquals(body.text(), projection.text());
        TextNode node = body.expectFirst("p").textNodes().get(0);
        List<TextProjection.Segment> segments = projection.segments();
        assertEquals(1, segments.size());
        assertSegment(segments.get(0), node, "Hello", 0, 5);
    }

    @Test void searchAcrossNodesMapsBackToEachTextNode() {
        // the acceptance case: "你<b>好</b> 世界" -- searching "好 世" crosses the <b> boundary
        Element body = parse("<p>你<b>好</b> 世界</p>").body();
        TextProjection projection = body.textProject();

        String text = projection.text();
        assertEquals("你好 世界", text);
        assertEquals(body.text(), text);

        int hit = text.indexOf("好 世");
        assertEquals(1, hit);
        List<TextProjection.Segment> segments = projection.segments(hit, hit + 3);
        assertEquals(2, segments.size());

        TextNode boldNode = body.expectFirst("b").textNodes().get(0);
        TextNode tailNode = body.expectFirst("p").textNodes().get(1);
        assertEquals("好", boldNode.getWholeText());
        assertEquals(" 世界", tailNode.getWholeText());
        assertSegment(segments.get(0), boldNode, "好", 1, 2);
        // the space is the leading whitespace of the trailing text node (collapsed to one)
        assertSegment(segments.get(1), tailNode, " 世", 2, 4);
    }

    @Test void newlinesAndNestedBlocksMatchTextBehavior() {
        String html = "<div>你<b>好</b>\n 世<div>界</div>x</div>";
        Element body = parse(html).body();
        TextProjection projection = body.textProject();

        assertEquals(body.text(), projection.text());
        // raw "你好 世" + synthetic space before nested block ("界") + trailing "x"
        assertEquals("你好 世 界 x", projection.text());

        // "界" sits inside the nested div; the space before it is a synthetic block-boundary space
        String text = projection.text();
        int nestedHit = text.indexOf("界");
        assertEquals(5, nestedHit);
        List<TextProjection.Segment> nested = projection.segments(nestedHit, nestedHit + 1);
        assertEquals(1, nested.size());
        TextNode nestedTextNode = body.expectFirst("div div").textNodes().get(0);
        assertSegment(nested.get(0), nestedTextNode, "界", 5, 6);

        // the synthetic space before the nested block maps to no source node
        int boundarySpace = nestedHit - 1;
        assertEquals(' ', text.charAt(boundarySpace));
        assertNull(projection.segment(boundarySpace));
        assertTrue(projection.segments(boundarySpace, boundarySpace + 1).isEmpty());

        // a range spanning that boundary returns the two adjacent mapped segments but no synthetic segment
        List<TextProjection.Segment> across = projection.segments(nestedHit - 2, nestedHit + 1); // "世 界"
        assertEquals(2, across.size());
        assertEquals("世", across.get(0).sourceText());
        assertEquals("界", across.get(1).sourceText());
    }

    @Test void acceptanceCaseWithNewlineAndNestedBlocks() {
        // "你<b>好</b> 世界" plus a newline and a nested block; the cross-node search for "好 世" must map back
        Element body = parse("<div>你<b>好</b>\n 世界<div>嵌套</div>尾</div>").body();
        TextProjection projection = body.textProject();

        assertEquals(body.text(), projection.text());
        String text = projection.text();
        int hit = text.indexOf("好 世");
        assertTrue(hit >= 0, "cross-node search term must be present in: " + text);

        List<TextProjection.Segment> segments = projection.segments(hit, hit + 3);
        TextNode boldNode = body.expectFirst("b").textNodes().get(0);
        TextNode worldNode = body.expectFirst("div").textNodes().get(1);
        // first segment is 好 in <b>; then the collapsed leading space, and 世 (separate in the source node
        // because the newline and a space were collapsed)
        assertSegment(segments.get(0), boldNode, "好", hit, hit + 1);
        assertEquals("\n 世界", worldNode.getWholeText());
        TextProjection.Segment space = segments.get(1);
        TextProjection.Segment shi = segments.get(segments.size() - 1);
        assertSame(worldNode, space.textNode());
        assertEquals("\n", space.sourceText()); // the collapsed separator originated from the newline
        assertSegment(shi, worldNode, "世", hit + 2, hit + 3);
    }

    @Test void decodesEntitiesAndHandlesEmoji() {
        Element body = parse("<p>&amp; &#128512; a&copy;b</p>").body();
        TextProjection projection = body.textProject();

        String expected = "& \uD83D\uDE00 a\u00A9b";
        assertEquals(expected, projection.text());
        assertEquals(body.text(), projection.text());

        // the emoji occupies two UTF-16 units; source offsets are UTF-16 indices too, so it spans 2..4
        int emoji = 2; // after "& "
        TextNode node = body.expectFirst("p").textNodes().get(0);
        TextProjection.Segment firstUnit = projection.segment(emoji);
        TextProjection.Segment secondUnit = projection.segment(emoji + 1);
        assertSame(node, firstUnit.textNode());
        assertSame(node, secondUnit.textNode());
        // the high surrogate is one unit; the low surrogate resolves to the same supplementary code point
        assertEquals(2, firstUnit.sourceStart());
        assertEquals(3, firstUnit.sourceEnd());
        assertEquals(2, secondUnit.sourceStart());
        assertEquals(4, secondUnit.sourceEnd());
        // querying the whole emoji returns it as one segment spanning the surrogate pair
        List<TextProjection.Segment> wholeEmoji = projection.segments(emoji, emoji + 2);
        assertEquals(1, wholeEmoji.size());
        assertSegment(wholeEmoji.get(0), node, "\uD83D\uDE00", emoji, emoji + 2);
    }

    @Test void collapsesConsecutiveWhitespaceAndDropsInvisibleChars() {
        Element body = parse("<p>a\n\t b\u200b\u00ad c   d</p>").body();
        TextProjection projection = body.textProject();

        assertEquals("a b c d", projection.text());
        assertEquals(body.text(), projection.text());

        // every mapped character resolves inside the single source text node
        TextNode node = body.expectFirst("p").textNodes().get(0);
        for (TextProjection.Segment segment : projection.segments()) {
            assertSame(node, segment.textNode());
        }
        // "b" is still discoverable at its source offset even though invisible chars followed it
        TextProjection.Segment b = projection.segment(projection.text().indexOf("b"));
        assertEquals("b", b.sourceText());
    }

    @Test void preWhitespaceIsPreservedByDefaultAndOptionallyNormalized() {
        String html = "<pre>line1\n line2  x</pre>";
        Element body = parse(html).body();

        TextProjection preserving = body.textProject();
        assertTrue(preserving.preservePreWhitespace());
        assertEquals("line1\n line2  x", preserving.text());
        assertEquals(body.text(), preserving.text());

        TextProjection normalizing = body.textProject(false);
        assertFalse(normalizing.preservePreWhitespace());
        assertEquals("line1 line2 x", normalizing.text());

        // verbatim pre mapping keeps source offsets identical
        TextNode preNode = body.expectFirst("pre").textNodes().get(0);
        TextProjection.Segment all = preserving.segments().get(0);
        assertSame(preNode, all.textNode());
        assertEquals(0, all.sourceStart());
        assertEquals(preNode.getWholeText().length(), all.sourceEnd());
    }

    @Test void doesNotOutputScriptOrStyleContent() {
        Element body = parse("<p>a<script>var x='b';</script><style>.c{color:red}</style>b</p>").body();
        TextProjection projection = body.textProject();

        // inline-style removal leaves a synthetic text-boundary separator, exactly as Element.text()
        assertEquals("a b", projection.text());
        assertEquals(body.text(), projection.text());
        for (TextProjection.Segment segment : projection.segments()) {
            Element parent = segment.textNode().parent();
            assertFalse(parent.nameIs("script") || parent.nameIs("style"));
        }
    }

    @Test void emptyRangesAndEmptyElements() {
        TextProjection empty = parse("<div></div>").body().expectFirst("div").textProject();
        assertEquals("", empty.text());
        assertTrue(empty.segments().isEmpty());
        assertTrue(empty.segments(0, 0).isEmpty());
        assertNull(empty.segment(0));

        TextProjection nonEmpty = project("<p>abc</p>");
        assertTrue(nonEmpty.segments(1, 1).isEmpty());
    }

    @Test void trimsLeadingAndTrailingWhitespaceAndMapsCorrectly() {
        Element body = parse("<p>   Hello  </p>").body();
        TextProjection projection = body.textProject();
        assertEquals("Hello", projection.text());
        assertEquals(body.text(), projection.text());

        TextNode node = body.expectFirst("p").textNodes().get(0);
        TextProjection.Segment h = projection.segment(0);
        assertSame(node, h.textNode());
        assertEquals("H", h.sourceText());
        assertEquals(3, h.sourceStart());
    }

    @Test void rejectsOutOfRangeRanges() {
        TextProjection projection = project("<p>abc</p>");
        assertThrows(IndexOutOfBoundsException.class, () -> projection.segments(-1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> projection.segments(2, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> projection.segments(2, 1));
    }

    @Test void projectedTextAlwaysMatchesTextAcrossVariedHtml() {
        String[] htmls = {
            "",
            "plain",
            "<p>one</p><p>two</p>",
            "<div><h1>Title</h1><p>Body<br>next line</p></div>",
            "a<b>b</b><i>c</i>d",
            "<span>a<div>b</div>c</span>",
            "<table><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>",
            "<pre>  keep   \n me </pre><p>after</p>",
            "<script>var x = 1;</script>real<style>.x{}</style>text",
            "<p>leading",
            "&amp;&lt;&gt;&quot;&copy;&#128512;&#128128;",
            "<p>  multiple   spaces\n\there\t\t </p>",
            "<ul><li>one<li>two<li>three</ul>",
            "<div>a</div>b<div>c</div>",
            "<b>bold</b> and <i>italic</i> &amp; <a href=x>link</a>"
        };
        for (String html : htmls) {
            Document doc = parse(html);
            Element body = doc.body();
            TextProjection projection = body.textProject();
            assertEquals(body.text(), projection.text(), "html: " + html);
            assertSegmentsReconstructText(projection);
        }
    }

    @Test void segmentsReconstructProjectedTextExceptSyntheticSpaces() {
        TextProjection projection = project("<p>a</p><p>b</p>");
        assertEquals("a b", projection.text());
        assertSegmentsReconstructText(projection);
        // the gap between the two blocks is exactly the synthetic space
        List<TextProjection.Segment> segments = projection.segments();
        assertEquals(2, segments.size());
        assertEquals(1, segments.get(0).textEnd());
        assertEquals(2, segments.get(1).textStart());
    }

    @Test void surrogatePairInsideLargerNodeMapsAsOneSourceCodePoint() {
        Element body = parse("<p>x&#128512;y</p>").body();
        TextProjection projection = body.textProject();
        assertEquals("x\uD83D\uDE00y", projection.text());
        TextNode node = body.expectFirst("p").textNodes().get(0);

        // query range covering the whole emoji: x=0, emoji high=1 low=2, y=3
        List<TextProjection.Segment> emojiSegments = projection.segments(1, 3);
        assertEquals(1, emojiSegments.size());
        assertSegment(emojiSegments.get(0), node, "\uD83D\uDE00", 1, 3);
        assertEquals(1, emojiSegments.get(0).sourceStart());
        assertEquals(3, emojiSegments.get(0).sourceEnd()); // code point occupies 2 UTF-16 units

        // range from x through y stays one contiguous segment
        List<TextProjection.Segment> whole = projection.segments(0, 4);
        assertEquals(1, whole.size());
        assertSegment(whole.get(0), node, "x\uD83D\uDE00y", 0, 4);
    }

    /** Concatenating every segment's projected slice must reproduce the projected text, apart from synthetic
     * spaces, and each slice must equal the claimed source substring. */
    private static void assertSegmentsReconstructText(TextProjection projection) {
        StringBuilder rebuilt = new StringBuilder();
        int cursor = 0;
        for (TextProjection.Segment segment : projection.segments()) {
            while (cursor < segment.textStart()) {
                assertEquals(' ', projection.text().charAt(cursor), "only synthetic spaces may be unmapped");
                rebuilt.append(' ');
                cursor++;
            }
            String slice = projection.text().substring(segment.textStart(), segment.textEnd());
            // source range must be valid (normalization may change the actual characters)
            assertTrue(segment.sourceStart() >= 0 && segment.sourceEnd() <= segment.textNode().getWholeText().length()
                && segment.sourceStart() < segment.sourceEnd());
            rebuilt.append(slice);
            cursor = segment.textEnd();
        }
        while (cursor < projection.text().length()) {
            assertEquals(' ', projection.text().charAt(cursor));
            rebuilt.append(' ');
            cursor++;
        }
        assertEquals(projection.text(), rebuilt.toString());
    }
}
