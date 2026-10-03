package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.LinkAuditor.Finding;
import org.jsoup.safety.LinkAuditor.Report;
import org.jsoup.safety.LinkAuditor.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for the read-only {@link LinkAuditor}.
 */
public class LinkAuditorTest {
    private static final String Base = "https://safe.example/a/";

    private static LinkAuditor auditor() {
        return new LinkAuditor().allow("safe.example");
    }

    private static Report audit(String html, String base) {
        return auditor().audit(Jsoup.parse(html, base));
    }

    @Test
    public void relativeLinkResolvesAgainstElementBaseUri() {
        Report report = audit("<p><a href=\"../b\">ok</a></p>", Base);
        assertTrue(report.isClean(), report.toString());
    }

    @Test
    public void lookAlikeHostIsRejected() {
        // safe.example.evil.test only *starts with* the allowed name; must not match
        Report report = audit("<a href=\"https://safe.example.evil.test/x\">x</a>", Base);
        assertEquals(1, report.size());
        Finding f = report.findings().get(0);
        assertEquals(Status.HOST_NOT_ALLOWED, f.status());
        assertEquals("https://safe.example.evil.test/x", f.value());
        assertEquals("https://safe.example.evil.test/x", f.resolvedUrl());
    }

    @Test
    public void prefixAndSuffixAttacksDoNotMatch() {
        LinkAuditor a = auditor();
        assertTrue(a.isAllowedHost("safe.example"));
        assertFalse(a.isAllowedHost("safe.example.evil.test"));
        assertFalse(a.isAllowedHost("unsafe.example"));
        assertFalse(a.isAllowedHost("safe.examplex"));
        assertFalse(a.isAllowedHost("safe.example.evil.example"));
    }

    @Test
    public void javascriptSchemeIsRejected() {
        Report report = audit("<a href=\"javascript:alert(1)\">x</a>", Base);
        assertEquals(1, report.size());
        Finding f = report.findings().get(0);
        assertEquals(Status.DANGEROUS_SCHEME, f.status());
        assertEquals("javascript:alert(1)", f.value());
    }

    @Test
    public void dangerousSchemesCaseInsensitiveAndVaried() {
        Report report = audit(
            "<a href=\"JaVaScRiPt:alert(1)\">a</a>"
                + "<a href=\"data:text/html;base64,AAAA\">b</a>"
                + "<a href=\"file:///etc/passwd\">c</a>"
                + "<img src=\"vbscript:x\">", Base);
        assertEquals(4, report.size());
        for (Finding f : report.findings())
            assertEquals(Status.DANGEROUS_SCHEME, f.status());
    }

    @Test
    public void userInfoIsJudgedByRealHost() {
        // user@safe.example: real host is safe.example -> allowed
        Report ok = audit("<a href=\"https://user@safe.example/in\">x</a>", Base);
        assertTrue(ok.isClean(), ok.toString());

        // safe.example@evil.test: real host is evil.test -> rejected
        Report bad = audit("<a href=\"https://safe.example@evil.test/\">x</a>", Base);
        assertEquals(1, bad.size());
        Finding f = bad.findings().get(0);
        assertEquals(Status.HOST_NOT_ALLOWED, f.status());
        assertEquals("https://safe.example@evil.test/", f.value()); // original text preserved
        assertEquals("https://evil.test/", f.resolvedUrl()); // user-info stripped in normalized form
    }

    @Test
    public void hostCaseAndDefaultPortAreConsistent() {
        // upper-case scheme+host and explicit default port behave exactly like the canonical form
        Report report = audit(
            "<a href=\"HTTPS://SAFE.EXAMPLE:443/b\">a</a>"
                + "<img src=\"http://Safe.Example.:80/img.png\">", Base);
        assertTrue(report.isClean(), report.toString());

        LinkAuditor a = auditor();
        assertTrue(a.isAllowedHost("SAFE.EXAMPLE"));
        assertTrue(a.isAllowedHost("safe.example."));
        assertTrue(a.isAllowedHost(" Safe.Example "));
    }

    @Test
    public void nonDefaultPortStillMatchesByHost() {
        // host rules are port-independent; a non-default port on an allowed host still passes
        Report report = audit("<a href=\"https://safe.example:8443/x\">x</a>", Base);
        assertTrue(report.isClean(), report.toString());
    }

    @Test
    public void normalizedUrlDropsDefaultPortAndUserInfo() {
        Report report = audit("<a href=\"https://user@evil.test:443/x\">x</a>", Base);
        assertEquals(1, report.size());
        assertEquals("https://evil.test/x", report.findings().get(0).resolvedUrl());
    }

    @Test
    public void subdomainRuleMatchesOnDotBoundaries() {
        LinkAuditor a = new LinkAuditor().allowSubdomains("example.com");
        assertTrue(a.isAllowedHost("example.com"));
        assertTrue(a.isAllowedHost("a.example.com"));
        assertTrue(a.isAllowedHost("a.b.example.com"));
        assertFalse(a.isAllowedHost("notexample.com"));
        assertFalse(a.isAllowedHost("example.com.evil.test"));
        assertFalse(a.isAllowedHost("example.comm"));

        Report ok = new LinkAuditor().allowSubdomains("cdn.safe.example")
            .audit(Jsoup.parse("<img src=\"https://cdn.safe.example/i.png\">", Base));
        assertTrue(ok.isClean(), ok.toString());
    }

    @Test
    public void baseElementIsHonored() {
        // no parser base URI; the <base href> in the head supplies the base for body elements
        String html = "<html><head><base href=\"https://safe.example/a/\"></head>"
            + "<body><a href=\"../b\">ok</a><a href=\"/c\">ok2</a></body></html>";
        Report report = auditor().audit(Jsoup.parse(html));
        assertTrue(report.isClean(), report.toString());
    }

    @Test
    public void baseElementCanAlsoExposeBadLinks() {
        String html = "<html><head><base href=\"https://safe.example/a/\"></head>"
            + "<body><a href=\"https://evil.test/x\">bad</a></body></html>";
        Report report = auditor().audit(Jsoup.parse(html));
        assertEquals(1, report.size());
        assertEquals(Status.HOST_NOT_ALLOWED, report.findings().get(0).status());
    }

    @Test
    public void xmlModeIsAudited() {
        String xml = "<?xml version=\"1.0\"?><root><a href=\"../b\">ok</a>"
            + "<a HREF=\"javascript:alert(1)\">bad</a>"
            + "<img src=\"https://evil.test/i.png\"/></root>";
        Document doc = Jsoup.parse(xml, Base, Parser.xmlParser());
        Report report = auditor().audit(doc);
        assertEquals(2, report.size(), report.toString());
        List<Finding> findings = report.findings();
        assertEquals(Status.DANGEROUS_SCHEME, findings.get(0).status());
        assertEquals(Status.HOST_NOT_ALLOWED, findings.get(1).status());
    }

    @Test
    public void emptyValuesAreSkipped() {
        Report report = audit(
            "<a href=\"\">empty</a><a href=\"   \">blank</a><img src=\"\"><a>no attr</a>", Base);
        assertTrue(report.isClean(), report.toString());
    }

    @Test
    public void relativeUrlWithoutBaseIsReportedSeparately() {
        Report report = auditor().audit(Jsoup.parse("<a href=\"../b\">x</a>"));
        assertEquals(1, report.size());
        Finding f = report.findings().get(0);
        assertEquals(Status.NO_BASE, f.status());
        assertNull(f.resolvedUrl());

        // an absolute URL needs no base and is still checked by host
        Report abs = auditor().audit(Jsoup.parse("<a href=\"https://safe.example/x\">x</a>"));
        assertTrue(abs.isClean(), abs.toString());
    }

    @Test
    public void parseFailureIsReportedSeparately() {
        Report report = audit("<a href=\"https://safe.example/%\">bad escape</a>", Base);
        assertEquals(1, report.size());
        assertEquals(Status.PARSE_FAILURE, report.findings().get(0).status());
    }

    @Test
    public void protocolRelativeUrlsResolveAgainstBaseScheme() {
        Report ok = audit("<img src=\"//safe.example/i.png\">", Base);
        assertTrue(ok.isClean(), ok.toString());

        Report bad = audit("<img src=\"//evil.test/i.png\">", Base);
        assertEquals(1, bad.size());
        assertEquals(Status.HOST_NOT_ALLOWED, bad.findings().get(0).status());
    }

    @Test
    public void auditDoesNotModifyDom() {
        String html = "<p><a href=\"https://evil.test/x\">bad</a><img src=\"javascript:x\"></p>";
        Document doc = Jsoup.parse(html, Base);
        String before = doc.html();
        Report report = auditor().audit(doc);
        assertEquals(2, report.size());
        assertEquals(before, doc.html()); // read-only: DOM untouched
    }

    @Test
    public void findingsKeepLocationAndOriginalText() {
        String html = "<body><div><p><a href=\"https://evil.test/x\">label</a></p></div>";
        Parser parser = Parser.htmlParser().setTrackPosition(true);
        Document doc = parser.parseInput(html, Base);
        Report report = auditor().audit(doc);
        assertEquals(1, report.size());
        Finding f = report.findings().get(0);
        assertEquals("https://evil.test/x", f.value());
        assertEquals("href", f.attribute());
        assertEquals("a", f.element().tagName());
        assertTrue(f.element().cssSelector().contains("a"));
        assertTrue(f.sourceRange().isTracked());
        assertEquals(1, f.sourceRange().start().lineNumber());
        assertTrue(f.toString().contains("HOST_NOT_ALLOWED"));
        assertTrue(f.toString().contains("https://evil.test/x"));
    }

    @Test
    public void extraAttributesCanBeAudited() {
        LinkAuditor a = new LinkAuditor().allow("safe.example").auditAttribute("action");
        Report report = a.audit(Jsoup.parse(
            "<form action=\"https://evil.test/post\"><button formaction=\"/ok\">go</button></form>", Base));
        assertEquals(1, report.size());
        Finding f = report.findings().get(0);
        assertEquals("action", f.attribute());
        assertEquals(Status.HOST_NOT_ALLOWED, f.status());
    }

    @Test
    public void reportGroupsFindingsByStatus() {
        Report report = audit(
            "<a href=\"javascript:x\">a</a><a href=\"https://evil.test/\">b</a>", Base);
        assertFalse(report.isClean());
        assertEquals(2, report.size());
        assertEquals(1, report.findings(Status.DANGEROUS_SCHEME).size());
        assertEquals(1, report.findings(Status.HOST_NOT_ALLOWED).size());
        assertEquals(0, report.findings(Status.NO_BASE).size());
        assertTrue(report.toString().contains("2 finding(s)"));
    }
}
