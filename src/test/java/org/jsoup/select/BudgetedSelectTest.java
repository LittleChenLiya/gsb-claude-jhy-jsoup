package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for {@link NodeBudget} budgeted selects.

 <p>The fixture is a small 6-element DOM: {@code section, header, div, p, p, span}. The traversal
 visits them in document order.</p>
 */
public class BudgetedSelectTest {

    /** Build the 6-element fixture: section, header, div, p, p, span. */
    private static Element sixNodeDom() {
        Element root = new Element("section");
        root.append("<header></header><div><p></p><p></p></div><span></span>");
        assertEquals(6, root.select("*").size(), "fixture should have 6 elements");
        return root;
    }

    @Test
    public void largeBudgetMatchesOrdinarySelect() {
        Element root = sixNodeDom();

        Elements ordinary = root.select("p");
        BudgetedResult<Element> budgeted = root.select("p", NodeBudget.of(1000));

        assertTrue(budgeted.isComplete(), "large budget should complete");
        assertFalse(budgeted.isIncomplete());
        assertEquals(ordinary.size(), budgeted.size(), "same number of hits");
        for (int i = 0; i < ordinary.size(); i++) {
            assertEquals(ordinary.get(i), budgeted.matches().get(i), "hit " + i + " should match ordinary select");
        }
        assertEquals(6, budgeted.accessedNodes(), "all 6 nodes should be accessed");
        assertEquals(1000, budgeted.budget());
        assertEquals(994, budgeted.remaining());
    }

    @Test
    public void smallBudgetIsIncompleteAndDoesNotExceedLimit() {
        Element root = sixNodeDom();

        // the traversal visits section(1), header(2), div(3) then stops on the 4th access
        BudgetedResult<Element> result = root.select("p", NodeBudget.of(3));

        assertFalse(result.isComplete(), "small budget must be marked incomplete");
        assertTrue(result.isIncomplete());
        assertTrue(result.accessedNodes() <= 3, "accessed count must not exceed budget, was " + result.accessedNodes());
        assertEquals(3, result.accessedNodes());
        // partial hits are a prefix of the full traversal; never the complete set
        assertTrue(result.size() <= root.select("p").size());
        assertEquals(0, result.remaining());
    }

    @Test
    public void budgetOfZeroAccessesNoNodes() {
        Element root = sixNodeDom();

        BudgetedResult<Element> result = root.select("p", NodeBudget.of(0));

        assertFalse(result.isComplete(), "zero budget is incomplete");
        assertEquals(0, result.accessedNodes(), "no nodes should be accessed");
        assertTrue(result.isEmpty(), "no hits without access");
    }

    @Test
    public void hasInternalTraversalIsBilled() {
        Element root = sixNodeDom();

        // reach div (section, header, div = 3 accesses); :has(p) then scans its descendants
        // (p, p) internally. With a budget of 4 the first internal descendant is charged.
        BudgetedResult<Element> result = root.select("div:has(p)", NodeBudget.of(4));

        assertFalse(result.isComplete(), "budget should stop the traversal");
        assertTrue(result.accessedNodes() <= 4, "accessed must not exceed budget, was " + result.accessedNodes());
        // the internal :has traversal is billed: at least one descendant was charged inside :has()
        assertTrue(result.accessedNodes() >= 4, "the :has() internal access should be charged, was " + result.accessedNodes());
    }

    @Test
    public void notConditionIsBilled() {
        Element root = sixNodeDom();

        // :not() must not bypass billing; the node itself is charged before the negation runs
        BudgetedResult<Element> result = root.select("div:not(.missing)", NodeBudget.of(3));

        assertFalse(result.isComplete());
        assertTrue(result.accessedNodes() <= 3, "accessed must not exceed budget");
    }

    @Test
    public void nestedHasInsideNotIsBilled() {
        Element root = sixNodeDom();

        // :not(:has(...)) must still charge the internal :has() traversal
        BudgetedResult<Element> result = root.select("div:not(:has(span))", NodeBudget.of(5));

        assertFalse(result.isComplete());
        assertTrue(result.accessedNodes() <= 5);
    }

    @Test
    public void noMatchReturnsCompleteEmpty() {
        Element root = sixNodeDom();

        BudgetedResult<Element> result = root.select("table", NodeBudget.of(1000));

        assertTrue(result.isComplete(), "a no-match traversal still completes");
        assertTrue(result.isEmpty());
        assertEquals(6, result.accessedNodes(), "all nodes visited even with no match");
    }

    @Test
    public void illegalSelectorThrows() {
        Element root = sixNodeDom();

        assertThrows(Selector.SelectorParseException.class,
            () -> root.select("div???", NodeBudget.of(1000)),
            "illegal selector should throw regardless of budget");
    }

    @Test
    public void ordinarySelectUnaffectedAfterBudgetedSelect() {
        Element root = sixNodeDom();

        Elements before = root.select("p");

        // run a budgeted select that exhausts early
        BudgetedResult<Element> budgeted = root.select("p", NodeBudget.of(2));
        assertFalse(budgeted.isComplete());

        // the next ordinary select must be exactly the same as before: no residue
        Elements after = root.select("p");
        assertEquals(before.size(), after.size(), "ordinary select after budget should be unchanged");
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i), after.get(i));
        }

        // and another budgeted select with a fresh budget is independent
        BudgetedResult<Element> again = root.select("p", NodeBudget.of(1000));
        assertTrue(again.isComplete());
        assertEquals(before.size(), again.size());
    }

    @Test
    public void budgetIsExhaustedFlag() {
        NodeBudget budget = NodeBudget.of(2);
        assertFalse(budget.isExhausted());
        budget.charge();
        budget.charge();
        assertTrue(budget.isExhausted());
        assertEquals(2, budget.used());
        assertEquals(0, budget.remaining());
    }

    @Test
    public void negativeBudgetRejected() {
        assertThrows(IllegalArgumentException.class, () -> NodeBudget.of(-1));
    }

    @Test
    public void documentRootBudgetedSelect() {
        Document doc = Jsoup.parse("<div><p></p><p></p></div>");
        // budgeted select on a Document root behaves like an ordinary select
        BudgetedResult<Element> result = doc.select("p", NodeBudget.of(1000));
        assertTrue(result.isComplete());
        assertEquals(doc.select("p").size(), result.size());
    }
}
