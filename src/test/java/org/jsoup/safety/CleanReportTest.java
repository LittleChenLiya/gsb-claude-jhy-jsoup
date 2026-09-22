package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CleanReportTest {
    private static final String BaseUri = "https://site.example.com/";

    private static final String Dirty =
        "<p onclick=\"steal()\">Hello <script>alert(1)</script>world</p>" +
        "<a href=\"javascript:alert(1)\">bad</a>" +
        "<a href=\"/relative\">rel</a>" +
        "<a href=\"https://ext.example.com/\">ext</a>" +
        "<a href=\"https://ext.example.com/\" rel=\"noopener\">ovr</a>";

    private static Document dirty() {
        return Jsoup.parse(Dirty, BaseUri);
    }

    private static CleanReport.Entry find(List<CleanReport.Entry> entries, CleanReport.Action action, String pathPrefix) {
        for (CleanReport.Entry entry : entries) {
            if (entry.action() == action && entry.path().startsWith(pathPrefix)) return entry;
        }
        return null;
    }

    @Test public void cleanWithReportMatchesCleanAndLeavesInputAlone() {
        Cleaner cleaner = new Cleaner(Safelist.basic());
        Document doc = dirty();
        String before = doc.html();

        CleanResult result = cleaner.cleanWithReport(doc);

        assertEquals(cleaner.clean(dirty()).body().html(), result.document().body().html());
        assertEquals(before, doc.html()); // input document not modified
    }

    @Test public void reportsEachRemovalAndOverrideWithPathAndReason() {
        CleanResult result = new Cleaner(Safelist.basic()).cleanWithReport(dirty());
        List<CleanReport.Entry> entries = result.report().entries();
        assertFalse(result.report().isTruncated());
        assertEquals(0, result.report().omittedCount());

        CleanReport.Entry script = find(entries, CleanReport.Action.RemovedElement, "/html[0]/body[1]/p[0]/script[1]");
        assertNotNull(script, "script removal reported with pre-clean path (text sibling shifts its index)");
        assertEquals(CleanReport.Reason.TagNotAllowed, script.reason());
        assertEquals("script", script.tagName());

        CleanReport.Entry onclick = find(entries, CleanReport.Action.RemovedAttribute, "/html[0]/body[1]/p[0]/@onclick");
        assertNotNull(onclick);
        assertEquals(CleanReport.Reason.AttributeNotAllowed, onclick.reason());
        assertEquals("onclick", onclick.attributeName());

        CleanReport.Entry jsHref = find(entries, CleanReport.Action.RemovedAttribute, "/html[0]/body[1]/a[1]/@href");
        assertNotNull(jsHref);
        assertEquals(CleanReport.Reason.ProtocolNotAllowed, jsHref.reason());

        // relative URL resolved against the base URI, same host: kept, no enforced rel
        assertNull(find(entries, CleanReport.Action.RemovedAttribute, "/html[0]/body[1]/a[2]/@href"));
        assertNull(find(entries, CleanReport.Action.EnforcedAttribute, "/html[0]/body[1]/a[2]/@rel"));

        // external link: enforced rel=nofollow added
        CleanReport.Entry enforced = find(entries, CleanReport.Action.EnforcedAttribute, "/html[0]/body[1]/a[3]/@rel");
        assertNotNull(enforced);
        assertEquals(CleanReport.Reason.EnforcedAttributeAdded, enforced.reason());

        // existing rel=noopener removed and overridden by the enforced value
        CleanReport.Entry removedRel = find(entries, CleanReport.Action.RemovedAttribute, "/html[0]/body[1]/a[4]/@rel");
        assertNotNull(removedRel);
        assertEquals(CleanReport.Reason.AttributeNotAllowed, removedRel.reason());
        CleanReport.Entry override = find(entries, CleanReport.Action.EnforcedAttribute, "/html[0]/body[1]/a[4]/@rel");
        assertNotNull(override);
        assertEquals(CleanReport.Reason.EnforcedAttributeOverride, override.reason());

        // script data content discarded as a non-safe node
        CleanReport.Entry data = find(entries, CleanReport.Action.RemovedNode, "/html[0]/body[1]/p[0]/script[1]/#data[0]");
        assertNotNull(data);
        assertEquals(CleanReport.Reason.NodeTypeNotAllowed, data.reason());
    }

    @Test public void truncationCapsReportButNotCleaning() {
        Cleaner cleaner = new Cleaner(Safelist.basic());
        CleanResult full = cleaner.cleanWithReport(dirty());
        int total = full.report().entries().size();
        assertTrue(total > 1);

        CleanResult capped = cleaner.cleanWithReport(dirty(), new CleanReport.Options().maxEntries(1));
        assertEquals(1, capped.report().entries().size());
        assertTrue(capped.report().isTruncated());
        assertEquals(total - 1, capped.report().omittedCount());
        // cleaning is unaffected by the cap
        assertEquals(full.document().body().html(), capped.document().body().html());
        assertEquals(cleaner.clean(dirty()).body().html(), capped.document().body().html());
    }

    @Test public void locatesSafeChildrenInsideRemovedParents() {
        Document doc = Jsoup.parse("<bad><worse><i onclick=\"x\">ok</i></worse></bad>", BaseUri);
        CleanResult result = new Cleaner(Safelist.basic()).cleanWithReport(doc);

        assertEquals("<i>ok</i>", result.document().body().html());
        CleanReport.Entry entry = find(result.report().entries(), CleanReport.Action.RemovedAttribute, "/html[0]/body[1]/bad[0]/worse[0]/i[0]/@onclick");
        assertNotNull(entry, "path uses the original tree, not the cleaned DOM");
        assertNotNull(find(result.report().entries(), CleanReport.Action.RemovedElement, "/html[0]/body[1]/bad[0]/worse[0]"));
    }

    @Test public void textNodesCountTowardStableChildIndexes() {
        Document doc = Jsoup.parse("Hello<script>alert(1)</script>world", BaseUri);
        CleanResult result = new Cleaner(Safelist.basic()).cleanWithReport(doc);
        CleanReport.Entry script = find(result.report().entries(), CleanReport.Action.RemovedElement, "/html[0]/body[1]/script[1]");
        assertNotNull(script, "leading text node makes the script child index 1");
    }

    @Test public void sourcePositionsTrackedWhenAvailableElseUnknown() {
        Cleaner cleaner = new Cleaner(Safelist.basic());

        Document untracked = dirty();
        for (CleanReport.Entry entry : cleaner.cleanWithReport(untracked).report().entries())
            assertEquals("unknown", entry.position());

        Parser parser = Parser.htmlParser().setTrackPosition(true);
        Document tracked = Jsoup.parse(Dirty, BaseUri, parser);
        CleanReport.Entry script = find(cleaner.cleanWithReport(tracked).report().entries(),
            CleanReport.Action.RemovedElement, "/html[0]/body[1]/p[0]/script[1]");
        assertNotNull(script);
        assertNotEquals("unknown", script.position());
        assertTrue(script.position().contains("1,"), "carries line/column info: " + script.position());
    }

    @Test public void recleaningACleanDocumentReportsNothing() {
        Cleaner cleaner = new Cleaner(Safelist.basic());
        Document clean = cleaner.clean(dirty());
        CleanResult again = cleaner.cleanWithReport(clean);
        assertTrue(again.report().entries().isEmpty());
        assertEquals(clean.body().html(), again.document().body().html());
    }

    @Test public void originalValuesOnlyRecordedOnOptInAndLengthLimited() {
        Cleaner cleaner = new Cleaner(Safelist.basic());

        CleanReport.Entry def = find(cleaner.cleanWithReport(dirty()).report().entries(),
            CleanReport.Action.RemovedAttribute, "/html[0]/body[1]/p[0]/@onclick");
        assertNotNull(def);
        assertNull(def.originalValue());

        CleanReport.Entry opted = find(cleaner.cleanWithReport(dirty(),
                new CleanReport.Options().includeOriginalValues(true).maxValueLength(4)).report().entries(),
            CleanReport.Action.RemovedAttribute, "/html[0]/body[1]/p[0]/@onclick");
        assertNotNull(opted);
        assertEquals("stea…", opted.originalValue());
    }

    @Test public void commentsAreReportedAsRemovedNodes() {
        Document doc = Jsoup.parse("<p>ok</p><!-- secret -->", BaseUri);
        CleanResult result = new Cleaner(Safelist.basic()).cleanWithReport(doc);
        CleanReport.Entry comment = find(result.report().entries(), CleanReport.Action.RemovedNode, "/html[0]/body[1]/#comment[1]");
        assertNotNull(comment);
        assertEquals(CleanReport.Reason.NodeTypeNotAllowed, comment.reason());
    }

    @Test public void acceptanceDemoPrintsInputCleanedHtmlAndAuditJson() {
        Cleaner cleaner = new Cleaner(Safelist.basic());
        Document doc = dirty();

        CleanResult result = cleaner.cleanWithReport(doc);
        assertEquals(cleaner.clean(dirty()).body().html(), result.document().body().html());

        System.out.println("=== INPUT ===");
        System.out.println(Dirty);
        System.out.println("=== CLEANED ===");
        System.out.println(result.document().body().html());
        System.out.println("=== AUDIT JSON ===");
        System.out.println(result.report().toJson());

        CleanResult capped = cleaner.cleanWithReport(dirty(), new CleanReport.Options().maxEntries(1));
        assertEquals(result.document().body().html(), capped.document().body().html());
        System.out.println("=== AUDIT JSON (maxEntries=1; cleaning unaffected) ===");
        System.out.println(capped.report().toJson());
        System.out.println("=== CLEANED (capped report) ===");
        System.out.println(capped.document().body().html());
    }
}
