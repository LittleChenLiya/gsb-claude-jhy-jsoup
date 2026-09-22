package org.jsoup.safety;

import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.jspecify.annotations.Nullable;

/**
 A single audited action taken by the {@link Cleaner} during {@link Cleaner#cleanWithReport(Document, int, int)}: the
 removal of an element, attribute, or other node, or the application of an enforced attribute.
 <p>
 Each action records the path of the affected node in the <b>original</b> (dirty) document, computed from stable child
 indexes before any cleaning took place; the source position of the node or attribute when position tracking was
 enabled during parse (otherwise {@link #UnknownSource}); and the reason the Cleaner took the action, as decided by the
 {@link Safelist} at the point of cleaning.
 </p>
 @see Cleaner#cleanWithReport(Document, int, int)
 @see CleaningReport
 @since 1.24.1
 */
public final class CleaningAction {
    /** The source position value used when the input was not parsed with position tracking enabled. */
    public static final String UnknownSource = "unknown";

    /** The kind of action the Cleaner took. */
    public enum Type {
        /** An element was removed because its tag is not allowed. Its safe children, if any, were retained. */
        RemovedElement,
        /** An attribute was removed from an otherwise safe element. */
        RemovedAttribute,
        /** A non-element node (such as a comment or a data node) was removed. */
        RemovedNode,
        /** A safelist-enforced attribute was set on the output element, either added or overriding a source value. */
        EnforcedAttribute
    }

    /** The reason the Cleaner took the action, as decided by the {@link Safelist}. */
    public enum Reason {
        /** The element's tag is not in the safelist. */
        DisallowedTag,
        /** The attribute is not allowed on the element. */
        DisallowedAttribute,
        /** The attribute's URL value uses a protocol that is not allowed. */
        DisallowedProtocol,
        /** The node type is not copied to the clean output (e.g. comments, or data nodes under a removed parent). */
        DisallowedNode,
        /** The attribute was set (or its value overridden) by a safelist-enforced attribute. */
        EnforcedAttribute
    }

    private final Type type;
    private final Reason reason;
    private final String path;
    private final String sourcePosition;
    private final String target;
    private final @Nullable String attribute;
    private final @Nullable String value;
    private final boolean replaced;

    CleaningAction(Type type, Reason reason, String path, String sourcePosition, String target,
                   @Nullable String attribute, @Nullable String value, boolean replaced) {
        this.type = type;
        this.reason = reason;
        this.path = path;
        this.sourcePosition = sourcePosition;
        this.target = target;
        this.attribute = attribute;
        this.value = value;
        this.replaced = replaced;
    }

    /** The kind of action taken. */
    public Type type() {
        return type;
    }

    /** Why the Cleaner took this action. */
    public Reason reason() {
        return reason;
    }

    /**
     The path of the affected node in the original document, before cleaning. Each segment is a node name
     ({@code tag} for elements, {@code #text}, {@code #comment}, etc.) followed by its stable child index within its
     parent, e.g. {@code #document>html[0]>body[1]>script[0]}. Positions refer to the dirty document only; they are
     never indexes into the cleaned output.
     */
    public String path() {
        return path;
    }

    /**
     The source position of the affected node or attribute, in the form
     {@code line,column:pos-line,column:pos}, if the input was parsed with
     {@link org.jsoup.parser.Parser#setTrackPosition(boolean)} enabled; otherwise {@link #UnknownSource}.
     */
    public String sourcePosition() {
        return sourcePosition;
    }

    /** The name of the affected node: the tag name for elements, or the node name (e.g. {@code #comment}). */
    public String target() {
        return target;
    }

    /** The attribute name, for {@link Type#RemovedAttribute} and {@link Type#EnforcedAttribute} actions; otherwise null. */
    public @Nullable String attribute() {
        return attribute;
    }

    /**
     A length-limited summary of the removed or overridden source value, if value capture was enabled via
     {@code cleanWithReport}'s {@code maxValueLength}; otherwise {@code null}. Removed values are never captured by
     default.
     */
    public @Nullable String value() {
        return value;
    }

    /**
     For {@link Type#EnforcedAttribute} actions: {@code true} if the enforced attribute overrode a different source
     value; {@code false} if it was newly added. Always {@code false} for removals.
     */
    public boolean replaced() {
        return replaced;
    }

    /** A JSON representation of this action. */
    @Override public String toString() {
        return toJson();
    }

    String toJson() {
        StringBuilder sb = StringUtil.borrowBuilder();
        sb.append('{');
        appendJsonField(sb, "type", type.name()).append(',');
        appendJsonField(sb, "reason", reason.name()).append(',');
        appendJsonField(sb, "path", path).append(',');
        appendJsonField(sb, "sourcePosition", sourcePosition).append(',');
        appendJsonField(sb, "target", target).append(',');
        appendJsonField(sb, "attribute", attribute).append(',');
        appendJsonField(sb, "value", value).append(',');
        sb.append("\"replaced\":").append(replaced);
        sb.append('}');
        return StringUtil.releaseBuilder(sb);
    }

    static StringBuilder appendJsonField(StringBuilder sb, String name, @Nullable String value) {
        sb.append('\"').append(name).append("\":");
        if (value == null) sb.append("null");
        else appendJsonString(sb, value);
        return sb;
    }

    static void appendJsonString(StringBuilder sb, String value) {
        sb.append('\"');
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('\"');
    }
}
