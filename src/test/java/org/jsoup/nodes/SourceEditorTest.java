package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for {@link SourceEditor}, {@link SourceEditPlan}, and {@link SourceEdit}.
 */
public class SourceEditorTest {

    @Test
    void attributeEditPreservesUnmodifiedSource() {
        // single-quoted attribute, entity, irregular whitespace, mixed case: all must survive untouched
        String html = "<div id='main'  data-note=\"a &amp; b\">\n  <P CLASS=lead>Hi   there</P>\n</div>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.setAttribute(editor.document().selectFirst("div"), "id", "primary");

        String edited = editor.plan().commit(html);
        assertEquals("<div id='primary'  data-note=\"a &amp; b\">\n  <P CLASS=lead>Hi   there</P>\n</div>", edited);

        Document reparsed = Jsoup.parse(edited);
        Element div = reparsed.selectFirst("div");
        assertEquals("primary", div.id());
        assertEquals("a & b", div.attr("data-note"));
        assertEquals("lead", reparsed.selectFirst("p").className());
    }

    @Test
    void replaceTextEscapesAndPreservesRest() {
        String html = "<p title='keep &quot;me\"'>Old &amp; text</p>";
        SourceEditor editor = SourceEditor.parse(html);
        TextNode text = editor.document().selectFirst("p").textNodes().get(0);
        editor.replaceText(text, "5 < 6 & 7");

        String edited = editor.plan().commit(html);
        assertEquals("<p title='keep &quot;me\"'>5 &lt; 6 &amp; 7</p>", edited);
        assertEquals("5 < 6 & 7", Jsoup.parse(edited).selectFirst("p").text());
    }

    @Test
    void setAttributeEscapesValue() {
        String html = "<a href=x>y</a>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.setAttribute(editor.document().selectFirst("a"), "href", "https://e.com/?a=1&b=\"2\"");

        String edited = editor.plan().commit(html);
        assertEquals("<a href=https://e.com/?a=1&amp;b=&quot;2&quot;>y</a>", edited);
        assertEquals("https://e.com/?a=1&b=\"2\"", Jsoup.parse(edited).selectFirst("a").attr("href"));
    }

    @Test
    void newAttributeInsertedBeforeTagEnd() {
        String html = "<img src=x><br/>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.setAttribute(editor.document().selectFirst("img"), "alt", "A & B");
        editor.setAttribute(editor.document().selectFirst("br"), "data-x", "1");

        String edited = editor.plan().commit(html);
        assertEquals("<img src=x alt=\"A &amp; B\"><br data-x=\"1\"/>", edited);
        Document reparsed = Jsoup.parse(edited);
        assertEquals("A & B", reparsed.selectFirst("img").attr("alt"));
        assertEquals("1", reparsed.selectFirst("br").attr("data-x"));
    }

    @Test
    void valuelessAndEmptyAttributesGainValues() {
        String html = "<input disabled><div id=''>x</div>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.setAttribute(editor.document().selectFirst("input"), "disabled", "on");
        editor.setAttribute(editor.document().selectFirst("div"), "id", "a");

        String edited = editor.plan().commit(html);
        assertEquals("<input disabled=\"on\"><div id='a'>x</div>", edited); // original quote style kept
        Document reparsed = Jsoup.parse(edited);
        assertEquals("on", reparsed.selectFirst("input").attr("disabled"));
        assertEquals("a", reparsed.selectFirst("div").id());
    }

    @Test
    void removeNodeDeletesElementAndContents() {
        String html = "<p>Hello <b>World</b>!</p>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.remove(editor.document().selectFirst("b"));
        assertEquals("<p>Hello !</p>", editor.plan().commit(html));
    }

    @Test
    void removeVoidAndChildlessElements() {
        String html = "<p>a<br>b<span></span>c</p>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.remove(editor.document().selectFirst("br"));
        editor.remove(editor.document().selectFirst("span"));
        assertEquals("<p>abc</p>", editor.plan().commit(html));
    }

    @Test
    void overlappingEditsRejectedWithConflictDetails() {
        String html = "<div><p class=lead>Hi</p></div>";
        SourceEditor editor = SourceEditor.parse(html);
        Element div = editor.document().selectFirst("div");
        Element p = editor.document().selectFirst("p");
        editor.remove(div); // range covers the child p
        editor.setAttribute(p, "class", "x");

        SourceEditException e = assertThrows(SourceEditException.class, editor::plan);
        String msg = e.getMessage();
        assertTrue(msg.contains("conflicts with"), msg);
        // points out the actual overlapping ranges
        Range divRange = div.sourceRange();
        assertTrue(msg.contains("[" + divRange.startPos() + ", " + div.endSourceRange().endPos() + ")"), msg);
        Range.AttributeRange attrRange = p.attributes().sourceRange("class");
        assertTrue(msg.contains("[" + attrRange.valueRange().startPos() + ", " + attrRange.valueRange().endPos() + ")"), msg);
    }

    @Test
    void implicitNodesRejected() {
        String html = "<p>Hi</p>"; // html, head, body are implicitly created
        SourceEditor editor = SourceEditor.parse(html);
        Element body = editor.document().body();
        assertTrue(body.sourceRange().isImplicit());
        SourceEditException e = assertThrows(SourceEditException.class, () -> editor.remove(body));
        assertTrue(e.getMessage().contains("implicit"), e.getMessage());
    }

    @Test
    void implicitEndTagElementWithChildrenRejected() {
        String html = "<p>One<p>Two"; // no explicit </p> end tags
        SourceEditor editor = SourceEditor.parse(html);
        Element first = editor.document().selectFirst("p");
        assertTrue(first.endSourceRange().isImplicit());
        assertThrows(SourceEditException.class, () -> editor.remove(first));
    }

    @Test
    void untrackedPositionsRejected() {
        String html = "<p>Hi</p>";
        Document doc = Jsoup.parse(html); // no position tracking
        SourceEditor editor = new SourceEditor(doc, html);
        TextNode text = doc.selectFirst("p").textNodes().get(0);
        assertThrows(SourceEditException.class, () -> editor.replaceText(text, "New"));
    }

    @Test
    void duplicateAttributesRejected() {
        String html = "<div id=one id=two>x</div>";
        SourceEditor editor = SourceEditor.parse(html);
        Element div = editor.document().selectFirst("div");
        assertEquals("one", div.id()); // parser keeps the first occurrence
        SourceEditException e = assertThrows(SourceEditException.class, () -> editor.setAttribute(div, "id", "three"));
        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
    }

    @Test
    void surrogatePairsUseUtf16Offsets() {
        String html = "<p title='\uD83D\uDE00'>a</p><b>bold</b>"; // emoji is one codepoint, two UTF-16 chars
        SourceEditor editor = SourceEditor.parse(html);
        editor.replaceText(editor.document().selectFirst("b").textNodes().get(0), "done");
        editor.setAttribute(editor.document().selectFirst("p"), "data-n", "1");

        String edited = editor.plan().commit(html);
        assertEquals("<p title='\uD83D\uDE00' data-n=\"1\">a</p><b>done</b>", edited);
        assertEquals("done", Jsoup.parse(edited).selectFirst("b").text());
    }

    @Test
    void sourceChangeDetectedByDigest() {
        String html = "<p>Hi</p>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.replaceText(editor.document().selectFirst("p").textNodes().get(0), "Yo");
        SourceEditPlan plan = editor.plan();

        assertThrows(SourceEditException.class, () -> plan.commit("<p>Hi!</p>"));
        assertThrows(SourceEditException.class, () -> plan.commit(html + " "));
        assertEquals("<p>Yo</p>", plan.commit(html)); // unchanged source commits fine
        assertEquals("<p>Yo</p>", plan.commit());      // and the captured source works, too
        assertEquals(SourceEditPlan.digest(html), plan.sourceDigest());
    }

    @Test
    void editsReportedInSourceOrderAppliedInReverse() {
        String html = "<div id=a><p>one</p><p>two</p></div>";
        SourceEditor editor = SourceEditor.parse(html);
        Document doc = editor.document();
        // request edits out of source order
        editor.remove(doc.select("p").get(1));
        editor.setAttribute(doc.selectFirst("div"), "id", "b");
        editor.replaceText(doc.select("p").get(0).textNodes().get(0), "1");

        SourceEditPlan plan = editor.plan();
        List<SourceEdit> reported = plan.edits();
        assertEquals(3, reported.size());
        for (int i = 1; i < reported.size(); i++)
            assertTrue(reported.get(i - 1).startPos() < reported.get(i).startPos(), "source order");
        assertEquals(SourceEdit.Kind.SetAttribute, reported.get(0).kind());
        assertEquals(SourceEdit.Kind.ReplaceText, reported.get(1).kind());
        assertEquals(SourceEdit.Kind.RemoveNode, reported.get(2).kind());

        List<SourceEdit> applyOrder = plan.editsInApplyOrder();
        assertEquals(reported.size(), applyOrder.size());
        for (int i = 1; i < applyOrder.size(); i++)
            assertTrue(applyOrder.get(i - 1).startPos() > applyOrder.get(i).startPos(), "reverse offset order");

        assertEquals("<div id=b><p>1</p></div>", plan.commit(html)); // original unquoted style preserved
    }

    @Test
    void doesNotReserializeDom() {
        // if the DOM were re-serialized, case, quotes, entities, and whitespace would be normalized
        String html = "<DIV   ID='X'\tdata-e=&quot;&amp;&quot;>A&nbsp;  B</DIV >";
        SourceEditor editor = SourceEditor.parse(html);
        editor.replaceText(editor.document().selectFirst("div").textNodes().get(0), "A");
        String edited = editor.plan().commit(html);
        // only the text node's span changed; everything else is character-identical
        assertEquals("<DIV   ID='X'\tdata-e=&quot;&amp;&quot;>A</DIV >", edited);
    }

    @Test
    void foreignNodeRejected() {
        SourceEditor editor = SourceEditor.parse("<p>a</p>");
        Document other = Jsoup.parse("<p>b</p>");
        assertThrows(SourceEditException.class,
            () -> editor.replaceText(other.selectFirst("p").textNodes().get(0), "x"));
    }

    @Test
    void parserRoundTripViaTrackedParser() {
        // binding to a caller-parsed document with tracking enabled
        String html = "<p class='z'>Text</p>";
        Parser parser = Parser.htmlParser().setTrackPosition(true);
        Document doc = parser.parseInput(html, "");
        SourceEditor editor = new SourceEditor(doc, html);
        editor.setAttribute(doc.selectFirst("p"), "class", "y");
        assertEquals("<p class='y'>Text</p>", editor.plan().commit(html));
    }

    @Test
    void demoPrintsOriginalEditsAndConflict() {
        // demonstrates the flow: original source, edit fragments, conflict rejection
        String html = "<div id='main'  data-note=\"a &amp; b\">\n  <p class=lead>Hello   <b>World</b>!</p>\n</div>";
        SourceEditor editor = SourceEditor.parse(html);
        editor.setAttribute(editor.document().selectFirst("div"), "id", "primary");
        editor.replaceText(editor.document().selectFirst("p").textNodes().get(0), "Hi   there ");
        SourceEditPlan plan = editor.plan();

        StringBuilder demo = new StringBuilder();
        demo.append("== Original ==\n").append(html).append("\n== Edits ==\n");
        for (SourceEdit edit : plan.edits()) demo.append(edit).append('\n');
        demo.append("== Edited ==\n").append(plan.commit(html)).append('\n');

        SourceEditor conflicting = SourceEditor.parse(html);
        conflicting.remove(conflicting.document().selectFirst("div"));
        conflicting.setAttribute(conflicting.document().selectFirst("p"), "class", "x");
        try {
            conflicting.plan();
            fail("expected a conflict");
        } catch (SourceEditException e) {
            demo.append("== Conflict rejected ==\n").append(e.getMessage());
        }

        String out = demo.toString();
        System.out.println(out);
        assertTrue(out.contains("== Edited =="));
        assertTrue(out.contains("conflicts with"));
        assertTrue(out.contains("id='primary'"));
    }
}
