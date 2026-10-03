package org.jsoup.safety;

import org.jsoup.helper.Validate;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 A read-only auditor that checks the link targets ({@code href} and {@code src} attributes) found in a document
 against a set of allowed hosts, <b>without</b> modifying the DOM and <b>without</b> cleaning the HTML.
 <p>
 Unlike the {@link Cleaner}, which rewrites a document against a {@link Safelist}, the auditor only inspects: each
 link attribute is resolved against <b>the element's own {@link Element#baseUri() baseUri}</b> (which honors a
 {@code <base href>} element seen during the parse, or the base URI supplied to the parser), and the resolved target
 is classified. The document is never changed, and no network requests are ever made &mdash; resolution is purely
 syntactic, using {@link java.net.URI}.
 </p>
 <p>
 Scheme, host, port, and user-info are handled uniformly:
 </p>
 <ul>
 <li>Only {@code http} and {@code https} targets can pass; every other scheme (e.g. {@code javascript:},
 {@code data:}, {@code file:}) is reported as {@link Status#DANGEROUS_SCHEME}. Scheme matching is
 case-insensitive.</li>
 <li>The host is taken from the resolved URL, so user-info tricks such as
 {@code https://safe.example@evil.test/} are judged by the real host ({@code evil.test}). Host matching is
 case-insensitive and ignores a single trailing dot.</li>
 <li>Allowed hosts are matched by <b>exact name</b> (see {@link #allow(String)}) or by an <b>explicit subdomain
 rule</b> (see {@link #allowSubdomains(String)}), never by raw string prefix &mdash; allowing
 {@code safe.example} does <i>not</i> allow {@code safe.example.evil.test} or {@code unsafe.example}.</li>
 <li>Ports do not affect host matching, but default ports (80 for http, 443 for https) are normalized away in the
 reported {@link Finding#resolvedUrl()}, so {@code https://safe.example} and {@code https://SAFE.EXAMPLE:443/}
 are treated consistently.</li>
 </ul>
 <p>
 Problems are reported in distinct categories: {@link Status#PARSE_FAILURE} (the attribute value or the base URI
 could not be parsed, or no host could be determined), {@link Status#NO_BASE} (a relative URL with no usable
 absolute base), {@link Status#DANGEROUS_SCHEME}, and {@link Status#HOST_NOT_ALLOWED}. Each {@link Finding} keeps
 the source element (for location, including its {@link Element#sourceRange() source range} when position tracking
 was enabled) and the original attribute text.
 </p>
 <p>
 Attributes with empty or blank values are skipped (there is no target to audit). By default, {@code href} and
 {@code src} attributes are audited; see {@link #auditAttribute(String)} to add more (e.g. {@code action}).
 </p>
 <p>
 Example:
 </p>
 <pre>{@code
 LinkAuditor auditor = new LinkAuditor()
     .allow("safe.example")
     .allowSubdomains("cdn.example");
 Document doc = Jsoup.parse(html, "https://safe.example/a/");
 LinkAuditor.Report report = auditor.audit(doc);
 if (!report.isClean()) {
     for (LinkAuditor.Finding f : report.findings())
         System.out.println(f);
 }
 }</pre>
 <p>
 A LinkAuditor may be reused across multiple documents and shared across threads once configured; configuration
 methods mutate the auditor, so complete configuration before publishing it to other threads.
 </p>
 @see Cleaner
 @since 1.24.1
 */
public class LinkAuditor {
    /**
     The classification of an audited link attribute that did not pass.
     */
    public enum Status {
        /** The attribute value (or the element's base URI) could not be parsed into a URL with a determinable host. */
        PARSE_FAILURE,
        /** The attribute value is relative and the element has no usable absolute base URI to resolve it against. */
        NO_BASE,
        /** The resolved URL uses a scheme other than {@code http} or {@code https} (e.g. {@code javascript:}). */
        DANGEROUS_SCHEME,
        /** The resolved URL is http(s), but its host is not in the allowed set. */
        HOST_NOT_ALLOWED
    }

    private static final String Http = "http";
    private static final String Https = "https";

    private final Set<String> exactHosts = new LinkedHashSet<>();
    private final Set<String> subdomainRoots = new LinkedHashSet<>();
    private final Set<String> attributes = new LinkedHashSet<>();

    /**
     Create a new auditor that checks {@code href} and {@code src} attributes. No hosts are allowed initially; add
     them with {@link #allow(String)} and {@link #allowSubdomains(String)}.
     */
    public LinkAuditor() {
        attributes.add("href");
        attributes.add("src");
    }

    /**
     Allow links to exactly this host (case-insensitive; a single trailing dot is ignored). Does not allow
     subdomains or look-alike names &mdash; matching is by exact host name, not string prefix.
     @param host the host name to allow, e.g. {@code "safe.example"}
     @return this, for chaining
     */
    public LinkAuditor allow(String host) {
        exactHosts.add(normalizeHost(host));
        return this;
    }

    /**
     Allow links to this host and to any of its subdomains, e.g. {@code allowSubdomains("example.com")} allows
     {@code example.com} and {@code a.b.example.com}, but not {@code notexample.com} or
     {@code example.com.evil.test}. Matching is done on dot-separated host name boundaries, never by raw string
     prefix or suffix.
     @param host the host name to allow along with its subdomains
     @return this, for chaining
     */
    public LinkAuditor allowSubdomains(String host) {
        subdomainRoots.add(normalizeHost(host));
        return this;
    }

    /**
     Add an attribute to audit, in addition to the default {@code href} and {@code src} (e.g. {@code action},
     {@code formaction}, {@code poster}). Attribute names are matched case-insensitively.
     @param attribute the attribute name to audit
     @return this, for chaining
     */
    public LinkAuditor auditAttribute(String attribute) {
        Validate.notEmpty(attribute);
        attributes.add(attribute);
        return this;
    }

    /**
     Test if a host name is allowed by this auditor's rules. The name is normalized (lower-cased, single trailing
     dot removed) before matching, so {@code "SAFE.EXAMPLE"}, {@code "safe.example"}, and {@code "safe.example."}
     are equivalent.
     @param host the host name to test
     @return true if the host is allowed exactly or by an explicit subdomain rule
     */
    public boolean isAllowedHost(String host) {
        if (host == null) return false;
        String norm = normalizeHost(host);
        if (exactHosts.contains(norm)) return true;
        for (String root : subdomainRoots) {
            if (norm.equals(root) || norm.endsWith("." + root)) return true;
        }
        return false;
    }

    /**
     Audit every element in the document. The document is not modified.
     @param document the document to audit
     @return the audit report
     */
    public Report audit(Document document) {
        Validate.notNull(document);
        return audit((Element) document);
    }

    /**
     Audit this element and all its descendants. The element is not modified.
     @param root the root element to audit
     @return the audit report
     */
    public Report audit(Element root) {
        Validate.notNull(root);
        Report report = new Report();
        Elements els = root.getAllElements();
        for (Element el : els) {
            auditElement(el, report);
        }
        return report;
    }

    private void auditElement(Element el, Report report) {
        if (el.attributesSize() == 0) return;
        Attributes attrs = el.attributes();
        for (String key : attributes) {
            if (!attrs.hasKeyIgnoreCase(key)) continue;
            String value = attrs.getIgnoreCase(key);
            if (value == null || value.trim().isEmpty()) continue; // no target to audit
            Finding finding = evaluate(el, key, value);
            if (finding != null) report.add(finding);
        }
    }

    private Finding evaluate(Element el, String key, String value) {
        String base = el.baseUri();
        URI resolved;
        try {
            URI valueUri = new URI(value);
            if (base == null || base.isEmpty()) {
                if (!valueUri.isAbsolute())
                    return new Finding(Status.NO_BASE, el, key, value, null,
                        "Relative URL and the element has no base URI to resolve against");
                resolved = valueUri;
            } else {
                URI baseUri = new URI(base);
                resolved = baseUri.resolve(valueUri);
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            return new Finding(Status.PARSE_FAILURE, el, key, value, null,
                "Could not parse URL: " + e.getMessage());
        }

        String scheme = resolved.getScheme();
        if (scheme == null)
            return new Finding(Status.NO_BASE, el, key, value, null,
                "Resolved URL is not absolute; the element's base URI is not a usable absolute URL");
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!scheme.equals(Http) && !scheme.equals(Https))
            return new Finding(Status.DANGEROUS_SCHEME, el, key, value, resolved.toASCIIString(),
                "Scheme '" + scheme + "' is not http or https");

        String host = resolved.getHost();
        if (host == null || host.isEmpty())
            return new Finding(Status.PARSE_FAILURE, el, key, value, resolved.toASCIIString(),
                "Could not determine a host from the resolved URL");

        String normalized = normalize(resolved, scheme, host);
        if (!isAllowedHost(host))
            return new Finding(Status.HOST_NOT_ALLOWED, el, key, value, normalized,
                "Host '" + normalizeHost(host) + "' is not in the allowed set");
        return null; // allowed
    }

    /**
     Builds a canonical display form of a resolved http(s) URL: lower-cased scheme and host, user-info and default
     port removed. Used for consistent reporting; the original attribute text is always kept in
     {@link Finding#value()}.
     */
    private static String normalize(URI uri, String scheme, String host) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(normalizeHost(host));
        int port = uri.getPort();
        int defaultPort = scheme.equals(Https) ? 443 : 80;
        if (port != -1 && port != defaultPort) sb.append(':').append(port);
        String path = uri.getRawPath();
        sb.append(path == null || path.isEmpty() ? "/" : path);
        if (uri.getRawQuery() != null) sb.append('?').append(uri.getRawQuery());
        if (uri.getRawFragment() != null) sb.append('#').append(uri.getRawFragment());
        return sb.toString();
    }

    private static String normalizeHost(String host) {
        Validate.notNull(host);
        String norm = host.trim().toLowerCase(Locale.ROOT);
        if (norm.endsWith(".")) norm = norm.substring(0, norm.length() - 1);
        return norm;
    }

    /**
     A single audited link attribute that did not pass the auditor's rules. Keeps the source element (for
     location) and the original attribute text; never holds a modified copy of the document.
     */
    public static class Finding {
        private final Status status;
        private final Element element;
        private final String attribute;
        private final String value;
        private final String resolvedUrl; // nullable
        private final String message;

        Finding(Status status, Element element, String attribute, String value, String resolvedUrl, String message) {
            this.status = status;
            this.element = element;
            this.attribute = attribute;
            this.value = value;
            this.resolvedUrl = resolvedUrl;
            this.message = message;
        }

        /** The classification of this finding. */
        public Status status() {
            return status;
        }

        /** The element carrying the offending attribute. Its position in the source (if tracked) is available via
         {@link #sourceRange()}, and a CSS path via {@code element().cssSelector()}. */
        public Element element() {
            return element;
        }

        /** The name of the audited attribute (e.g. {@code href}). */
        public String attribute() {
            return attribute;
        }

        /** The original, unmodified attribute value as found in the source. */
        public String value() {
            return value;
        }

        /** The resolved (and normalized) absolute URL, if resolution got far enough to produce one; otherwise
         {@code null}. User-info is removed and default ports are normalized away. */
        public String resolvedUrl() {
            return resolvedUrl;
        }

        /** A human-readable description of the problem. */
        public String message() {
            return message;
        }

        /** The source range of the element's start tag, if position tracking was enabled when parsing; otherwise an
         untracked range. */
        public Range sourceRange() {
            return element.sourceRange();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            Range range = sourceRange();
            if (range.isTracked()) sb.append(range).append(' ');
            sb.append('[').append(status).append("] ");
            sb.append(element.tagName()).append('[').append(attribute).append("=\"");
            if (value.length() > 80) sb.append(value, 0, 79).append("...");
            else sb.append(value);
            sb.append("\"]");
            if (resolvedUrl != null) sb.append(" -> ").append(resolvedUrl);
            sb.append(" (").append(message).append(')');
            return sb.toString();
        }
    }

    /**
     The result of an audit: an ordered list of {@link Finding}s. An empty report means every audited link target
     resolved to an allowed http(s) host.
     */
    public static class Report {
        private final List<Finding> findings = new ArrayList<>();

        Report() {
        }

        void add(Finding finding) {
            findings.add(finding);
        }

        /** All findings, in document order. */
        public List<Finding> findings() {
            return Collections.unmodifiableList(findings);
        }

        /** Findings of a single status, in document order. */
        public List<Finding> findings(Status status) {
            List<Finding> out = new ArrayList<>();
            for (Finding f : findings)
                if (f.status() == status) out.add(f);
            return out;
        }

        /** The number of findings. */
        public int size() {
            return findings.size();
        }

        /** True if no link attribute failed the audit. */
        public boolean isClean() {
            return findings.isEmpty();
        }

        @Override
        public String toString() {
            if (findings.isEmpty()) return "LinkAudit: clean";
            StringBuilder sb = new StringBuilder("LinkAudit: ").append(findings.size()).append(" finding(s)");
            for (Finding f : findings)
                sb.append('\n').append("  ").append(f);
            return sb.toString();
        }
    }
}
