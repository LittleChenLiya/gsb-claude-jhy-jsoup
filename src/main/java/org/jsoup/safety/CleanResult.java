package org.jsoup.safety;

import org.jsoup.nodes.Document;

/**
 The result of {@link Cleaner#cleanWithReport(Document)}: the cleaned document plus the audit report of what was
 removed or overridden during cleaning.
 */
public class CleanResult {
    private final Document document;
    private final CleanReport report;

    CleanResult(Document document, CleanReport report) {
        this.document = document;
        this.report = report;
    }

    /** The cleaned document. Identical to what {@link Cleaner#clean(Document)} returns for the same input. */
    public Document document() {
        return document;
    }

    /** The audit report of removals and enforced-attribute changes made during cleaning. */
    public CleanReport report() {
        return report;
    }
}
