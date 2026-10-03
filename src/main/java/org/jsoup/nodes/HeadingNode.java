package org.jsoup.nodes;

import java.util.ArrayList;
import java.util.List;

/**
 A single heading in a {@link HeadingOutline}.
 <p>
 A heading node captures the heading's {@link #level() level} (h1-h6), its {@link #text() text} (using
 {@link Element#text()} semantics), the originating {@link #element() element} (which records its position in the
 document), and its {@link #id() id}.
 <p>
 Heading nodes are immutable once built. The {@link #children()} list returned by this node is an independent copy:
 mutating it has no effect on the outline it came from, so a caller may freely reorder or prune a sub-list without
 touching the extracted result.

 @see HeadingOutline
 */
public class HeadingNode {
    static final String GENERATED_ID_PREFIX = "heading-";

    private final int level;
    private final String text;
    private final Element element;
    private final String id;
    private final boolean idGenerated;
    private final boolean idConflict;
    private final List<HeadingNode> children = new ArrayList<>();

    HeadingNode(int level, String text, Element element, String id, boolean idGenerated, boolean idConflict) {
        this.level = level;
        this.text = text;
        this.element = element;
        this.id = id;
        this.idGenerated = idGenerated;
        this.idConflict = idConflict;
    }

    void addChild(HeadingNode child) {
        children.add(child);
    }

    /**
     Get the heading level.

     @return the level, from {@code 1} (h1) to {@code 6} (h6)
     */
    public int level() {
        return level;
    }

    /**
     Get the heading text, using {@link Element#text()} semantics: the normalized, combined text of the heading and
     all its children, with whitespace normalized and trimmed.

     @return the heading text
     */
    public String text() {
        return text;
    }

    /**
     Get the originating heading element. The element records the heading's position in the document (via its parent,
     sibling index, and document order). The document is never modified by the extractor.

     @return the heading element
     */
    public Element element() {
        return element;
    }

    /**
     Get the heading id. If the heading had an {@code id} attribute, that value is returned as-is. Otherwise a stable,
     sequential id (of the form {@code heading-n}) is generated <strong>for the report only</strong> and is never
     written back to the DOM.

     @return the heading id
     @see #idGenerated()
     */
    public String id() {
        return id;
    }

    /**
     Check whether the {@link #id() id} was generated for the report because the heading had no {@code id} attribute.
     A generated id is never written back to the DOM.

     @return {@code true} if the id was generated, {@code false} if it came from the element's {@code id} attribute
     */
    public boolean idGenerated() {
        return idGenerated;
    }

    /**
     Check whether this heading's {@link #id() id} collides with another heading's id in the extracted outline.
     Colliding ids are reported as-is; the originating elements are never rewritten.

     @return {@code true} if the id is shared by more than one heading in the outline
     */
    public boolean idConflict() {
        return idConflict;
    }

    /**
     Get the direct child headings of this heading, in document order.
     <p>
     The returned list is an independent copy: changes to it (adding, removing, or reordering) do not affect the
     extracted outline.

     @return a copy of the direct child headings
     */
    public List<HeadingNode> children() {
        return new ArrayList<>(children);
    }

    @Override
    public String toString() {
        return "h" + level + " " + text;
    }
}
