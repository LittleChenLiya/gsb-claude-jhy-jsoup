package org.jsoup.outline;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HeadingOutlineTest {

    private static HeadingOutline outlineOf(String bodyHtml) {
        Document doc = Jsoup.parse("<html><body>" + bodyHtml + "</body></html>");
        return HeadingOutline.extract(doc.body());
    }

    @Test
    public void levelJumpsAttachToNearestSmallerHeading() {
        // h2, h4, h3 -> h4 and h3 are both subsections of the same h2; the following h1 starts a new root.
        HeadingOutline outline = outlineOf(
            "<h2>A</h2><h4>B</h4><h3>C</h3><h1>D</h1>");

        List<Section> roots = outline.roots();
        assertEquals(2, roots.size());

        Section h2 = roots.get(0);
        assertEquals(2, h2.level());
        assertEquals("A", h2.text());

        List<Section> subs = h2.children();
        assertEquals(2, subs.size());
        assertEquals(4, subs.get(0).level());
        assertEquals("B", subs.get(0).text());
        assertEquals(3, subs.get(1).level());
        assertEquals("C", subs.get(1).text());
        assertTrue(subs.get(0).children().isEmpty());
        assertTrue(subs.get(1).children().isEmpty());

        Section h1 = roots.get(1);
        assertEquals(1, h1.level());
        assertEquals("D", h1.text());
        assertTrue(h1.children().isEmpty());
    }

    @Test
    public void levelFallbackSkipsMissingLevelsWithoutInventingHeadings() {
        // h1, h3, h2, h5: h3 nests under h1; h2 falls back to h1; h5 nests under h2. No heading is fabricated.
        HeadingOutline outline = outlineOf(
            "<h1>top</h1><h3>deep</h3><h2>mid</h2><h5>deeper</h5>");

        List<Section> roots = outline.roots();
        assertEquals(1, roots.size());
        Section top = roots.get(0);
        assertEquals(1, top.level());

        List<Section> children = top.children();
        assertEquals(2, children.size());
        assertEquals(3, children.get(0).level());
        assertEquals("deep", children.get(0).text());
        assertEquals(2, children.get(1).level());
        assertEquals("mid", children.get(1).text());

        List<Section> deeper = children.get(1).children();
        assertEquals(1, deeper.size());
        assertEquals(5, deeper.get(0).level());
        assertEquals("deeper", deeper.get(0).text());
    }

    @Test
    public void chineseTextPreservedWithElementTextSemantics() {
        HeadingOutline outline = outlineOf(
            "<h1>介绍</h1><h2>背景 <span>与<em>动机</em></span></h2><h3>方法&nbsp;论</h3>");

        List<Section> roots = outline.roots();
        assertEquals(1, roots.size());
        assertEquals("介绍", roots.get(0).text());

        Section h2 = roots.get(0).children().get(0);
        assertEquals("背景 与动机", h2.text()); // Element.text() normalizes and merges inline elements

        Section h3 = h2.children().get(0);
        assertEquals("方法 论", h3.text()); // &nbsp; collapses to a space under Element.text()
    }

    @Test
    public void templateScriptAndStyleHeadingsAreExcluded() {
        HeadingOutline outline = outlineOf(
            "<h1>real</h1>"
                + "<template><h2>in template</h2></template>"
                + "<script>var x = 1;</script>"
                + "<style>h2 { color: red; }</style>"
                + "<h2>also real</h2>");

        List<Section> roots = outline.roots();
        assertEquals(1, roots.size());
        assertEquals("real", roots.get(0).text());

        List<Section> children = roots.get(0).children();
        assertEquals(1, children.size());
        assertEquals("also real", children.get(0).text());
        assertFalse(outline.toString().contains("in template"));
    }

    @Test
    public void headingsOutsideTheRootAreExcluded() {
        Document doc = Jsoup.parse(
            "<html><body><h1>outside</h1><div id=content><h2>inside</h2></div></body></html>");
        Element content = doc.getElementById("content");
        assertNotNull(content);

        HeadingOutline outline = HeadingOutline.extract(content);
        List<Section> roots = outline.roots();
        assertEquals(1, roots.size());
        assertEquals(2, roots.get(0).level());
        assertEquals("inside", roots.get(0).text());
    }

    @Test
    public void existingIdsKeptMissingIdsGeneratedStablyAndNotWrittenBack() {
        Document doc = Jsoup.parse("<html><body>"
            + "<h1 id=intro>一</h1><h2>二</h2><h3>三</h3><h2 id=part2>四</h2>"
            + "</body></html>");
        HeadingOutline outline = HeadingOutline.extract(doc.body());

        Section intro = outline.roots().get(0);
        assertEquals("intro", intro.id());
        assertFalse(intro.hasGeneratedId());

        Section noId1 = intro.children().get(0);
        Section noId2 = noId1.children().get(0);
        Section part2 = intro.children().get(1);
        assertEquals("outline-1", noId1.id());
        assertEquals("outline-2", noId2.id());
        assertTrue(noId1.hasGeneratedId());
        assertTrue(noId2.hasGeneratedId());
        assertEquals("part2", part2.id());

        // Stable: a second extraction produces the same generated ids.
        HeadingOutline again = HeadingOutline.extract(doc.body());
        assertEquals("outline-1", again.roots().get(0).children().get(0).id());
        assertEquals("outline-2", again.roots().get(0).children().get(0).children().get(0).id());

        // Generated ids are report-only: the DOM was not written back.
        assertEquals("", noId1.element().id());
        assertEquals("", noId2.element().id());
        assertNull(doc.getElementById("outline-1"));
    }

    @Test
    public void duplicateIdsReportedAsConflictsWithoutRewritingNodes() {
        Document doc = Jsoup.parse("<html><body>"
            + "<h1 id=dup>first</h1><h2 id=unique>uniq</h2><h2 id=dup>second</h2>"
            + "</body></html>");
        String domBefore = doc.html();

        HeadingOutline outline = HeadingOutline.extract(doc.body());

        List<IdConflict> conflicts = outline.idConflicts();
        assertEquals(1, conflicts.size());
        IdConflict conflict = conflicts.get(0);
        assertEquals("dup", conflict.id());
        assertEquals(2, conflict.sections().size());
        assertEquals("first", conflict.sections().get(0).text());
        assertEquals("second", conflict.sections().get(1).text());

        // Nodes are reported as-is, not rewritten.
        assertEquals("dup", conflict.sections().get(0).element().id());
        assertEquals("dup", conflict.sections().get(1).element().id());
        assertEquals(domBefore, doc.html());
    }

    @Test
    public void childAndResultListsAreIndependentUnmodifiableCopies() {
        HeadingOutline outline = outlineOf("<h1>a</h1><h2>b</h2><h3>c</h3>");
        Section root = outline.roots().get(0);
        Section child = root.children().get(0);

        // Returned lists cannot be mutated...
        List<Section> children = root.children();
        assertThrows(UnsupportedOperationException.class, () -> children.add(child));
        assertThrows(UnsupportedOperationException.class, () -> children.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> outline.roots().add(child));
        assertThrows(UnsupportedOperationException.class, () -> outline.idConflicts().add(null));

        // ...and repeated calls return independent containers that share no mutable state with the outline.
        List<Section> first = root.children();
        List<Section> second = root.children();
        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(1, root.children().size()); // unchanged after the failed mutation attempts
        assertEquals(1, outline.roots().size());
    }

    @Test
    public void extractionDoesNotModifyTheDocument() {
        Document doc = Jsoup.parse("<html><head><style>h1{}</style></head><body>"
            + "<h1 id=a>一</h1><h4>二</h4><template><h2>t</h2></template><h1 id=a>三</h1>"
            + "</body></html>");
        String domBefore = doc.html();
        String outerBefore = doc.outerHtml();

        HeadingOutline.extract(doc.body());
        HeadingOutline.extract(doc.body()); // idempotent, still no writes

        assertEquals(domBefore, doc.html());
        assertEquals(outerBefore, doc.outerHtml());
    }

    @Test
    public void sectionsExposeSourceElementPositions() {
        Document doc = Jsoup.parse("<html><body><h1>x</h1><p>gap</p><h2>y</h2></body></html>");
        HeadingOutline outline = HeadingOutline.extract(doc.body());

        Section h1 = outline.roots().get(0);
        Section h2 = h1.children().get(0);
        assertSame(doc.body().child(0), h1.element());
        assertSame(doc.body().child(2), h2.element());
        assertEquals("h2", h2.element().tagName());
    }

    @Test
    public void treeViewRendersHierarchy() {
        HeadingOutline outline = outlineOf(
            "<h1 id=top>介绍</h1><h2>背景</h2><h4>细节</h4><h1>次章</h1>");
        String tree = outline.toString();
        assertEquals(
            "h1 #top: 介绍\n" +
            "  h2 #outline-1 (generated id): 背景\n" +
            "    h4 #outline-2 (generated id): 细节\n" +
            "h1 #outline-3 (generated id): 次章\n",
            tree);
    }
}
