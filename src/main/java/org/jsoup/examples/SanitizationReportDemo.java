package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.safety.SanitizationReport;

/**
 A demo of the {@link SanitizationReport} functionality, showing the before and after of sanitization
 and the report of what was removed and why.
 <p>
 Run with:
 <pre>
 java -cp target/classes org.jsoup.examples.SanitizationReportDemo
 </pre>
 */
public class SanitizationReportDemo {
    public static void main(String[] args) {
        String dirtyHtml = "<script>alert('XSS')</script>"
            + "<p onclick='stealCookies()'>Hello</p>"
            + "<a href='javascript:alert(1)'>Bad Link</a>"
            + "<a href='https://example.com'>Good Link</a>"
            + "<a href='/relative'>Relative Link</a>";

        System.out.println("=== Before Sanitization ===");
        System.out.println(dirtyHtml);
        System.out.println();

        Document dirty = Jsoup.parseBodyFragment(dirtyHtml, "https://example.com/");
        Safelist safelist = Safelist.relaxed();
        Cleaner cleaner = new Cleaner(safelist);
        SanitizationReport report = new SanitizationReport();
        Document clean = cleaner.clean(dirty, report);

        System.out.println("=== After Sanitization ===");
        System.out.println(clean.body().html());
        System.out.println();

        System.out.println("=== Sanitization Report ===");
        System.out.println("Deletions:");
        if (report.deletions().isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (SanitizationReport.Deletion deletion : report.deletions()) {
                System.out.println("  - " + deletion);
            }
        }
        System.out.println();
        System.out.println("Conversions:");
        if (report.conversions().isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (SanitizationReport.Conversion conversion : report.conversions()) {
                System.out.println("  - " + conversion);
            }
        }
        System.out.println();

        // Verify clean result is the same without a report
        Document cleanWithoutReport = cleaner.clean(Jsoup.parseBodyFragment(dirtyHtml, "https://example.com/"));
        System.out.println("=== Consistency Check ===");
        System.out.println("Clean result matches without report: "
            + clean.body().html().equals(cleanWithoutReport.body().html()));
    }
}
