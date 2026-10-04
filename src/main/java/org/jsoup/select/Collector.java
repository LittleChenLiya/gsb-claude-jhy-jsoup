package org.jsoup.select;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.LeafNode;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toCollection;

/**
 * Collects a list of elements that match the supplied criteria.
 *
 * @author Jonathan Hedley
 */
public class Collector {

    private Collector() {}

    /**
     Build a list of elements, by visiting the root and every descendant of root, and testing it against the Evaluator.
     @param eval Evaluator to test elements against
     @param root root of tree to descend
     @return list of matches; empty if none
     */
    public static Elements collect(Evaluator eval, Element root) {
        Stream<Element> stream = eval.wantsNodes() ?
            streamNodes(eval, root, Element.class) :
            stream(eval, root);
        Elements els = stream.collect(toCollection(Elements::new));
        eval.reset(); // drops any held memos
        return els;
    }

    /**
     Obtain a Stream of elements by visiting the root and every descendant of root and testing it against the evaluator.

     <p>If a {@link NodeBudget} is {@linkplain NodeBudget#current() in effect}, each node tested is charged one
     access; when the budget is spent a {@link BudgetExhaustedException} is thrown from the stream. This is the
     billing point for the ordinary element traversal path.</p>

     @param evaluator Evaluator to test elements against
     @param root root of tree to descend
     @return A {@link Stream} of matches
     @since 1.19.1
     */
    public static Stream<Element> stream(Evaluator evaluator, Element root) {
        evaluator.reset();
        Predicate<Element> matcher = evaluator.asPredicate(root);
        return root.stream().filter(bill(matcher));
    }

    /**
     Obtain a Stream of nodes, of the specified type, by visiting the root and every descendant of root and testing it
     against the evaluator.

     <p>If a {@link NodeBudget} is in effect, each node tested is charged one access.</p>

     @param evaluator Evaluator to test elements against
     @param root root of tree to descend
     @param type the type of node to collect (e.g. {@link Element}, {@link LeafNode}, {@link TextNode} etc)
     @param <T> the type of node to collect
     @return A {@link Stream} of matches
     @since 1.21.1
     */
    public static <T extends Node> Stream<T> streamNodes(Evaluator evaluator, Element root, Class<T> type) {
        evaluator.reset();
        Predicate<Node> matcher = evaluator.asNodePredicate(root);
        return root.nodeStream(type).filter(bill(matcher));
    }

    /**
     Wrap a node predicate so that, when a {@link NodeBudget} is in effect for the current thread, each node tested is
     charged one access before the predicate runs. Without a budget in effect, the wrapper is a pass-through, so
     ordinary selects are unaffected.
     */
    static <T extends Node> Predicate<T> bill(Predicate<T> matcher) {
        return node -> {
            NodeBudget budget = NodeBudget.current();
            if (budget != null)
                budget.charge(); // throws BudgetExhaustedException when spent
            return matcher.test(node);
        };
    }

    /**
     Finds the first Element that matches the Evaluator that descends from the root, and stops the query once that first
     match is found.
     @param eval Evaluator to test elements against
     @param root root of tree to descend
     @return the first match; {@code null} if none
     */
    public static @Nullable Element findFirst(Evaluator eval, Element root) {
        Element el = stream(eval, root).findFirst().orElse(null);
        eval.reset();
        return el;
    }

    /**
     Finds the first Node that matches the Evaluator that descends from the root, and stops the query once that first
     match is found.

     @param eval Evaluator to test elements against
     @param root root of tree to descend
     @param type the type of node to collect (e.g. {@link Element}, {@link LeafNode}, {@link TextNode} etc)
     @return the first match; {@code null} if none
     @since 1.21.1
     */
    public static <T extends Node> @Nullable T findFirstNode(Evaluator eval, Element root, Class<T> type) {
        T node = streamNodes(eval, root, type).findFirst().orElse(null);
        eval.reset();
        return node;
    }

    /**
     Build a list of nodes that match the supplied criteria, by visiting the root and every descendant of root, and
     testing it against the Evaluator.

     @param evaluator Evaluator to test elements against
     @param root root of tree to descend
     @param type the type of node to collect (e.g. {@link Element}, {@link LeafNode}, {@link TextNode} etc)
     @param <T> the type of node to collect
     @return list of matches; empty if none
     */
    public static <T extends Node> Nodes<T> collectNodes(Evaluator evaluator, Element root, Class<T> type) {
        return streamNodes(evaluator, root, type).collect(toCollection(Nodes::new));
    }
}
