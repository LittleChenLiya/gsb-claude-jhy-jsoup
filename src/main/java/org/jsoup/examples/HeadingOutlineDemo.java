package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.outline.HeadingOutline;

/**
 Demonstrates {@link HeadingOutline}: extracts a heading outline from a content element, prints the tree, reports
 duplicate ids, and shows that the source DOM is left unchanged.
 <p>Run with: {@code java org.jsoup.examples.HeadingOutlineDemo}</p>
 */
public class HeadingOutlineDemo {
    public static void main(String[] args) {
        String html = "<html><head><title>Demo</title></head><body>"
            + "<h1>文档大纲演示</h1>"
            + "<div id=content>"
            + "<h2 id=background>背景</h2>"
            + "<h4>细节一</h4>"
            + "<h3>细节二</h3>"
            + "<h2 id=background>重复的背景</h2>"
            + "<template><h2>模板里的标题</h2></template>"
            + "</div>"
            + "<h1>新的篇章</h1>"
            + "</body></html>";

        Document doc = Jsoup.parse(html);
        String domBefore = doc.html();

        HeadingOutline outline = HeadingOutline.extract(doc.body());

        System.out.println("== Heading outline ==");
        System.out.print(outline);

        System.out.println("== Id conflicts ==");
        if (outline.idConflicts().isEmpty()) System.out.println("(none)");
        outline.idConflicts().forEach(System.out::println);

        String domAfter = doc.html();
        System.out.println("== DOM unchanged ==");
        System.out.println(domBefore.equals(domAfter)
            ? "OK: the source document was not modified (generated ids were not written back)."
            : "FAIL: the source document changed!");
    }
}
