package org.jsoup.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 Reports that two or more extracted headings share the same {@code id} attribute. The conflict is reported as-is;
 the source DOM nodes are never rewritten.
 */
public final class IdConflict {
    private final String id;
    private final List<Section> sections;

    IdConflict(String id, List<Section> sections) {
        this.id = id;
        this.sections = new ArrayList<>(sections);
    }

    /** @return the duplicated id */
    public String id() {
        return id;
    }

    /**
     The sections (in document order) that share {@link #id()}.
     @return an independent unmodifiable copy of the conflicting section list
     */
    public List<Section> sections() {
        return Collections.unmodifiableList(new ArrayList<>(sections));
    }

    @Override public String toString() {
        return "Duplicate id #" + id + " on " + sections.size() + " headings: " + sections;
    }
}
