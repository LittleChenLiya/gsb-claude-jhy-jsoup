package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for {@link TextProjection}.
 */
public class TextProjectionTest {
    private static final String crossNodeHtml =
        "<div>\n<p>你<b>好</b> 世界</p>\n<div><span>尾</span></div>\n</div>";

    @Test void projectsTextLikeElementText() {
        Document doc = Jsoup.parse(crossNodeHtml);
        Element body = doc.body();
        TextProjection projection = body.textProjection();

        assertEquals(body.text(), projection.text());
        assertEquals("你好 世界 尾", projection.text());
    }

    @Test void mapsSearchAcrossNodes() {
        Document doc = Jsoup.parse(crossNodeHtml);
        Element p = doc.selectFirst("p");
        TextProjection projection = p.textProjection();

        assertEquals("你好 世界", projection.text());
        int index = projection.text().indexOf("好 世");
        assertTrue(index >= 0);

        List<TextSource> sources = projection.mapRange(index, index + "好 世".length());
        assertEquals(2, sources.size());

        TextNode hao = (TextNode) p.expectFirst("b").childNode(0);
        TextNode world = (TextNode) p.textNodes().get(p.textNodes().size() - 1);
        assertEquals("好", sources.get(0).text());
        assertSame(hao, sources.get(0).node());
        assertEquals(" 世", sources.get(1).text());
        assertSame(world, sources.get(1).node());
        assertEquals(0, sources.get(1).start());
        assertEquals(2, sources.get(1).end());
    }

    @Test void mapsAcrossLineBreakLikeTextDoes() {
        // br is rendered as a space by text(), and the separator has no TextNode source
        Document doc = Jsoup.parse("<p>你<b>好</b><br>世界</p>");
        Element p = doc.selectFirst("p");
        TextProjection projection = p.textProjection();

        assertEquals(p.text(), projection.text());
        assertEquals("你好 世界", projection.text());
        assertNull(projection.sourceAt(2)); // the synthetic space produced by <br>

        int index = projection.text().indexOf("好 世");
        List<TextSource> sources = projection.mapRange(index, index + 3);
        assertEquals(2, sources.size());
        assertEquals("好", sources.get(0).text());
        assertEquals("世", sources.get(1).text());
        assertNotSame(sources.get(0).node(), sources.get(1).node());
    }

    @Test void everyCharacterHasSourceOrIsSeparator() {
        Document doc = Jsoup.parse("<div>你<b>好</b> 世界</div>");
        TextProjection projection = doc.body().textProjection();
        String text = projection.text();
        StringBuilder rebuilt = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            TextSource source = projection.sourceAt(i);
            assertNotNull(source, "no synthetic separators expected for inline text at " + i);
            rebuilt.append(text, i, i + 1);
        }
        assertEquals(text, rebuilt.toString());
    }

    @Test void blockSeparatorsHaveNoSource() {
        Document doc = Jsoup.parse("<div>a</div><div>b</div>");
        TextProjection projection = doc.body().textProjection();
        assertEquals(doc.body().text(), projection.text());
        assertEquals("a b", projection.text());
        assertNull(projection.sourceAt(1));

        List<TextSource> sources = projection.mapRange(0, projection.length());
        assertEquals(2, sources.size());
        assertEquals("a", sources.get(0).text());
        assertEquals("b", sources.get(1).text());
    }

    @Test void nestedBlocksProjectLikeText() {
        String html = "<div>One<div>Two<span>Three</span></div>Four</div>";
        Document doc = Jsoup.parse(html);
        Element body = doc.body();
        assertEquals(body.text(), body.textProjection().text());
    }

    @Test void decodesEntities() {
        Document doc = Jsoup.parse("<p>A &amp; B&lt;C &copy;</p>");
        TextProjection projection = doc.body().textProjection();
        assertEquals(doc.body().text(), projection.text());
        assertEquals("A & B<C ©", projection.text());
        for (TextSource source : projection.sources()) {
            assertFalse(source.text().contains("amp"));
        }
    }

    @Test void handlesEmojiCodePoints() {
        String emoji = "😀🎉";
        Document doc = Jsoup.parse("<p>hi <b>" + emoji + "</b> bye</p>");
        TextProjection projection = doc.body().textProjection();
        assertEquals(doc.body().text(), projection.text());

        int index = projection.text().indexOf(emoji);
        List<TextSource> sources = projection.mapRange(index, index + emoji.length());
        assertEquals(1, sources.size());
        assertEquals(emoji, sources.get(0).text());
        // supplementary code points are two UTF-16 chars and must not split a code point
        assertEquals(0, sources.get(0).start());
        assertEquals(emoji.length(), sources.get(0).end());
    }

    @Test void collapsesConsecutiveWhitespace() {
        Document doc = Jsoup.parse("<p>  foo\t\n bar  &nbsp;x</p>");
        TextProjection projection = doc.body().textProjection();
        assertEquals(doc.body().text(), projection.text());
        assertEquals("foo bar x", projection.text());

        TextNode node = doc.selectFirst("p").textNodes().get(0);
        List<TextSource> all = projection.sources();
        // leading whitespace is trimmed, and collapsed whitespace maps to the first char of the run
        TextSource first = all.get(0);
        assertSame(node, first.node());
        assertEquals("foo", projection.text().substring(0, 3));
        assertEquals(2, first.start()); // index of 'f' in "  foo\t\n bar  \u00a0x"
    }

    @Test void preWhitespaceKeptByDefault() {
        String html = "<pre>  line\n  two  </pre>";
        Document doc = Jsoup.parse(html);
        Element pre = doc.selectFirst("pre");
        assertEquals(pre.text(), pre.textProjection().text());
        assertEquals("line\n  two", pre.textProjection().text()); // pre's leading newline is consumed by the parser

        TextProjection collapsed = pre.textProjection(false);
        assertEquals("line two", collapsed.text());
    }

    @Test void scriptAndStyleAreNotProjected() {
        String html = "<p>visible<script>var secret = 'hidden';</script>" +
            "<style>.hidden { color: red; }</style>text</p>";
        Document doc = Jsoup.parse(html);
        Element body = doc.body();
        assertEquals(body.text(), body.textProjection().text());
        assertEquals("visible text", body.textProjection().text()); // the script boundary adds a text separator
        for (TextSource source : body.textProjection().sources()) {
            assertFalse(source.text().contains("secret"));
            assertFalse(source.text().contains("hidden"));
            assertFalse(source.text().contains("red"));
        }
    }

    @Test void emptyRangeReturnsEmptyList() {
        Document doc = Jsoup.parse("<p>hello</p>");
        TextProjection projection = doc.body().textProjection();
        assertEquals(0, projection.mapRange(2, 2).size());
        assertEquals(0, projection.mapRange(0, 0).size());
        assertEquals(0, Jsoup.parse("<p></p>").body().textProjection().length());
        assertEquals(0, Jsoup.parse("<p></p>").body().textProjection().sources().size());
    }

    @Test void rejectsOutOfBoundsRanges() {
        Document doc = Jsoup.parse("<p>hello</p>");
        TextProjection projection = doc.body().textProjection();
        assertThrows(IllegalArgumentException.class, () -> projection.mapRange(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> projection.mapRange(2, 1));
        assertThrows(IllegalArgumentException.class, () -> projection.mapRange(0, 6));
        assertThrows(IllegalArgumentException.class, () -> projection.sourceAt(5));
    }
}
