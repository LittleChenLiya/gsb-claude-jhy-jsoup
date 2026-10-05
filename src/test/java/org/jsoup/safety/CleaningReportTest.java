package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.CleaningReport.Conversion;
import org.jsoup.safety.CleaningReport.Entry;
import org.jsoup.safety.CleaningReport.Kind;
import org.jsoup.safety.CleaningReport.Reason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for {@link CleaningReport}: removals and link conversions recorded during a single clean pass.
 */
public class CleaningReportTest {
    private static final String Base = "https://example.com/";

    private static Document dirty(String html) {
        return Jsoup.parse(html, Base);
    }

    @Test public void scriptOnclickAndProtocolReasonsAreAccurate() {
        Document dirty = dirty(
            "<div><script>alert(1)</script>" +
            "<p onclick='x()'>Hi</p>" +
            "<a href='javascript:bad()'>bad</a> " +
            "<a href='https://ok.com/'>ok</a></div>");
        Cleaner cleaner = new Cleaner(Safelist.relaxed());
        CleaningReport report = new CleaningReport();

        Document clean = cleaner.clean(dirty, report);
        String cleanHtml = clean.body().html();

        // the allowed https link is preserved; the dangerous content is gone
        assertTrue(cleanHtml.contains("https://ok.com/"));
        assertFalse(cleanHtml.contains("javascript:"));
        assertFalse(cleanHtml.contains("script"));
        assertFalse(cleanHtml.contains("onclick"));

        // script node removed with UnsafeTag reason, located by original DOM identity
        List<Entry> removedNodes = report.entries(Kind.RemovedNode);
        assertEquals(1, removedNodes.size());
        Entry scriptEntry = removedNodes.get(0);
        assertSame(dirty.selectFirst("script"), scriptEntry.source());
        assertEquals(Reason.UnsafeTag, scriptEntry.reason());
        assertTrue(scriptEntry.rule().contains("script"), scriptEntry.rule());

        // onclick removed with UnsafeAttribute reason
        List<Entry> removedAttrs = report.entries(Kind.RemovedAttribute);
        assertEquals(2, removedAttrs.size());
        Entry onclick = removedAttrs.get(0);
        assertEquals(Reason.UnsafeAttribute, onclick.reason());
        assertEquals("onclick", onclick.attributeName());
        assertSame(dirty.selectFirst("p"), onclick.source());
        assertTrue(onclick.rule().contains("onclick"), onclick.rule());

        // javascript: href removed with UnsafeProtocol reason
        Entry jsHref = removedAttrs.get(1);
        assertEquals(Reason.UnsafeProtocol, jsHref.reason());
        assertEquals("href", jsHref.attributeName());
        assertSame(dirty.select("a").get(0), jsHref.source());
        assertTrue(jsHref.rule().contains("http"), jsHref.rule());

        // no entry for the allowed https link
        for (Entry entry : report.entries())
            assertNotSame(dirty.select("a").get(1), entry.source());
    }

    @Test public void scriptSubtreeIsCountedOnce() {
        Document dirty = dirty("<div><script>var x = 1; alert(x);</script><p>ok</p></div>");
        CleaningReport report = new CleaningReport();
        new Cleaner(Safelist.relaxed()).clean(dirty, report);

        List<Entry> removedNodes = report.entries(Kind.RemovedNode);
        assertEquals(1, removedNodes.size()); // one entry for the whole subtree
        Entry script = removedNodes.get(0);
        assertSame(dirty.selectFirst("script"), script.source());
        assertEquals(1, script.descendantCount()); // the script data child is folded in, not separately reported
        assertEquals(2, report.totalRemovedNodes()); // script element + its data node
        assertEquals(1, report.removedNodeCount());
    }

    @Test public void descendantsOfRemovedParentFoldIntoSameReason() {
        Document dirty = dirty("<div><custom-el><script>alert(1)</script><b onclick='y()'>kept</b></custom-el></div>");
        CleaningReport report = new CleaningReport();
        Document clean = new Cleaner(Safelist.relaxed()).clean(dirty, report);

        // custom-el and its discarded descendants (script element + data node) fold into one entry
        List<Entry> removedNodes = report.entries(Kind.RemovedNode);
        assertEquals(1, removedNodes.size());
        Entry custom = removedNodes.get(0);
        assertSame(dirty.selectFirst("custom-el"), custom.source());
        assertEquals(Reason.UnsafeTag, custom.reason());
        assertEquals(2, custom.descendantCount()); // <script> and its data node; the safe <b> is copied out, not counted
        assertEquals(3, report.totalRemovedNodes());

        // the safe <b> child survives in the output; its own unsafe attribute is an independent entry
        assertTrue(clean.body().html().contains("<b>kept</b>"));
        List<Entry> attrs = report.entries(Kind.RemovedAttribute);
        assertEquals(1, attrs.size());
        assertEquals("onclick", attrs.get(0).attributeName());
        assertSame(dirty.selectFirst("b"), attrs.get(0).source());
    }

    @Test public void relativeLinkConversionKeepsSourcePositionAndCategory() {
        Document dirty = dirty("<a href='/rel/path?q=1'>rel</a> <a href='https://ok.com/'>abs</a>");
        CleaningReport report = new CleaningReport();
        Document clean = new Cleaner(Safelist.relaxed()).clean(dirty, report);

        List<Entry> conversions = report.entries(Kind.ConvertedLink);
        assertEquals(1, conversions.size()); // only the relative one was rewritten
        Entry conversion = conversions.get(0);
        assertEquals(Conversion.RelativeToAbsolute, conversion.conversion());
        assertSame(dirty.select("a").get(0), conversion.source()); // original DOM identity
        assertEquals("href", conversion.attributeName());
        assertEquals("/rel/path?q=1", conversion.originalValue());
        assertEquals("https://example.com/rel/path?q=1", conversion.convertedValue());
        assertTrue(clean.body().html().contains("https://example.com/rel/path?q=1"));
    }

    @Test public void unresolvableRelativeLinkIsKeptAndCategorized() {
        // an anchor link passes the safelist ("#" protocol) but cannot be made absolute without a base URI, so it is kept as-is
        Document dirty = Jsoup.parse("<a href='#frag'>anchor</a>"); // no base URI
        Safelist safelist = Safelist.relaxed().addProtocols("a", "href", "#");
        CleaningReport report = new CleaningReport();
        Document clean = new Cleaner(safelist).clean(dirty, report);

        List<Entry> conversions = report.entries(Kind.ConvertedLink);
        assertEquals(1, conversions.size());
        assertEquals(Conversion.UnresolvableKept, conversions.get(0).conversion());
        assertEquals("#frag", conversions.get(0).originalValue());
        assertTrue(clean.body().html().contains("#frag"));
    }

    @Test public void reportDoesNotRetainDangerousAttributeValues() {
        Document dirty = dirty("<p onclick='steal()'>x</p><a href='javascript:evil(1)'>y</a>");
        CleaningReport report = new CleaningReport();
        new Cleaner(Safelist.relaxed()).clean(dirty, report);

        assertEquals(2, report.entries(Kind.RemovedAttribute).size());
        for (Entry entry : report.entries()) {
            assertNull(entry.originalValue());
            assertNull(entry.convertedValue());
        }
        String rendered = report.toString();
        assertFalse(rendered.contains("steal()"), rendered);
        assertFalse(rendered.contains("evil(1)"), rendered);
        assertFalse(rendered.contains("javascript:"), rendered);
    }

    @Test public void cleanResultIsIdenticalWithAndWithoutReport() {
        String html = "<div><script>alert(1)</script><p onclick='x()'>Hi</p>" +
            "<a href='/rel'>r</a> <a href='javascript:bad()'>b</a> <a href='https://ok.com/'>ok</a><!-- c --></div>";
        Cleaner cleaner = new Cleaner(Safelist.relaxed());

        Document withoutReport = cleaner.clean(dirty(html));
        CleaningReport report = new CleaningReport();
        Document withReport = cleaner.clean(dirty(html), report);

        assertEquals(withoutReport.html(), withReport.html());
        assertFalse(report.isClean());
        assertFalse(cleaner.isValid(dirty(html)));
    }

    @Test public void cleanDocumentProducesEmptyReport() {
        Document dirty = dirty("<p>Hello <b>there</b>!</p>");
        CleaningReport report = new CleaningReport();
        new Cleaner(Safelist.relaxed()).clean(dirty, report);
        assertTrue(report.isClean());
        assertEquals(0, report.totalRemovedNodes());
    }
}
