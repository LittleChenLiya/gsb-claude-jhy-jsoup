package org.jsoup.select;

import org.jsoup.nodes.Node;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 The result of a {@linkplain Selector#select(String, Element, NodeBudget) budgeted selector
 evaluation}: the nodes matched before the traversal ended, together with a flag indicating whether
 the traversal completed or was stopped by the {@linkplain NodeBudget node-access budget}.

 <p>If {@link #isComplete()} is {@code false}, the traversal was stopped because the budget was
 exhausted. The returned {@link #matches()} are the partial hits found up to that point. They are
 <b>not</b> a complete result and must not be treated as such -- the status flags make that explicit.</p>

 @see Selector#select(String, Element, NodeBudget)
 @see NodeBudget
 */
public final class BudgetedResult<T extends Node> {
    private final List<T> matches;
    private final boolean complete;
    private final int accessedNodes;
    private final int budget;

    BudgetedResult(List<T> matches, boolean complete, int accessedNodes, int budget) {
        this.matches = matches;
        this.complete = complete;
        this.accessedNodes = accessedNodes;
        this.budget = budget;
    }

    /**
     The nodes matched before the traversal ended (or the budget was spent).
     @return the matched nodes; empty if none matched or no nodes were accessed
     */
    public List<T> matches() {
        return matches;
    }

    /**
     Whether the full traversal completed.
     @return {@code true} if the traversal completed and {@link #matches()} is the complete result;
     {@code false} if the budget stopped the traversal early
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     Whether the traversal was stopped early because the budget was exhausted.
     @return {@code true} if the budget was spent before the traversal finished
     */
    public boolean isIncomplete() {
        return !complete;
    }

    /**
     The number of node accesses charged during the evaluation. This never exceeds the
     {@linkplain #budget() budget}.
     @return the number of nodes accessed
     */
    public int accessedNodes() {
        return accessedNodes;
    }

    /**
     The node-access budget that was applied.
     @return the budget limit
     */
    public int budget() {
        return budget;
    }

    /**
     The number of node accesses remaining (0 if the budget was exhausted).
     @return remaining accesses
     */
    public int remaining() {
        return Math.max(0, budget - accessedNodes);
    }

    /** @return the number of matched nodes */
    public int size() {
        return matches.size();
    }

    /** @return {@code true} if no nodes matched */
    public boolean isEmpty() {
        return matches.isEmpty();
    }

    /** @return the first matched node, or {@code null} if none */
    public @Nullable T first() {
        return matches.isEmpty() ? null : matches.get(0);
    }

    @Override
    public String toString() {
        String status = complete ? "complete" : "INCOMPLETE (budget exhausted)";
        return status + "; hits=" + matches.size()
            + "; accessed=" + accessedNodes + "/" + budget
            + "; remaining=" + remaining();
    }
}
