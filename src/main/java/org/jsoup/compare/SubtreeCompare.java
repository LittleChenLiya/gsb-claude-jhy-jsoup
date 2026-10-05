package org.jsoup.compare;

import org.jsoup.helper.Validate;
import org.jsoup.internal.Normalizer;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.ProcessingInstruction;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 Compares two DOM subtrees for equivalence under a {@link CompareConfig}, and produces canonical
 {@link Fingerprint}s.

 <h2>Canonicalization rules</h2>
 <ul>
 <li>Nodes are visited in document order. Each node emits a typed token (see {@link Fingerprint}).</li>
 <li>Elements contribute their normal (lower-cased) tag name and their attributes. Attributes are sorted by name
 (case-insensitively, then by the original name) so attribute order does not affect equivalence. Attributes named
 in {@link CompareConfig#ignoredAttributes()} are omitted.</li>
 <li>Text nodes outside {@code pre} elements have whitespace normalized (runs of whitespace collapse to a single
 space) and trimmed; whitespace-only text nodes are skipped. Text nodes inside {@code pre} elements are emitted
 verbatim, so an extra space inside {@code pre} still causes a difference.</li>
 <li>Data nodes (e.g. {@code script}, {@code style}), CDATA sections, comments, processing instructions,
 declarations, and document types are emitted verbatim. Node types are never conflated: a text node and a comment
 with the same content are different tokens.</li>
 </ul>

 <h2>Verification: trust, but verify</h2>
 A fingerprint digest is only a fast-path hint. {@link #compare(Node, Node, CompareConfig)} always walks both
 canonical streams token by token: if the digests differ, the streams locate the first difference; if the digests
 match, the streams are still compared token by token, so an injected constant or broken digest function cannot
 make a genuinely different subtree compare as equivalent.

 <h2>Non-mutation guarantee</h2>
 Comparison and fingerprinting are read-only: the supplied nodes are never modified, cloned into, or otherwise
 changed. (Internal lazy caches may be populated, but the DOM content, structure, attributes, and text are
 untouched.)

 <h2>Usage</h2>
 <pre>{@code
 CompareConfig config = CompareConfig.builder()
     .ignoreAttributes("class", "data-timestamp")
     .ignoreWhitespace(true)
     .build();
 CompareResult result = SubtreeCompare.compare(expected, actual, config);
 if (!result.isEquivalent()) {
     System.out.println(result.firstDifference().get().message());
 }
 }</pre>
 * @since 1.24.1
 */
public final class SubtreeCompare {
    /** The tag name whose text content is preserved verbatim. */
    private static final String PREFORMATTED_TAG = "pre";

    private SubtreeCompare() {}

    /**
     Compares two subtrees with the {@linkplain CompareConfig#defaults() default configuration}: ordinary
     whitespace is ignored, no attributes are ignored, and SHA-256 digests are used.
     @param expected the expected subtree root
     @param actual the actual subtree root
     @return the comparison result
     */
    public static CompareResult compare(Node expected, Node actual) {
        return compare(expected, actual, CompareConfig.defaults());
    }

    /**
     Compares two subtrees with the supplied configuration.
     @param expected the expected subtree root
     @param actual the actual subtree root
     @param config the configuration
     @return the comparison result
     */
    public static CompareResult compare(Node expected, Node actual, CompareConfig config) {
        Validate.notNull(expected);
        Validate.notNull(actual);
        Validate.notNull(config);

        Fingerprint expectedFingerprint = fingerprint(expected, config);
        Fingerprint actualFingerprint = fingerprint(actual, config);

        if (expectedFingerprint.version() != actualFingerprint.version()) {
            Difference difference = new Difference(
                NodePath.root(),
                "fingerprint version v" + expectedFingerprint.version(),
                "fingerprint version v" + actualFingerprint.version());
            return CompareResult.different(expectedFingerprint, actualFingerprint, difference);
        }

        Difference firstDifference = firstDifference(expectedFingerprint.tokens(), actualFingerprint.tokens());
        if (firstDifference == null) {
            return CompareResult.equivalent(expectedFingerprint, actualFingerprint);
        }
        return CompareResult.different(expectedFingerprint, actualFingerprint, firstDifference);
    }

    /**
     Computes the canonical fingerprint of a subtree with the
     {@linkplain CompareConfig#defaults() default configuration}.
     @param node the subtree root
     @return the fingerprint
     */
    public static Fingerprint fingerprint(Node node) {
        return fingerprint(node, CompareConfig.defaults());
    }

    /**
     Computes the canonical fingerprint of a subtree with the supplied configuration.
     @param node the subtree root
     @param config the configuration
     @return the fingerprint
     */
    public static Fingerprint fingerprint(Node node, CompareConfig config) {
        Validate.notNull(node);
        Validate.notNull(config);

        List<Fingerprint.Token> tokens = new ArrayList<>();
        walk(node, null, NodePath.root(), tokens, config, false);

        byte[] digestInput = serialize(tokens, config.fingerprintVersion());
        byte[] digest = config.digester().digest(digestInput);
        return new Fingerprint(config.fingerprintVersion(), config.digester().algorithm(), digest, tokens);
    }

    /**
     Walks the subtree depth-first, appending canonical tokens.
     @param node the current node
     @param parent the parent element, or null for the root
     @param parentPath the path of the parent
     @param tokens the token list to append to
     @param config the configuration
     @param inPre whether the node is inside a preformatted element
     */
    private static void walk(Node node, Element parent, NodePath parentPath,
                             List<Fingerprint.Token> tokens, CompareConfig config, boolean inPre) {
        if (node instanceof Element) {
            walkElement((Element) node, parent, parentPath, tokens, config, inPre);
        } else if (node instanceof DocumentType) {
            appendLeaf(node, parent, parentPath, tokens, Fingerprint.DOCUMENT_TYPE,
                documentTypeFields((DocumentType) node));
        } else if (node instanceof XmlDeclaration) {
            XmlDeclaration declaration = (XmlDeclaration) node;
            List<String> fields = new ArrayList<>(3);
            fields.add(declaration.name());
            fields.add(declaration.getWholeDeclaration());
            fields.add(Boolean.toString(declaration.isMarkupDeclaration()));
            appendLeaf(node, parent, parentPath, tokens, Fingerprint.DECLARATION, fields);
        } else if (node instanceof ProcessingInstruction) {
            ProcessingInstruction pi = (ProcessingInstruction) node;
            List<String> fields = new ArrayList<>(2);
            fields.add(pi.target());
            fields.add(pi.data());
            appendLeaf(node, parent, parentPath, tokens, Fingerprint.PROCESSING_INSTRUCTION, fields);
        } else if (node instanceof CDataNode) {
            CDataNode cdata = (CDataNode) node;
            List<String> fields = new ArrayList<>(1);
            fields.add(cdata.getWholeText());
            appendLeaf(node, parent, parentPath, tokens, Fingerprint.CDATA, fields);
        } else if (node instanceof TextNode) {
            String text = ((TextNode) node).getWholeText();
            if (config.ignoreWhitespace() && !inPre) {
                text = StringUtil.normaliseWhitespace(text).trim();
                if (text.isEmpty()) {
                    return; // whitespace-only text node: skipped
                }
            }
            List<String> fields = new ArrayList<>(1);
            fields.add(text);
            appendLeaf(node, parent, parentPath, tokens, Fingerprint.TEXT, fields);
        } else if (node instanceof DataNode) {
            DataNode data = (DataNode) node;
            List<String> fields = new ArrayList<>(1);
            fields.add(data.getWholeData());
            appendLeaf(node, parent, parentPath, tokens, Fingerprint.DATA, fields);
        } else if (node instanceof Comment) {
            Comment comment = (Comment) node;
            List<String> fields = new ArrayList<>(1);
            fields.add(comment.getData());
            appendLeaf(node, parent, parentPath, tokens, Fingerprint.COMMENT, fields);
        }
        // Unknown node types (none currently defined) emit no token and have no children to visit.
    }

    private static List<String> documentTypeFields(DocumentType doctype) {
        List<String> fields = new ArrayList<>(3);
        fields.add(doctype.name());
        fields.add(doctype.publicId());
        fields.add(doctype.systemId());
        return fields;
    }

    private static void appendLeaf(Node node, Element parent, NodePath parentPath,
                                   List<Fingerprint.Token> tokens, byte type, List<String> fields) {
        String nodeName = node.nodeName();
        int index = siblingIndex(node, parent, nodeName);
        NodePath path = parentPath.append(nodeName, index);
        tokens.add(new Fingerprint.Token(type, fields, path));
    }

    private static void walkElement(Element element, Element parent, NodePath parentPath,
                                    List<Fingerprint.Token> tokens, CompareConfig config, boolean inPre) {
        String name = element.normalName();
        int index = siblingIndex(element, parent, name);
        NodePath path = parentPath.append(name, index);
        boolean pre = inPre || PREFORMATTED_TAG.equals(name);

        List<String> fields = new ArrayList<>();
        fields.add(name);
        if (element.attributesSize() > 0) {
            List<Attribute> attributes = new ArrayList<>();
            for (Attribute attribute : element.attributes()) {
                if (config.isIgnoredAttribute(attribute.getKey())) continue;
                attributes.add(attribute);
            }
            attributes.sort(Comparator
                .comparing((Attribute a) -> Normalizer.asciiLowerCase(a.getKey()))
                .thenComparing(Attribute::getKey));
            for (Attribute attribute : attributes) {
                fields.add(attribute.getKey());
                fields.add(attribute.getValue());
            }
        }
        tokens.add(new Fingerprint.Token(Fingerprint.ELEMENT, fields, path));

        for (Node child : element.childNodes()) {
            walk(child, element, path, tokens, config, pre);
        }
        tokens.add(new Fingerprint.Token(Fingerprint.ELEMENT_END, new ArrayList<String>(0), path));
    }

    /**
     Returns the 1-based index of the node among its preceding siblings that share the same step name.
     */
    private static int siblingIndex(Node node, Element parent, String stepName) {
        if (parent == null) return 1;
        int count = 0;
        for (Node sibling : parent.childNodes()) {
            if (sibling == node) break;
            String siblingName = sibling instanceof Element ? ((Element) sibling).normalName() : sibling.nodeName();
            if (siblingName.equals(stepName)) count++;
        }
        return count + 1;
    }

    /**
     Test-only entry point to the canonical-stream serialization, so tests can assert that the rule version is
     mixed into the digest input.
     @param tokens the canonical stream
     @param version the rule version to serialize with
     @return the serialized bytes
     */
    static byte[] serializeForTest(List<Fingerprint.Token> tokens, int version) {
        return serialize(tokens, version);
    }

    /**
     Serializes the canonical stream for digesting.
     <pre>
     magic bytes, version int, then per token: type byte, field count int, then per field: length int + UTF-8 bytes
     </pre>
     */
    private static byte[] serialize(List<Fingerprint.Token> tokens, int version) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.write(Fingerprint.MAGIC);
            out.writeInt(version);
            for (Fingerprint.Token token : tokens) {
                out.writeByte(token.type);
                out.writeInt(token.fields.size());
                for (String field : token.fields) {
                    byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
                    out.writeInt(fieldBytes.length);
                    out.write(fieldBytes);
                }
            }
            out.flush();
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw IOException
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /**
     Walks both canonical streams token by token and returns the first difference, or null if identical.
     */
    private static Difference firstDifference(List<Fingerprint.Token> expected, List<Fingerprint.Token> actual) {
        int common = Math.min(expected.size(), actual.size());
        for (int i = 0; i < common; i++) {
            Fingerprint.Token expectedToken = expected.get(i);
            Fingerprint.Token actualToken = actual.get(i);
            if (expectedToken.type != actualToken.type || !expectedToken.fields.equals(actualToken.fields)) {
                return new Difference(expectedToken.path, describe(expectedToken), describe(actualToken));
            }
        }
        if (expected.size() == actual.size()) {
            return null;
        }
        if (expected.size() > actual.size()) {
            Fingerprint.Token extra = expected.get(actual.size());
            return new Difference(extra.path, describe(extra), null);
        }
        Fingerprint.Token extra = actual.get(expected.size());
        return new Difference(extra.path, null, describe(extra));
    }

    private static String describe(Fingerprint.Token token) {
        switch (token.type) {
            case Fingerprint.ELEMENT: {
                StringBuilder sb = new StringBuilder("<").append(token.fields.get(0));
                for (int i = 1; i < token.fields.size(); i += 2) {
                    sb.append(' ').append(token.fields.get(i)).append("=\"")
                      .append(truncate(token.fields.get(i + 1))).append('"');
                }
                return sb.append('>').toString();
            }
            case Fingerprint.ELEMENT_END:
                return "</" + token.path.last().name() + ">";
            case Fingerprint.TEXT:
                return "text \"" + truncate(token.fields.get(0)) + "\"";
            case Fingerprint.DATA:
                return "data \"" + truncate(token.fields.get(0)) + "\"";
            case Fingerprint.CDATA:
                return "cdata \"" + truncate(token.fields.get(0)) + "\"";
            case Fingerprint.COMMENT:
                return "<!--" + truncate(token.fields.get(0)) + "-->";
            case Fingerprint.DECLARATION:
                return "declaration <!" + token.fields.get(0) + " " + truncate(token.fields.get(1)) + ">";
            case Fingerprint.PROCESSING_INSTRUCTION:
                return "processing instruction <?" + token.fields.get(0) + " " + truncate(token.fields.get(1)) + "?>";
            case Fingerprint.DOCUMENT_TYPE:
                return "<!DOCTYPE " + token.fields.get(0) + ">";
            default:
                return "unknown token";
        }
    }

    private static String truncate(String value) {
        if (value.length() <= 40) return value;
        return value.substring(0, 40) + "...";
    }
}
