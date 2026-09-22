package org.jsoup.safety;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
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
        copySafeNodes(dirtyDocument.body(), clean.body());
        clean.outputSettings(dirtyDocument.outputSettings().clone());

        return clean;
    }

    /**
     Creates a new, clean document from the original dirty document, exactly as {@link #clean(Document)} does, and
     additionally returns an audit report of every removal and enforced-attribute override decided during that single
     cleaning pass. The original document is not modified, and the cleaned document is identical to the result of
     {@link #clean(Document)} for the same input and safelist.
     <p>
     Each {@link CleaningAction} in the report carries the path of the affected node in the original document
     (computed from stable child indexes before cleaning), its source position when position tracking was enabled
     during parse (otherwise {@link CleaningAction#UnknownSource}), and the action and reason decided by the safelist
     (disallowed tag, attribute, or protocol; unsupported node; enforced attribute).
     <p>
     Removed source values are not captured by default; pass a {@code maxValueLength} greater than {@code 0} to
     include a length-limited summary of each removed or overridden value in the report. When the number of actions
     exceeds {@code maxActions}, cleaning continues unaffected and the report is marked
     {@link CleaningReport#isTruncated() truncated}, with the count of unrecorded actions available via
     {@link CleaningReport#omittedActions()}.
     <p>Example:
     <pre>{@code
     Cleaner cleaner = new Cleaner(Safelist.basic());
     CleaningReport report = cleaner.cleanWithReport(Jsoup.parse(dirtyHtml), 100, 0);
     Document clean = report.document();
     for (CleaningAction action : report.actions())
         System.out.println(action.path() + " " + action.type() + " " + action.reason());
     }</pre>
     @param dirtyDocument Untrusted base document to clean.
     @param maxActions maximum number of actions to record in the report; cleaning itself is never limited
     @param maxValueLength maximum length of removed or overridden source values to capture in the report, or
     {@code 0} to not capture values
     @return the cleaned document and its audit report
     @since 1.24.1
     */
    public CleaningReport cleanWithReport(Document dirtyDocument, int maxActions, int maxValueLength) {
        Validate.notNull(dirtyDocument);
        Validate.isTrue(maxActions >= 0, "maxActions must be >= 0");
        Validate.isTrue(maxValueLength >= 0, "maxValueLength must be >= 0");

        CleaningAudit audit = new CleaningAudit(maxActions, maxValueLength);
        Document clean = Document.createShell(dirtyDocument.baseUri());
        copySafeNodes(dirtyDocument.body(), clean.body(), audit);
        clean.outputSettings(dirtyDocument.outputSettings().clone());

        return new CleaningReport(clean, audit.actions, audit.omitted);
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
        int numDiscarded = copySafeNodes(dirtyDocument.body(), clean.body());
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
        int numDiscarded = copySafeNodes(dirty.body(), clean.body());
        return numDiscarded == 0 && errorList.isEmpty();
    }

    /**
     Iterates the input and copies trusted nodes (tags, attributes, text) into the destination.
     */
    private final class CleaningVisitor implements NodeVisitor {
        private int numDiscarded = 0;
        private final Element root;
        private Element destination; // current element to append nodes to
        private final CleaningAudit audit; // may be null, when not reporting

        private CleaningVisitor(Element root, Element destination, CleaningAudit audit) {
            this.root = root;
            this.destination = destination;
            this.audit = audit;
        }

        @Override public void head(Node source, int depth) {
            if (source instanceof Element) {
                Element sourceEl = (Element) source;

                if (safelist.isSafeTag(sourceEl.normalName())) { // safe, clone and copy safe attrs
                    ElementMeta meta = createSafeElement(sourceEl, audit);
                    Element destChild = meta.el;
                    destination.appendChild(destChild);

                    numDiscarded += meta.numAttribsDiscarded;
                    destination = destChild;
                } else if (source != root) { // not a safe tag, so don't add. don't count root against discarded.
                    numDiscarded++;
                    if (audit != null) audit.removedElement(sourceEl);
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
                if (audit != null) audit.removedNode(source);
            }
        }

        @Override public void tail(Node source, int depth) {
            if (source instanceof Element && safelist.isSafeTag(source.normalName())) {
                destination = destination.parent(); // would have descended, so pop destination stack
            }
        }
    }

    private int copySafeNodes(Element source, Element dest) {
        return copySafeNodes(source, dest, null);
    }

    private int copySafeNodes(Element source, Element dest, CleaningAudit audit) {
        CleaningVisitor cleaningVisitor = new CleaningVisitor(source, dest, audit);
        cleaningVisitor.traverse(source);
        return cleaningVisitor.numDiscarded;
    }

    private ElementMeta createSafeElement(Element sourceEl, CleaningAudit audit) {
        Element dest = sourceEl.shallowClone(); // reuses tag, clones attributes and preserves any user data
        String sourceTag = sourceEl.tagName();
        Attributes destAttrs = dest.attributes();
        dest.clearAttributes(); // clear all non-internal attributes, ready for safe copy

        int numDiscarded = 0;
        Attributes sourceAttrs = sourceEl.attributes();
        for (Attribute sourceAttr : sourceAttrs) {
            Safelist.AttributeStatus status = safelist.attributeStatus(sourceTag, sourceEl, sourceAttr);
            if (status == Safelist.AttributeStatus.Allowed) { // will keep this attr
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
                // an Enforced attribute is reported by the enforced-attributes pass below, as an override
                if (audit != null && status != Safelist.AttributeStatus.Enforced)
                    audit.removedAttribute(sourceEl, sourceAttr, status);
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
            if (audit != null) {
                boolean present = sourceAttrs.hasKeyIgnoreCase(enforcedAttr.getKey());
                String prev = present ? sourceAttrs.getIgnoreCase(enforcedAttr.getKey()) : null;
                // record additions and overrides; an already-matching value is a no-op
                if (!present || !prev.equals(enforcedAttr.getValue()))
                    audit.enforcedAttribute(sourceEl, sourceAttrs, enforcedAttr, prev);
            }
            destAttrs.removeIgnoreCase(enforcedAttr.getKey());
            destAttrs.put(enforcedAttr.getKey(), enforcedAttr.getValue());
        }
        dest.attributes().addAll(destAttrs); // re-attach, if removed in clear
        return new ElementMeta(dest, numDiscarded);
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
