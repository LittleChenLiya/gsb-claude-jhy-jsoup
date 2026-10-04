package org.jsoup.select;

/**
 Internal control-flow exception thrown when a {@link NodeBudget} is exhausted during a budgeted
 select. It is raised by the billed traversal predicates and by the internal {@code :has()}
 traversals, then caught by the budgeted select entry point. It never escapes to callers.
 */
final class BudgetExhaustedException extends RuntimeException {
    final transient NodeBudget budget;

    BudgetExhaustedException(NodeBudget budget) {
        this.budget = budget;
    }

    // control-flow only; no stack trace needed
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
