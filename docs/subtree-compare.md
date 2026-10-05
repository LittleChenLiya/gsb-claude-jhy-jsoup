# Subtree Comparison & Canonical Fingerprints (`org.jsoup.compare`)

An explicit, read-only module for comparing two DOM subtrees under a configurable rule set, producing a
versioned canonical fingerprint, and reporting the structural path of the first difference.

## Rule set (`CompareConfig`)

A comparison is governed by an immutable rule set:

| Rule | Default | Meaning |
| --- | --- | --- |
| `ignoredAttributes` | (none) | Attribute names (case-insensitive) excluded everywhere in the subtree. |
| `collapseWhitespace` | `true` | Collapse ordinary whitespace runs to a single space and drop whitespace-only text nodes. |
| `fingerprintVersion` | `1` (`CURRENT_FINGERPRINT_VERSION`) | The fingerprint version the rules are bound to. |
| `hasherFactory` | SHA-256 | Digest backend for the fingerprint. |

```java
CompareConfig config = CompareConfig.builder()
    .ignoreAttributes("data-ts", "data-nonce")
    .collapseWhitespace(true)
    .fingerprintVersion(CompareConfig.CURRENT_FINGERPRINT_VERSION)
    .build();
```

## Canonical form

Each subtree is reduced to a **canonical token stream**; two subtrees are equivalent iff their streams are
identical. Per node, in document order:

- **Element** — normalized tag name plus its attributes **sorted by name** (attribute order never affects
  equivalence), minus the ignored attributes.
- **Text** — outside whitespace-preserving elements, whitespace is normalized (`StringUtil.normaliseWhitespace`)
  and trimmed; whitespace-only text nodes vanish from the stream, so ordinary indentation and attribute
  reordering never cause a difference. Inside `<pre>` (and any other `Tag.preserveWhitespace()` element, and
  `CDataNode`), text is kept **verbatim** — a single extra space inside `<pre>` is a difference.
- **Node types stay distinct** — element, text, CDATA, comment, data, doctype, XML declaration, and processing
  instruction tokens carry different type tags, so a comment never equals a text node with the same content.
- **Paths** — each token carries a structural path such as `/#root/html[1]/body[2]/div[1]/p[2]/#text[1]`.
  Indexes count only *significant* (emitted) siblings, so dropped whitespace-only text nodes cannot shift paths.

The canonical string framing is length-prefixed (`len:value;`), so token boundaries are unambiguous.

## Fingerprint (`Fingerprint`)

`SubtreeComparer.fingerprint(node, config)` digests the canonical stream into
`jsoup-subtree-v<version>:<hex>`. The **rule set is bound to the fingerprint version**: the version is embedded
in every fingerprint, and fingerprints of different versions never compare equal — a digest is only meaningful
under the exact rules that produced it.

## Verification, not trust

`SubtreeComparer.compare(a, b, config)` computes both fingerprints, but a digest match is only a hint. The
comparer **always walks both canonical streams token by token** to verify actual equivalence. An injected weak
or constant hasher (via `hasherFactory`) therefore cannot mask a content difference: digests may collide, the
stream walk cannot be fooled. `ComparisonResult.fingerprintsMatch()` is informational only; `equal()` is the
verdict.

On a mismatch, `firstDifferencePath()` reports the structural path of the first diverging node (or of the first
extra node when one stream is a prefix of the other), and `differenceSummary()` describes it.

## Read-only guarantee

Traversal never mutates the DOM: attributes are iterated only when `attributesSize()` reports an existing
attribute collection, so no `Attributes` object is ever lazily materialized during comparison.

## Fixtures

Small HTML fixtures live in `src/test/resources/compare/`:

- `tree-a.html` — baseline document (attributes, nested paragraphs, a `<pre>` block).
- `tree-b-attr-reorder.html` — same document with attributes reordered and indentation removed; equal to A.
- `tree-c-pre-extra-space.html` — A plus exactly one extra space inside `<pre>`; **not** equal to A.

## Running the targeted tests

```sh
mvn -Dtest=org.jsoup.compare.SubtreeComparerTest test
```

Offline / minimal alternative (no Maven), with all outputs on the build drive:

```sh
javac -cp jspecify.jar;re2j.jar -d main-classes $(find src/main/java -name '*.java')
javac -cp main-classes;junit-platform-console-standalone.jar -d test-classes \
    src/test/java/org/jsoup/compare/SubtreeComparerTest.java
java -jar junit-platform-console-standalone.jar execute \
    -cp main-classes;test-classes;src/test/resources \
    -c org.jsoup.compare.SubtreeComparerTest
```
