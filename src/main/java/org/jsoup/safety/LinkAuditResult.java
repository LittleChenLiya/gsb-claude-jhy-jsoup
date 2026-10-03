package org.jsoup.safety;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jspecify.annotations.Nullable;

import java.net.URL;

/**
 The result of auditing a single link (href or src attribute) in a document.
 <p>
 Each result captures the element, the attribute that was checked, the original attribute value,
 the resolved URL (if resolution succeeded), the audit status, the element's source location, and
 the parsed scheme, host, and port.
 </p>
 @see LinkAuditor
 */
public class LinkAuditResult {
    /** The audit status for a link. */
    public enum Status {
        /** The link is allowed (http/https scheme and host in the allowed set). */
        ALLOWED,
        /** The link's host is not in the allowed set. */
        REJECTED_HOST,
        /** The link's scheme is not http or https (e.g. javascript:, ftp:, mailto:). */
        REJECTED_SCHEME,
        /** The link could not be parsed or resolved to a valid URL. */
        PARSE_FAILURE,
        /** The link is relative but the element has no base URI to resolve against. */
        NO_BASE
    }

    private final Element element;
    private final String attributeKey;
    private final String originalValue;
    private final @Nullable URL resolvedUrl;
    private final Status status;
    private final @Nullable Range sourceRange;
    private final @Nullable String scheme;
    private final @Nullable String host;
    private final int port;

    LinkAuditResult(Element element, String attributeKey, String originalValue,
                    @Nullable URL resolvedUrl, Status status, @Nullable Range sourceRange) {
        this(element, attributeKey, originalValue, resolvedUrl, status, sourceRange, null);
    }

    LinkAuditResult(Element element, String attributeKey, String originalValue,
                    @Nullable URL resolvedUrl, Status status, @Nullable Range sourceRange,
                    @Nullable String scheme) {
        this.element = element;
        this.attributeKey = attributeKey;
        this.originalValue = originalValue;
        this.resolvedUrl = resolvedUrl;
        this.status = status;
        this.sourceRange = sourceRange;
        if (scheme != null) {
            this.scheme = scheme;
            this.host = resolvedUrl != null ? resolvedUrl.getHost() : null;
            this.port = resolvedUrl != null ? resolvedUrl.getPort() : -1;
        } else if (resolvedUrl != null) {
            this.scheme = resolvedUrl.getProtocol();
            this.host = resolvedUrl.getHost();
            this.port = resolvedUrl.getPort();
        } else {
            this.scheme = null;
            this.host = null;
            this.port = -1;
        }
    }

    /**
     The element that contains the link.
     @return the element
     */
    public Element element() {
        return element;
    }

    /**
     The attribute key that was checked (e.g. "href" or "src").
     @return the attribute key
     */
    public String attributeKey() {
        return attributeKey;
    }

    /**
     The original attribute value, exactly as it appeared in the source.
     @return the original value
     */
    public String originalValue() {
        return originalValue;
    }

    /**
     The resolved URL, or {@code null} if resolution failed.
     @return the resolved URL or null
     */
    public @Nullable URL resolvedUrl() {
        return resolvedUrl;
    }

    /**
     The resolved URL as an external form string, or {@code null} if resolution failed.
     @return the resolved URL string or null
     */
    public @Nullable String resolvedUrlString() {
        return resolvedUrl != null ? resolvedUrl.toExternalForm() : null;
    }

    /**
     The audit status.
     @return the status
     */
    public Status status() {
        return status;
    }

    /**
     The element's source range (location in the original source), or {@code null} if not tracked.
     @return the source range or null
     */
    public @Nullable Range sourceRange() {
        return sourceRange;
    }

    /**
     The scheme of the resolved URL, or {@code null} if resolution failed.
     @return the scheme or null
     */
    public @Nullable String scheme() {
        return scheme;
    }

    /**
     The host of the resolved URL, or {@code null} if resolution failed.
     @return the host or null
     */
    public @Nullable String host() {
        return host;
    }

    /**
     The port of the resolved URL, or {@code -1} if not specified or resolution failed.
     @return the port or -1
     */
    public int port() {
        return port;
    }

    /**
     Tests if the link is allowed.
     @return true if the status is {@link Status#ALLOWED}
     */
    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(status).append(' ');
        sb.append(element.tagName()).append('[').append(attributeKey).append("] ");
        sb.append("original=\"").append(originalValue).append("\"");
        if (resolvedUrl != null) {
            sb.append(" resolved=").append(resolvedUrl.toExternalForm());
        }
        if (sourceRange != null && sourceRange.isTracked()) {
            sb.append(" at ").append(sourceRange.start());
        }
        return sb.toString();
    }
}
