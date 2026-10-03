package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HeadingOutline} and {@link HeadingNode}.
 */
class HeadingOutlineTest {

    private static HeadingOutline parse(String html) {
        return HeadingOutline.extract(Jsoup.parse(html).body());
    }

    /**
     * h2, h4, h3 form two subsections under the same h2, then h1 becomes a new root.
     * Chinese heading text is used throughout.
     */
    @Test
    void levelJumpAndFallback() {
        String html = "<body>"
            + "<h2>第一章</h2>"
            + "<h4>第一节</h4>"
            + "<h3>第二节</h3>"
            + "<h1>新的根</h1>"
            + "</body>";
        HeadingOutline outline = parse(html);

        // h2 and h1 are the two roots
        List<HeadingNode> roots = outline.roots();
        assertEquals(2, roots.size());
        assertEquals(2, roots.get(0).level());
        assertEquals("第一章", roots.get(0).text());
        assertEquals(1, roots.get(1).level());
        assertEquals("新的根", roots.get(1).text());

        // h4 and h3 are both children of h2 (two subsections under the same h2)
        List<HeadingNode> subs = roots.get(0).children();
        assertEquals(2, subs.size());
        assertEquals(4, subs.get(0).level());
        assertEquals("第一节", subs.get(0).text());
        assertEquals(3, subs.get(1).level());
        assertEquals("第二节", subs.get(1).text());

        // h1 has no children
        assertTrue(roots.get(1).children().isEmpty());

        // no fabricated h3: the outline has exactly 4 headings
        assertEquals(4, outline.size());
    }

    /**
     * A level jump (h2 followed directly by h4) attaches the h4 to the nearest smaller-level heading (h2)
     * and does not fabricate a missing h3.
     */
    @Test
    void levelFallbackAttachesToNearestSmaller() {
        String html = "<body><h2>父标题</h2><h4>子标题</h4></body>";
        HeadingOutline outline = parse(html);

        assertEquals(1, outline.roots().size());
        HeadingNode h2 = outline.roots().get(0);
        assertEquals(2, h2.level());
        assertEquals(1, h2.children().size());
        HeadingNode h4 = h2.children().get(0);
        assertEquals(4, h4.level());
        assertEquals("子标题", h4.text());
        // no h3 was fabricated
        assertEquals(2, outline.size());
        assertTrue(outline.headings().stream().noneMatch(n -> n.level() == 3));
    }

    /**
     * Headings inside template elements are excluded.
     */
    @Test
    void templateHeadingsExcluded() {
        String html = "<body>"
            + "<h2>可见标题</h2>"
            + "<template><h2>模板内标题</h2></template>"
            + "</body>";
        HeadingOutline outline = parse(html);

        assertEquals(1, outline.size());
        assertEquals(1, outline.roots().size());
        assertEquals("可见标题", outline.roots().get(0).text());
    }

    /**
     * Headings inside script and style elements are excluded.
     */
    @Test
    void scriptStyleHeadingsExcluded() {
        String html = "<body>"
            + "<h2>可见标题</h2>"
            + "<script><h2>脚本内标题</h2></script>"
            + "<style>h2 { color: red; }</style>"
            + "</body>";
        HeadingOutline outline = parse(html);

        assertEquals(1, outline.size());
        assertEquals("可见标题", outline.roots().get(0).text());
    }

    /**
     * Duplicate ids are reported as conflicts but the originating nodes are not rewritten.
     */
    @Test
    void duplicateIdsMarkedConflictNotRewritten() {
        String html = "<body>"
            + "<h2 id=\"dup\">标题一</h2>"
            + "<h3 id=\"dup\">标题二</h3>"
            + "</body>";
        Document doc = Jsoup.parse(html);
        String before = doc.body().html();
        HeadingOutline outline = HeadingOutline.extract(doc.body());

        List<HeadingNode> headings = outline.headings();
        assertEquals(2, headings.size());
        for (HeadingNode h : headings) {
            assertEquals("dup", h.id());
            assertTrue(h.idConflict());
            assertFalse(h.idGenerated());
        }
        // the DOM is untouched: ids remain, no attributes were added or removed
        assertEquals(before, doc.body().html());
        Element h2 = doc.body().selectFirst("h2");
        Element h3 = doc.body().selectFirst("h3");
        assertEquals("dup", h2.id());
        assertEquals("dup", h3.id());
        assertEquals(1, h2.attributes().size());
        assertEquals(1, h3.attributes().size());
    }

    /**
     * Headings without an id get a stable, sequential id generated for the report only; the DOM is not modified.
     */
    @Test
    void generatedIdsAreReportOnly() {
        String html = "<body><h2>无ID标题</h2><h3>子标题</h3></body>";
        Document doc = Jsoup.parse(html);
        String before = doc.body().html();
        HeadingOutline outline = HeadingOutline.extract(doc.body());

        HeadingNode h2 = outline.roots().get(0);
        HeadingNode h3 = h2.children().get(0);
        assertEquals("heading-1", h2.id());
        assertTrue(h2.idGenerated());
        assertEquals("heading-2", h3.id());
        assertTrue(h3.idGenerated());
        // the DOM is untouched: no id attributes were written back
        assertFalse(h2.element().hasAttr("id"));
        assertFalse(h3.element().hasAttr("id"));
        assertEquals(before, doc.body().html());
    }

    /**
     * Existing ids are kept as-is and not marked as generated.
     */
    @Test
    void existingIdsKept() {
        String html = "<body><h2 id=\"keep-me\">标题</h2></body>";
        HeadingOutline outline = parse(html);

        HeadingNode h2 = outline.roots().get(0);
        assertEquals("keep-me", h2.id());
        assertFalse(h2.idGenerated());
        assertFalse(h2.idConflict());
    }

    /**
     * The sub-lists returned by heading nodes are independent copies: mutating one does not affect the outline.
     */
    @Test
    void sublistIndependence() {
        String html = "<body><h2>父标题</h2><h3>子标题一</h3><h3>子标题二</h3></body>";
        HeadingOutline outline = parse(html);

        HeadingNode h2 = outline.roots().get(0);
        int originalCount = h2.children().size();
        assertEquals(2, originalCount);

        // mutate the returned list; the outline must be unaffected
        List<HeadingNode> kids = h2.children();
        kids.clear();
        assertEquals(0, kids.size());
        assertEquals(originalCount, h2.children().size());
        assertEquals(2, outline.roots().get(0).children().size());

        // adding to a returned list must not affect the outline either
        List<HeadingNode> more = h2.children();
        more.add(new HeadingNode(6, "虚构", h2.element(), "heading-99", true, false));
        assertEquals(originalCount, h2.children().size());
    }

    /**
     * Demonstration: printing the outline tree and verifying the original DOM is unchanged.
     */
    @Test
    void printTreeAndDomUnchanged() {
        String html = "<body>"
            + "<h1>文档标题</h1>"
            + "<h2>章节一</h2>"
            + "<h3>小节</h3>"
            + "<h2>章节二</h2>"
            + "<template><h2>模板内</h2></template>"
            + "</body>";
        Document doc = Jsoup.parse(html);
        String beforeHtml = doc.body().html();

        HeadingOutline outline = HeadingOutline.extract(doc.body());
        String tree = outline.toTreeString();
        System.out.println("=== 标题大纲 ===");
        System.out.println(tree);

        // the tree reflects the hierarchy (generated ids are marked in the report)
        assertTrue(tree.contains("h1 [heading-1]"));
        assertTrue(tree.contains("文档标题"));
        assertTrue(tree.contains("h2 [heading-2]"));
        assertTrue(tree.contains("章节一"));
        assertTrue(tree.contains("h3 [heading-3]"));
        assertTrue(tree.contains("小节"));
        assertTrue(tree.contains("h2 [heading-4]"));
        assertTrue(tree.contains("章节二"));
        assertFalse(tree.contains("模板内"));

        // the original DOM is unchanged
        assertEquals(beforeHtml, doc.body().html());
        assertEquals(4, outline.size());
    }

    /**
     * An empty body yields an empty outline.
     */
    @Test
    void emptyOutline() {
        HeadingOutline outline = parse("<body><p>没有标题</p></body>");
        assertTrue(outline.isEmpty());
        assertEquals(0, outline.size());
        assertTrue(outline.roots().isEmpty());
        assertEquals("", outline.toTreeString());
    }
}
