package org.jsoup.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 An audit report produced by {@link Cleaner#cleanWithReport(org.jsoup.nodes.Document, CleanReport.Options)}, describing
 every removal or enforced-attribute change the Cleaner made, and why. Entries are recorded at the point the Cleaner
 makes each decision, during the single cleaning pass; the report never re-cleans or diffs documents.
 <p>
 Each entry carries the path of the affected node in the <b>original</b> (dirty) document, computed from stable child
 indexes of element and text nodes before any cleaning took place, so positions of removed nodes (or of nodes whose
 parents were removed) remain accurate. If the input document was parsed with position tracking enabled
 ({@link org.jsoup.parser.Parser#setTrackPosition(boolean)}), entries also include the source range of the node or
 attribute; otherwise the position is reported as {@code "unknown"}.
 </p>
 <p>
 The report is capped at a maximum number of entries ({@link Options#maxEntries(int)}). Cleaning always continues to
 completion after the cap is reached; the report is then marked {@link #isTruncated() truncated} and
 {@link #omittedCount()} returns how many further entries were dropped.
 </p>
 <p>
 <b>Security note:</b> by default, entries do not include the original (removed) values, as those may contain
 sensitive or hostile content. Opt in with {@link Options#includeOriginalValues(boolean)} if you need length-limited
 value summaries, and treat them as untrusted data.
 </p>
 */
public class CleanReport {
    /** The kind of change the Cleaner made. */
    public enum Action {
        /** An element was removed because its tag is not allowed. */
        RemovedElement,
        /** An attribute was removed from an otherwise safe element. */
        RemovedAttribute,
        /** A non-element, non-text node (comment, data node, etc.) was removed. */
        RemovedNode,
        /** An enforced attribute was added to, or replaced a value on, an element. */
        EnforcedAttribute
    }

    /** Why the Cleaner made the change. */
    public enum Reason {
        TagNotAllowed("tag not allowed"),
        AttributeNotAllowed("attribute not allowed"),
        ProtocolNotAllowed("protocol not allowed"),
        NodeTypeNotAllowed("node type not allowed"),
        EnforcedAttributeOverride("enforced attribute override"),
        EnforcedAttributeAdded("enforced attribute added");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        /** A human-readable description of this reason. */
        public String description() {
            return description;
        }
    }

    /**
     Configuration for a {@link CleanReport}: the entry cap and whether to include length-limited summaries of
     removed original values.
     */
    public static class Options {
        /** The default maximum number of entries a report holds before truncating. */
        public static final int DefaultMaxEntries = 100;
        /** The default maximum length of an included original-value summary. */
        public static final int DefaultMaxValueLength = 64;

        private int maxEntries = DefaultMaxEntries;
        private boolean includeOriginalValues = false;
        private int maxValueLength = DefaultMaxValueLength;

        public Options() {}

        /** Maximum number of entries to record before truncating the report. Cleaning is unaffected. */
        public Options maxEntries(int maxEntries) {
            if (maxEntries < 0) throw new IllegalArgumentException("maxEntries must be >= 0");
            this.maxEntries = maxEntries;
            return this;
        }

        /**
         Whether to include length-limited summaries of removed original values. Defaults to false, as removed
         content is untrusted and may be sensitive.
         */
        public Options includeOriginalValues(boolean include) {
            this.includeOriginalValues = include;
            return this;
        }

        /** Maximum length of an included original-value summary; longer values are truncated with an ellipsis. */
        public Options maxValueLength(int maxValueLength) {
            if (maxValueLength < 1) throw new IllegalArgumentException("maxValueLength must be >= 1");
            this.maxValueLength = maxValueLength;
            return this;
        }

        public int maxEntries() { return maxEntries; }
        public boolean includeOriginalValues() { return includeOriginalValues; }
        public int maxValueLength() { return maxValueLength; }
    }

    /** A single audited change. */
    public static class Entry {
        private final Action action;
        private final Reason reason;
        private final String path;
        private final String position;
        private final String tagName;      // set for element and attribute entries
        private final String attributeName; // set for attribute entries
        private final String originalValue; // nullable; only set when Options#includeOriginalValues is on

        Entry(Action action, Reason reason, String path, String position, String tagName, String attributeName,
              String originalValue) {
            this.action = action;
            this.reason = reason;
            this.path = path;
            this.position = position;
            this.tagName = tagName;
            this.attributeName = attributeName;
            this.originalValue = originalValue;
        }

        /** The kind of change made. */
        public Action action() { return action; }
        /** Why the change was made. */
        public Reason reason() { return reason; }
        /**
         Path of the affected node in the original document, e.g. {@code /body/div[0]/#text[1]} or
         {@code /body/a[2]/@href}. Indexes count element and text-node children of each parent, as they were before
         cleaning.
         */
        public String path() { return path; }
        /** Source range of the node or attribute, or {@code "unknown"} if position tracking was not enabled. */
        public String position() { return position; }
        /** The tag name involved, or null for non-element node removals. */
        public String tagName() { return tagName; }
        /** The attribute name involved, or null for element/node removals. */
        public String attributeName() { return attributeName; }
        /** The length-limited original value summary, or null if value recording was not enabled. */
        public String originalValue() { return originalValue; }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            field(sb, "action", action.name()); sb.append(',');
            field(sb, "reason", reason.description()); sb.append(',');
            field(sb, "path", path); sb.append(',');
            field(sb, "position", position);
            if (tagName != null) { sb.append(','); field(sb, "tag", tagName); }
            if (attributeName != null) { sb.append(','); field(sb, "attribute", attributeName); }
            if (originalValue != null) { sb.append(','); field(sb, "originalValue", originalValue); }
            sb.append('}');
            return sb.toString();
        }

        @Override public String toString() {
            return action + " " + reason.description() + " at " + path + " (" + position + ")";
        }
    }

    private final Options options;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private boolean truncated = false;
    private int omittedCount = 0;

    CleanReport(Options options) {
        this.options = options;
    }

    Options options() {
        return options;
    }

    void record(Entry entry) {
        if (entries.size() < options.maxEntries()) {
            entries.add(entry);
        } else {
            truncated = true;
            omittedCount++;
        }
    }

    /** The recorded entries, capped at {@link Options#maxEntries()}. */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** True if the entry cap was reached and further changes were only counted, not recorded. */
    public boolean isTruncated() {
        return truncated;
    }

    /** How many entries were dropped after the cap was reached. */
    public int omittedCount() {
        return omittedCount;
    }

    /** The maximum number of entries this report holds. */
    public int maxEntries() {
        return options.maxEntries();
    }

    /** A JSON serialization of this report, for logging and audit pipelines. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"truncated\":").append(truncated)
            .append(",\"omittedCount\":").append(omittedCount)
            .append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(entries.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":");
        quote(sb, value);
    }

    static void quote(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
