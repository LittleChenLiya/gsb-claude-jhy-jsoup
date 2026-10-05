package org.jsoup.safety;

import org.jsoup.nodes.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 A report of the sanitization performed by a {@link Cleaner}.
 <p>
 Records the nodes and attributes that were removed, along with the safelist rule that caused their removal,
 and any relative link conversions that were applied. The report is populated during the same cleaning pass
 that produces the clean document; it is not necessary to run the cleaner again to obtain the report.
 </p>
 <p>
 For security, the report does not retain the original values of removed attributes, as those may contain
 dangerous payloads. Only the attribute key and the reason for removal are recorded.
 </p>
 */
public class SanitizationReport {
    private final List<Deletion> deletions = new ArrayList<>();
    private final List<Conversion> conversions = new ArrayList<>();

    /**
     A record of a node or attribute that was removed during sanitization.
     */
    public static class Deletion {
        private final Node node;
        private final String reason;
        private final String attributeKey; // null for node deletions

        Deletion(Node node, String reason, String attributeKey) {
            this.node = node;
            this.reason = reason;
            this.attributeKey = attributeKey;
        }

        /**
         Get the original node from the dirty document that was removed.
         @return the original node
         */
        public Node node() {
            return node;
        }

        /**
         Get the reason for the removal.
         @return the reason
         */
        public String reason() {
            return reason;
        }

        /**
         Get the attribute key that was removed, if this is an attribute deletion.
         @return the attribute key, or null if this is a node deletion
         */
        public String attributeKey() {
            return attributeKey;
        }

        @Override
        public String toString() {
            if (attributeKey != null) {
                return "Deletion{node=" + node.nodeName() + ", attribute=" + attributeKey + ", reason=" + reason + "}";
            }
            return "Deletion{node=" + node.nodeName() + ", reason=" + reason + "}";
        }
    }

    /**
     A record of a relative link conversion that was applied during sanitization.
     */
    public static class Conversion {
        private final Node node;
        private final String attributeKey;
        private final String category;

        Conversion(Node node, String attributeKey, String category) {
            this.node = node;
            this.attributeKey = attributeKey;
            this.category = category;
        }

        /**
         Get the original node from the dirty document that was converted.
         @return the original node
         */
        public Node node() {
            return node;
        }

        /**
         Get the attribute key that was converted.
         @return the attribute key
         */
        public String attributeKey() {
            return attributeKey;
        }

        /**
         Get the conversion category.
         @return the category
         */
        public String category() {
            return category;
        }

        @Override
        public String toString() {
            return "Conversion{node=" + node.nodeName() + ", attribute=" + attributeKey + ", category=" + category + "}";
        }
    }

    /**
     Get the list of deletions.
     @return an unmodifiable list of deletions
     */
    public List<Deletion> deletions() {
        return Collections.unmodifiableList(deletions);
    }

    /**
     Get the list of conversions.
     @return an unmodifiable list of conversions
     */
    public List<Conversion> conversions() {
        return Collections.unmodifiableList(conversions);
    }

    // Package-private methods for Cleaner to add entries

    void addDeletion(Node node, String reason, String attributeKey) {
        deletions.add(new Deletion(node, reason, attributeKey));
    }

    void addConversion(Node node, String attributeKey, String category) {
        conversions.add(new Conversion(node, attributeKey, category));
    }
}
