package org.jsoup.safety;

import org.jsoup.helper.Validate;
import org.jsoup.internal.StringUtil;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.Range;
import org.jsoup.select.NodeVisitor;
import org.jspecify.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 A read-only auditor for links (href and src attributes) in a document. It checks whether each link's
 resolved target is on an allowed host, without modifying the DOM or making any network requests.
 <p>
 The auditor uses each element's own {@link Element#baseUri() base URI} to resolve relative links,
 so {@code <base>} tags and per-element base URIs are respected. Only {@code http} and {@code https}
 schemes are allowed; all other schemes (e.g. {@code javascript:}, {@code ftp:}, {@code mailto:}) are
 rejected as dangerous. Host matching is performed by label parsing, never by string prefix.
 </p>
 <p>
 Usage:
 </p>
 <pre>{@code
 Document doc = Jsoup.parse(html, "https://safe.example/a/");
 LinkAuditor auditor = new LinkAuditor(AllowedHosts.of("safe.example", "*.trusted.example"));
 List<LinkAuditResult> results = auditor.audit(doc);
 for (LinkAuditResult result : results) {
     if (!result.isAllowed()) {
         System.out.println(result.status() + ": " + result.originalValue() + " at " + result.sourceRange());
     }
 }
 }</pre>
 <p>
 The auditor is thread-safe if the supplied {@link AllowedHosts} is not mutated after construction.
 </p>
 @see AllowedHosts
 @see LinkAuditResult
 */
public class LinkAuditor {
    private static final Pattern SCHEME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+-.]*:");
    private static final List<String> DEFAULT_ATTRIBUTES = Collections.unmodifiableList(Arrays.asList("href", "src"));

    private final AllowedHosts allowedHosts;
    private final List<String> attributes;

    /**
     Creates a new LinkAuditor with the supplied allowed hosts.
     @param allowedHosts the allowed host rules
     */
    public LinkAuditor(AllowedHosts allowedHosts) {
        this(allowedHosts, DEFAULT_ATTRIBUTES);
    }

    /**
     Creates a new LinkAuditor with the supplied allowed hosts and custom attributes to check.
     @param allowedHosts the allowed host rules
     @param attributes the attribute keys to check (e.g. "href", "src", "action")
     */
    public LinkAuditor(AllowedHosts allowedHosts, Collection<String> attributes) {
        Validate.notNull(allowedHosts);
        Validate.notNull(attributes);
        Validate.isTrue(!attributes.isEmpty(), "At least one attribute must be specified");
        this.allowedHosts = allowedHosts;
        this.attributes = Collections.unmodifiableList(new ArrayList<>(attributes));
    }

    /**
     Creates a new LinkAuditor with the supplied allowed host rules.
     @param hosts the allowed host rules (exact names or {@code *.domain} subdomain rules)
     */
    public LinkAuditor(String... hosts) {
        this(AllowedHosts.of(hosts));
    }

    /**
     Audits all links in the supplied document. The entire document is traversed, including the head
     and body, so XML documents without a body element are also audited.
     @param doc the document to audit
     @return the audit results, one per link found
     */
    public List<LinkAuditResult> audit(Document doc) {
        Validate.notNull(doc);
        return audit((Element) doc);
    }

    /**
     Audits all links in the supplied element and its descendants.
     @param root the root element to audit
     @return the audit results, one per link found
     */
    public List<LinkAuditResult> audit(Element root) {
        Validate.notNull(root);
        List<LinkAuditResult> results = new ArrayList<>();
        LinkVisitor visitor = new LinkVisitor(results);
        visitor.traverse(root);
        return results;
    }

    /**
     Audits a single element's links (does not descend into children).
     @param element the element to audit
     @return the audit results for this element's links
     */
    public List<LinkAuditResult> auditElement(Element element) {
        Validate.notNull(element);
        List<LinkAuditResult> results = new ArrayList<>();
        collectLinks(element, results);
        return results;
    }

    /**
     Tests if the supplied URL is allowed by this auditor's rules.
     @param url the URL to test
     @return true if the URL's scheme is http/https and its host is allowed
     */
    public boolean isAllowed(@Nullable URL url) {
        if (url == null) return false;
        String scheme = url.getProtocol();
        if (!StringUtil.isHttpScheme(scheme)) return false;
        return allowedHosts.isAllowed(url);
    }

    /**
     Tests if the supplied URL string is allowed by this auditor's rules.
     @param url the URL string to test
     @return true if the URL's scheme is http/https and its host is allowed
     */
    public boolean isAllowed(@Nullable String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            return isAllowed(new URL(url));
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private void collectLinks(Element element, List<LinkAuditResult> results) {
        // Skip <base> tags - their href is a base URI declaration, not a link
        if (element.tag().normalName().equals("base")) {
            return;
        }
        for (String attributeKey : attributes) {
            if (element.attributes().hasKeyIgnoreCase(attributeKey)) {
                LinkAuditResult result = auditLink(element, attributeKey);
                if (result != null) {
                    results.add(result);
                }
            }
        }
    }

    private LinkAuditResult auditLink(Element element, String attributeKey) {
        String value = element.attributes().getIgnoreCase(attributeKey);
        if (value.isEmpty()) {
            return null;
        }

        String baseUri = element.baseUri();
        URL resolvedUrl = null;
        LinkAuditResult.Status status;
        String explicitScheme = null;

        Matcher schemeMatcher = SCHEME_PATTERN.matcher(value);
        if (schemeMatcher.find()) {
            String scheme = value.substring(0, value.indexOf(':'));
            if (!StringUtil.isHttpScheme(scheme)) {
                // Dangerous scheme (javascript:, ftp:, mailto:, etc.)
                status = LinkAuditResult.Status.REJECTED_SCHEME;
                explicitScheme = scheme;
                resolvedUrl = tryParseUrl(value);
            } else {
                // http/https URL - parse and check host
                resolvedUrl = tryParseUrl(value);
                if (resolvedUrl == null || isEmptyHost(resolvedUrl)) {
                    status = LinkAuditResult.Status.PARSE_FAILURE;
                } else if (!allowedHosts.isAllowed(resolvedUrl)) {
                    status = LinkAuditResult.Status.REJECTED_HOST;
                } else {
                    status = LinkAuditResult.Status.ALLOWED;
                }
            }
        } else {
            // Relative URL - resolve against the element's base URI
            if (baseUri.isEmpty()) {
                status = LinkAuditResult.Status.NO_BASE;
            } else {
                resolvedUrl = resolveAgainstBase(baseUri, value);
                if (resolvedUrl == null || isEmptyHost(resolvedUrl)) {
                    status = LinkAuditResult.Status.PARSE_FAILURE;
                } else {
                    String scheme = resolvedUrl.getProtocol();
                    if (!StringUtil.isHttpScheme(scheme)) {
                        status = LinkAuditResult.Status.REJECTED_SCHEME;
                    } else if (!allowedHosts.isAllowed(resolvedUrl)) {
                        status = LinkAuditResult.Status.REJECTED_HOST;
                    } else {
                        status = LinkAuditResult.Status.ALLOWED;
                    }
                }
            }
        }

        Range sourceRange = element.sourceRange();
        return new LinkAuditResult(element, attributeKey, value, resolvedUrl, status, sourceRange, explicitScheme);
    }

    private static boolean isEmptyHost(URL url) {
        String host = url.getHost();
        return host == null || host.isEmpty();
    }

    private static @Nullable URL tryParseUrl(String value) {
        try {
            return new URL(value);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static @Nullable URL resolveAgainstBase(String baseUri, String value) {
        try {
            URL base = new URL(baseUri);
            return new URL(base, value);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private final class LinkVisitor implements NodeVisitor {
        private final List<LinkAuditResult> results;

        private LinkVisitor(List<LinkAuditResult> results) {
            this.results = results;
        }

        @Override
        public void head(Node node, int depth) {
            if (node instanceof Element) {
                collectLinks((Element) node, results);
            }
        }

        @Override
        public void tail(Node node, int depth) {
        }
    }
}
