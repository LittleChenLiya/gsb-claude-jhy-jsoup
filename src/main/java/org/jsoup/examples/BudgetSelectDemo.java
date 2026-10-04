package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.BudgetedResult;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeBudget;
import org.jsoup.select.Selector;

/**
 A short demo of budgeted selects. A {@link NodeBudget} caps how many nodes the selector engine may
 access; when the budget is spent, the traversal stops and an <em>incomplete</em> result is returned
 with the hits found so far. Ordinary {@link Element#select(String)} behavior is unchanged.
 */
public class BudgetSelectDemo {

    public static void main(String[] args) {
        // 8 elements: #root(Document), html, head, body, div, p, p, span
        Document doc = Jsoup.parse("<div id=d class=box><p>a</p><p>b</p><span>c</span></div>");

        System.out.println("Document: " + doc.body().children());
        System.out.println("Total elements: " + doc.select("*").size());
        System.out.println();

        // 1. baseline ordinary select
        Elements ordinary = doc.select("p");
        System.out.println("ordinary select 'p': " + ordinary.size() + " hits");
        System.out.println();

        // 2. large budget: complete, same hits as ordinary select
        NodeBudget large = NodeBudget.of(1000);
        BudgetedResult<Element> largeResult = doc.select("p", large);
        System.out.println("large budget (1000): " + largeResult);
        System.out.println("  same hits as ordinary select: " + sameElements(ordinary, largeResult));
        System.out.println();

        // 3. small budget: incomplete, traversal stopped early
        NodeBudget small = NodeBudget.of(2);
        BudgetedResult<Element> smallResult = doc.select("p", small);
        System.out.println("small budget (2): " + smallResult);
        System.out.println("  -> incomplete is explicit; partial hits are not a full result");
        System.out.println();

        // 4. :has() counts its internal descendant traversal
        NodeBudget hasBudget = NodeBudget.of(6); // enough to reach div; the 6th access is the p inside :has()
        BudgetedResult<Element> hasResult = doc.select("div:has(p)", hasBudget);
        System.out.println("'div:has(p)' budget 4: " + hasResult);
        System.out.println();

        // 5. :not() (and :not(:has())) are billed too
        NodeBudget notBudget = NodeBudget.of(3);
        BudgetedResult<Element> notResult = doc.select("div:not(.missing)", notBudget);
        System.out.println("'div:not(.missing)' budget 3: " + notResult);
        System.out.println();

        // 6. zero budget: no nodes accessed
        NodeBudget zero = NodeBudget.of(0);
        BudgetedResult<Element> zeroResult = doc.select("p", zero);
        System.out.println("zero budget (0): " + zeroResult);
        System.out.println();

        // 7. no residue: the next ordinary select is unaffected
        Elements after = doc.select("p");
        System.out.println("ordinary select after budgeted run: " + after.size() + " hits (unchanged: "
            + after.equals(ordinary) + ")");

        // 8. no match: complete but empty
        BudgetedResult<Element> none = doc.select("table", NodeBudget.of(1000));
        System.out.println("'table' (no match): " + none);

        // 9. illegal selector still throws
        try {
            doc.select("div???", NodeBudget.of(1000));
            System.out.println("illegal selector: NO EXCEPTION (bug)");
        } catch (Selector.SelectorParseException e) {
            System.out.println("illegal selector: SelectorParseException thrown as expected");
        }
    }

    private static boolean sameElements(Elements a, BudgetedResult<Element> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.matches().get(i))) return false;
        }
        return true;
    }
}
