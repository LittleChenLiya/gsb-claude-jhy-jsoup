package org.jsoup.compare;

import org.jsoup.internal.StringUtil;
import org.jspecify.annotations.Nullable;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.DocumentType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.ProcessingInstruction;
import org.jsoup.nodes.TextNode;
import org.jsoup.nodes.XmlDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 Builds the canonical token stream of a DOM subtree under a {@link CompareConfig}. The stream fully determines
 equivalence: two subtrees are equivalent iff their token streams are identical. The fingerprint is only a digest
 of this stream.

 <p>The traversal is strictly read-only: attributes are only iterated when {@link Node#attributesSize()} reports
 that an attribute collection already exists, so no {@code Attributes} object is ever lazily materialized and the
 DOM is never modified.</p>
 */
final class CanonicalForm {
    private CanonicalForm() {}

    /** Field separator between token parts. The canonical string framing is length-prefixed, see canonicalString. */
    private static final String FS = "\u001F";

    /** A single canonical token: the structural path of the node plus its canonical content. */
    static final class Token {
        final String path;
        final String canonical;

        Token(String path, String canonical) {
            this.path = path;
            this.canonical = canonical;
        }
    }

    /** @return the canonical token stream of the subtree rooted at {@code root}. */
    static List<Token> tokens(Node root, CompareConfig config) {
        List<Token> out = new ArrayList<>();
        emit(root, tokenFor(root, false, config), "/" + segmentName(root), false, config, out);
        return out;
    }

    /** @return the canonical string form of a token stream (length-prefixed fields, one token per line). */
    static String canonicalString(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (Token token : tokens) {
            field(sb, token.path);
            field(sb, token.canonical);
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     Emit {@code node} (if its precomputed token is non-null) and recurse into its children. Children are indexed
     by significant (emitted) siblings only, so dropped whitespace-only text nodes cannot shift the paths of real
     content; this keeps paths (and hence fingerprints) indentation-insensitive.
     */
    private static void emit(Node node, @Nullable String token, String path, boolean preserveWs,
                             CompareConfig config, List<Token> out) {
        if (token != null) // null means the node vanishes under the rules (whitespace-only text)
            out.add(new Token(path, token));

        boolean childPreserveWs = preserveWs ||
            (node instanceof Element && ((Element) node).tag().preserveWhitespace());
        int significant = 0;
        for (Node child : node.childNodes()) {
            String childToken = tokenFor(child, childPreserveWs, config);
            if (childToken == null)
                continue; // a dropped node is always a leaf (whitespace-only text), so nothing else is lost
            significant++;
            String childPath = path + "/" + segmentName(child) + "[" + significant + "]";
            emit(child, childToken, childPath, childPreserveWs, config, out);
        }
    }

    /** @return the canonical content of this node, or null if the node is dropped from the stream. */
    private static String tokenFor(Node node, boolean preserveWs, CompareConfig config) {
        if (node instanceof Document) return "document";
        if (node instanceof Element) {
            Element el = (Element) node;
            return "element" + FS + el.normalName() + FS + canonicalAttributes(el, config);
        }
        if (node instanceof CDataNode) return "cdata" + FS + ((CDataNode) node).getWholeText();
        if (node instanceof TextNode) {
            String text = ((TextNode) node).getWholeText();
            if (preserveWs || !config.collapseWhitespace())
                return "text" + FS + text; // pre content: verbatim, never collapsed
            String normalised = StringUtil.normaliseWhitespace(text).trim();
            return normalised.isEmpty() ? null : "text" + FS + normalised;
        }
        if (node instanceof Comment) return "comment" + FS + ((Comment) node).getData();
        if (node instanceof DataNode) return "data" + FS + ((DataNode) node).getWholeData();
        if (node instanceof DocumentType) {
            DocumentType dt = (DocumentType) node;
            return "doctype" + FS + dt.name() + FS + dt.publicId() + FS + dt.systemId();
        }
        if (node instanceof XmlDeclaration) {
            XmlDeclaration decl = (XmlDeclaration) node;
            return "xmldecl" + FS + decl.name() + FS + canonicalAttributes(decl, config);
        }
        if (node instanceof ProcessingInstruction) {
            ProcessingInstruction pi = (ProcessingInstruction) node;
            return "pi" + FS + pi.target() + FS + pi.data();
        }
        return "node" + FS + node.nodeName();
    }

    /** Sorted (order-insensitive), ignore-filtered, read-only attribute view of a node. */
    private static String canonicalAttributes(Node node, CompareConfig config) {
        if (node.attributesSize() == 0) // never materialize an Attributes object: read-only guarantee
            return "";
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Attribute attr : node.attributes()) {
            String key = attr.getKey();
            if (config.ignoredAttributes().contains(key.toLowerCase(Locale.ROOT)))
                continue;
            sorted.put(key, attr.getValue());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            field(sb, e.getKey());
            field(sb, e.getValue());
        }
        return sb.toString();
    }

    /** Append a length-prefixed field so the framing is unambiguous regardless of content. */
    private static void field(StringBuilder sb, String value) {
        sb.append(value.length()).append(':').append(value).append(';');
    }

    private static String segmentName(Node node) {
        return node instanceof Element ? ((Element) node).normalName() : node.nodeName();
    }
}
