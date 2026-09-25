package org.jsoup.examples;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.SourceEdit;
import org.jsoup.nodes.SourceEditException;
import org.jsoup.nodes.SourceEditPlan;
import org.jsoup.nodes.SourceEditor;

/**
 Example program that plans minimal, source-offset based edits to an HTML document's original source, preserving
 every unmodified byte (quote styles, entities, whitespace) instead of re-serializing the DOM.
 <p>Prints the original source, the planned edit fragments, the edited result, and a rejected overlapping-edit
 conflict. Runs in well under 90 seconds.</p>
 <p>To invoke: {@code java -cp jsoup.jar org.jsoup.examples.SourceEditing}</p>
 */
public class SourceEditing {
    public static void main(String[] args) {
        String html = "<div id='main'  data-note=\"a &amp; b\">\n" +
            "  <p class=lead>Hello   <b>World</b>!</p>\n" +
            "</div>";

        SourceEditor editor = SourceEditor.parse(html); // parses with position tracking enabled
        Document doc = editor.document();

        Element div = doc.selectFirst("div");
        Element p = doc.selectFirst("p");
        editor.setAttribute(div, "id", "primary");
        editor.replaceText(p.textNodes().get(0), "Hi   there <you> ");

        SourceEditPlan plan = editor.plan();

        System.out.println("== Original source ==");
        System.out.println(html);
        System.out.println("\n== Planned edits (source order) ==");
        for (SourceEdit edit : plan.edits())
            System.out.println(edit);
        System.out.println("\n== Edited source (unmodified regions preserved exactly) ==");
        System.out.println(plan.commit(html));

        System.out.println("\n== Overlapping edits are rejected ==");
        SourceEditor conflicting = SourceEditor.parse(html);
        conflicting.remove(conflicting.document().selectFirst("div"));
        conflicting.setAttribute(conflicting.document().selectFirst("p"), "class", "x");
        try {
            conflicting.plan();
        } catch (SourceEditException e) {
            System.out.println(e.getMessage());
        }
    }
}
