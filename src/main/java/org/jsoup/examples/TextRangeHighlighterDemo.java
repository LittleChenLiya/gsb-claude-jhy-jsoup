package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.TextRangeHighlighter;

/**
 * Short demo of {@link TextRangeHighlighter}: shows the DOM before and after
 * highlighting a text range, and defines the text view used for offsets.
 *
 * <p><b>Text view definition.</b> The text view is the concatenation of all
 * {@link org.jsoup.nodes.TextNode#getWholeText()} values in document order,
 * excluding any text node that is a descendant of a {@code <script>} or
 * {@code <style>} element. Offsets are UTF-16 code-unit indices into this
 * concatenated string; they must not split a surrogate pair.
 */
public class TextRangeHighlighterDemo {

    public static void main(String[] args) {
        // Acceptance case: document A<b>BC</b>D, highlight [1, 4).
        String html = "A<b>BC</b>D";
        Document doc = Jsoup.parse(html);

        System.out.println("=== Text view (before) ===");
        System.out.println(TextRangeHighlighter.textView(doc));

        System.out.println("=== DOM before ===");
        System.out.println(doc.body().html());

        TextRangeHighlighter.UndoRecord undo = TextRangeHighlighter.highlight(doc, 1, 4, "m1");

        System.out.println("=== DOM after highlighting [1, 4) ===");
        System.out.println(doc.body().html());

        System.out.println("=== Text view (after, unchanged) ===");
        System.out.println(TextRangeHighlighter.textView(doc));

        System.out.println("=== Undo ===");
        undo.undo();
        System.out.println(doc.body().html());
    }
}
