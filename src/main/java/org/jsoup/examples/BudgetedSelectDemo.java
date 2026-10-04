package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.SelectResult;
import org.jsoup.select.Selector;

/**
 * A short demo of {@link Selector#selectWithBudget(String, Element, int)}: runs the same queries against a small
 * 6-element DOM with different node-visit budgets, and prints the hits and the budget status of each run.
 */
public class BudgetedSelectDemo {
    public static void main(String[] args) {
        Document doc = Jsoup.parseBodyFragment(
            "<div id=d1><p id=p1><span id=s1></span></p><p id=p2><b id=b1></b></p></div>");
        Element root = doc.body(); // 6 elements: body, div#d1, p#p1, span#s1, p#p2, b#b1

        report(root, "p", 1000);               // large budget: same as a regular select
        report(root, "p", 3);                  // small budget: stops early, incomplete
        report(root, "p", 0);                  // zero budget: visits nothing
        report(root, "div:has(span)", 1000);   // nested :has() visits are charged
        report(root, "div:has(span)", 6);
        report(root, "p:not(:has(span))", 1000); // negation cannot bypass the budget either
        report(root, "p:not(:has(span))", 6);

        // a regular select after the budgeted runs is unaffected:
        System.out.println("regular select after budgeted runs: " + root.select("p").eachAttr("id"));
    }

    private static void report(Element root, String query, int budget) {
        SelectResult result = Selector.selectWithBudget(query, root, budget);
        System.out.printf("query=%-22s budget=%-4d -> hits=%s visited=%d %s%n",
            query, budget, result.elements().eachAttr("id"), result.visited(),
            result.isComplete() ? "COMPLETE" : "INCOMPLETE (budget exhausted)");
    }
}
