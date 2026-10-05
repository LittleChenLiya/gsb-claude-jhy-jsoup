package org.jsoup.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 A path locating a node within a subtree, as a list of {@link PathStep}s. The path is reported with the first
 canonical-stream difference (see {@link Difference#path()}).
 <p>
 Paths render in a simplified XPath-like form, e.g. {@code /html[1]/body[1]/div[2]/p[1]/#text[1]}. Indices are
 1-based among siblings sharing the same node name.
 </p>
 * @since 1.24.1
 */
public final class NodePath {
    private final List<PathStep> steps;

    private NodePath(List<PathStep> steps) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    static NodePath root() {
        return new NodePath(Collections.emptyList());
    }

    NodePath append(String name, int index) {
        List<PathStep> copy = new ArrayList<>(steps);
        copy.add(new PathStep(name, index));
        return new NodePath(copy);
    }

    /**
     Returns the steps of this path, from the subtree root to the located node.
     @return an unmodifiable list of steps
     */
    public List<PathStep> steps() {
        return steps;
    }

    /**
     Returns the last step of this path, or {@code null} if this is the root path.
     @return the last step, or null
     */
    public PathStep last() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (PathStep step : steps) {
            sb.append('/').append(step.name()).append('[').append(step.index()).append(']');
        }
        return sb.toString();
    }
}
