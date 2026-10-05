/**
 Explicit subtree comparison for jsoup: canonical fingerprints, configurable equivalence rules, and first-difference
 node paths.

 <h2>Overview</h2>
 <p>
 {@link org.jsoup.compare.SubtreeCompare} compares two DOM subtrees for equivalence under a
 {@link org.jsoup.compare.CompareConfig}. It produces a canonical {@link org.jsoup.compare.Fingerprint} of each
 subtree and a {@link org.jsoup.compare.CompareResult} that reports whether the subtrees are equivalent and, if
 not, the {@link org.jsoup.compare.NodePath} of the first differing node.
 </p>

 <h2 id="canonical-stream">Canonical stream</h2>
 <p>
 A subtree is serialized into a depth-first stream of typed tokens:
 </p>
 <table border="1">
 <caption>Canonical token types</caption>
 <tr><th>Token</th><th>Fields</th></tr>
 <tr><td>{@code E} element start</td><td>normal tag name, then sorted filtered attribute key/value pairs</td></tr>
 <tr><td>{@code /E} element end</td><td>none</td></tr>
 <tr><td>{@code T} text</td><td>the (possibly normalized) text</td></tr>
 <tr><td>{@code D} data</td><td>raw data ({@code script}, {@code style} contents)</td></tr>
 <tr><td>{@code A} CDATA</td><td>raw CDATA text</td></tr>
 <tr><td>{@code C} comment</td><td>raw comment data</td></tr>
 <tr><td>{@code X} declaration</td><td>name, declaration data, markup-declaration flag</td></tr>
 <tr><td>{@code P} processing instruction</td><td>target, data</td></tr>
 <tr><td>{@code !} document type</td><td>name, public id, system id</td></tr>
 </table>

 <h2>Equivalence rules</h2>
 <ul>
 <li><b>Attributes.</b> Attribute order does not affect equivalence: attributes are sorted by name
 (case-insensitively, then by original name) before fingerprinting. Attributes listed in
 {@link org.jsoup.compare.CompareConfig#ignoredAttributes()} are ignored entirely. Attribute values are
 significant.</li>
 <li><b>Ordinary whitespace.</b> When {@link org.jsoup.compare.CompareConfig#ignoreWhitespace()} is true (the
 default), whitespace runs in text nodes are collapsed to a single space, leading and trailing whitespace is
 trimmed, and whitespace-only text nodes (e.g. indentation between tags) are skipped.</li>
 <li><b>{@code pre} whitespace is preserved.</b> Text nodes inside a {@code pre} element are emitted verbatim, so
 an extra space (or newline) inside {@code pre} still causes a difference.</li>
 <li><b>Node types are distinguished.</b> A text node, comment, data node, CDATA section, and declaration with
 the same content are different tokens; node types are never conflated.</li>
 </ul>

 <h2 id="version-binding">Fingerprint version binding</h2>
 <p>
 The canonicalization rules are versioned. {@link org.jsoup.compare.CompareConfig#FINGERPRINT_VERSION} is the
 current version; it is embedded in every fingerprint and mixed into the digest input. Fingerprints produced under
 different rule versions therefore cannot compare as equal. When the canonicalization rules change, the version
 must be incremented.
 </p>

 <h2 id="verification">Verification: trust, but verify</h2>
 <p>
 A digest is only a fast-path hint. {@link org.jsoup.compare.SubtreeCompare#compare} always walks both canonical
 streams token by token:
 </p>
 <ul>
 <li>if the digests differ, the token walk locates the first difference (reported as a
 {@link org.jsoup.compare.Difference} with its {@link org.jsoup.compare.NodePath});</li>
 <li>if the digests match, the token walk is still performed and must match token for token before the subtrees
 are declared equivalent.</li>
 </ul>
 <p>
 An injected constant or broken {@link org.jsoup.compare.DigestFunction} therefore cannot make a genuinely
 different subtree compare as equivalent.
 </p>

 <h2>Non-mutation guarantee</h2>
 <p>
 Comparison and fingerprinting never modify the supplied nodes: the DOM content, structure, attributes, and text
 are untouched.
 </p>

 <h2>Running the tests</h2>
 <pre>{@code
 mvn test -Dtest=SubtreeCompareTest
 }</pre>
 */
package org.jsoup.compare;
