package org.jsoup.nodes;

import org.jsoup.helper.Validate;
import org.jsoup.internal.Normalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 Renames the {@code id} attributes within an HTML fragment by adding a caller-supplied prefix, and updates the
 fragment's internal references so that they keep pointing at the renamed ids. Useful when combining several
 fragments into one document, where identical ids would otherwise collide.

 <p>The following references are updated:</p>
 <ul>
     <li>{@code href} attributes whose whole value is a fragment reference (e.g. {@code href="#intro"}). Hrefs that
     contain a URL (e.g. {@code href="page.html#intro"} or absolute URLs) are left unchanged.</li>
     <li>{@code for} attributes on {@code <label>} elements.</li>
     <li>{@code form} attributes.</li>
     <li>The space-separated id lists in {@code aria-labelledby} and {@code aria-describedby}. Token order, duplicate
     tokens, and the original whitespace separators are preserved.</li>
 </ul>

 <p>Text content (including the contents of {@code <script>} and {@code <style>} elements) is never modified, and
 attribute values that merely contain an id as a substring are not touched. References that do not match any id in
 the fragment keep their original value and are listed in the {@linkplain Result#unresolvedReferences() report}.
 Ids with an empty value are ignored (left as-is, and not added to the id map).</p>

 <p>The input nodes are not modified; the operation works on a deep clone, which is returned in the result. The
 operation is all-or-nothing: if the fragment contains a duplicate id, or if a prefixed id would collide with
 another existing id, an {@link IllegalArgumentException} is thrown and no result is produced.</p>

 @since 1.24.1
 */
public final class FragmentIdRenamer {
    private FragmentIdRenamer() {
    }

    /**
     Renames the ids in {@code fragment} by prepending {@code prefix}, and updates internal references.
     @param fragment the fragment root element; not modified
     @param prefix the prefix to add to every id; may be empty, must not be null
     @return the rename result, containing the renamed clone, the old-to-new id map, and the unresolved reference
     report
     @throws IllegalArgumentException if the fragment contains a duplicate id, or a renamed id would collide with an
     existing id
     */
    public static Result rename(Element fragment, String prefix) {
        Validate.notNullParam(fragment, "fragment");
        return rename(Collections.singletonList((Node) fragment), prefix);
    }

    /**
     Renames the ids in a list of sibling fragment nodes by prepending {@code prefix}, and updates internal
     references.
     @param fragment the fragment nodes; not modified
     @param prefix the prefix to add to every id; may be empty, must not be null
     @return the rename result, containing the renamed clones, the old-to-new id map, and the unresolved reference
     report
     @throws IllegalArgumentException if the fragment contains a duplicate id, or a renamed id would collide with an
     existing id
     */
    public static Result rename(List<? extends Node> fragment, String prefix) {
        Validate.notNullParam(fragment, "fragment");
        Validate.notNullParam(prefix, "prefix");

        List<Node> clones = new ArrayList<>(fragment.size());
        for (Node node : fragment) {
            Validate.notNull(node);
            clones.add(node.clone());
        }

        Set<Element> elements = new LinkedHashSet<>();
        for (Node clone : clones) {
            if (clone instanceof Element)
                elements.addAll(((Element) clone).getAllElements());
        }

        // collect non-empty ids, in document order; fail on duplicates
        Map<String, Element> ids = new LinkedHashMap<>();
        for (Element el : elements) {
            String id = el.attr("id");
            if (id.isEmpty()) continue;
            if (ids.containsKey(id))
                Validate.fail("Duplicate id '%s' in fragment", id);
            ids.put(id, el);
        }

        // fail if a prefixed id would collide with another existing id
        for (String id : ids.keySet()) {
            String newId = prefix + id;
            if (!newId.equals(id) && ids.containsKey(newId))
                Validate.fail("Renaming id '%s' to '%s' would conflict with an existing id", id, newId);
        }

        Map<String, String> idMap = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : ids.entrySet()) {
            String newId = prefix + entry.getKey();
            idMap.put(entry.getKey(), newId);
            entry.getValue().attr("id", newId);
        }

        List<UnresolvedReference> unresolved = new ArrayList<>();
        for (Element el : elements) {
            for (Attribute attr : el.attributes().asList()) {
                String key = Normalizer.asciiLowerCase(attr.getKey());
                String value = attr.getValue();
                switch (key) {
                    case "href":
                        if (value.startsWith("#") && value.length() > 1)
                            el.attr(attr.getKey(), remap(value.substring(1), idMap, unresolved, el, attr.getKey(), true));
                        break;
                    case "for":
                        if (el.normalName().equals("label") && !value.isEmpty())
                            el.attr(attr.getKey(), remap(value, idMap, unresolved, el, attr.getKey(), false));
                        break;
                    case "form":
                        if (!value.isEmpty())
                            el.attr(attr.getKey(), remap(value, idMap, unresolved, el, attr.getKey(), false));
                        break;
                    case "aria-labelledby":
                    case "aria-describedby":
                        if (!value.isEmpty())
                            el.attr(attr.getKey(), remapIdList(value, idMap, unresolved, el, attr.getKey()));
                        break;
                    default:
                        break;
                }
            }
        }

        return new Result(clones, idMap, unresolved);
    }

    private static String remap(String id, Map<String, String> idMap, List<UnresolvedReference> unresolved,
        Element el, String attrKey, boolean hashRef) {
        String mapped = idMap.get(id);
        if (mapped != null)
            return hashRef ? "#" + mapped : mapped;
        unresolved.add(new UnresolvedReference(el.normalName(), attrKey, id));
        return hashRef ? "#" + id : id;
    }

    private static String remapIdList(String value, Map<String, String> idMap, List<UnresolvedReference> unresolved,
        Element el, String attrKey) {
        StringBuilder sb = new StringBuilder(value.length());
        int pos = 0, length = value.length();
        while (pos < length) {
            int tokenStart = pos;
            while (pos < length && !Character.isWhitespace(value.charAt(pos))) pos++;
            if (pos > tokenStart) {
                String token = value.substring(tokenStart, pos);
                String mapped = idMap.get(token);
                if (mapped != null) {
                    sb.append(mapped);
                } else {
                    unresolved.add(new UnresolvedReference(el.normalName(), attrKey, token));
                    sb.append(token);
                }
            }
            int wsStart = pos;
            while (pos < length && Character.isWhitespace(value.charAt(pos))) pos++;
            sb.append(value, wsStart, pos);
        }
        return sb.toString();
    }

    /**
     The outcome of a {@link FragmentIdRenamer#rename rename} operation.
     */
    public static final class Result {
        private final List<Node> nodes;
        private final Map<String, String> idMap;
        private final List<UnresolvedReference> unresolved;

        private Result(List<Node> nodes, Map<String, String> idMap, List<UnresolvedReference> unresolved) {
            this.nodes = Collections.unmodifiableList(nodes);
            this.idMap = Collections.unmodifiableMap(idMap);
            this.unresolved = Collections.unmodifiableList(unresolved);
        }

        /**
         Gets the renamed fragment nodes. These are deep clones of the input; the input nodes were not modified.
         @return the renamed fragment nodes, in input order
         */
        public List<Node> nodes() {
            return nodes;
        }

        /**
         Gets the renamed fragment as a single element. Only valid when the input was a single element.
         @return the renamed clone of the input element
         */
        public Element element() {
            Validate.isTrue(nodes.size() == 1 && nodes.get(0) instanceof Element,
                "Result does not contain exactly one element");
            return (Element) nodes.get(0);
        }

        /**
         Gets the map of old ids to their new (prefixed) names, in document order.
         @return the old-to-new id map
         */
        public Map<String, String> idMap() {
            return idMap;
        }

        /**
         Gets the report of references that did not match any id in the fragment. Those references were left
         unchanged.
         @return the unresolved references, in document order
         */
        public List<UnresolvedReference> unresolvedReferences() {
            return unresolved;
        }

        /**
         Checks if any references could not be resolved to an id in the fragment.
         @return true if there are unresolved references
         */
        public boolean hasUnresolvedReferences() {
            return !unresolved.isEmpty();
        }
    }

    /**
     A reference (from {@code href}, {@code for}, {@code form}, {@code aria-labelledby}, or
     {@code aria-describedby}) that points to an id not present in the renamed fragment. The reference was left
     unchanged.
     */
    public static final class UnresolvedReference {
        private final String tag;
        private final String attribute;
        private final String reference;

        private UnresolvedReference(String tag, String attribute, String reference) {
            this.tag = tag;
            this.attribute = attribute;
            this.reference = reference;
        }

        /**
         Gets the normal (lower-case) name of the element holding the unresolved reference.
         @return the element name
         */
        public String tag() {
            return tag;
        }

        /**
         Gets the name of the attribute holding the unresolved reference.
         @return the attribute name
         */
        public String attribute() {
            return attribute;
        }

        /**
         Gets the unresolved id (without any leading {@code #}).
         @return the unresolved id
         */
        public String reference() {
            return reference;
        }

        @Override
        public String toString() {
            return String.format("%s[%s] -> %s", tag, attribute, reference);
        }
    }
}
