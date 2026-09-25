package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SourceEditorTest {
    static Parser TrackingHtmlParser = Parser.htmlParser().setTrackPosition(true);

    private static TextNode textOf(Element el) {
        return (TextNode) el.childNode(0);
    }

    @Test void attributeEditPreservesUnmodifiedSource() {
        String html = "<div  id='one'   data-x=\"a&amp;b\"  class=Foo>Text &amp; more</div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element div = doc.expectFirst("div");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.setAttribute(div, "data-x", "c&d");
        String out = editor.commit();

        // only the edited attribute changed; quote style, case, entities, and irregular whitespace elsewhere are untouched
        assertEquals("<div  id='one'   data-x=\"c&amp;d\"  class=Foo>Text &amp; more</div>", out);

        Document reparsed = Jsoup.parse(out);
        Element rediv = reparsed.expectFirst("div");
        assertEquals("c&d", rediv.attr("data-x"));
        assertEquals("one", rediv.attr("id"));
        assertEquals("Foo", rediv.attr("class"));
        assertEquals("Text & more", rediv.text());
    }

    @Test void editsReportedInSourceOrderAppliedInReverseOffsetOrder() {
        String html = "<p id=a>One</p><p id=b>Two</p>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element p1 = doc.expectFirst("p");
        Element p2 = doc.select("p").get(1);

        SourceEditor editor = SourceEditor.on(html, doc);
        // plan out of source order: later text first, earlier attribute second
        editor.replaceText(textOf(p2), "2");
        editor.setAttribute(p1, "id", "x");

        List<SourceEditor.Edit> edits = editor.edits();
        assertEquals(2, edits.size());
        // reported in original source order (ascending offsets)
        assertTrue(edits.get(0).startPos() < edits.get(1).startPos());
        assertEquals("id=\"x\"", edits.get(0).replacement());
        assertEquals("2", edits.get(1).replacement());

        // applied in descending offset order so unapplied offsets stay valid
        List<SourceEditor.Edit> applicationOrder = editor.editsDescending();
        assertEquals(2, applicationOrder.size());
        assertTrue(applicationOrder.get(0).startPos() > applicationOrder.get(1).startPos());

        // the replacement changes lengths; only descending application yields the correct result
        assertEquals("<p id=\"x\">One</p><p id=b>2</p>", editor.commit());
    }

    @Test void removeParentThenEditChildIsRejected() {
        String html = "<div><p id=a>One</p></div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element div = doc.expectFirst("div");
        Element p = doc.expectFirst("p");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.remove(div);

        SourceEditor.SourceEditException ex =
            assertThrows(SourceEditor.SourceEditException.class, () -> editor.setAttribute(p, "id", "x"));
        // the conflict message points out both ranges
        assertTrue(ex.getMessage().contains("conflicts"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[0, 26)"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[8, 12)"), ex.getMessage());

        // same for replacing text inside the removed subtree
        SourceEditor.SourceEditException ex2 =
            assertThrows(SourceEditor.SourceEditException.class, () -> editor.replaceText(textOf(p), "Two"));
        assertTrue(ex2.getMessage().contains("conflicts"), ex2.getMessage());
    }

    @Test void editChildThenRemoveParentIsRejected() {
        String html = "<div><p>One</p></div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element div = doc.expectFirst("div");
        Element p = doc.expectFirst("p");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.replaceText(textOf(p), "Two");
        assertThrows(SourceEditor.SourceEditException.class, () -> editor.remove(div));
    }

    @Test void surrogatePairOffsetsAreUtf16Positions() {
        String html = "<p>\uD83D\uDE00<b>x</b></p>"; // emoji is one codepoint, two UTF-16 chars
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element b = doc.expectFirst("b");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.replaceText(textOf(b), "y");

        SourceEditor.Edit edit = editor.edits().get(0);
        assertEquals(html.indexOf('x'), edit.startPos()); // offset counts the surrogate pair as two chars
        assertEquals(html.indexOf('x') + 1, edit.endPos());
        assertEquals("<p>\uD83D\uDE00<b>y</b></p>", editor.commit());
    }

    @Test void duplicateAttributesEditFirstOccurrenceOnly() {
        String html = "<div id=1 id=2>x</div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element div = doc.expectFirst("div");
        assertEquals("1", div.attr("id")); // parser keeps the first

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.setAttribute(div, "id", "9");
        String out = editor.commit();

        assertEquals("<div id=\"9\" id=2>x</div>", out); // second duplicate untouched in source
        assertEquals("9", Jsoup.parse(out).expectFirst("div").attr("id"));
    }

    @Test void commitRejectsChangedSource() {
        String html = "<p id=a>One</p>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        SourceEditor editor = SourceEditor.on(html, doc);
        editor.setAttribute(doc.expectFirst("p"), "id", "x");

        assertEquals(SourceEditor.digest(html), editor.digest());
        SourceEditor.SourceEditException ex = assertThrows(SourceEditor.SourceEditException.class,
            () -> editor.commit("<p id=a>One</p> "));
        assertTrue(ex.getMessage().contains("digest"), ex.getMessage());

        // the untouched original still commits
        assertEquals("<p id=\"x\">One</p>", editor.commit());
    }

    @Test void implicitElementEndIsRejected() {
        String html = "<p>One<p>Two";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element p1 = doc.expectFirst("p");
        assertTrue(p1.endSourceRange().isImplicit());

        SourceEditor editor = SourceEditor.on(html, doc);
        SourceEditor.SourceEditException ex =
            assertThrows(SourceEditor.SourceEditException.class, () -> editor.remove(p1));
        assertTrue(ex.getMessage().contains("end tag"), ex.getMessage());
    }

    @Test void untrackedParseIsRejected() {
        String html = "<p>One</p>";
        Document doc = Jsoup.parse(html); // no position tracking
        SourceEditor editor = SourceEditor.on(html, doc);

        SourceEditor.SourceEditException ex = assertThrows(SourceEditor.SourceEditException.class,
            () -> editor.replaceText(textOf(doc.expectFirst("p")), "Two"));
        assertTrue(ex.getMessage().contains("setTrackPosition"), ex.getMessage());
    }

    @Test void newContentIsEscaped() {
        String html = "<div title=a>x</div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element div = doc.expectFirst("div");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.setAttribute(div, "title", "a\"b&c<d>");
        editor.replaceText(textOf(div), "x < y & z");
        String out = editor.commit();

        assertEquals("<div title=\"a&quot;b&amp;c&lt;d&gt;\">x &lt; y &amp; z</div>", out);

        Document reparsed = Jsoup.parse(out);
        Element rediv = reparsed.expectFirst("div");
        assertEquals("a\"b&c<d>", rediv.attr("title"));
        assertEquals("x < y & z", rediv.text());
    }

    @Test void removeAttributeAbsorbsPrecedingWhitespace() {
        String html = "<div  id=1   class=foo>t</div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element div = doc.expectFirst("div");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.removeAttribute(div, "id");
        editor.removeAttribute(div, "absent"); // no-op
        String out = editor.commit();

        assertEquals("<div   class=foo>t</div>", out);
        Element rediv = Jsoup.parse(out).expectFirst("div");
        assertFalse(rediv.hasAttr("id"));
        assertEquals("foo", rediv.attr("class"));
    }

    @Test void newAttributeIsInsertedBeforeTagEnd() {
        String html = "<div>x</div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element div = doc.expectFirst("div");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.setAttribute(div, "id", "a");
        assertEquals("<div id=\"a\">x</div>", editor.commit());
    }

    @Test void removeElementAndLeafNodes() {
        String html = "<div><span>One</span> tail</div>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        Element span = doc.expectFirst("span");

        SourceEditor editor = SourceEditor.on(html, doc);
        editor.remove(span);
        assertEquals("<div> tail</div>", editor.commit());

        // removing a leaf text node
        SourceEditor editor2 = SourceEditor.on(html, doc);
        editor2.remove((TextNode) doc.expectFirst("div").childNode(1));
        assertEquals("<div><span>One</span></div>", editor2.commit());
    }

    @Test void editsListIsUnmodifiable() {
        String html = "<p id=a>One</p>";
        Document doc = Jsoup.parse(html, TrackingHtmlParser);
        SourceEditor editor = SourceEditor.on(html, doc);
        editor.setAttribute(doc.expectFirst("p"), "id", "x");
        assertThrows(UnsupportedOperationException.class, () -> editor.edits().clear());
    }
}
