package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.SourceEditor;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/**
 Demonstrates planning minimal source edits with {@link SourceEditor}: the original source, the planned edit
 fragments, and conflict rejection.
 */
public class SourceEditDemo {
    public static void main(String[] args) {
        String html = "<div  id='main'   data-x=\"a&amp;b\">Hi <b>there</b></div>";
        Document doc = Jsoup.parse(html, Parser.htmlParser().setTrackPosition(true));

        Element div = doc.expectFirst("div");
        SourceEditor editor = SourceEditor.on(html, doc);
        editor.setAttribute(div, "data-x", "c&d");
        editor.replaceText((TextNode) doc.expectFirst("b").childNode(0), "y'all <3");

        System.out.println("Original : " + html);
        System.out.println("Edits (source order):");
        for (SourceEditor.Edit edit : editor.edits())
            System.out.println("  " + edit);
        System.out.println("Edited   : " + editor.commit());

        // overlapping edits are rejected: removing a parent conflicts with editing its child
        try {
            SourceEditor conflict = SourceEditor.on(html, doc);
            conflict.remove(div);
            conflict.setAttribute(doc.expectFirst("b"), "id", "x");
            System.out.println("ERROR: conflict not detected");
        } catch (SourceEditor.SourceEditException e) {
            System.out.println("Rejected : " + e.getMessage());
        }

        // a changed source is rejected by digest check
        try {
            editor.commit(html.replace("Hi", "Hey"));
            System.out.println("ERROR: stale source not detected");
        } catch (SourceEditor.SourceEditException e) {
            System.out.println("Rejected : " + e.getMessage());
        }
    }
}
