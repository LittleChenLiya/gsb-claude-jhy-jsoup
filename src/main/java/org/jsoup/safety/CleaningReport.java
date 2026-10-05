package org.jsoup.safety;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 An optional report of the changes a {@link Cleaner} applied while cleaning a single document. Pass an instance to
 {@link Cleaner#clean(org.jsoup.nodes.Document, CleaningReport)} and it will be filled in during that same cleaning
 pass; the cleaner does not re-run or guess afterwards, so every entry reflects an actual removal or rewrite.
 <p>
 Each {@link Entry} is located by the identity of the node in the <b>original</b> (dirty) document, so positions can
 be traced back to the source DOM. When an element is removed, its discarded descendants are folded into the same
 entry (see {@link Entry#descendantCount()}) and are not reported again, so a removed subtree is only counted once.
 For safety, the report never retains the original values of removed (dangerous) attributes; values are only kept on
 {@link Kind#ConvertedLink} entries, which describe attributes that passed the safelist and were rewritten in place.
 </p>
 <p>
 The report does not influence the cleaning result: cleaning with a report produces the same output as cleaning
 without one.
 </p>
 @see Cleaner#clean(org.jsoup.nodes.Document, CleaningReport)
 */
public class CleaningReport {
    /** Why a node or attribute was removed. */
    public enum Reason {
        /** The element's tag is not in the safelist. */
        UnsafeTag,
        /** The attribute is not allowed by the safelist on this tag (or does not match an enforced value). */
        UnsafeAttribute,
        /** The attribute's URL protocol is not in the safelist protocols for this tag and attribute. */
        UnsafeProtocol,
        /** The node type (e.g. a comment or processing instruction) is not copied by the cleaner. */
        DiscardedNode
    }

    /** The category of a URL rewrite applied to a kept (safelist-passing) attribute. */
    public enum Conversion {
        /** A relative URL was resolved to an absolute URL against the document's base URI. */
        RelativeToAbsolute,
        /** The URL could not be resolved against the base URI; the original value was kept as-is. */
        UnresolvableKept
    }

    /** The kind of change an entry describes. */
    public enum Kind {
        /** A node (element or other) was removed. */
        RemovedNode,
        /** An attribute was removed from a kept element. */
        RemovedAttribute,
        /** A kept attribute's URL value was rewritten (e.g. made absolute). */
        ConvertedLink
    }

    /**
     A single cleaning event. The {@link #source()} is the node in the original document that the event applies to
     (the removed node itself, or the kept element owning the affected attribute).
     */
    public static final class Entry {
        private final Kind kind;
        private final Node source;
        private final @Nullable String attributeName;
        private final @Nullable Reason reason;
        private final @Nullable Conversion conversion;
        private final String rule;
        private final @Nullable String originalValue;
        private final @Nullable String convertedValue;
        private int descendantCount;

        private Entry(Kind kind, Node source, @Nullable String attributeName, @Nullable Reason reason,
            @Nullable Conversion conversion, String rule, @Nullable String originalValue,
            @Nullable String convertedValue) {
            this.kind = kind;
            this.source = source;
            this.attributeName = attributeName;
            this.reason = reason;
            this.conversion = conversion;
            this.rule = rule;
            this.originalValue = originalValue;
            this.convertedValue = convertedValue;
        }

        /** The kind of change this entry describes. */
        public Kind kind() {
            return kind;
        }

        /**
         The node in the original (dirty) document this entry applies to, by identity. For {@link Kind#RemovedNode}
         this is the removed node; otherwise it is the kept element owning the attribute.
         */
        public Node source() {
            return source;
        }

        /** The name of the affected attribute, for {@link Kind#RemovedAttribute} and {@link Kind#ConvertedLink} entries; null otherwise. */
        public @Nullable String attributeName() {
            return attributeName;
        }

        /** Why the node or attribute was removed, for removal entries; null for conversions. */
        public @Nullable Reason reason() {
            return reason;
        }

        /** The category of URL rewrite, for {@link Kind#ConvertedLink} entries; null for removals. */
        public @Nullable Conversion conversion() {
            return conversion;
        }

        /** A description of the safelist rule (or cleaner behavior) that caused this entry. */
        public String rule() {
            return rule;
        }

        /**
         The attribute value before rewriting, for {@link Kind#ConvertedLink} entries only. Removed (unsafe) attribute
         values are never retained in the report.
         */
        public @Nullable String originalValue() {
            return originalValue;
        }

        /** The attribute value after rewriting, for {@link Kind#ConvertedLink} entries only; null otherwise. */
        public @Nullable String convertedValue() {
            return convertedValue;
        }

        /**
         The number of descendant nodes that were discarded as part of a {@link Kind#RemovedNode} removal. Those
         descendants are attributed to this entry's reason and are not reported (or counted) separately.
         */
        public int descendantCount() {
            return descendantCount;
        }

        void noteDiscardedDescendant() {
            descendantCount++;
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder(kind.name()).append(": ");
            switch (kind) {
                case RemovedNode:
                    sb.append('<').append(source.nodeName()).append('>');
                    break;
                case RemovedAttribute:
                case ConvertedLink:
                    sb.append('<').append(source.nodeName()).append(' ').append(attributeName).append('>');
                    break;
            }
            if (reason != null) sb.append(" [").append(reason).append(']');
            if (conversion != null) sb.append(" [").append(conversion).append(']');
            sb.append(" - ").append(rule);
            if (descendantCount > 0) sb.append(" (+").append(descendantCount).append(" descendants)");
            return sb.toString();
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    Entry addRemovedNode(Node source, Reason reason, String rule) {
        Entry entry = new Entry(Kind.RemovedNode, source, null, reason, null, rule, null, null);
        entries.add(entry);
        return entry;
    }

    void addRemovedAttribute(Element source, String attributeName, Reason reason, String rule) {
        entries.add(new Entry(Kind.RemovedAttribute, source, attributeName, reason, null, rule, null, null));
    }

    void addConvertedLink(Element source, String attributeName, Conversion conversion, String originalValue,
        String convertedValue, String rule) {
        entries.add(new Entry(Kind.ConvertedLink, source, attributeName, null, conversion, rule, originalValue,
            convertedValue));
    }

    /** All entries, in document (traversal) order. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Entries of the given kind, in document (traversal) order. */
    public List<Entry> entries(Kind kind) {
        List<Entry> filtered = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind == kind) filtered.add(entry);
        }
        return Collections.unmodifiableList(filtered);
    }

    /** The number of removed top-level nodes (removed subtrees). Descendants folded into a removed parent are not counted separately. */
    public int removedNodeCount() {
        return entries(Kind.RemovedNode).size();
    }

    /** The number of attributes removed from kept elements. */
    public int removedAttributeCount() {
        return entries(Kind.RemovedAttribute).size();
    }

    /** The number of kept attributes whose URL value was rewritten. */
    public int convertedLinkCount() {
        return entries(Kind.ConvertedLink).size();
    }

    /** The total number of removed nodes, including descendants folded into a removed parent. */
    public int totalRemovedNodes() {
        int total = 0;
        for (Entry entry : entries) {
            if (entry.kind == Kind.RemovedNode) total += 1 + entry.descendantCount;
        }
        return total;
    }

    /** True if nothing was removed or rewritten. */
    public boolean isClean() {
        return entries.isEmpty();
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("CleaningReport: ")
            .append(removedNodeCount()).append(" nodes removed (").append(totalRemovedNodes()).append(" incl. descendants), ")
            .append(removedAttributeCount()).append(" attributes removed, ")
            .append(convertedLinkCount()).append(" links converted");
        for (Entry entry : entries)
            sb.append("\n  ").append(entry);
        return sb.toString();
    }
}
