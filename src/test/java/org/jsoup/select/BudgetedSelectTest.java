package org.jsoup.select;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 Tests for {@link Selector#selectWithBudget(String, Element, int)}: a select entry bounded by a node-visit budget.
 */
public class BudgetedSelectTest {

    /** A 6-element DOM: body > div#d1 > (p#p1 > span#s1), (p#p2 > b#b1). */
    private static Element sixElementDom() {
        Document doc = Jsoup.parseBodyFragment(
            "<div id=d1><p id=p1><span id=s1></span></p><p id=p2><b id=b1></b></p></div>");
        Element body = doc.body();
        assertEquals(6, body.getAllElements().size()); // body, div#d1, p#p1, span#s1, p#p2, b#b1
        return body;
    }

    @Test void largeBudgetMatchesRegularSelect() {
        Element root = sixElementDom();
        SelectResult result = Selector.selectWithBudget("p", root, 1000);

        assertTrue(result.isComplete());
        assertEquals(root.select("p"), result.elements());
        assertEquals(2, result.elements().size());
        assertEquals(6, result.visited()); // every element visited exactly once
        assertEquals(1000, result.budget());
    }

    @Test void smallBudgetIsIncompleteAndWithinLimit() {
        Element root = sixElementDom();
        SelectResult result = Selector.selectWithBudget("p", root, 3);

        assertFalse(result.isComplete());
        assertEquals(3, result.visited()); // body, div#d1, p#p1
        assertTrue(result.visited() <= result.budget());
        // partial hits are a prefix of the full result, not masquerading as complete:
        assertEquals(1, result.elements().size());
        assertEquals("p1", result.elements().get(0).id());
        assertNotEquals(root.select("p"), result.elements());
    }

    @Test void zeroBudgetVisitsNothing() {
        Element root = sixElementDom();
        SelectResult result = Selector.selectWithBudget("p", root, 0);

        assertFalse(result.isComplete());
        assertEquals(0, result.visited());
        assertTrue(result.elements().isEmpty());
    }

    @Test void hasSelectorChargesInternalVisits() {
        Element root = sixElementDom();
        SelectResult full = Selector.selectWithBudget("div:has(span)", root, 1000);

        assertTrue(full.isComplete());
        assertEquals("[d1]", full.elements().eachAttr("id").toString());
        // 6 outer visits + 2 inner :has() visits (p#p1, span#s1 under div#d1):
        assertEquals(8, full.visited());

        // a plain "div" completes within 6 visits; the :has() form needs its inner visits budgeted too:
        assertTrue(Selector.selectWithBudget("div", root, 6).isComplete());
        SelectResult shortByInner = Selector.selectWithBudget("div:has(span)", root, 6);
        assertFalse(shortByInner.isComplete());
        assertTrue(shortByInner.visited() <= 6);

        // one visit short of the full requirement is also incomplete:
        SelectResult shortByOne = Selector.selectWithBudget("div:has(span)", root, full.visited() - 1);
        assertFalse(shortByOne.isComplete());
        assertEquals(full.visited() - 1, shortByOne.visited());
    }

    @Test void notSelectorCannotBypassBudget() {
        Element root = sixElementDom();
        SelectResult full = Selector.selectWithBudget("p:not(:has(span))", root, 1000);

        assertTrue(full.isComplete());
        assertEquals("[p2]", full.elements().eachAttr("id").toString());
        assertEquals(root.select("p:not(:has(span))"), full.elements());
        // inner :has() traversals under :not() are charged, so more than the 6 outer visits were needed:
        assertTrue(full.visited() > 6, "expected inner visits to be charged, visited=" + full.visited());

        SelectResult limited = Selector.selectWithBudget("p:not(:has(span))", root, full.visited() - 1);
        assertFalse(limited.isComplete());
        assertTrue(limited.visited() <= limited.budget());
    }

    @Test void budgetLeavesNoResidualState() {
        Element root = sixElementDom();

        SelectResult incomplete = Selector.selectWithBudget("p", root, 1);
        assertFalse(incomplete.isComplete());

        // the next regular select is unaffected by the exhausted budget:
        assertEquals(2, root.select("p").size());
        assertEquals(2, Selector.select("p", root).size());

        // and a following budgeted select gets a fresh, full budget:
        SelectResult next = Selector.selectWithBudget("p", root, 1000);
        assertTrue(next.isComplete());
        assertEquals(2, next.elements().size());
    }

    @Test void noMatchIsCompleteAndEmpty() {
        Element root = sixElementDom();
        SelectResult result = Selector.selectWithBudget("em", root, 1000);

        assertTrue(result.isComplete());
        assertTrue(result.elements().isEmpty());
        assertEquals(6, result.visited());
    }

    @Test void invalidSelectorThrowsAndLeavesNoState() {
        Element root = sixElementDom();
        assertThrows(Selector.SelectorParseException.class,
            () -> Selector.selectWithBudget("p[", root, 10));
        assertThrows(Selector.SelectorParseException.class,
            () -> Selector.selectWithBudget(":bogus(p)", root, 10));

        // no residual budget state after the failures:
        assertEquals(2, root.select("p").size());
    }

    @Test void invalidArgumentsRejected() {
        Element root = sixElementDom();
        assertThrows(IllegalArgumentException.class, () -> Selector.selectWithBudget("p", root, -1));
        assertThrows(IllegalArgumentException.class, () -> Selector.selectWithBudget("", root, 10));
        assertThrows(IllegalArgumentException.class, () -> Selector.selectWithBudget((Evaluator) null, root, 10));
    }

    @Test void evaluatorOverloadSelects() {
        Element root = sixElementDom();
        Evaluator eval = Selector.evaluatorOf("span");
        SelectResult result = Selector.selectWithBudget(eval, root, 1000);

        assertTrue(result.isComplete());
        assertEquals("[s1]", result.elements().eachAttr("id").toString());
    }
}
