package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LinkAuditorTest {

    @Test
    public void relativeLinkWithBasePasses() {
        String html = "<a href=\"../b\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        LinkAuditResult result = results.get(0);
        assertTrue(result.isAllowed());
        assertEquals(LinkAuditResult.Status.ALLOWED, result.status());
        assertEquals("https://safe.example/b", result.resolvedUrlString());
        assertEquals("safe.example", result.host());
        assertEquals("https", result.scheme());
    }

    @Test
    public void evilSubdomainRejected() {
        String html = "<a href=\"https://safe.example.evil.test/bad\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        LinkAuditResult result = results.get(0);
        assertFalse(result.isAllowed());
        assertEquals(LinkAuditResult.Status.REJECTED_HOST, result.status());
        assertEquals("safe.example.evil.test", result.host());
    }

    @Test
    public void javascriptLinkRejected() {
        String html = "<a href=\"javascript:alert(1)\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        LinkAuditResult result = results.get(0);
        assertFalse(result.isAllowed());
        assertEquals(LinkAuditResult.Status.REJECTED_SCHEME, result.status());
        assertEquals("javascript", result.scheme());
    }

    @Test
    public void userInfoJudgedByRealHost() {
        String html = "<a href=\"https://user@safe.example/secret\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        LinkAuditResult result = results.get(0);
        assertTrue(result.isAllowed());
        assertEquals("safe.example", result.host());
    }

    @Test
    public void caseInsensitiveHosts() {
        String html = "<a href=\"https://SAFE.EXAMPLE/page\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isAllowed());
        assertEquals("SAFE.EXAMPLE", results.get(0).host());
    }

    @Test
    public void caseInsensitiveRules() {
        AllowedHosts hosts = AllowedHosts.of("SAFE.EXAMPLE");
        assertTrue(hosts.isAllowed("safe.example"));
        assertTrue(hosts.isAllowed("SAFE.EXAMPLE"));
        assertTrue(hosts.isAllowed("Safe.Example"));
    }

    @Test
    public void defaultPortConsistent() {
        LinkAuditor auditor = new LinkAuditor("safe.example");
        assertTrue(auditor.isAllowed("http://safe.example/"));
        assertTrue(auditor.isAllowed("http://safe.example:80/"));
        assertTrue(auditor.isAllowed("https://safe.example/"));
        assertTrue(auditor.isAllowed("https://safe.example:443/"));
        assertTrue(auditor.isAllowed("http://safe.example:8080/"));
    }

    @Test
    public void noStringPrefixMatching() {
        AllowedHosts hosts = AllowedHosts.of("safe.example");
        assertFalse(hosts.isAllowed("safe.example.evil.test"));
        assertFalse(hosts.isAllowed("safe.exampleevil.test"));
        assertFalse(hosts.isAllowed("xsafe.example"));
        assertTrue(hosts.isAllowed("safe.example"));
    }

    @Test
    public void subdomainRuleMatchesSubdomains() {
        AllowedHosts hosts = AllowedHosts.of("*.trusted.example");
        assertTrue(hosts.isAllowed("trusted.example"));
        assertTrue(hosts.isAllowed("a.trusted.example"));
        assertTrue(hosts.isAllowed("b.a.trusted.example"));
        assertFalse(hosts.isAllowed("trusted.example.evil.test"));
        assertFalse(hosts.isAllowed("eviltrusted.example"));
    }

    @Test
    public void baseTagSetsBaseUri() {
        String html = "<html><head><base href=\"https://cdn.example/root/\"></head><body>"
            + "<a href=\"../page\">link</a></body></html>";
        Document doc = Jsoup.parse(html);
        LinkAuditor auditor = new LinkAuditor("cdn.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        LinkAuditResult result = results.get(0);
        assertTrue(result.isAllowed());
        assertEquals("https://cdn.example/page", result.resolvedUrlString());
    }

    @Test
    public void baseTagRejectsOtherHost() {
        String html = "<html><head><base href=\"https://cdn.example/root/\"></head><body>"
            + "<a href=\"https://evil.example/page\">link</a></body></html>";
        Document doc = Jsoup.parse(html);
        LinkAuditor auditor = new LinkAuditor("cdn.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isAllowed());
        assertEquals(LinkAuditResult.Status.REJECTED_HOST, results.get(0).status());
    }

    @Test
    public void baseTagWithRelativeLinkRejected() {
        String html = "<html><head><base href=\"https://cdn.example/root/\"></head><body>"
            + "<a href=\"//evil.example/page\">link</a></body></html>";
        Document doc = Jsoup.parse(html);
        LinkAuditor auditor = new LinkAuditor("cdn.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isAllowed());
        assertEquals(LinkAuditResult.Status.REJECTED_HOST, results.get(0).status());
    }

    @Test
    public void xmlModeAudit() {
        String xml = "<root xmlns=\"http://www.w3.org/1999/xhtml\">"
            + "<a href=\"../b\">link</a>"
            + "<a href=\"https://safe.example/page\">link2</a></root>";
        Document doc = Jsoup.parse(xml, "https://safe.example/a/", Parser.xmlParser());
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(2, results.size());
        assertTrue(results.get(0).isAllowed());
        assertTrue(results.get(1).isAllowed());
    }

    @Test
    public void xmlModeRejectsEvilLink() {
        String xml = "<root><a href=\"https://evil.example/page\">link</a></root>";
        Document doc = Jsoup.parse(xml, "https://safe.example/a/", Parser.xmlParser());
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertFalse(results.get(0).isAllowed());
        assertEquals(LinkAuditResult.Status.REJECTED_HOST, results.get(0).status());
    }

    @Test
    public void nullDocumentThrows() {
        LinkAuditor auditor = new LinkAuditor("safe.example");
        assertThrows(IllegalArgumentException.class, () -> auditor.audit((Document) null));
    }

    @Test
    public void nullElementThrows() {
        LinkAuditor auditor = new LinkAuditor("safe.example");
        assertThrows(IllegalArgumentException.class, () -> auditor.audit((Element) null));
    }

    @Test
    public void nullHostsThrows() {
        assertThrows(IllegalArgumentException.class, () -> AllowedHosts.of((String[]) null));
    }

    @Test
    public void nullHostInCollectionThrows() {
        assertThrows(IllegalArgumentException.class, () -> AllowedHosts.of(java.util.Arrays.asList("safe.example", null)));
    }

    @Test
    public void nullUrlIsNotAllowed() {
        LinkAuditor auditor = new LinkAuditor("safe.example");
        assertFalse(auditor.isAllowed((URL) null));
        assertFalse(auditor.isAllowed((String) null));
    }

    @Test
    public void emptyUrlIsNotAllowed() {
        LinkAuditor auditor = new LinkAuditor("safe.example");
        assertFalse(auditor.isAllowed(""));
    }

    @Test
    public void noBaseReported() {
        String html = "<a href=\"../b\">link</a>";
        Document doc = Jsoup.parse(html);
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals(LinkAuditResult.Status.NO_BASE, results.get(0).status());
        assertNull(results.get(0).resolvedUrl());
    }

    @Test
    public void parseFailureReported() {
        String html = "<a href=\"http://\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals(LinkAuditResult.Status.PARSE_FAILURE, results.get(0).status());
    }

    @Test
    public void dangerousSchemeReported() {
        String html = "<a href=\"ftp://files.example/file.zip\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals(LinkAuditResult.Status.REJECTED_SCHEME, results.get(0).status());
        assertEquals("ftp", results.get(0).scheme());
    }

    @Test
    public void mailtoRejected() {
        String html = "<a href=\"mailto:user@example.com\">email</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals(LinkAuditResult.Status.REJECTED_SCHEME, results.get(0).status());
    }

    @Test
    public void sourceRangeTracked() {
        String html = "<a href=\"https://safe.example/page\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/", Parser.htmlParser().setTrackPosition(true));
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        LinkAuditResult result = results.get(0);
        assertNotNull(result.sourceRange());
        assertTrue(result.sourceRange().isTracked());
    }

    @Test
    public void originalValuePreserved() {
        String html = "<a href=\"  https://safe.example/page  \">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals("  https://safe.example/page  ", results.get(0).originalValue());
    }

    @Test
    public void elementReferencePreserved() {
        String html = "<a href=\"https://safe.example/page\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertNotNull(results.get(0).element());
        assertEquals("a", results.get(0).element().tagName());
    }

    @Test
    public void noDomModification() {
        String html = "<a href=\"https://safe.example/page\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        String originalHtml = doc.html();
        LinkAuditor auditor = new LinkAuditor("safe.example");
        auditor.audit(doc);
        assertEquals(originalHtml, doc.html());
    }

    @Test
    public void noDomModificationWithRejectedLink() {
        String html = "<a href=\"javascript:alert(1)\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        String originalHtml = doc.html();
        LinkAuditor auditor = new LinkAuditor("safe.example");
        auditor.audit(doc);
        assertEquals(originalHtml, doc.html());
    }


    @Test
    public void multipleLinksAudited() {
        String html = "<a href=\"https://safe.example/1\">1</a>"
            + "<a href=\"https://evil.example/2\">2</a>"
            + "<img src=\"https://safe.example/img.png\">";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(3, results.size());
        assertTrue(results.get(0).isAllowed());
        assertFalse(results.get(1).isAllowed());
        assertTrue(results.get(2).isAllowed());
        assertEquals("href", results.get(0).attributeKey());
        assertEquals("href", results.get(1).attributeKey());
        assertEquals("src", results.get(2).attributeKey());
    }

    @Test
    public void customAttributes() {
        String html = "<form action=\"https://safe.example/submit\"><button>go</button></form>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor(AllowedHosts.of("safe.example"), java.util.Arrays.asList("action"));
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals("action", results.get(0).attributeKey());
        assertTrue(results.get(0).isAllowed());
    }

    @Test
    public void emptyAttributeSkipped() {
        String html = "<a href=\"\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(0, results.size());
    }

    @Test
    public void allowedHostsOfCollection() {
        AllowedHosts hosts = AllowedHosts.of(java.util.Arrays.asList("safe.example", "*.trusted.example"));
        assertEquals(2, hosts.size());
        assertTrue(hosts.isAllowed("safe.example"));
        assertTrue(hosts.isAllowed("a.trusted.example"));
    }

    @Test
    public void allowedHostsToString() {
        AllowedHosts hosts = AllowedHosts.of("safe.example", "*.trusted.example");
        String str = hosts.toString();
        assertTrue(str.contains("safe.example"));
        assertTrue(str.contains("*.trusted.example"));
    }

    @Test
    public void allowedHostsIsAllowedUrl() throws Exception {
        AllowedHosts hosts = AllowedHosts.of("safe.example");
        URL url = new URL("https://safe.example/page");
        assertTrue(hosts.isAllowed(url));
    }

    @Test
    public void protocolRelativeUrlResolved() {
        String html = "<a href=\"//safe.example/page\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isAllowed());
        assertEquals("https", results.get(0).scheme());
        assertEquals("safe.example", results.get(0).host());
    }

    @Test
    public void resultToString() {
        String html = "<a href=\"https://safe.example/page\">link</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        String str = results.get(0).toString();
        assertTrue(str.contains("ALLOWED"));
        assertTrue(str.contains("href"));
        assertTrue(str.contains("https://safe.example/page"));
    }

    @Test
    public void srcAttributeAudited() {
        String html = "<img src=\"https://safe.example/logo.png\">";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals("src", results.get(0).attributeKey());
        assertTrue(results.get(0).isAllowed());
    }

    @Test
    public void srcAttributeRejected() {
        String html = "<img src=\"https://evil.example/track.png\">";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(1, results.size());
        assertEquals("src", results.get(0).attributeKey());
        assertFalse(results.get(0).isAllowed());
        assertEquals(LinkAuditResult.Status.REJECTED_HOST, results.get(0).status());
    }

    @Test
    public void mixedAllowedAndRejected() {
        String html = "<a href=\"https://safe.example/1\">1</a>"
            + "<a href=\"https://safe.example.evil.test/2\">2</a>"
            + "<a href=\"javascript:alert(3)\">3</a>"
            + "<a href=\"../b\">4</a>";
        Document doc = Jsoup.parse(html, "https://safe.example/a/");
        LinkAuditor auditor = new LinkAuditor("safe.example");
        List<LinkAuditResult> results = auditor.audit(doc);
        assertEquals(4, results.size());
        assertTrue(results.get(0).isAllowed());
        assertEquals(LinkAuditResult.Status.REJECTED_HOST, results.get(1).status());
        assertEquals(LinkAuditResult.Status.REJECTED_SCHEME, results.get(2).status());
        assertTrue(results.get(3).isAllowed());
        assertEquals("https://safe.example/b", results.get(3).resolvedUrlString());
    }
}

