package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.FragmentIdRenamer;

/**
 Example program that demonstrates {@link FragmentIdRenamer}: two fragments that both use {@code id="title"} are
 prefixed with {@code a_} and {@code b_} so they can be merged into one document without collisions, and a third
 fragment shows how external links, missing targets, and script / style text are handled.
 <p>To invoke from the command line, assuming you've downloaded the jsoup-examples
 jar to your current directory:</p>
 <p><code>java -cp jsoup-examples.jar org.jsoup.examples.FragmentIdRenameExample</code></p>
 */
public class FragmentIdRenameExample {
    public static void main(String[] args) {
        String fragmentHtml =
            "<section id=\"title\">\n"
                + "  <h1 id=\"heading\">News</h1>\n"
                + "  <label for=\"title\">Section</label>\n"
                + "  <a href=\"#title\">Back to top</a>\n"
                + "  <p aria-labelledby=\"heading title\">Body</p>\n"
                + "</section>";

        System.out.println("=== Original fragment ===");
        System.out.println(fragmentHtml);

        Element first = Jsoup.parseBodyFragment(fragmentHtml).selectFirst("section");
        Element second = Jsoup.parseBodyFragment(fragmentHtml).selectFirst("section");
        FragmentIdRenamer.Result resA = FragmentIdRenamer.rename(first, "a_");
        FragmentIdRenamer.Result resB = FragmentIdRenamer.rename(second, "b_");

        Element merged = new Element("div");
        merged.appendChild(resA.element());
        merged.appendChild(resB.element());

        System.out.println("\n=== Merged after prefixing with a_ and b_ ===");
        System.out.println(merged.html());
        System.out.println("\nID map (a_): " + resA.idMap());
        System.out.println("ID map (b_): " + resB.idMap());

        String tricky =
            "<div id=\"title\"></div>\n"
                + "<a href=\"https://example.com/#title\">external</a>\n"
                + "<a href=\"#missing\">missing</a>\n"
                + "<label for=\"absent\">label</label>\n"
                + "<p aria-describedby=\"title missing\">described</p>\n"
                + "<script>document.getElementById('title');</script>\n"
                + "<style>#title { color: red; }</style>";

        System.out.println("\n=== Original fragment (external link, missing targets, script/style) ===");
        System.out.println(tricky);

        FragmentIdRenamer.Result res = FragmentIdRenamer.rename(Jsoup.parseBodyFragment(tricky).body(), "x_");

        System.out.println("\n=== Renamed with prefix x_ ===");
        System.out.println(res.element().html());
        System.out.println("\nID map: " + res.idMap());
        System.out.println("Unresolved references (left unchanged):");
        for (FragmentIdRenamer.UnresolvedReference ref : res.unresolvedReferences())
            System.out.println("  " + ref);
    }
}
