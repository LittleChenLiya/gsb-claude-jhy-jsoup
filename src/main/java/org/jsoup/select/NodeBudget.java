package org.jsoup.select;

import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

/**
 A node-access budget for a {@linkplain Selector#select(String, Element, NodeBudget) budgeted select}.

 <p>A budget caps how many nodes the selector engine may access while evaluating a query. When the
 budget is exhausted, evaluation stops and a {@link BudgetedResult#isIncomplete() partial} result is
 returned. The budget is charged along the same {@link Evaluator} and traversal paths used by an
 ordinary select -- including the internal descendant and sibling traversals performed by
 {@code :has()} -- so nested relationships and negations cannot bypass the budget.</p>

 <p>A budget instance is scoped to a single evaluation. Passing a budget to a select does not change
 the behavior of subsequent ordinary selects: the budget is held in a {@link ThreadLocal} that is
 always cleared when the budgeted select returns (or is stopped), so no residue remains.</p>

 <p>Usage:
 <pre>{@code
 NodeBudget budget = NodeBudget.of(100);
 BudgetedResult<Element> result = doc.select("div:has(p)", budget);
 if (result.isComplete()) {
     // result.matches() is the full set of matching elements
 } else {
     // result.matches() is only the hits found before the budget was spent
 }
 }</pre>

 @see Selector#select(String, Element, NodeBudget)
 @see BudgetedResult
 */
public final class NodeBudget {
    private final int limit;
    private int used;
    private boolean exhausted;

    private NodeBudget(int limit) {
        this.limit = limit;
    }

    /**
     Create a budget that allows up to {@code limit} node accesses.
     @param limit the maximum number of nodes to access; must be {@code >= 0}
     @return a new budget
     */
    public static NodeBudget of(int limit) {
        Validate.isTrue(limit >= 0, "Budget must be >= 0");
        return new NodeBudget(limit);
    }

    /** @return the maximum number of node accesses this budget allows */
    public int limit() {
        return limit;
    }

    /** @return the number of node accesses charged so far */
    public int used() {
        return used;
    }

    /** @return the number of accesses remaining (0 once the budget is exhausted) */
    public int remaining() {
        return Math.max(0, limit - used);
    }

    /** @return {@code true} if the budget has been exhausted */
    public boolean isExhausted() {
        return exhausted;
    }

    /**
     Charge one node access. Package-private so the billed traversal predicates can charge, but
     callers cannot reset or otherwise manipulate a budget mid-evaluation.
     @throws BudgetExhaustedException when the budget has been spent
     */
    void charge() {
        if (used >= limit) {
            exhausted = true;
            throw new BudgetExhaustedException(this);
        }
        used++;
        if (used >= limit)
            exhausted = true;
    }

    @Override
    public String toString() {
        return "NodeBudget[" + used + "/" + limit + (exhausted ? ", exhausted" : "") + "]";
    }

    // the current budget for the executing thread; consumed by the billed traversal predicates
    private static final ThreadLocal<NodeBudget> CURRENT = new ThreadLocal<>();

    static @Nullable NodeBudget current() {
        return CURRENT.get();
    }

    static void setCurrent(NodeBudget budget) {
        CURRENT.set(budget);
    }

    static void clearCurrent() {
        CURRENT.remove();
    }
}
