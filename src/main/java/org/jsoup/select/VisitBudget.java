package org.jsoup.select;

/**
 A thread-local node-visit budget meter, used to bound the amount of traversal work a single select evaluation may
 perform. A budget is activated for the current thread with {@link #start(int)}, charged once per visited node via
 {@link #charge()} from the traversal and evaluator code paths (including the nested traversals run by
 {@code :has()}), and always released with {@link #stop()}. When no budget is active, {@link #charge()} is a no-op, so
 regular (unbudgeted) selects are unaffected.
 <p>Not reentrant: a budgeted select must not itself start another budgeted select on the same thread.</p>
 */
final class VisitBudget {
    private static final ThreadLocal<VisitBudget> Active = new ThreadLocal<>();

    private final int budget;
    private int visited;

    private VisitBudget(int budget) {
        this.budget = budget;
    }

    /** Activates a new budget of {@code budget} node visits for the current thread. */
    static VisitBudget start(int budget) {
        VisitBudget visitBudget = new VisitBudget(budget);
        Active.set(visitBudget);
        return visitBudget;
    }

    /** Deactivates any budget for the current thread, leaving no residual state. */
    static void stop() {
        Active.remove();
    }

    /**
     Charges one node visit against the active budget, if any. Throws {@link BudgetExhaustedException} if the budget
     is already exhausted; the node raising the exception is not counted as visited.
     */
    static void charge() {
        VisitBudget visitBudget = Active.get();
        if (visitBudget != null)
            visitBudget.chargeOne();
    }

    private void chargeOne() {
        if (visited >= budget)
            throw BudgetExhaustedException.Instance;
        visited++;
    }

    /** @return the number of nodes visited (charged) so far; never exceeds the budget. */
    int visited() {
        return visited;
    }

    /** Thrown (internally) when a node visit would exceed the active budget. Carries no stack trace. */
    static final class BudgetExhaustedException extends RuntimeException {
        static final BudgetExhaustedException Instance = new BudgetExhaustedException();

        private BudgetExhaustedException() {
            super(null, null, false, false);
        }
    }
}
