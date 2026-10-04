package org.jsoup.select;

/**
 The result of a budgeted select (see {@link Selector#selectWithBudget(String, org.jsoup.nodes.Element, int)}).
 <p>Carries the elements matched before the traversal stopped, whether the traversal ran to completion, and the number
 of nodes actually visited. When {@link #isComplete()} is {@code false}, the node-visit budget was exhausted and
 {@link #elements()} holds only a partial, prefix subset of the full match set &mdash; callers must not treat it as the
 complete result.</p>
 */
public final class SelectResult {
    private final Elements elements;
    private final boolean complete;
    private final int visited;
    private final int budget;

    SelectResult(Elements elements, boolean complete, int visited, int budget) {
        this.elements = elements;
        this.complete = complete;
        this.visited = visited;
        this.budget = budget;
    }

    /**
     The matched elements, in document order. If {@link #isComplete()} is {@code false}, this is only the partial set
     of matches found before the budget was exhausted.
     @return matching elements (possibly partial); empty if none
     */
    public Elements elements() {
        return elements;
    }

    /**
     @return {@code true} if the traversal visited every candidate node and {@link #elements()} is the complete match
     set; {@code false} if the budget was exhausted mid-traversal and the result is incomplete
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     @return the number of nodes visited (charged) during the evaluation, including nodes visited internally by nested
     selectors such as {@code :has()}; never exceeds {@link #budget()}
     */
    public int visited() {
        return visited;
    }

    /**
     @return the node-visit budget this evaluation was limited to
     */
    public int budget() {
        return budget;
    }

    @Override public String toString() {
        return String.format("SelectResult{hits=%d, visited=%d, budget=%d, complete=%s}",
            elements.size(), visited, budget, complete);
    }
}
