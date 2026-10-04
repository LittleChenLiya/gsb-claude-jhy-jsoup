package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for TextHighlighter: text-range highlighting with mark elements, structure preservation,
 surrogate-pair safety, plan-then-apply atomicity, and baseline-validated undo.
 */
public class TextHighlighterTest {

    private static Element bodyOf(String html) {
        return Jsoup.parse(html).body();
    }

    @Test public void acceptanceHighlightsAcrossInlineElementsAndUndoes() {
        // Acceptance: document A<b>BC</b>D, select [1, 4) -> highlights BC and D; the b element stays.
        Element body = bodyOf("<p>A<b>BC</b>D</p>");
        assertEquals("ABCD", TextHighlighter.textView(body));
        String before = body.html();

        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 4, "m1");

        assertEquals(2, hl.marks().size());
        assertEquals("BC", hl.marks().get(0).text());
        assertEquals("D", hl.marks().get(1).text());
        assertEquals("m1", hl.marks().get(0).attr(TextHighlighter.MarkIdAttr));
        assertEquals(
            "<p>A<b><mark class=\"jsoup-hl\" data-hl-id=\"m1\">BC</mark></b>" +
            "<mark class=\"jsoup-hl\" data-hl-id=\"m1\">D</mark></p>",
            body.html());
        // the original b element is preserved and now parents the first mark
        Element b = body.selectFirst("b");
        assertNotNull(b);
        assertSame(b, hl.marks().get(0).parent());

        // undo restores the original node text and order
        assertTrue(hl.undo());
        assertTrue(hl.isUndone());
        assertEquals(before, body.html());
        Element p = body.selectFirst("p");
        assertNotNull(p);
        assertEquals(3, p.childNodeSize());
        assertEquals("A", ((TextNode) p.childNode(0)).getWholeText());
        assertEquals("b", ((Element) p.childNode(1)).normalName());
        assertEquals("BC", ((TextNode) p.childNode(1).childNode(0)).getWholeText());
        assertEquals("D", ((TextNode) p.childNode(2)).getWholeText());
    }

    @Test public void invalidRangeLeavesDomCompletelyUnchanged() {
        Element body = bodyOf("<p>A<b>BC</b>D</p>");
        String before = body.html();
        String domBefore = body.ownerDocument().html();

        assertThrows(IllegalArgumentException.class, () -> TextHighlighter.highlight(body, -1, 2, "x"));
        assertThrows(IllegalArgumentException.class, () -> TextHighlighter.highlight(body, 0, 5, "x"));
        assertThrows(IllegalArgumentException.class, () -> TextHighlighter.highlight(body, 3, 2, "x"));

        assertEquals(before, body.html());
        assertEquals(domBefore, body.ownerDocument().html());
        assertEquals(0, body.select("mark").size());
    }

    @Test public void emptyRangeIsANoOp() {
        Element body = bodyOf("<p>AB</p>");
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 1, "x");
        assertEquals(0, hl.marks().size());
        assertEquals("<p>AB</p>", body.html());
        assertTrue(hl.undo());
        assertEquals("<p>AB</p>", body.html());
    }

    @Test public void crossNodeSpanSplitsAndWrapsEachSegment() {
        Element body = bodyOf("<div>AB<i>CD</i>EF</div>");
        body.ownerDocument().outputSettings().prettyPrint(false); // keep block html on one line
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 5, "x");

        assertEquals(
            "<div>A<mark class=\"jsoup-hl\" data-hl-id=\"x\">B</mark>" +
            "<i><mark class=\"jsoup-hl\" data-hl-id=\"x\">CD</mark></i>" +
            "<mark class=\"jsoup-hl\" data-hl-id=\"x\">E</mark>F</div>",
            body.html());
        assertNotNull(body.selectFirst("i")); // structure preserved

        assertTrue(hl.undo());
        assertEquals("<div>AB<i>CD</i>EF</div>", body.html());
    }

    @Test public void fullTextNodeSelectionNeedsNoSplitting() {
        Element body = bodyOf("<p>ABCD</p>");
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 0, 4, "x");
        assertEquals("<p><mark class=\"jsoup-hl\" data-hl-id=\"x\">ABCD</mark></p>", body.html());
        assertTrue(hl.undo());
        assertEquals("<p>ABCD</p>", body.html());
    }

    @Test public void surrogatePairsAreNeverSplit() {
        // text view: A 😀 B -> UTF-16 units: 'A', high, low, 'B' (length 4)
        String emoji = "A😀B";
        Element body = bodyOf("<p>" + emoji + "</p>");
        assertEquals(4, TextHighlighter.textView(body).length());

        // [1, 2) ends in the middle of the pair; the end snaps out to 3
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 2, "e");
        assertEquals(1, hl.start());
        assertEquals(3, hl.end());
        assertEquals("😀", hl.marks().get(0).text());
        assertEquals(emoji, TextHighlighter.textView(body)); // pair intact
        assertTrue(hl.undo());
        assertEquals("<p>" + emoji + "</p>", body.html());

        // [2, 3) starts in the middle of the pair; the start snaps out to 1
        hl = TextHighlighter.highlight(body, 2, 3, "e");
        assertEquals(1, hl.start());
        assertEquals(3, hl.end());
        assertEquals("😀", hl.marks().get(0).text());
        assertTrue(hl.undo());

        // [3, 4) sits on a clean boundary and is left alone
        hl = TextHighlighter.highlight(body, 3, 4, "e");
        assertEquals(3, hl.start());
        assertEquals(4, hl.end());
        assertEquals("B", hl.marks().get(0).text());
        assertTrue(hl.undo());
        assertEquals("<p>" + emoji + "</p>", body.html());
    }

    @Test public void textViewExcludesScriptAndStyle() {
        Element body = bodyOf("<div>ab<script>var x = 1;</script>cd<style>p { color: red }</style>ef</div>");
        assertEquals("abcdef", TextHighlighter.textView(body));

        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 5, "x");
        assertEquals(3, hl.marks().size());
        assertEquals("b", hl.marks().get(0).text());
        assertEquals("cd", hl.marks().get(1).text());
        assertEquals("e", hl.marks().get(2).text());
        // script and style content untouched
        assertEquals("var x = 1;", body.selectFirst("script").data());
        assertEquals("p { color: red }", body.selectFirst("style").data());
        assertTrue(hl.undo());
        assertEquals("abcdef", TextHighlighter.textView(body));
        assertEquals(0, body.select("mark").size());
    }

    @Test public void undoRefusedWhenMarkContentEdited() {
        Element body = bodyOf("<p>A<b>BC</b>D</p>");
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 4, "m1");

        // user edits the highlighted content after the highlight was applied
        hl.marks().get(0).text("user edit");

        assertFalse(hl.undo());
        assertFalse(hl.isUndone());
        assertNotNull(hl.refusalReason());
        // the user's edit is not discarded; the DOM is exactly as the user left it
        assertEquals(
            "<p>A<b><mark class=\"jsoup-hl\" data-hl-id=\"m1\">user edit</mark></b>" +
            "<mark class=\"jsoup-hl\" data-hl-id=\"m1\">D</mark></p>",
            body.html());
    }

    @Test public void undoRefusedWhenAdjacentSplitNodeEdited() {
        Element body = bodyOf("<p>ABCDE</p>");
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 3, "x");
        assertEquals("<p>A<mark class=\"jsoup-hl\" data-hl-id=\"x\">BC</mark>DE</p>", body.html());

        // user edits the trailing text node that splitting produced
        Element p = body.selectFirst("p");
        assertNotNull(p);
        ((TextNode) p.childNode(2)).text("user tail");

        assertFalse(hl.undo());
        assertNotNull(hl.refusalReason());
        assertEquals("<p>A<mark class=\"jsoup-hl\" data-hl-id=\"x\">BC</mark>user tail</p>", body.html());
    }

    @Test public void undoRefusedWhenMarkRemoved() {
        Element body = bodyOf("<p>A<b>BC</b>D</p>");
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 4, "m1");
        hl.marks().get(1).remove(); // user deletes one mark

        assertFalse(hl.undo());
        assertNotNull(hl.refusalReason());
        // nothing was restored or deleted: the first mark and all text are still as the user left them
        assertEquals(1, body.select("mark").size());
        assertEquals("ABC", TextHighlighter.textView(body)); // the deleted "D" stays deleted
    }

    @Test public void undoIsAtomicAcrossGroups() {
        // two original text nodes are involved; editing one must block the whole undo
        Element body = bodyOf("<div>AB<i>CD</i>EF</div>");
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 5, "x");
        hl.marks().get(2).text("edited");

        assertFalse(hl.undo());
        // the first, unedited group was not partially restored; the user's edit is intact
        assertEquals(3, body.select("mark").size());
        assertEquals("ABCDeditedF", TextHighlighter.textView(body));
    }

    @Test public void demoTextViewAndDomBeforeAfter() {
        Element body = bodyOf("<p>A<b>BC</b>D</p>");
        System.out.println("[demo] text view definition: document-order TextNode whole text, excluding script/style");
        System.out.println("[demo] text view   : " + TextHighlighter.textView(body));
        System.out.println("[demo] DOM before  : " + body.html());
        TextHighlighter.Highlight hl = TextHighlighter.highlight(body, 1, 4, "m1");
        System.out.println("[demo] DOM after   : " + body.html());
        assertTrue(hl.undo());
        System.out.println("[demo] DOM undone  : " + body.html());
        assertEquals("<p>A<b>BC</b>D</p>", body.html());
    }
}
