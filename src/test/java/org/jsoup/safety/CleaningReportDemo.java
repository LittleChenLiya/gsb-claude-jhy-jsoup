package org.jsoup.safety;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 A small before / after demo of {@link Cleaner} with a {@link CleaningReport}. Run with:
 {@code java -cp classes;test-classes org.jsoup.safety.CleaningReportDemo}
 */
public class CleaningReportDemo {
    public static void main(String[] args) {
        String html =
            "<div><script>alert('xss')</script>" +
            "<p onclick='track()'>Hello <b>there</b>!</p>" +
            "<a href='/about'>relative</a> " +
            "<a href='javascript:steal()'>evil</a> " +
            "<a href='https://example.com/ok'>ok</a>" +
            "<!-- a comment --></div>";

        Document dirty = Jsoup.parse(html, "https://example.com/");
        Cleaner cleaner = new Cleaner(Safelist.relaxed());
        CleaningReport report = new CleaningReport();
        Document clean = cleaner.clean(dirty, report);

        System.out.println("== Before (dirty) ==");
        System.out.println(dirty.body().html());
        System.out.println();
        System.out.println("== After (clean) ==");
        System.out.println(clean.body().html());
        System.out.println();
        System.out.println("== " + report);
    }
}
