package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.AllowedHosts;
import org.jsoup.safety.LinkAuditor;
import org.jsoup.safety.LinkAuditResult;

import java.util.List;

/**
 Demo program for the {@link LinkAuditor}. Audits a sample HTML document and prints the results.
 <p>
 This demo is designed to run in under 90 seconds. It parses a small HTML document with relative
 links, absolute links, javascript links, and user-info links, then audits them against a set of
 allowed hosts.
 </p>
 <p>
 To invoke from the command line:
 </p>
 <p><code>java -cp jsoup-examples.jar org.jsoup.examples.LinkAuditorDemo</code></p>
 */
public class LinkAuditorDemo {
    private static final String SAMPLE_HTML =
        "<html><head><base href=\"https://safe.example/a/\"></head><body>"
        + "<a href=\"../b\">Relative link (allowed)</a>"
        + "<a href=\"https://safe.example/page\">Absolute link (allowed)</a>"
        + "<a href=\"https://safe.example.evil.test/bad\">Evil subdomain (rejected)</a>"
        + "<a href=\"javascript:alert(1)\">JavaScript link (rejected)</a>"
        + "<a href=\"https://user@safe.example/secret\">User-info link (allowed by host)</a>"
        + "<img src=\"https://img.trusted.example/logo.png\">"
        + "<img src=\"https://img.evil.example/track.png\">"
        + "</body></html>";

    /**
     Runs the demo.
     @param args ignored
     */
    public static void main(String[] args) {
        long start = System.currentTimeMillis();

        Document doc = Jsoup.parse(SAMPLE_HTML);
        AllowedHosts hosts = AllowedHosts.of("safe.example", "*.trusted.example");
        LinkAuditor auditor = new LinkAuditor(hosts);

        List<LinkAuditResult> results = auditor.audit(doc);

        System.out.println("=== Link Auditor Demo ===");
        System.out.println("Base URI: " + doc.baseUri());
        System.out.println("Allowed hosts: " + hosts);
        System.out.println("Links found: " + results.size());
        System.out.println();

        int allowed = 0;
        int rejected = 0;
        for (LinkAuditResult result : results) {
            if (result.isAllowed()) {
                allowed++;
                System.out.println("[ALLOWED]  " + result.element().tagName()
                    + "[" + result.attributeKey() + "] "
                    + result.originalValue() + " -> " + result.resolvedUrlString());
            } else {
                rejected++;
                System.out.println("[REJECTED] " + result.status() + " "
                    + result.element().tagName() + "[" + result.attributeKey() + "] "
                    + result.originalValue() + " -> " + result.resolvedUrlString());
            }
        }

        System.out.println();
        System.out.println("Summary: " + allowed + " allowed, " + rejected + " rejected");
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Elapsed: " + elapsed + "ms");
    }
}
