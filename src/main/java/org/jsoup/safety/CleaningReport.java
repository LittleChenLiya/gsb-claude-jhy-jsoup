package org.jsoup.safety;

import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;

import java.util.Collections;
import java.util.List;

/**
 The result of {@link Cleaner#cleanWithReport(Document, int, int)}: the cleaned {@link Document}, together with an
 audit report describing every removal and enforced-attribute override the {@link Cleaner} decided on while cleaning.
 <p>
 The report is produced from the Cleaner's actual decisions during the single cleaning pass; the input is not
 re-cleaned, and no before/after HTML comparison is used. The cleaned document is identical to the result of
 {@link Cleaner#clean(Document)} for the same input and safelist, and the input document is never modified.
 </p>
 <p>
 Reported {@link CleaningAction#path() paths} refer to node positions in the <b>original</b> document, computed from
 stable child indexes before any cleaning took place. They are never indexes into the cleaned output, so actions on
 safe children of removed elements still point at their true source locations.
 </p>
 @see CleaningAction
 @since 1.24.1
 */
public final class CleaningReport {
    private final Document document;
    private final List<CleaningAction> actions;
    private final int omittedActions;

    CleaningReport(Document document, List<CleaningAction> actions, int omittedActions) {
        this.document = document;
        this.actions = Collections.unmodifiableList(actions);
        this.omittedActions = omittedActions;
    }

    /**
     The cleaned document. Identical to what {@link Cleaner#clean(Document)} returns for the same input.
     */
    public Document document() {
        return document;
    }

    /**
     The recorded cleaning actions, in document order, capped at the {@code maxActions} limit passed to
     {@link Cleaner#cleanWithReport(Document, int, int)}. Cleaning itself is never truncated; see
     {@link #omittedActions()} for how many further actions were taken but not recorded.
     */
    public List<CleaningAction> actions() {
        return actions;
    }

    /**
     The number of cleaning actions that were taken but not recorded, because the {@code maxActions} report limit was
     reached. {@code 0} when the report is complete.
     */
    public int omittedActions() {
        return omittedActions;
    }

    /**
     {@code true} if the report hit its {@code maxActions} limit and some cleaning actions were not recorded. The
     cleaned document is always complete regardless.
     */
    public boolean isTruncated() {
        return omittedActions > 0;
    }

    /**
     A JSON representation of this report, e.g.
     {@code {"truncated":false,"omittedActions":0,"actions":[{"type":"RemovedElement",...}]}}.
     */
    public String toJson() {
        StringBuilder sb = StringUtil.borrowBuilder();
        sb.append('{');
        sb.append("\"truncated\":").append(isTruncated()).append(',');
        sb.append("\"omittedActions\":").append(omittedActions).append(',');
        sb.append("\"actions\":[");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(actions.get(i).toJson());
        }
        sb.append("]}");
        return StringUtil.releaseBuilder(sb);
    }

    /** The {@link #toJson()} representation of this report. */
    @Override public String toString() {
        return toJson();
    }
}
