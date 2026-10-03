package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.HashMap;
import java.util.Map;

/**
 * Example program that migrates (renames) a set of attribute names on a DOM element, printing the attributes before
 * and after. The migration returns a copy, so the original node is left untouched.
 * <p>
 * This is useful when cleaning user-supplied HTML: old attribute names can be moved to their new names in one
 * explicit plan, without mutating the parsed document.
 */
public class AttributeMigration {
    public static void main(String[] args) {
        String html = "<a title=\"Main title\" data-title=\"Data title\" old-href=\"/old\" class=\"link\">Example</a>";
        Document doc = Jsoup.parse(html);
        Element el = doc.expectFirst("a");

        System.out.println("Before: " + el.attributes().html());

        // Build the migration plan: move old-href to href, and swap title with data-title.
        Map<String, String> plan = new HashMap<>();
        plan.put("old-href", "href");
        plan.put("title", "data-title");
        plan.put("data-title", "title");

        Attributes migrated = el.attributes().migrate(plan);

        System.out.println("After:  " + migrated.html());

        // The original node is unchanged.
        System.out.println("Source: " + el.attributes().html());
    }
}
