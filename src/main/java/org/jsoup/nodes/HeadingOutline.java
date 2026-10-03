package org.jsoup.nodes;

import org.jsoup.helper.Validate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A heading outline extracted from a specified body {@link Element}.
 * <p>
 * The outline preserves the h1-h6 hierarchy, the heading text (using {@link Element#text()} semantics), and the
 * position of each heading element. The document is never modified: existing {@code id} attributes are kept as-is,
 * headings without an id get a stable, sequential id generated for the report only (never written back to the DOM),
 * and duplicate ids are reported as conflicts without rewriting the nodes.
 * <p>
 * Headings are attached to the nearest smaller-level heading. When the level jumps (e.g. h2 followed directly by
 * h4), the h4 is nested under the h2 and no missing h3 is fabricated. An h1 encountered after nested headings starts
 * a new root.
 * <p>
 * Only nodes within the supplied body element are considered; nodes outside the body and nodes inside
 * {@code script}, {@code style}, or {@code template} elements are excluded.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * Document doc = Jsoup.parse(html);
 * HeadingOutline outline = HeadingOutline.extract(doc.body());
 * for (HeadingNode root : outline.roots()) {
 *     System.out.println(root);
 * }
 * }</pre>
 *
 * @see HeadingNode
 */
public class HeadingOutline {
    private static final String[] SKIP_TAGS = {"script", "style", "template"};

    private final List<HeadingNode> roots;
    private final List<HeadingNode> inOrder;

    HeadingOutline(List<HeadingNode> roots, List<HeadingNode> inOrder) {
        this.roots = Collections.unmodifiableList(new ArrayList<>(roots));
        this.inOrder = Collections.unmodifiableList(new ArrayList<>(inOrder));
    }

    /**
     * Extract the heading outline from the given body element.
     *
     * @param body the body element to extract headings from (typically {@code doc.body()})
     * @return the extracted heading outline
     */
    public static HeadingOutline extract(Element body) {
        Validate.notNull(body);

        // Collect raw headings in document order, skipping script/style/template subtrees.
        List<RawHeading> raws = new ArrayList<>();
        Map<String, Integer> idCounts = new HashMap<>();
        int generated = 0;

        Deque<Node> walk = new ArrayDeque<>();
        pushChildrenReverse(walk, body);
        while (!walk.isEmpty()) {
            Node n = walk.pop();
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            String name = el.normalName();
            if (isSkipped(name)) continue;
            int level = headingLevel(name);
            if (level > 0) {
                String id;
                boolean idGenerated;
                if (el.hasAttr("id")) {
                    id = el.id();
                    idGenerated = false;
                } else {
                    id = HeadingNode.GENERATED_ID_PREFIX + (++generated);
                    idGenerated = true;
                }
                raws.add(new RawHeading(level, el.text(), el, id, idGenerated));
                idCounts.merge(id, 1, Integer::sum);
            }
            pushChildrenReverse(walk, el);
        }

        // Build the tree, attaching each heading to the nearest smaller-level heading.
        List<HeadingNode> roots = new ArrayList<>();
        List<HeadingNode> inOrder = new ArrayList<>();
        Deque<HeadingNode> stack = new ArrayDeque<>();
        for (RawHeading raw : raws) {
            boolean conflict = idCounts.get(raw.id) > 1;
            HeadingNode node = new HeadingNode(raw.level, raw.text, raw.element, raw.id, raw.idGenerated, conflict);
            inOrder.add(node);
            while (!stack.isEmpty() && stack.peek().level() >= node.level()) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().addChild(node);
            }
            stack.push(node);
        }
        return new HeadingOutline(roots, inOrder);
    }

    /**
     * Extract the heading outline from the body of the given document.
     *
     * @param doc the document whose body to extract headings from
     * @return the extracted heading outline
     */
    public static HeadingOutline extract(Document doc) {
        Validate.notNull(doc);
        return extract(doc.body());
    }

    /**
     * Get the root headings of the outline, in document order. A root heading is one with no smaller-level heading
     * before it (e.g. the first heading, or an h1 following nested headings).
     * <p>
     * The returned list is an independent copy.
     *
     * @return a copy of the root headings
     */
    public List<HeadingNode> roots() {
        return new ArrayList<>(roots);
    }

    /**
     * Get all headings in the outline, in document order.
     * <p>
     * The returned list is an independent copy.
     *
     * @return a copy of all headings
     */
    public List<HeadingNode> headings() {
        return new ArrayList<>(inOrder);
    }

    /**
     * Get the number of headings in the outline.
     *
     * @return the heading count
     */
    public int size() {
        return inOrder.size();
    }

    /**
     * Check whether the outline contains no headings.
     *
     * @return {@code true} if no headings were found
     */
    public boolean isEmpty() {
        return inOrder.isEmpty();
    }

    /**
     * Render the outline as an indented tree, one heading per line.
     *
     * @return the tree representation
     */
    public String toTreeString() {
        StringBuilder sb = new StringBuilder();
        for (HeadingNode root : roots) {
            render(sb, root, 0);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toTreeString();
    }

    private static void render(StringBuilder sb, HeadingNode node, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append('h').append(node.level());
        sb.append(" [").append(node.id()).append(']');
        if (node.idConflict()) {
            sb.append(" (conflict)");
        }
        if (node.idGenerated()) {
            sb.append(" (generated)");
        }
        sb.append(' ').append(node.text());
        sb.append('\n');
        for (HeadingNode child : node.children()) {
            render(sb, child, depth + 1);
        }
    }

    private static void pushChildrenReverse(Deque<Node> walk, Element el) {
        List<Node> kids = el.childNodes();
        for (int i = kids.size() - 1; i >= 0; i--) {
            walk.push(kids.get(i));
        }
    }

    private static boolean isSkipped(String name) {
        for (String tag : SKIP_TAGS) {
            if (tag.equals(name)) return true;
        }
        return false;
    }

    private static int headingLevel(String name) {
        if (name.length() == 2 && name.charAt(0) == 'h') {
            char c = name.charAt(1);
            if (c >= '1' && c <= '6') return c - '0';
        }
        return 0;
    }

    private static final class RawHeading {
        final int level;
        final String text;
        final Element element;
        final String id;
        final boolean idGenerated;

        RawHeading(int level, String text, Element element, String id, boolean idGenerated) {
            this.level = level;
            this.text = text;
            this.element = element;
            this.id = id;
            this.idGenerated = idGenerated;
        }
    }
}
