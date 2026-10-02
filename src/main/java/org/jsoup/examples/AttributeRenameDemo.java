package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Document.OutputSettings.Syntax;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * Demonstrates the explicit attribute rename plan ({@link Attributes#migrate(Attributes.RenamePlan, Syntax)}).
 * <p>
 * The plan is applied to a <b>copy</b> of the node's attributes: values travel with their source, an exchange of two
 * names is allowed, while merging two sources onto one target (or overwriting an attribute that is not itself renamed)
 * is rejected. The demo prints the DOM's attributes before and after the migration, and shows the difference between
 * HTML's case-insensitive and XML's case-sensitive name matching.
 * </p>
 */
public class AttributeRenameDemo {
    public static void main(String[] args) {
        swapTitleAndDataTitleInHtml();
        rejectMergeInHtml();
        htmlIdVsXmlId();
    }

    private static void swapTitleAndDataTitleInHtml() {
        System.out.println("== HTML: swap title and data-title ==");
        Document doc = Jsoup.parse("<a title=\"Hello\" data-title=\"World\" href=\"/x\">Link</a>");
        Element a = doc.expectFirst("a");
        assert a != null;

        System.out.println("Before: " + describe(a.attributes()));

        Attributes migrated = a.attributes().migrate(new Attributes.RenamePlan()
            .rename("title", "data-title")
            .rename("data-title", "title"));

        System.out.println("After:  " + describe(migrated));
        System.out.println("Original node is unchanged: " + a.outerHtml());
        System.out.println();
    }

    private static void rejectMergeInHtml() {
        System.out.println("== HTML: merging x and y into z is rejected ==");
        Attributes source = new Attributes()
            .put("x", "1")
            .put("y", "2")
            .put("z", "3");
        System.out.println("Before: " + describe(source));

        Attributes.RenamePlan plan = new Attributes.RenamePlan()
            .rename("x", "z")
            .rename("y", "z");
        try {
            source.migrate(plan);
            System.out.println("Unexpectedly migrated!");
        } catch (IllegalArgumentException e) {
            System.out.println("Rejected: " + e.getMessage());
        }
        System.out.println("After:  " + describe(source));
        System.out.println();
    }

    private static void htmlIdVsXmlId() {
        System.out.println("== ID vs id: HTML folds case, XML keeps them distinct ==");
        Document html = Jsoup.parse("<div id=\"lower\" ID=\"upper\"></div>");
        Element htmlDiv = html.expectFirst("div");
        assert htmlDiv != null;
        System.out.println("HTML before: " + describe(htmlDiv.attributes()));
        Attributes htmlMigrated = htmlDiv.attributes()
            .migrate(new Attributes.RenamePlan().rename("ID", "name"), Syntax.html);
        System.out.println("HTML after:  " + describe(htmlMigrated));

        Document xml = Parser.xmlParser().parseInput("<div id=\"lower\" ID=\"upper\"></div>", "");
        Element xmlDiv = xml.expectFirst("div");
        assert xmlDiv != null;
        System.out.println("XML before:  " + describe(xmlDiv.attributes()));
        Attributes xmlMigrated = xmlDiv.attributes()
            .migrate(new Attributes.RenamePlan().rename("ID", "name"), Syntax.xml);
        System.out.println("XML after:   " + describe(xmlMigrated));
    }

    private static String describe(Attributes attributes) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (org.jsoup.nodes.Attribute attribute : attributes) {
            if (!first) sb.append(", ");
            sb.append(attribute.getKey()).append('=').append(attribute.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }
}