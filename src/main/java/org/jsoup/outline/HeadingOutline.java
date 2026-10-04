package org.jsoup.outline;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 Extracts a heading outline ({@code h1} .. {@code h6}) from a content element, without modifying the source document.
 <p>
 The extraction walks the given root element in document order and:
 </p>
 <ul>
 <li>keeps each heading's level, text (per {@link Element#text()}), and source {@link Element} position;</li>
 <li>nests a heading under the nearest preceding heading with a smaller level, so level jumps (e.g. {@code h2} then
 {@code h4}) attach to the nearest smaller-level ancestor and no synthetic headings are invented for skipped
 levels;</li>
 <li>ignores anything outside the root element, and any headings inside {@code script}, {@code style}, or
 {@code template} elements;</li>
 <li>preserves existing {@code id} attributes; headings without an {@code id} get a stable generated identifier
 ({@code outline-1}, {@code outline-2}, ...) that exists only in this report and is never written back to the
 DOM;</li>
 <li>reports duplicate {@code id} attributes as {@link IdConflict}s, leaving the DOM nodes untouched.</li>
 </ul>
 <p>Example:</p>
 <pre>{@code
 Document doc = Jsoup.parse(html);
 HeadingOutline outline = HeadingOutline.extract(doc.body());
 System.out.println(outline); // indented tree view
 }</pre>
 <p>This type is immutable after extraction; the child lists returned by {@link Section#children()} and the lists
 returned by {@link #roots()} and {@link #idConflicts()} are independent unmodifiable copies that share no mutable
 container with this outline.</p>
 */
public final class HeadingOutline {
    private final List<Section> roots;
    private final List<IdConflict> idConflicts;

    private HeadingOutline(List<Section> roots, List<IdConflict> idConflicts) {
        this.roots = roots;
        this.idConflicts = idConflicts;
    }

    /**
     Extract the heading outline rooted at (and including) {@code root}.
     @param root the content element to scan; only headings within this element are considered
     @return the extracted outline; the source document is not modified
     */
    public static HeadingOutline extract(Element root) {
        Validate.notNull(root);

        // Collect candidate headings in document order, not descending into script/style/template subtrees.
        List<Element> headings = new ArrayList<>();
        NodeTraversor.filter(new NodeFilter() {
            @Override public FilterResult head(Node node, int depth) {
                if (node instanceof Element) {
                    Element el = (Element) node;
                    switch (el.normalName()) {
                        case "script":
                        case "style":
                        case "template":
                            return FilterResult.SKIP_CHILDREN;
                        default:
                            if (headingLevel(el) > 0) headings.add(el);
                    }
                }
                return FilterResult.CONTINUE;
            }
        }, root);

        List<Section> roots = new ArrayList<>();
        Deque<Section> stack = new ArrayDeque<>(); // open sections, shallowest at the bottom
        Map<String, List<Section>> sectionsById = new LinkedHashMap<>();
        Set<String> usedIds = new HashSet<>();
        int generated = 0;

        for (Element el : headings) {
            int level = headingLevel(el);
            String id = el.id();
            boolean generatedId = id.isEmpty();
            if (generatedId) { // report-only stable identifier; never written back to the DOM
                String candidate;
                do {
                    candidate = "outline-" + (++generated);
                } while (usedIds.contains(candidate));
                id = candidate;
            }
            usedIds.add(id);

            Section section = new Section(level, el.text(), id, generatedId, el);
            if (!generatedId) sectionsById.computeIfAbsent(id, k -> new ArrayList<>()).add(section);

            // Attach to the nearest preceding heading with a smaller level; skipped levels are not invented.
            while (!stack.isEmpty() && stack.peek().level() >= level) stack.pop();
            if (stack.isEmpty()) roots.add(section);
            else stack.peek().addChild(section);
            stack.push(section);
        }

        List<IdConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<Section>> entry : sectionsById.entrySet()) {
            List<Section> same = entry.getValue();
            if (same.size() > 1) conflicts.add(new IdConflict(entry.getKey(), same));
        }

        return new HeadingOutline(roots, conflicts);
    }

    /**
     The top-level sections of this outline, in document order.
     @return an independent unmodifiable copy of the root section list
     */
    public List<Section> roots() {
        return Collections.unmodifiableList(new ArrayList<>(roots));
    }

    /**
     Duplicate {@code id} attributes found among the extracted headings. The conflicting DOM nodes are reported
     as-is and are never modified.
     @return an independent unmodifiable copy of the conflict list; empty if all heading ids are unique
     */
    public List<IdConflict> idConflicts() {
        return Collections.unmodifiableList(new ArrayList<>(idConflicts));
    }

    /**
     Renders this outline as an indented tree, one heading per line, e.g.:
     <pre>{@code
     h1 #intro: 介绍
       h2 #outline-1 (generated id): 背景
     }</pre>
     @return the tree view of this outline
     */
    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Section root : roots) append(sb, root, 0);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Section section, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append('h').append(section.level()).append(" #").append(section.id());
        if (section.hasGeneratedId()) sb.append(" (generated id)");
        sb.append(": ").append(section.text()).append('\n');
        for (Section child : section.children()) append(sb, child, depth + 1);
    }

    /** @return the heading level 1..6 for {@code h1}..{@code h6}, or 0 if {@code el} is not a heading */
    private static int headingLevel(Element el) {
        String tag = el.normalName();
        if (tag.length() == 2 && tag.charAt(0) == 'h') {
            char c = tag.charAt(1);
            if (c >= '1' && c <= '6') return c - '0';
        }
        return 0;
    }
}
