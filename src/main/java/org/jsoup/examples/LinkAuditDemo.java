package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.LinkAuditor;

/**
 Example program demonstrating the read-only {@link LinkAuditor}: it parses a document containing relative links,
 look-alike hosts, user-info tricks, and dangerous schemes, then prints the audit report. Runs entirely offline and
 does not modify the document.
 <p>To invoke from the command line:</p>
 <p><code>java -cp jsoup.jar org.jsoup.examples.LinkAuditDemo</code></p>
 */
public class LinkAuditDemo {
    public static void main(String[] args) {
        long start = System.nanoTime();

        String html =
            "<html><head><base href=\"https://safe.example/a/\"></head><body>"
                + "<p><a href=\"../b\">relative OK</a></p>"
                + "<p><a href=\"https://safe.example.evil.test/x\">look-alike host</a></p>"
                + "<p><a href=\"javascript:alert(1)\">script</a></p>"
                + "<p><a href=\"https://user@safe.example/in\">user-info OK</a></p>"
                + "<p><a href=\"https://safe.example@evil.test/\">user-info trick</a></p>"
                + "<img src=\"//cdn.safe.example/img.png\">"
                + "<img src=\"\">"
                + "</body></html>";

        Parser parser = Parser.htmlParser().setTrackPosition(true);
        Document doc = parser.parseInput(html, "");
        String before = doc.html();

        LinkAuditor auditor = new LinkAuditor()
            .allow("safe.example")
            .allowSubdomains("cdn.safe.example");

        LinkAuditor.Report report = auditor.audit(doc);

        System.out.println(report);
        System.out.println("DOM unchanged: " + before.equals(doc.html()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println("Audit completed in " + elapsedMs + " ms (no network access, read-only)");
        if (elapsedMs > 90_000)
            throw new IllegalStateException("demo exceeded 90 seconds");
    }
}
