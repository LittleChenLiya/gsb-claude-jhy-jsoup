package org.jsoup.compare;

import org.jsoup.helper.Validate;

/**
 A single step in a {@link NodePath}: a node name and a 1-based index among siblings with that same name.
 * @since 1.24.1
 */
public final class PathStep {
    private final String name;
    private final int index;

    PathStep(String name, int index) {
        Validate.notNull(name);
        Validate.isTrue(index >= 1);
        this.name = name;
        this.index = index;
    }

    /**
     Returns the node name (the element's normal name, or the leaf node's node name, e.g. {@code #text}).
     @return the node name
     */
    public String name() {
        return name;
    }

    /**
     Returns the 1-based index among siblings with the same name.
     @return the index
     */
    public int index() {
        return index;
    }

    @Override
    public String toString() {
        return name + "[" + index + "]";
    }
}
