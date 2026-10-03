package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.helper.ValidationException;
import org.jsoup.nodes.Document.OutputSettings.Syntax;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Attributes#migrate(Map)} and {@link Attributes#migrate(Map, Syntax)}.
 */
public class AttributeMigrationTest {

    @Test
    void swapsTitleAndDataTitle_valuesFollowSources() {
        Attributes attrs = new Attributes();
        attrs.put("title", "Main Title");
        attrs.put("data-title", "Data Title");

        Attributes migrated = attrs.migrate(plan(
            "title", "data-title",
            "data-title", "title"
        ));

        // values travel with their sources, so the swap exchanges the values along with the names
        assertEquals("Main Title", migrated.get("data-title"));
        assertEquals("Data Title", migrated.get("title"));

        // the original attributes are untouched
        assertEquals("Main Title", attrs.get("title"));
        assertEquals("Data Title", attrs.get("data-title"));
        assertTrue(attrs.hasKey("title"));
        assertTrue(attrs.hasKey("data-title"));
    }

    @Test
    void duplicateTargetIsRejectedInOneBatch() {
        Attributes attrs = new Attributes();
        attrs.put("x", "1");
        attrs.put("y", "2");
        attrs.put("z", "old");

        // two sources cannot be merged onto one target; the whole batch is rejected
        assertThrows(ValidationException.class, () -> attrs.migrate(plan(
            "x", "z",
            "y", "z"
        )));

        // nothing was partially applied
        assertEquals("1", attrs.get("x"));
        assertEquals("2", attrs.get("y"));
        assertEquals("old", attrs.get("z"));
    }

    @Test
    void existingUnmigratedTargetCannotBeOverwritten() {
        Attributes attrs = new Attributes();
        attrs.put("x", "1");
        attrs.put("z", "old");

        assertThrows(ValidationException.class, () -> attrs.migrate(plan("x", "z")));

        assertEquals("1", attrs.get("x"));
        assertEquals("old", attrs.get("z"));
    }

    @Test
    void targetIsFreeWhenItIsAlsoMigrated() {
        // z is itself migrated, so its name is freed and x may move into it
        Attributes attrs = new Attributes();
        attrs.put("x", "1");
        attrs.put("z", "old");

        Attributes migrated = attrs.migrate(plan(
            "x", "z",
            "z", "x"
        ));

        assertEquals("1", migrated.get("z"));
        assertEquals("old", migrated.get("x"));
    }

    @Test
    void htmlNameComparisonIsCaseInsensitive() {
        Attributes attrs = new Attributes();
        attrs.put("ID", "abc");

        // HTML folds ASCII case, so the lower-case source matches the mixed-case attribute
        Attributes migrated = attrs.migrate(plan("id", "new-id"));
        assertEquals("abc", migrated.get("new-id"));
        assertFalse(migrated.hasKey("ID"));

        // the same source named twice with different case is ambiguous in HTML mode
        assertThrows(ValidationException.class, () -> attrs.migrate(plan(
            "ID", "one",
            "id", "two"
        )));
    }

    @Test
    void xmlNameComparisonIsCaseSensitive() {
        Attributes attrs = new Attributes();
        attrs.put("ID", "upper");
        attrs.put("id", "lower");

        // XML keeps the two names distinct
        Attributes migrated = attrs.migrate(plan("id", "new-id"), Syntax.xml);
        assertEquals("lower", migrated.get("new-id"));
        assertEquals("upper", migrated.get("ID"));
        assertFalse(migrated.hasKey("id"));

        // the other casing is untouched
        assertEquals("upper", migrated.get("ID"));
    }

    @Test
    void xmlSwapIsCaseSensitive() {
        Attributes attrs = new Attributes();
        attrs.put("title", "Main");
        attrs.put("TITLE", "Upper");

        // in XML mode, only the exact-case source moves
        Attributes migrated = attrs.migrate(plan("title", "data-title"), Syntax.xml);
        assertEquals("Main", migrated.get("data-title"));
        assertEquals("Upper", migrated.get("TITLE"));
        assertFalse(migrated.hasKey("title"));
    }

    @Test
    void migrationPreservesAttributeOrder() {
        Attributes attrs = new Attributes();
        attrs.put("a", "1");
        attrs.put("title", "2");
        attrs.put("b", "3");
        attrs.put("data-title", "4");
        attrs.put("c", "5");

        Attributes migrated = attrs.migrate(plan(
            "title", "data-title",
            "data-title", "title"
        ));

        // order is preserved; the renamed attributes stay in their original positions
        assertEquals(" a=\"1\" data-title=\"2\" b=\"3\" title=\"4\" c=\"5\"", migrated.html());
    }

    @Test
    void migrationPreservesInternalUserData() {
        Attributes attrs = new Attributes();
        attrs.put("title", "Hello");
        attrs.userData("owner", "node-1");

        Attributes migrated = attrs.migrate(plan("title", "data-title"));

        assertEquals("Hello", migrated.get("data-title"));

        // user data carries over, with its own map copy
        assertEquals("node-1", migrated.userData("owner"));
        assertEquals("node-1", attrs.userData("owner"));
        assertNotSame(migrated.userData(), attrs.userData());

        // internal data is not serialized as an HTML attribute
        String html = migrated.html();
        assertEquals(" data-title=\"Hello\"", html);
        assertFalse(html.contains("owner"));
        assertFalse(html.contains("node-1"));
    }

    @Test
    void migratedAttributesSerializeWithoutInternalData() {
        Attributes attrs = new Attributes();
        attrs.put("title", "Hello");
        attrs.put("data-title", "World");
        attrs.userData("secret", "s3cret");
        attrs.put(Attributes.internalKey("note"), "internal");

        Attributes migrated = attrs.migrate(plan(
            "title", "data-title",
            "data-title", "title"
        ));

        // the internal note is carried but never emitted
        assertEquals("internal", migrated.get(Attributes.internalKey("note")));
        String html = migrated.html();
        assertEquals(" data-title=\"Hello\" title=\"World\"", html);
        assertFalse(html.contains("secret"));
        assertFalse(html.contains("s3cret"));
        assertFalse(html.contains("internal"));
    }

    @Test
    void nonExistentSourceIsANoop() {
        Attributes attrs = new Attributes();
        attrs.put("title", "Hello");

        Attributes migrated = attrs.migrate(plan("ghost", "boo"));

        assertEquals(attrs, migrated);
        assertEquals("Hello", migrated.get("title"));
    }

    @Test
    void emptyPlanReturnsEqualCopy() {
        Attributes attrs = new Attributes();
        attrs.put("title", "Hello");
        attrs.userData("owner", "node-1");

        Attributes migrated = attrs.migrate(Collections.emptyMap());

        assertNotSame(attrs, migrated);
        assertEquals(attrs, migrated);
        assertEquals("node-1", migrated.userData("owner"));
    }

    @Test
    void nullPlanIsRejected() {
        Attributes attrs = new Attributes();
        assertThrows(ValidationException.class, () -> attrs.migrate(null));
    }

    @Test
    void nullTargetIsRejected() {
        Attributes attrs = new Attributes();
        attrs.put("x", "1");
        Map<String, String> plan = new HashMap<>();
        plan.put("x", null);
        assertThrows(ValidationException.class, () -> attrs.migrate(plan));
    }

    @Test
    void conflictingPlanAppliesNothing() {
        Attributes attrs = new Attributes();
        attrs.put("title", "Main");
        attrs.put("data-title", "Data");
        attrs.put("x", "1");
        attrs.put("z", "old");

        // the swap is fine, but the x/y to z merge is not; the whole batch must fail atomically
        assertThrows(ValidationException.class, () -> attrs.migrate(plan(
            "title", "data-title",
            "data-title", "title",
            "x", "z",
            "y", "z"
        )));

        // no rename leaked into the result
        assertEquals("Main", attrs.get("title"));
        assertEquals("Data", attrs.get("data-title"));
        assertEquals("1", attrs.get("x"));
        assertEquals("old", attrs.get("z"));
    }

    @Test
    void migrationDoesNotModifyOriginalNode() {
        String html = "<div title=\"Main\" data-title=\"Data\" x=\"1\"></div>";
        Document doc = Jsoup.parse(html);
        Element el = doc.expectFirst("div");
        Attributes attrs = el.attributes();

        Attributes migrated = attrs.migrate(plan(
            "title", "data-title",
            "data-title", "title"
        ));

        // the node's attributes are unchanged
        assertEquals("Main", el.attr("title"));
        assertEquals("Data", el.attr("data-title"));
        assertEquals("1", el.attr("x"));

        // the copy has the swap
        assertEquals("Main", migrated.get("data-title"));
        assertEquals("Data", migrated.get("title"));

        // the document HTML is unchanged
        assertEquals("<div title=\"Main\" data-title=\"Data\" x=\"1\"></div>", el.outerHtml());
    }

    @Test
    void migrationOnXmlDocumentIsCaseSensitive() {
        String html = "<root ID=\"upper\" id=\"lower\"/>";
        Document doc = Jsoup.parse(html, org.jsoup.parser.Parser.xmlParser());
        Element el = doc.expectFirst("root");

        Attributes source = el.attributes();
        Attributes migrated = source.migrate(plan("id", "new-id"), Syntax.xml);

        assertEquals("lower", migrated.get("new-id"));
        assertEquals("upper", migrated.get("ID"));
        assertFalse(migrated.hasKey("id"));
        // the source node is untouched (Node.attr is case-insensitive, so use the case-sensitive accessor)
        assertEquals("lower", source.get("id"));
        assertEquals("upper", source.get("ID"));
        assertEquals(" ID=\"upper\" id=\"lower\"", source.html());
    }

    private static Map<String, String> plan(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
