# Link Auditor API

The Link Auditor is a read-only tool for checking links (href and src attributes) in HTML and XML documents. It verifies that each link's resolved target is on an allowed host, without modifying the DOM or making any network requests.

## Classes

### AllowedHosts

Represents a set of allowed host rules. Hosts may be specified as:

- **Exact names**: `example.com` — matches only `example.com` (case-insensitive)
- **Subdomain rules**: `*.example.com` — matches `example.com` and all subdomains (e.g. `a.example.com`, `b.a.example.com`)

Matching is performed by parsing the host into labels and comparing them label-by-label, never by string prefix or suffix operations.

```java
// Create from varargs
AllowedHosts hosts = AllowedHosts.of("safe.example", "*.trusted.example");

// Create from a collection
AllowedHosts hosts = AllowedHosts.of(Arrays.asList("safe.example", "*.trusted.example"));

// Test a host
boolean allowed = hosts.isAllowed("safe.example");        // true
boolean allowed = hosts.isAllowed("a.trusted.example");   // true
boolean allowed = hosts.isAllowed("safe.example.evil.test"); // false
```

### LinkAuditor

The main auditor class. Uses each element's own base URI to resolve relative links, so `<base>` tags and per-element base URIs are respected.

```java
// Create with allowed hosts
LinkAuditor auditor = new LinkAuditor("safe.example", "*.trusted.example");

// Create with AllowedHosts
LinkAuditor auditor = new LinkAuditor(AllowedHosts.of("safe.example"));

// Create with custom attributes to check
LinkAuditor auditor = new LinkAuditor(
    AllowedHosts.of("safe.example"),
    Arrays.asList("href", "src", "action")
);

// Audit a document
Document doc = Jsoup.parse(html, "https://safe.example/a/");
List<LinkAuditResult> results = auditor.audit(doc);

// Audit an element and its descendants
List<LinkAuditResult> results = auditor.audit(doc.body());

// Audit a single element (no descent)
List<LinkAuditResult> results = auditor.auditElement(element);

// Test a URL directly
boolean allowed = auditor.isAllowed("https://safe.example/page");
boolean allowed = auditor.isAllowed(new URL("https://safe.example/page"));
```

### LinkAuditResult

Represents the result of auditing a single link.

```java
LinkAuditResult result = results.get(0);

result.element();         // the element containing the link
result.attributeKey();    // "href" or "src"
result.originalValue();   // the original attribute value
result.resolvedUrl();     // the resolved URL (URL object)
result.resolvedUrlString(); // the resolved URL as a string
result.status();          // ALLOWED, REJECTED_HOST, REJECTED_SCHEME, PARSE_FAILURE, NO_BASE
result.sourceRange();     // the element's source location (if tracked)
result.scheme();          // the URL scheme (http, https, javascript, etc.)
result.host();            // the URL host
result.port();            // the URL port (-1 if not specified)
result.isAllowed();       // true if status == ALLOWED
```

## Status Values

| Status | Description |
|--------|-------------|
| `ALLOWED` | The link is allowed (http/https scheme and host in the allowed set) |
| `REJECTED_HOST` | The link's host is not in the allowed set |
| `REJECTED_SCHEME` | The link's scheme is not http or https (e.g. javascript:, ftp:, mailto:) |
| `PARSE_FAILURE` | The link could not be parsed or resolved to a valid URL |
| `NO_BASE` | The link is relative but the element has no base URI to resolve against |

## Behavior

- **Read-only**: The auditor never modifies the DOM. The document's HTML is unchanged after auditing.
- **No network requests**: The auditor only parses URLs; it never connects to any host.
- **Base URI resolution**: Each element's own `baseUri()` is used to resolve relative links. This includes `<base>` tags in the document head.
- **Scheme handling**: Only `http` and `https` schemes are allowed. All other schemes are rejected as dangerous.
- **Host matching**: Hosts are matched by label parsing (not string prefix), case-insensitively.
- **User info**: URLs with user info (e.g. `https://user@host/`) are judged by the real host, not the user info.
- **Default ports**: Default ports (80 for http, 443 for https) are handled consistently.

## Example

```java
String html = "<html><head><base href=\"https://safe.example/a/\"></head><body>"
    + "<a href=\"../b\">Relative link (allowed)</a>"
    + "<a href=\"https://safe.example/page\">Absolute link (allowed)</a>"
    + "<a href=\"https://safe.example.evil.test/bad\">Evil subdomain (rejected)</a>"
    + "<a href=\"javascript:alert(1)\">JavaScript link (rejected)</a>"
    + "<a href=\"https://user@safe.example/secret\">User-info link (allowed by host)</a>"
    + "<img src=\"https://img.trusted.example/logo.png\">"
    + "<img src=\"https://img.evil.example/track.png\">"
    + "</body></html>";

Document doc = Jsoup.parse(html);
LinkAuditor auditor = new LinkAuditor("safe.example", "*.trusted.example");
List<LinkAuditResult> results = auditor.audit(doc);

for (LinkAuditResult result : results) {
    System.out.println(result.status() + ": " + result.originalValue()
        + " -> " + result.resolvedUrlString());
}
```

Output:
```
ALLOWED: ../b -> https://safe.example/b
ALLOWED: https://safe.example/page -> https://safe.example/page
REJECTED_HOST: https://safe.example.evil.test/bad -> https://safe.example.evil.test/bad
REJECTED_SCHEME: javascript:alert(1) -> null
ALLOWED: https://user@safe.example/secret -> https://user@safe.example/secret
ALLOWED: https://img.trusted.example/logo.png -> https://img.trusted.example/logo.png
REJECTED_HOST: https://img.evil.example/track.png -> https://img.evil.example/track.png
```

## XML Mode

The auditor works with XML documents too:

```java
String xml = "<root><a href=\"../b\">link</a></root>";
Document doc = Jsoup.parse(xml, "https://safe.example/a/", Parser.xmlParser());
LinkAuditor auditor = new LinkAuditor("safe.example");
List<LinkAuditResult> results = auditor.audit(doc);
```

## Position Tracking

To get element source locations, enable position tracking before parsing:

```java
Document doc = Jsoup.parse(html, baseUri, Parser.htmlParser().setTrackPosition(true));
LinkAuditor auditor = new LinkAuditor("safe.example");
List<LinkAuditResult> results = auditor.audit(doc);
for (LinkAuditResult result : results) {
    Range range = result.sourceRange();
    if (range.isTracked()) {
        System.out.println("At line " + range.start().lineNumber() + ", column " + range.start().columnNumber());
    }
}
```

## Demo

A demo program is available at `org.jsoup.examples.LinkAuditorDemo`. It audits a sample HTML document and prints the results. The demo runs in under 90 seconds (typically under 100ms).

To run:
```bash
java -cp jsoup-examples.jar org.jsoup.examples.LinkAuditorDemo
```
