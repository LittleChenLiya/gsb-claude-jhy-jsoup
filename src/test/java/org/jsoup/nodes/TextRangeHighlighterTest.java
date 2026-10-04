package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TextRangeHighlighterTest {

    @Test
    public void textViewExcludesScriptAndStyle() {
        Document doc = Jsoup.parse("<p>Hello</p><script>var x=1;</script><style>.a{}</style><p>World</p>");
        assertEquals("HelloWorld", TextRangeHighlighter.textView(doc));
    }

    @Test
    public void textViewIsInDocumentOrder() {
        Document doc = Jsoup.parse("<div>A<span>B</span>C</div>");
        assertEquals("ABC", TextRangeHighlighter.textView(doc));
    }

    @Test
    public void highlightCrossNodePreservesStructure() {
        Document doc = Jsoup.parse("A<b>BC</b>D");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 1, 4, "m1");

        Element b = doc.select("b").first();
        assertNotNull(b);
        assertEquals("BC", b.text());
        Element markInB = b.select("mark").first();
        assertNotNull(markInB);
        assertEquals("BC", markInB.text());
        assertEquals("m1", markInB.attr(TextRangeHighlighter.MARK_ID_ATTR));
        Element markAfterB = b.nextElementSibling();
        assertNotNull(markAfterB);
        assertEquals("D", markAfterB.text());
        assertEquals("ABCD", TextRangeHighlighter.textView(doc));
        assertTrue(undo.isValid());
    }

    @Test
    public void highlightSplitsTextNode() {
        Document doc = Jsoup.parse("<p>ABCDEF</p>");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 2, 4, "m2");

        Element p = doc.select("p").first();
        assertEquals(3, p.childNodeSize());
        assertTrue(p.childNode(0) instanceof TextNode);
        assertEquals("AB", ((TextNode) p.childNode(0)).getWholeText());
        Element mark = (Element) p.childNode(1);
        assertEquals("mark", mark.tagName());
        assertEquals("CD", mark.text());
        assertTrue(p.childNode(2) instanceof TextNode);
        assertEquals("EF", ((TextNode) p.childNode(2)).getWholeText());
        assertEquals("ABCDEF", TextRangeHighlighter.textView(doc));
        assertTrue(undo.isValid());
    }

    @Test
    public void highlightWholeTextNode() {
        Document doc = Jsoup.parse("<p>ABCD</p>");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 0, 4, "m3");

        Element p = doc.select("p").first();
        assertEquals(1, p.childNodeSize());
        Element mark = (Element) p.childNode(0);
        assertEquals("mark", mark.tagName());
        assertEquals("ABCD", mark.text());
        assertTrue(undo.isValid());
    }

    @Test
    public void undoRestoresOriginalTextAndOrder() {
        Document doc = Jsoup.parse("A<b>BC</b>D");
        String originalHtml = doc.body().html();
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 1, 4, "m1");
        assertNotEquals(originalHtml, doc.body().html());

        undo.undo();

        assertEquals(originalHtml, doc.body().html());
        assertEquals("ABCD", TextRangeHighlighter.textView(doc));
        assertFalse(undo.isValid());
    }

    @Test
    public void undoSplitsRestoresOriginalTextAndOrder() {
        Document doc = Jsoup.parse("<p>ABCDEF</p>");
        String originalHtml = doc.body().html();
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 2, 4, "m2");
        assertNotEquals(originalHtml, doc.body().html());

        undo.undo();

        assertEquals(originalHtml, doc.body().html());
        assertEquals("ABCDEF", TextRangeHighlighter.textView(doc));
        Element p = doc.select("p").first();
        assertEquals(1, p.childNodeSize());
        assertEquals("ABCDEF", ((TextNode) p.childNode(0)).getWholeText());
    }

    @Test
    public void undoRejectsWhenMarkContentChanged() {
        Document doc = Jsoup.parse("A<b>BC</b>D");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 1, 4, "m1");

        Element mark = doc.select("mark").first();
        mark.text("CHANGED");

        assertFalse(undo.isValid());
        assertThrows(IllegalStateException.class, undo::undo);
        assertEquals("CHANGED", mark.text());
    }

    @Test
    public void undoRejectsWhenMarkRemoved() {
        Document doc = Jsoup.parse("A<b>BC</b>D");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 1, 4, "m1");

        doc.select("mark").first().remove();

        assertFalse(undo.isValid());
        assertThrows(IllegalStateException.class, undo::undo);
    }

    @Test
    public void invalidRangeLeavesDomUnchanged() {
        Document doc = Jsoup.parse("<p>ABCDEF</p>");
        String originalHtml = doc.body().html();

        assertThrows(IllegalArgumentException.class, () -> TextRangeHighlighter.highlight(doc, 0, 100, "m"));
        assertThrows(IllegalArgumentException.class, () -> TextRangeHighlighter.highlight(doc, 4, 2, "m"));
        assertThrows(IllegalArgumentException.class, () -> TextRangeHighlighter.highlight(doc, -1, 2, "m"));

        assertEquals(originalHtml, doc.body().html());
        assertEquals("ABCDEF", TextRangeHighlighter.textView(doc));
    }

    @Test
    public void rangeCuttingSurrogatePairIsRejected() {
        Document doc = Jsoup.parse("<p>A\uD83D\uDE00B</p>");
        String originalHtml = doc.body().html();

        assertThrows(IllegalArgumentException.class, () -> TextRangeHighlighter.highlight(doc, 0, 2, "m"));
        assertThrows(IllegalArgumentException.class, () -> TextRangeHighlighter.highlight(doc, 2, 4, "m"));

        assertEquals(originalHtml, doc.body().html());
    }

    @Test
    public void rangeNotCuttingSurrogatePairIsAccepted() {
        Document doc = Jsoup.parse("<p>A\uD83D\uDE00B</p>");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 1, 3, "m");
        assertTrue(undo.isValid());
        Element mark = doc.select("mark").first();
        assertEquals("\uD83D\uDE00", mark.text());
        undo.undo();
        assertEquals("A\uD83D\uDE00B", TextRangeHighlighter.textView(doc));
    }

    @Test
    public void highlightMultipleSegments() {
        Document doc = Jsoup.parse("<p>ABC</p><p>DEF</p>");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 1, 5, "m");

        Elements marks = doc.select("mark");
        assertEquals(2, marks.size());
        assertEquals("BC", marks.get(0).text());
        assertEquals("DE", marks.get(1).text());
        assertEquals("ABCDEF", TextRangeHighlighter.textView(doc));
        assertTrue(undo.isValid());
    }

    @Test
    public void emptyRangeHighlightsNothing() {
        Document doc = Jsoup.parse("<p>ABC</p>");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 2, 2, "m");
        assertEquals(0, doc.select("mark").size());
        assertTrue(undo.isValid());
    }

    @Test
    public void highlightWithAnchorStructure() {
        Document doc = Jsoup.parse("<a>link text</a>");
        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 2, 6, "m");

        Element a = doc.select("a").first();
        assertNotNull(a);
        assertEquals("link text", a.text());
        Element mark = a.select("mark").first();
        assertNotNull(mark);
        assertEquals("nk t", mark.text());
        assertEquals("link text", TextRangeHighlighter.textView(doc));
        assertTrue(undo.isValid());
    }
}
