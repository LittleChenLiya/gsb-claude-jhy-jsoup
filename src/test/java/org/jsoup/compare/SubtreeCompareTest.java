package org.jsoup.compare;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 Targeted tests for {@link SubtreeCompare}.
 <p>
 Run with:
 <pre>{@code
 mvn test -Dtest=SubtreeCompareTest
 }</pre>
 */
class SubtreeCompareTest {

    private static Document parse(String html) {
        return Jsoup.parse(html);
    }

    private static Element body(String html) {
        return parse(html).body();
    }

    @Test
    void attributeOrderAndIndentation_areEquivalent() {
        Node expected = body("<div a=\"1\" b=\"2\"><p>hello</p></div>");
        Node actual = body("<div b=\"2\" a=\"1\">\n  <p>\n    hello\n  </p>\n</div>");

        CompareResult result = SubtreeCompare.compare(expected, actual);

        assertTrue(result.isEquivalent(), () -> "should be equivalent: " + result);
    }

    @Test
    void preExtraSpace_differs() {
        Node expected = body("<pre>a b</pre>");
        Node actual = body("<pre>a  b</pre>");

        CompareResult result = SubtreeCompare.compare(expected, actual);

        assertFalse(result.isEquivalent());
        Difference difference = result.firstDifference().orElseThrow(AssertionError::new);
        assertEquals("/body[1]/pre[1]/#text[1]", difference.path().toString());
    }

    @Test
    void preNewline_isPreserved() {
        Node expected = body("<pre>line1\nline2</pre>");
        Node actual = body("<pre>line1 line2</pre>");

        CompareResult result = SubtreeCompare.compare(expected, actual);

        assertFalse(result.isEquivalent());
        Difference difference = result.firstDifference().orElseThrow(AssertionError::new);
        assertEquals("/body[1]/pre[1]/#text[1]", difference.path().toString());
    }

    @Test
    void injectedConstantDigester_stillDetectsDifference() {
        DigestFunction constant = input -> new byte[]{0};
        CompareConfig config = CompareConfig.builder().digester(constant).build();

        // Different text: digests are identical (both {0}), but the canonical streams differ.
        CompareResult different = SubtreeCompare.compare(body("<p>a</p>"), body("<p>b</p>"), config);
        assertFalse(different.isEquivalent(), "constant digester must not hide a text difference");
        assertTrue(different.firstDifference().isPresent());

        // Identical trees: digests match AND streams match.
        CompareResult same = SubtreeCompare.compare(body("<p>a</p>"), body("<p>a</p>"), config);
        assertTrue(same.isEquivalent(), "identical trees with constant digester are equivalent");
    }

    @Test
    void ignoredAttribute_doesNotCauseDifference() {
        CompareConfig config = CompareConfig.builder().ignoreAttributes("class").build();

        CompareResult result = SubtreeCompare.compare(
            body("<div class=\"x\" a=\"1\"></div>"),
            body("<div class=\"y\" a=\"1\"></div>"),
            config);

        assertTrue(result.isEquivalent(), () -> "ignored attribute should not matter: " + result);
    }

    @Test
    void notIgnoredAttribute_doesCauseDifference() {
        CompareResult result = SubtreeCompare.compare(
            body("<div class=\"x\"></div>"),
            body("<div class=\"y\"></div>"));

        assertFalse(result.isEquivalent());
        Difference difference = result.firstDifference().orElseThrow(AssertionError::new);
        assertEquals("/body[1]/div[1]", difference.path().toString());
    }

    @Test
    void attributeValues_areSignificant() {
        CompareResult result = SubtreeCompare.compare(
            body("<div a=\"1\"></div>"),
            body("<div a=\"2\"></div>"));

        assertFalse(result.isEquivalent());
    }

    @Test
    void firstDifferencePath_reported() {
        CompareResult result = SubtreeCompare.compare(
            body("<div><p>a</p></div>"),
            body("<div><p>b</p></div>"));

        assertFalse(result.isEquivalent());
        Difference difference = result.firstDifference().orElseThrow(AssertionError::new);
        assertEquals("/body[1]/div[1]/p[1]/#text[1]", difference.path().toString());
        assertTrue(difference.message().contains("/body[1]/div[1]/p[1]/#text[1]"));
    }

    @Test
    void nodeTypes_areDistinguished() {
        // Text node vs comment with the same content.
        CompareResult result = SubtreeCompare.compare(
            body("<div>x</div>"),
            body("<div><!--x--></div>"));

        assertFalse(result.isEquivalent());
    }

    @Test
    void dataNode_isVerbatim() {
        CompareResult result = SubtreeCompare.compare(
            body("<div><script>var a = 1;</script></div>"),
            body("<div><script>var a =  1;</script></div>"));

        assertFalse(result.isEquivalent(), "script whitespace is significant");
    }

    @Test
    void whitespaceOnlyText_isSkipped() {
        Node expected = body("<div><span>x</span></div>");
        Node actual = body("<div>  <span>x</span>\n</div>");

        CompareResult result = SubtreeCompare.compare(expected, actual);

        assertTrue(result.isEquivalent(), () -> "indentation should be ignored: " + result);
    }

    @Test
    void fingerprintVersion_isBoundAndDeterministic() {
        Fingerprint first = SubtreeCompare.fingerprint(body("<div a=\"1\">text</div>"));
        Fingerprint second = SubtreeCompare.fingerprint(body("<div a=\"1\">text</div>"));
        Fingerprint different = SubtreeCompare.fingerprint(body("<div a=\"2\">text</div>"));

        assertEquals(CompareConfig.FINGERPRINT_VERSION, first.version());
        assertEquals(first.version(), second.version());
        assertEquals(first.digestHex(), second.digestHex(), "same subtree -> same digest");
        assertNotEquals(first.digestHex(), different.digestHex(), "different subtree -> different digest");
        assertEquals("SHA-256", first.algorithm());
    }

    @Test
    void fingerprintVersion_isMixedIntoDigest() throws Exception {
        // The version is part of the digest input: changing the rule version changes the digest even for the
        // same canonical stream.
        Fingerprint fp = SubtreeCompare.fingerprint(body("<p>x</p>"));
        byte[] digestInput = SubtreeCompare.serializeForTest(fp.tokens(), 2);
        Fingerprint v2 = new Fingerprint(2, "SHA-256",
            MessageDigest.getInstance("SHA-256").digest(digestInput), fp.tokens());

        assertNotEquals(fp.digestHex(), v2.digestHex());
        assertEquals(1, fp.version());
        assertEquals(2, v2.version());
    }

    @Test
    void domIsNotModified() {
        String html = "<div a=\"1\" b=\"2\">\n  <p>Hello</p>\n</div>";
        Document doc = parse(html);
        Element element = doc.body().child(0);

        SubtreeCompare.compare(element, body("<div b=\"2\" a=\"1\"><p>Hello</p></div>"));

        // The original DOM is untouched: attributes keep their order, text keeps its whitespace.
        assertEquals("1", element.attr("a"));
        assertEquals("2", element.attr("b"));
        assertEquals("a", element.attributes().asList().get(0).getKey());
        assertEquals("b", element.attributes().asList().get(1).getKey());
        assertEquals("\n  ", ((org.jsoup.nodes.TextNode) element.childNode(0)).getWholeText());
        assertEquals("Hello", ((org.jsoup.nodes.TextNode) element.childNode(1).childNode(0)).getWholeText());
    }

    @Test
    void customDigester_algorithmReported() {
        DigestFunction custom = input -> new byte[]{1, 2, 3};
        CompareConfig config = CompareConfig.builder().digester(custom).build();

        Fingerprint fingerprint = SubtreeCompare.fingerprint(body("<p>x</p>"), config);

        assertEquals("custom", fingerprint.algorithm());
        assertEquals(3, fingerprint.digest().length);
    }
}
