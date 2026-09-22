package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.TextUtil;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.CleaningAction.Reason;
import org.jsoup.safety.CleaningAction.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for {@link Cleaner#cleanWithReport(Document, int, int)}.
 */
public class CleanerReportTest {
    private static final String BaseUri = "https://example.com/";

    private static Document tracked(String html) {
        return Parser.htmlParser().setTrackPosition(true).parseInput(html, BaseUri);
    }

    private static CleaningAction onlyAction(CleaningReport report) {
        assertEquals(1, report.actions().size());
        return report.actions().get(0);
    }

    @Test public void reportMatchesCleanAndExplainsDecisions() {
        String html = "<p onclick=\"bad()\">Hi <script>alert(1)</script>"
            + "<a href=\"javascript:steal()\">x</a> <a href=\"/ok\">ok</a> <a href=\"https://other.example/y\">y</a></p>";
        Document dirty = tracked(html);
        Cleaner cleaner = new Cleaner(Safelist.basic());

        CleaningReport report = cleaner.cleanWithReport(dirty, 100, 0);
        String expected = cleaner.clean(dirty).body().html();
        assertEquals(expected, report.document().body().html()); // identical to clean()
        // the same-site /ok link gets no enforced rel; the external one does
        assertEquals("<p>Hi <a rel=\"nofollow\">x</a> <a href=\"https://example.com/ok\">ok</a> "
                + "<a href=\"https://other.example/y\" rel=\"nofollow\">y</a></p>",
            TextUtil.stripNewlines(expected));

        List<CleaningAction> actions = report.actions();
        // p onclick removed; script element + its data removed; javascript: href removed; rel enforced on both links
        assertFalse(report.isTruncated());
        assertEquals(0, report.omittedActions());

        CleaningAction onclick = actions.get(0);
        assertEquals(Type.RemovedAttribute, onclick.type());
        assertEquals(Reason.DisallowedAttribute, onclick.reason());
        assertEquals("onclick", onclick.attribute());
        assertEquals("#root>html[0]>body[1]>p[0]", onclick.path());
        assertNotEquals(CleaningAction.UnknownSource, onclick.sourcePosition());
        assertNull(onclick.value()); // values not captured by default

        CleaningAction script = actions.get(1);
        assertEquals(Type.RemovedElement, script.type());
        assertEquals(Reason.DisallowedTag, script.reason());
        assertEquals("script", script.target());
        assertEquals("#root>html[0]>body[1]>p[0]>script[1]", script.path()); // text node is child 0

        CleaningAction data = actions.get(2);
        assertEquals(Type.RemovedNode, data.type());
        assertEquals(Reason.DisallowedNode, data.reason());
        assertEquals("#data", data.target());
        assertEquals("#root>html[0]>body[1]>p[0]>script[1]>#data[0]", data.path());

        CleaningAction jsHref = actions.get(3);
        assertEquals(Type.RemovedAttribute, jsHref.type());
        assertEquals(Reason.DisallowedProtocol, jsHref.reason());
        assertEquals("href", jsHref.attribute());
        assertEquals("#root>html[0]>body[1]>p[0]>a[2]", jsHref.path());

        CleaningAction rel1 = actions.get(4);
        assertEquals(Type.EnforcedAttribute, rel1.type());
        assertEquals(Reason.EnforcedAttribute, rel1.reason());
        assertEquals("rel", rel1.attribute());
        assertFalse(rel1.replaced()); // newly added
        assertEquals("#root>html[0]>body[1]>p[0]>a[2]", rel1.path());

        CleaningAction rel2 = actions.get(5);
        assertEquals(Type.EnforcedAttribute, rel2.type());
        assertEquals("#root>html[0]>body[1]>p[0]>a[6]", rel2.path());
        assertEquals(6, actions.size());

        // every action has a tracked source position here
        for (CleaningAction action : actions)
            assertTrue(action.sourcePosition().matches("\\d+,\\d+:\\d+-\\d+,\\d+:\\d+"), action.sourcePosition());
    }

    @Test public void pathsUsePreCleanStableChildIndexes() {
        // b follows the removed script in the source, so its path index is 1 even though it is child 0 in the output
        String html = "<span><script>x</script><b onclick=\"y\">hi</b></span>";
        CleaningReport report = new Cleaner(Safelist.relaxed()).cleanWithReport(Jsoup.parse(html), 100, 0);
        assertEquals("<span><b>hi</b></span>", TextUtil.stripNewlines(report.document().body().html()));

        List<CleaningAction> actions = report.actions();
        assertEquals(3, actions.size());
        assertEquals("#root>html[0]>body[1]>span[0]>script[0]", actions.get(0).path());
        assertEquals("#root>html[0]>body[1]>span[0]>script[0]>#data[0]", actions.get(1).path());
        CleaningAction onclick = actions.get(2);
        assertEquals(Type.RemovedAttribute, onclick.type());
        assertEquals("#root>html[0]>body[1]>span[0]>b[1]", onclick.path());
    }

    @Test public void deeplyNestedUnsafeTagsLocated() {
        String html = "<div><div><div><section><b>deep</b></section></div></div></div>";
        CleaningReport report = new Cleaner(Safelist.relaxed()).cleanWithReport(Jsoup.parse(html), 100, 0);
        assertEquals("<div><div><div><b>deep</b></div></div></div>", TextUtil.stripNewlines(report.document().body().html()));

        CleaningAction section = onlyAction(report);
        assertEquals(Type.RemovedElement, section.type());
        assertEquals(Reason.DisallowedTag, section.reason());
        assertEquals("section", section.target());
        assertEquals("#root>html[0]>body[1]>div[0]>div[0]>div[0]>section[0]", section.path());
    }

    @Test public void enforcedAttributeOverrideRecorded() {
        String html = "<a href=\"https://elsewhere.example/\" rel=\"follow\">t</a>";
        CleaningReport report = new Cleaner(Safelist.basic()).cleanWithReport(tracked(html), 100, 16);
        assertEquals("<a href=\"https://elsewhere.example/\" rel=\"nofollow\">t</a>",
            TextUtil.stripNewlines(report.document().body().html()));

        CleaningAction rel = onlyAction(report);
        assertEquals(Type.EnforcedAttribute, rel.type());
        assertEquals(Reason.EnforcedAttribute, rel.reason());
        assertEquals("rel", rel.attribute());
        assertTrue(rel.replaced());
        assertEquals("follow", rel.value()); // overridden source value, captured within the length limit
    }

    @Test public void valueSummaryIsOptInAndLengthLimited() {
        String html = "<p onmouseover=\"doSomethingQuiteLengthy()\">hi</p>";
        Cleaner cleaner = new Cleaner(Safelist.relaxed());

        CleaningReport noValues = cleaner.cleanWithReport(Jsoup.parse(html), 100, 0);
        assertNull(onlyAction(noValues).value());

        CleaningReport full = cleaner.cleanWithReport(Jsoup.parse(html), 100, 64);
        assertEquals("doSomethingQuiteLengthy()", onlyAction(full).value());

        CleaningReport capped = cleaner.cleanWithReport(Jsoup.parse(html), 100, 8);
        assertEquals("doSometh\u2026", onlyAction(capped).value());
    }

    @Test public void truncationCapsReportButNotCleaning() {
        String html = "<p onclick=\"a\" onmouseover=\"b\"><script>x</script><script>y</script></p>";
        Document dirty = Jsoup.parse(html);
        Cleaner cleaner = new Cleaner(Safelist.relaxed());

        CleaningReport report = cleaner.cleanWithReport(dirty, 1, 0);
        assertEquals(cleaner.clean(dirty).body().html(), report.document().body().html()); // cleaning unaffected
        assertEquals(1, report.actions().size());
        assertTrue(report.isTruncated());

        CleaningReport full = cleaner.cleanWithReport(dirty, 100, 0);
        assertFalse(full.isTruncated());
        assertEquals(full.actions().size() - 1, report.omittedActions());
        assertEquals(full.actions().get(0).toJson(), report.actions().get(0).toJson()); // same first action
    }

    @Test public void untrackedPositionsAreUnknown() {
        // no position tracking; leading p keeps the script in the body
        Document dirty = Jsoup.parse("<p onclick=\"y\">hi</p><script>x</script>");
        CleaningReport report = new Cleaner(Safelist.relaxed()).cleanWithReport(dirty, 100, 0);
        assertEquals(3, report.actions().size());
        for (CleaningAction action : report.actions())
            assertEquals(CleaningAction.UnknownSource, action.sourcePosition());
    }

    @Test public void recleaningCleanDocumentProducesNoActions() {
        Cleaner cleaner = new Cleaner(Safelist.basic());
        Document clean = cleaner.clean(Jsoup.parse("<p>Hi <a href=\"https://example.com/\">there</a></p>", BaseUri));

        CleaningReport report = cleaner.cleanWithReport(clean, 100, 0);
        assertTrue(report.actions().isEmpty());
        assertFalse(report.isTruncated());
        assertEquals(clean.body().html(), report.document().body().html());
    }

    @Test public void inputDocumentIsNotModified() {
        Document dirty = tracked("<p onclick=\"x\"><script>alert(1)</script>hi</p>");
        String before = dirty.html();
        new Cleaner(Safelist.basic()).cleanWithReport(dirty, 100, 32);
        assertEquals(before, dirty.html());
    }

    @Test public void attributeStatusAgreesWithIsSafeAttribute() {
        Document doc = Jsoup.parse(
            "<a href=\"http://ok\" rel=\"nofollow\">a</a><a href=\"javascript:x\" rel=\"follow\">b</a><p onclick=\"y\">c</p>");
        Safelist safelist = Safelist.basic();
        doc.body().getAllElements().forEach(el ->
            el.attributes().forEach(attr ->
                assertEquals(safelist.isSafeAttribute(el.tagName(), el, attr),
                    safelist.attributeStatus(el.tagName(), el, attr) == Safelist.AttributeStatus.Allowed,
                    el.tagName() + " " + attr.getKey())));
    }

    @Test public void reportJsonIsWellFormed() {
        String html = "<p onclick=\"say(&quot;hi&quot;)\">x</p>";
        CleaningReport report = new Cleaner(Safelist.relaxed()).cleanWithReport(Jsoup.parse(html), 5, 32);
        String json = report.toJson();
        assertTrue(json.startsWith("{\"truncated\":false,\"omittedActions\":0,\"actions\":[{"));
        assertTrue(json.contains("\"type\":\"RemovedAttribute\""));
        assertTrue(json.contains("\"reason\":\"DisallowedAttribute\""));
        assertTrue(json.contains("\"value\":\"say(\\\"hi\\\")\""), json); // quotes escaped
        assertTrue(json.endsWith("]}"));
        assertEquals(json, report.toString());
    }

    @Test public void commentsAndOtherNodesReported() {
        String html = "<p>one<!-- two --></p><?proc inst?>";
        CleaningReport report = new Cleaner(Safelist.relaxed()).cleanWithReport(Jsoup.parse(html), 100, 8);
        assertEquals("<p>one</p>", TextUtil.stripNewlines(report.document().body().html()));

        List<CleaningAction> actions = report.actions();
        assertEquals(2, actions.size());
        CleaningAction comment = actions.get(0);
        assertEquals(Type.RemovedNode, comment.type());
        assertEquals("#comment", comment.target());
        assertEquals("#root>html[0]>body[1]>p[0]>#comment[1]", comment.path());
        assertEquals(" two ", comment.value());
        assertEquals("#processing-instruction", actions.get(1).target());
    }

    @Test public void actionListIsUnmodifiableAndArgsValidated() {
        CleaningReport report = new Cleaner(Safelist.none()).cleanWithReport(Jsoup.parse("<b>x</b>"), 10, 0);
        assertThrows(UnsupportedOperationException.class, () -> report.actions().clear());
        Cleaner cleaner = new Cleaner(Safelist.none());
        Document doc = Jsoup.parse("<b>x</b>");
        assertThrows(IllegalArgumentException.class, () -> cleaner.cleanWithReport(doc, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> cleaner.cleanWithReport(doc, 0, -1));
    }

    @Test public void reportSerializesActionsInOrder() {
        String html = "x<script>a</script><p onclick=\"b\">c</p><script>d</script>"; // leading text keeps scripts in body
        CleaningReport report = new Cleaner(Safelist.relaxed()).cleanWithReport(Jsoup.parse(html), 100, 0);
        List<String> types = report.actions().stream().map(a -> a.type().name()).collect(Collectors.toList());
        assertEquals(Arrays.asList(
            "RemovedElement", "RemovedNode", "RemovedAttribute", "RemovedElement", "RemovedNode"), types);
    }
}
