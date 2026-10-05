package org.jsoup.compare;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 Targeted tests for {@link SubtreeComparer}. Run with:
 {@code mvn -Dtest=org.jsoup.compare.SubtreeComparerTest test}
 */
class SubtreeComparerTest {

    private static Document fixture(String name) throws IOException {
        URL url = SubtreeComparerTest.class.getResource("/compare/" + name);
        Objects.requireNonNull(url, "missing fixture " + name);
        try {
            return Jsoup.parse(new File(url.toURI()), "UTF-8");
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }

    @Test void attributeReorderAndIndentationAreInsignificant() throws IOException {
        Document a = fixture("tree-a.html");
        Document b = fixture("tree-b-attr-reorder.html");

        ComparisonResult result = SubtreeComparer.compare(a, b);

        assertTrue(result.equal(), () -> "expected equal but: " + result);
        assertNull(result.firstDifferencePath());
        assertTrue(result.fingerprintsMatch());
        assertEquals(result.fingerprintA(), SubtreeComparer.fingerprint(b));
    }

    @Test void extraSpaceInsidePreStillDiffers() throws IOException {
        Document a = fixture("tree-a.html");
        Document c = fixture("tree-c-pre-extra-space.html");

        ComparisonResult result = SubtreeComparer.compare(a, c);

        assertFalse(result.equal());
        assertFalse(result.fingerprintsMatch());
        assertNotNull(result.firstDifferencePath());
        assertTrue(result.firstDifferencePath().contains("pre["),
            () -> "path should point into <pre>: " + result.firstDifferencePath());
    }

    @Test void injectedConstantHasherCannotMaskDifferentText() {
        // A constant hasher makes every fingerprint identical; the comparer must still verify the canonical
        // stream and report the text difference.
        CompareConfig config = CompareConfig.builder()
            .hasherFactory(() -> new Hasher() {
                @Override public void update(String data) { /* swallow everything */ }
                @Override public String digestHex() { return "0000"; }
            })
            .build();
        Document a = Jsoup.parse("<p>alpha</p>");
        Document b = Jsoup.parse("<p>bravo</p>");

        ComparisonResult result = SubtreeComparer.compare(a, b, config);

        assertTrue(result.fingerprintsMatch(), "constant hasher should make digests match");
        assertEquals(result.fingerprintA().hex(), "0000");
        assertFalse(result.equal(), "digest match must not be trusted: texts differ");
        assertNotNull(result.firstDifferencePath());
        assertTrue(result.firstDifferencePath().contains("#text"),
            () -> "path should point at the text node: " + result.firstDifferencePath());
    }

    @Test void configuredAttributesAreIgnored() {
        Document a = Jsoup.parse("<div id='x' data-ts='1'>hi</div>");
        Document b = Jsoup.parse("<div id='x' data-ts='2'>hi</div>");

        assertFalse(SubtreeComparer.compare(a, b).equal(), "data-ts differs under default rules");

        CompareConfig config = CompareConfig.builder().ignoreAttributes("data-ts").build();
        ComparisonResult result = SubtreeComparer.compare(a, b, config);
        assertTrue(result.equal(), () -> "data-ts should be ignored: " + result);
        assertTrue(result.fingerprintsMatch());
    }

    @Test void textAndNodeTypesRemainDistinct() {
        Document a = Jsoup.parse("<div>x</div>");
        Document b = Jsoup.parse("<div><!--x--></div>");
        assertFalse(SubtreeComparer.compare(a, b).equal(), "text vs comment must differ");

        Document c = Jsoup.parse("<div>a b</div>");
        Document d = Jsoup.parse("<div>a  b</div>");
        assertTrue(SubtreeComparer.compare(c, d).equal(), "collapsed ordinary whitespace is insignificant");

        Document e = Jsoup.parse("<div>a b</div>");
        Document f = Jsoup.parse("<div>a c</div>");
        assertFalse(SubtreeComparer.compare(e, f).equal(), "text content still distinguishes");
    }

    @Test void whitespaceOnlyTextNodesVanish() {
        Document a = Jsoup.parse("<div><p>a</p></div>");
        Document b = Jsoup.parse("<div>\n   <p>a</p>\n </div>");
        assertTrue(SubtreeComparer.compare(a, b).equal());
    }

    @Test void fingerprintIsBoundToRuleVersion() {
        Document doc = Jsoup.parse("<p>versioned</p>");
        Fingerprint v1 = SubtreeComparer.fingerprint(doc,
            CompareConfig.builder().fingerprintVersion(1).build());
        Fingerprint v2 = SubtreeComparer.fingerprint(doc,
            CompareConfig.builder().fingerprintVersion(2).build());

        assertNotEquals(v1, v2, "different rule versions must not compare equal");
        assertEquals(v1.hex(), v2.hex(), "same content, same digest; only the version differs");
        assertEquals("jsoup-subtree-v1:" + v1.hex(), v1.toString());
        assertEquals(v2, Fingerprint.parse(v2.toString()), "fingerprint round-trips through its string form");
        assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse("sha256:abcd"));
    }

    @Test void comparisonDoesNotModifyDom() {
        Document doc = Jsoup.parse("<!DOCTYPE html><html><body><!--c--><pre> a </pre><p>t</p></body></html>");
        String before = doc.html();
        TextNode text = (TextNode) doc.selectFirst("p").childNode(0);
        int textAttrSize = text.attributesSize();

        SubtreeComparer.compare(doc, doc.clone());
        SubtreeComparer.fingerprint(doc);

        assertEquals(before, doc.html(), "DOM serialization must be unchanged");
        assertEquals(textAttrSize, text.attributesSize(), "leaf nodes must not gain materialized attributes");
    }

    @Test void firstDifferencePathPointsAtFirstDivergence() {
        Document a = Jsoup.parse("<div><p>one</p><p>two</p></div>");
        Document b = Jsoup.parse("<div><p>one</p><p>2</p></div>");

        ComparisonResult result = SubtreeComparer.compare(a, b);

        assertFalse(result.equal());
        assertEquals("/#root/html[1]/body[2]/div[1]/p[2]/#text[1]", result.firstDifferencePath());
    }

    @Test void extraNodeIsReported() {
        Document a = Jsoup.parse("<div><p>one</p></div>");
        Document b = Jsoup.parse("<div><p>one</p><p>two</p></div>");

        ComparisonResult result = SubtreeComparer.compare(a, b);

        assertFalse(result.equal());
        assertEquals("/#root/html[1]/body[2]/div[1]/p[2]", result.firstDifferencePath());
        assertTrue(result.differenceSummary().contains("extra node"));
    }
}
