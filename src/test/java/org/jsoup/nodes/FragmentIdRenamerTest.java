package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FragmentIdRenamerTest {

    private static Element fragment(String html) {
        return Jsoup.parseBodyFragment(html).body();
    }

    @Test
    public void prefixesTwoFragmentsSoTheyCanMerge() {
        String html = "<section id=\"title\">"
            + "<h1 id=\"heading\">Heading</h1>"
            + "<label for=\"title\">See</label>"
            + "<a href=\"#title\">top</a>"
            + "<p aria-labelledby=\"heading title\">text</p>"
            + "</section>";

        Element first = fragment(html);
        Element second = fragment(html);

        FragmentIdRenamer.Result resA = FragmentIdRenamer.rename(first, "a_");
        FragmentIdRenamer.Result resB = FragmentIdRenamer.rename(second, "b_");

        Element merged = new Element("div");
        merged.appendChild(resA.element());
        merged.appendChild(resB.element());

        assertNotNull(merged.getElementById("a_title"));
        assertNotNull(merged.getElementById("b_title"));
        assertNull(merged.getElementById("title"));

        // each copy's references point back to its own ids
        Element copyA = merged.child(0);
        Element copyB = merged.child(1);
        assertEquals("a_title", copyA.selectFirst("label").attr("for"));
        assertEquals("b_title", copyB.selectFirst("label").attr("for"));
        assertEquals("#a_title", copyA.selectFirst("a").attr("href"));
        assertEquals("#b_title", copyB.selectFirst("a").attr("href"));
        assertEquals("a_heading a_title", copyA.selectFirst("p").attr("aria-labelledby"));
        assertEquals("b_heading b_title", copyB.selectFirst("p").attr("aria-labelledby"));

        assertFalse(resA.hasUnresolvedReferences());
        assertFalse(resB.hasUnresolvedReferences());
        assertEquals(2, resA.idMap().size());
        assertEquals("a_title", resA.idMap().get("title"));
        assertEquals("a_heading", resA.idMap().get("heading"));
    }

    @Test
    public void leavesHrefsWithUrlsUntouched() {
        Element frag = fragment(
            "<div id=\"title\"></div>"
                + "<a id=\"l1\" href=\"https://example.com/#title\">abs</a>"
                + "<a id=\"l2\" href=\"other.html#title\">rel</a>"
                + "<a id=\"l3\" href=\"/path/page#title\">root</a>"
                + "<a id=\"l4\" href=\"mailto:a@b.c#title\">mail</a>"
                + "<a id=\"l5\" href=\"#\">empty</a>");

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(frag, "p_");
        Element out = res.element();

        assertEquals("https://example.com/#title", out.getElementById("p_l1").attr("href"));
        assertEquals("other.html#title", out.getElementById("p_l2").attr("href"));
        assertEquals("/path/page#title", out.getElementById("p_l3").attr("href"));
        assertEquals("mailto:a@b.c#title", out.getElementById("p_l4").attr("href"));
        assertEquals("#", out.getElementById("p_l5").attr("href"));
        assertFalse(res.hasUnresolvedReferences());
    }

    @Test
    public void keepsAndReportsReferencesToMissingTargets() {
        Element frag = fragment(
            "<div id=\"title\"></div>"
                + "<a href=\"#gone\">x</a>"
                + "<label for=\"nope\">y</label>"
                + "<input form=\"away\">"
                + "<p aria-labelledby=\"title gone\" aria-describedby=\"gone title\">z</p>");

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(frag, "p_");
        Element out = res.element();

        // unresolved references keep their original value
        assertEquals("#gone", out.selectFirst("a").attr("href"));
        assertEquals("nope", out.selectFirst("label").attr("for"));
        assertEquals("away", out.selectFirst("input").attr("form"));
        assertEquals("p_title gone", out.selectFirst("p").attr("aria-labelledby"));
        assertEquals("gone p_title", out.selectFirst("p").attr("aria-describedby"));

        List<FragmentIdRenamer.UnresolvedReference> report = res.unresolvedReferences();
        assertEquals(5, report.size());
        assertEquals("a[href] -> gone", report.get(0).toString());
        assertEquals("label[for] -> nope", report.get(1).toString());
        assertEquals("input[form] -> away", report.get(2).toString());
        assertEquals("p[aria-labelledby] -> gone", report.get(3).toString());
        assertEquals("p[aria-describedby] -> gone", report.get(4).toString());
    }

    @Test
    public void failsOnDuplicateIds() {
        Element frag = fragment("<div id=\"title\">one</div><span id=\"title\">two</span>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> FragmentIdRenamer.rename(frag, "p_"));
        assertTrue(ex.getMessage().contains("Duplicate id 'title'"));
    }

    @Test
    public void failsWhenPrefixedIdCollidesWithExistingId() {
        Element frag = fragment("<div id=\"x\"></div><div id=\"p_x\"></div>");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> FragmentIdRenamer.rename(frag, "p_"));
        assertTrue(ex.getMessage().contains("would conflict"));
    }

    @Test
    public void handlesEscapedAttributeValues() {
        Element frag = fragment(
            "<div id=\"a&amp;b\"></div>"
                + "<a href=\"#a&amp;b\">x</a>"
                + "<p aria-labelledby=\"a&amp;b\">y</p>");
        assertEquals("a&b", frag.getElementById("a&b").id()); // sanity: entities are decoded in the DOM

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(frag, "p_");
        Element out = res.element();

        assertEquals("p_a&b", out.selectFirst("div").id());
        assertEquals("#p_a&b", out.selectFirst("a").attr("href"));
        assertEquals("p_a&b", out.selectFirst("p").attr("aria-labelledby"));
        assertFalse(res.hasUnresolvedReferences());
        // and re-escapes on serialization
        assertTrue(out.html().contains("href=\"#p_a&amp;b\""));
    }

    @Test
    public void ignoresEmptyIdValues() {
        Element frag = fragment("<div id=\"\"></div><span id=\"\"></span><label for=\"\">x</label>");

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(frag, "p_");
        Element out = res.element();

        assertTrue(res.idMap().isEmpty());
        assertFalse(res.hasUnresolvedReferences());
        assertEquals("", out.selectFirst("div").attr("id"));
        assertEquals("", out.selectFirst("span").attr("id"));
        assertEquals("", out.selectFirst("label").attr("for"));
    }

    @Test
    public void doesNotTouchScriptOrStyleText() {
        Element frag = fragment(
            "<div id=\"title\"></div>"
                + "<script>var el = document.getElementById('title'); el.href = '#title';</script>"
                + "<style>#title { color: red; } a[href='#title'] { x: y; }</style>");

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(frag, "p_");
        Element out = res.element();

        assertEquals("var el = document.getElementById('title'); el.href = '#title';",
            out.selectFirst("script").data());
        assertEquals("#title { color: red; } a[href='#title'] { x: y; }",
            out.selectFirst("style").data());
        assertEquals("p_title", out.selectFirst("div").id());
    }

    @Test
    public void doesNotModifyInputAndPreservesListOrderAndDuplicates() {
        Element frag = fragment(
            "<h1 id=\"a\">A</h1><h2 id=\"b\">B</h2>"
                + "<p aria-labelledby=\"b  a\tb a\">x</p>");
        String originalHtml = frag.html();

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(frag, "pre_");

        // input untouched
        assertEquals(originalHtml, frag.html());
        assertNotSame(frag, res.element());
        assertEquals("a", frag.selectFirst("h1").id());

        // order, duplicates, and separators preserved in the clone
        assertEquals("pre_b  pre_a\tpre_b pre_a", res.element().selectFirst("p").attr("aria-labelledby"));
        assertEquals("pre_a", res.element().selectFirst("h1").id());
    }

    @Test
    public void renamesListOfSiblingNodes() {
        List<Node> nodes = Parser.parseFragment(
            "<div id=\"one\"></div>text<span id=\"two\"></span><a href=\"#one\">x</a>",
            new Element("div"), "");

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(nodes, "x_");

        assertEquals(4, res.nodes().size());
        assertEquals(2, res.idMap().size());
        assertEquals("x_one", res.idMap().get("one"));
        assertEquals("x_two", res.idMap().get("two"));
        Element anchor = (Element) res.nodes().get(3);
        assertEquals("#x_one", anchor.attr("href"));
        // originals untouched
        assertEquals("one", ((Element) nodes.get(0)).id());
    }

    @Test
    public void onlyUpdatesForOnLabels() {
        Element frag = fragment("<div id=\"x\"></div><output for=\"x\">o</output><label for=\"x\">l</label>");

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(frag, "p_");
        Element out = res.element();

        assertEquals("p_x", out.selectFirst("label").attr("for"));
        assertEquals("x", out.selectFirst("output").attr("for")); // not a label; left as-is, and not reported
        assertFalse(res.hasUnresolvedReferences());
    }
}
