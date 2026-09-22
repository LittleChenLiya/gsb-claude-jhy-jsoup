package org.jsoup.safety;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.Range;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 Collects {@link CleaningAction}s from the Cleaner's decision points during a single clean pass. Paths and positions
 are computed against the original (dirty) document, which the Cleaner never modifies, so they stay stable no matter
 what has already been removed from the clean copy.
 */
final class CleaningAudit {
    final List<CleaningAction> actions = new ArrayList<>();
    final int maxActions;
    final int maxValueLength;
    int omitted = 0;

    CleaningAudit(int maxActions, int maxValueLength) {
        this.maxActions = maxActions;
        this.maxValueLength = maxValueLength;
    }

    private void add(CleaningAction action) {
        if (actions.size() < maxActions) actions.add(action);
        else omitted++; // cleaning continues; only the report is capped
    }

    void removedElement(Element el) {
        add(new CleaningAction(CleaningAction.Type.RemovedElement, CleaningAction.Reason.DisallowedTag,
            pathOf(el), positionOf(el), el.normalName(), null, null, false));
    }

    void removedAttribute(Element el, Attribute attr, Safelist.AttributeStatus status) {
        CleaningAction.Reason reason = status == Safelist.AttributeStatus.DisallowedProtocol
            ? CleaningAction.Reason.DisallowedProtocol : CleaningAction.Reason.DisallowedAttribute;
        add(new CleaningAction(CleaningAction.Type.RemovedAttribute, reason,
            pathOf(el), attrPositionOf(el, attr.getKey()), el.normalName(), attr.getKey(), summarize(attr.getValue()), false));
    }

    void removedNode(Node node) {
        String value = null;
        if (node instanceof Comment) value = ((Comment) node).getData();
        else if (node instanceof DataNode) value = ((DataNode) node).getWholeData();
        add(new CleaningAction(CleaningAction.Type.RemovedNode, CleaningAction.Reason.DisallowedNode,
            pathOf(node), positionOf(node), node.nodeName(), null, summarize(value), false));
    }

    void enforcedAttribute(Element el, Attributes sourceAttrs, Attribute enforcedAttr, String prev) {
        add(new CleaningAction(CleaningAction.Type.EnforcedAttribute, CleaningAction.Reason.EnforcedAttribute,
            pathOf(el), enforcedAttrPositionOf(el, sourceAttrs, enforcedAttr.getKey()), el.normalName(),
            enforcedAttr.getKey(), summarize(prev), prev != null));
    }

    /**
     Builds a path to the node in the original document, from stable child indexes (element and text-node positions
     among their siblings), e.g. {@code #document>html[0]>body[1]>script[0]}. As the source tree is never modified
     during cleaning, these indexes remain valid even for nodes whose ancestors were removed from the clean output.
     */
    static String pathOf(Node node) {
        Deque<String> segments = new ArrayDeque<>();
        Node current = node;
        while (current != null) {
            String name = current instanceof Element ? ((Element) current).normalName() : current.nodeName();
            segments.push(current.parent() != null ? name + "[" + current.siblingIndex() + "]" : name);
            current = current.parent();
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            if (sb.length() > 0) sb.append('>');
            sb.append(segment);
        }
        return sb.toString();
    }

    private static String positionOf(Node node) {
        Range range = node.sourceRange();
        return range.isTracked() ? range.toString() : CleaningAction.UnknownSource;
    }

    private static String attrPositionOf(Element el, String key) {
        Range.AttributeRange range = el.attributes().sourceRange(key);
        return range.isTracked() ? range.nameRange().toString() : CleaningAction.UnknownSource;
    }

    private static String enforcedAttrPositionOf(Element el, Attributes sourceAttrs, String enforcedKey) {
        for (Attribute attr : sourceAttrs) {
            if (attr.getKey().equalsIgnoreCase(enforcedKey))
                return attrPositionOf(el, attr.getKey());
        }
        return positionOf(el); // no source attribute; point at the element
    }

    private String summarize(String value) {
        if (value == null || maxValueLength <= 0) return null;
        return value.length() <= maxValueLength ? value : value.substring(0, maxValueLength) + "\u2026";
    }
}
