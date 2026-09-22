package org.jsoup.safety;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.NodeInternals;
import org.jsoup.nodes.Range;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.ParseErrorList;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeVisitor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import static org.jsoup.internal.SharedConstants.DummyUri;

/**
 The {@link Safelist}-based HTML cleaner. Use to ensure that end-user provided HTML contains only the elements and attributes
 that you are expecting; no junk, and no cross-site scripting attacks!
 <p>
 The HTML cleaner parses the input as HTML and then runs it through a safelist, so the output HTML can only contain
 HTML that is allowed by the safelist.
 </p>
 <p>
 It is assumed that the input HTML is a body fragment; the clean methods only pull from the source's body, and the
 canned safelists only allow body-contained tags.
 </p>
 <p>
 Rather than interacting directly with a Cleaner object, generally see the {@code clean} methods in {@link org.jsoup.Jsoup}.
 </p>
 <p>
 A Cleaner may be reused across multiple documents and shared across concurrent threads once its {@link Safelist} has
 been configured. The cleaner uses the supplied safelist directly, so later safelist changes affect later cleaning
 calls. If you need a variant of an existing configuration, use {@link Safelist#Safelist(Safelist)} to make a copy.
 </p>
 */
public class Cleaner {
    private final Safelist safelist;

    /**
     Create a new cleaner, that sanitizes documents using the supplied safelist.
     @param safelist safe-list to clean with
     */
    public Cleaner(Safelist safelist) {
        Validate.notNull(safelist);
        this.safelist = safelist;
    }

    /**
     Creates a new, clean document, from the original dirty document, containing only elements allowed by the safelist.
     The original document is not modified. Only elements from the dirty document's <code>body</code> are used. The
     OutputSettings of the original document are cloned into the clean document.
     @param dirtyDocument Untrusted base document to clean.
     @return cleaned document.
     */
    public Document clean(Document dirtyDocument) {
        Validate.notNull(dirtyDocument);

        Document clean = Document.createShell(dirtyDocument.baseUri());
        copySafeNodes(dirtyDocument.body(), clean.body(), null);
        clean.outputSettings(dirtyDocument.outputSettings().clone());

        return clean;
    }

    /**
     Creates a new, clean document from the original dirty document (exactly as {@link #clean(Document)} does), and
     additionally returns a {@link CleanReport} auditing every removal and enforced-attribute change made during the
     clean. The report is produced from the Cleaner's actual decisions in the same single pass; the input is not
     re-scanned or compared afterwards.
     <p>
     Each report entry locates the affected node by a path of stable element / text-node child indexes in the
     <b>original</b> document, so entries remain accurate even when a removed element's parent was itself removed. If
     the input was parsed with {@link Parser#setTrackPosition(boolean)} enabled, entries also carry the source range
     of the removed node or attribute; otherwise the position is {@code "unknown"}.
     </p>
     <p>
     The report is capped (see {@link CleanReport.Options}); cleaning always runs to completion regardless of the cap,
     and the report notes how many entries were omitted. By default, removed original values are not recorded, as they
     are untrusted content; opt in via {@link CleanReport.Options#includeOriginalValues(boolean)} to get
     length-limited summaries.
     </p>
     <p>
     The original document is not modified. Only elements from the dirty document's <code>body</code> are used.
     </p>
     @param dirtyDocument Untrusted base document to clean.
     @return the cleaned document and its audit report.
     @see #cleanWithReport(Document, CleanReport.Options)
     */
    public CleanResult cleanWithReport(Document dirtyDocument) {
        return cleanWithReport(dirtyDocument, new CleanReport.Options());
    }

    /**
     As {@link #cleanWithReport(Document)}, with explicit report options (entry cap, optional original-value
     summaries).
     @param dirtyDocument Untrusted base document to clean.
     @param options report options
     @return the cleaned document and its audit report.
     */
    public CleanResult cleanWithReport(Document dirtyDocument, CleanReport.Options options) {
        Validate.notNull(dirtyDocument);
        Validate.notNull(options);

        Document clean = Document.createShell(dirtyDocument.baseUri());
        CleanReport report = new CleanReport(options);
        copySafeNodes(dirtyDocument.body(), clean.body(), report);
        clean.outputSettings(dirtyDocument.outputSettings().clone());

        return new CleanResult(clean, report);
    }

    /**
     Determines if the input document's <b>body</b> is valid, against the safelist. It is considered valid if all the
     tags and attributes in the input HTML are allowed by the safelist, and that there is no content in the
     <code>head</code>.
     <p>
     This method is intended to be used in a user interface as a validator for user input. Note that regardless of the
     output of this method, the input document <b>must always</b> be normalized using a method such as
     {@link #clean(Document)}, and the result of that method used to store or serialize the document before later reuse
     such as presentation to end users. This ensures that enforced attributes are set correctly, and that any
     differences between how a given browser and how jsoup parses the input HTML are normalized.
     </p>
     <p>Example:
     <pre>{@code
     Document inputDoc = Jsoup.parse(inputHtml);
     Cleaner cleaner = new Cleaner(Safelist.relaxed());
     boolean isValid = cleaner.isValid(inputDoc);
     Document normalizedDoc = cleaner.clean(inputDoc);
     }</pre>
     </p>
     @param dirtyDocument document to test
     @return true if no tags or attributes need to be removed; false if they do
     */
    public boolean isValid(Document dirtyDocument) {
        Validate.notNull(dirtyDocument);

        Document clean = Document.createShell(dirtyDocument.baseUri());
        int numDiscarded = copySafeNodes(dirtyDocument.body(), clean.body(), null);
        return numDiscarded == 0
            && dirtyDocument.head().childNodes().isEmpty(); // because we only look at the body, but we start from a shell, make sure there's nothing in the head
    }

    /**
     Determines if the input document's <b>body HTML</b> is valid, against the safelist. It is considered valid if all
     the tags and attributes in the input HTML are allowed by the safelist.
     <p>
     This method is intended to be used in a user interface as a validator for user input. Note that regardless of the
     output of this method, the input document <b>must always</b> be normalized using a method such as
     {@link #clean(Document)}, and the result of that method used to store or serialize the document before later reuse
     such as presentation to end users. This ensures that enforced attributes are set correctly, and that any
     differences between how a given browser and how jsoup parses the input HTML are normalized.
     </p>
     <p>Example:
     <pre>{@code
     Document inputDoc = Jsoup.parse(inputHtml);
     Cleaner cleaner = new Cleaner(Safelist.relaxed());
     boolean isValid = cleaner.isValidBodyHtml(inputHtml);
     Document normalizedDoc = cleaner.clean(inputDoc);
     }</pre>
     </p>
     @param bodyHtml HTML fragment to test
     @return true if no tags or attributes need to be removed; false if they do
     */
    public boolean isValidBodyHtml(String bodyHtml) {
        String baseUri = (safelist.preserveRelativeLinks()) ? DummyUri : ""; // fake base URI to allow relative URLs to remain valid
        Document clean = Document.createShell(baseUri);
        Document dirty = Document.createShell(baseUri);
        ParseErrorList errorList = ParseErrorList.tracking(1);
        List<Node> nodes = Parser.parseFragment(bodyHtml, dirty.body(), baseUri, errorList);
        dirty.body().insertChildren(0, nodes);
        int numDiscarded = copySafeNodes(dirty.body(), clean.body(), null);
        return numDiscarded == 0 && errorList.isEmpty();
    }

    /**
     Iterates the input and copies trusted nodes (tags, attributes, text) into the destination.
     */
    private final class CleaningVisitor implements NodeVisitor {
        private int numDiscarded = 0;
        private final Element root;
        private Element destination; // current element to append nodes to
        private final CleanReport report; // nullable; when set, decisions are audited

        private CleaningVisitor(Element root, Element destination, CleanReport report) {
            this.root = root;
            this.destination = destination;
            this.report = report;
        }

        @Override public void head(Node source, int depth) {
            if (source instanceof Element) {
                Element sourceEl = (Element) source;

                if (safelist.isSafeTag(sourceEl.normalName())) { // safe, clone and copy safe attrs
                    ElementMeta meta = createSafeElement(sourceEl, report);
                    Element destChild = meta.el;
                    destination.appendChild(destChild);

                    numDiscarded += meta.numAttribsDiscarded;
                    destination = destChild;
                } else if (source != root) { // not a safe tag, so don't add. don't count root against discarded.
                    numDiscarded++;
                    if (report != null)
                        report.record(new CleanReport.Entry(CleanReport.Action.RemovedElement,
                            CleanReport.Reason.TagNotAllowed, nodePath(sourceEl), positionOf(sourceEl),
                            sourceEl.normalName(), null, originalValue(sourceEl, report)));
                }
            } else if (source instanceof TextNode) {
                TextNode sourceText = (TextNode) source;
                TextNode destText = new TextNode(sourceText.getWholeText());
                destination.appendChild(destText);
            } else if (source instanceof DataNode && safelist.isSafeTag(source.parent().normalName())) {
                DataNode sourceData = (DataNode) source;
                DataNode destData = new DataNode(sourceData.getWholeData());
                destination.appendChild(destData);
            } else { // else, we don't care about comments, xml proc instructions, etc
                numDiscarded++;
                if (report != null && source != root)
                    report.record(new CleanReport.Entry(CleanReport.Action.RemovedNode,
                        CleanReport.Reason.NodeTypeNotAllowed, nodePath(source), positionOf(source),
                        null, null, originalValue(source, report)));
            }
        }

        @Override public void tail(Node source, int depth) {
            if (source instanceof Element && safelist.isSafeTag(source.normalName())) {
                destination = destination.parent(); // would have descended, so pop destination stack
            }
        }
    }

    private int copySafeNodes(Element source, Element dest, CleanReport report) {
        CleaningVisitor cleaningVisitor = new CleaningVisitor(source, dest, report);
        cleaningVisitor.traverse(source);
        return cleaningVisitor.numDiscarded;
    }

    private ElementMeta createSafeElement(Element sourceEl, CleanReport report) {
        Element dest = sourceEl.shallowClone(); // reuses tag, clones attributes and preserves any user data
        String sourceTag = sourceEl.tagName();
        Attributes destAttrs = dest.attributes();
        dest.clearAttributes(); // clear all non-internal attributes, ready for safe copy

        int numDiscarded = 0;
        Attributes sourceAttrs = sourceEl.attributes();
        for (Attribute sourceAttr : sourceAttrs) {
            if (safelist.isSafeAttribute(sourceTag, sourceEl, sourceAttr)) { // will keep this attr
                String key = sourceAttr.getKey();
                String value = sourceAttr.getValue();

                if (safelist.shouldAbsUrl(sourceTag, key)) { // configured to make absolute urls for this key (href)
                    value = sourceEl.absUrl(key);
                    if (value.isEmpty()) // could not be made abs; leave as-is to allow custom unknown protocols
                        value = sourceAttr.getValue();
                }
                Range.AttributeRange range = sourceAttrs.sourceRange(key);
                destAttrs.put(key, value);
                NodeInternals.attributeRange(destAttrs, key, range);
            } else {
                numDiscarded++;
                if (report != null)
                    report.record(new CleanReport.Entry(CleanReport.Action.RemovedAttribute,
                        safelist.failsProtocolCheck(sourceTag, sourceEl, sourceAttr)
                            ? CleanReport.Reason.ProtocolNotAllowed : CleanReport.Reason.AttributeNotAllowed,
                        nodePath(sourceEl) + "/@" + sourceAttr.getKey(), positionOf(sourceEl, sourceAttr.getKey()),
                        sourceEl.normalName(), sourceAttr.getKey(), originalValue(sourceAttr.getValue(), report)));
            }
        }

        Attributes enforcedAttrs = safelist.getEnforcedAttributes(sourceTag);
        // special case for <a href rel=nofollow>, only apply to external links:
        if (sourceEl.nameIs("a") && enforcedAttrs.get("rel").equals("nofollow")) {
            String href = sourceEl.absUrl("href");
            if (!href.isEmpty()) {
                try {
                    URL baseUrl = new URL(sourceEl.baseUri());
                    URL linkUrl = new URL(href);
                    String baseHost = baseUrl.getHost();
                    if (!baseHost.isEmpty() && baseHost.equalsIgnoreCase(linkUrl.getHost())) // same site, so don't set the nofollow
                        enforcedAttrs.remove("rel");
                } catch (MalformedURLException ignored) {}
            }
        }

        // apply enforced attributes case-insensitively, so a preserved-case source attr is canonicalized to the enforced key
        for (Attribute enforcedAttr : enforcedAttrs) {
            if (report != null) {
                String key = enforcedAttr.getKey();
                if (!sourceAttrs.hasKeyIgnoreCase(key))
                    report.record(new CleanReport.Entry(CleanReport.Action.EnforcedAttribute,
                        CleanReport.Reason.EnforcedAttributeAdded, nodePath(sourceEl) + "/@" + key,
                        positionOf(sourceEl), sourceEl.normalName(), key, null));
                else if (!sourceAttrs.getIgnoreCase(key).equals(enforcedAttr.getValue()))
                    report.record(new CleanReport.Entry(CleanReport.Action.EnforcedAttribute,
                        CleanReport.Reason.EnforcedAttributeOverride, nodePath(sourceEl) + "/@" + key,
                        positionOf(sourceEl, key), sourceEl.normalName(), key,
                        originalValue(sourceAttrs.getIgnoreCase(key), report)));
                // else the source already carried the enforced value; nothing changed, nothing to report
            }
            destAttrs.removeIgnoreCase(enforcedAttr.getKey());
            destAttrs.put(enforcedAttr.getKey(), enforcedAttr.getValue());
        }
        dest.attributes().addAll(destAttrs); // re-attach, if removed in clear
        return new ElementMeta(dest, numDiscarded);
    }

    /**
     Builds a path to the node in the source (dirty) tree, using stable child indexes that count element and text
     node children. Computed against the unmodified source tree, so it is valid even for nodes whose ancestors are
     removed by the clean.
     */
    private static String nodePath(Node node) {
        StringBuilder sb = new StringBuilder();
        Node current = node;
        while (current != null && !(current instanceof Document)) {
            sb.insert(0, "/" + nodeName(current) + "[" + stableIndex(current) + "]");
            current = current.parent();
        }
        return sb.toString();
    }

    private static String nodeName(Node node) {
        if (node instanceof Element) return ((Element) node).normalName();
        if (node instanceof TextNode) return "#text";
        if (node instanceof DataNode) return "#data";
        if (node instanceof Comment) return "#comment";
        return "#node";
    }

    /**
     The index of this node among its parent's children, counting only element and text node children (other node
     types take the count of element/text children preceding them, keeping the index stable regardless of cleaning).
     */
    private static int stableIndex(Node node) {
        Node parent = node.parent();
        if (parent == null) return 0;
        int index = 0;
        for (Node sibling : parent.childNodes()) {
            if (sibling == node) break;
            if (sibling instanceof Element || sibling instanceof TextNode) index++;
        }
        return index;
    }

    private static String positionOf(Node node) {
        Range range = node.sourceRange();
        return range.isTracked() ? range.toString() : "unknown";
    }

    private static String positionOf(Element el, String attributeKey) {
        Range.AttributeRange range = el.attributes().sourceRange(attributeKey);
        return range.isTracked() ? range.toString() : "unknown";
    }

    private static String originalValue(Node node, CleanReport report) {
        if (!report.options().includeOriginalValues()) return null;
        if (node instanceof TextNode) return limit(((TextNode) node).getWholeText(), report);
        if (node instanceof DataNode) return limit(((DataNode) node).getWholeData(), report);
        return limit(node.outerHtml(), report);
    }

    private static String originalValue(String value, CleanReport report) {
        return report.options().includeOriginalValues() ? limit(value, report) : null;
    }

    private static String limit(String value, CleanReport report) {
        int max = report.options().maxValueLength();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static class ElementMeta {
        Element el;
        int numAttribsDiscarded;

        ElementMeta(Element el, int numAttribsDiscarded) {
            this.el = el;
            this.numAttribsDiscarded = numAttribsDiscarded;
        }
    }

}
