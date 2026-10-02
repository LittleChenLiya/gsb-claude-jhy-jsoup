package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document.OutputSettings.Syntax;
import org.jsoup.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 Dedicated tests for {@link Attributes.RenamePlan} and {@link Attributes#migrate}.
 */
public class AttributesRenamePlanTest {

    private static Attributes attributes(String... keyVals) {
        Attributes attrs = new Attributes();
        for (int i = 0; i < keyVals.length; i += 2)
            attrs.put(keyVals[i], keyVals[i + 1]);
        return attrs;
    }

    @Test void swapsTitleAndDataTitleWithValues() {
        Attributes source = attributes("title", "T", "data-title", "D");
        Attributes.RenamePlan plan = new Attributes.RenamePlan()
            .rename("title", "data-title")
            .rename("data-title", "title");

        Attributes migrated = source.migrate(plan);

        // values follow their source
        assertEquals("D", migrated.get("title"));
        assertEquals("T", migrated.get("data-title"));
        // order preserved
        assertEquals("data-title", migrated.asList().get(0).getKey());
        assertEquals("title", migrated.asList().get(1).getKey());

        // the original attributes are untouched
        assertEquals("T", source.get("title"));
        assertEquals("D", source.get("data-title"));
        assertEquals(" title=\"T\" data-title=\"D\"", source.html());
    }

    @Test void migrateOnNodeDoesNotModifyNode() {
        Document doc = Jsoup.parse("<div title=\"T\" data-title=\"D\"></div>");
        Element div = doc.expectFirst("div");
        Attributes source = div.attributes();

        Attributes.RenamePlan plan = new Attributes.RenamePlan()
            .rename("title", "data-title")
            .rename("data-title", "title");
        Attributes migrated = source.migrate(plan);

        assertEquals("D", migrated.get("title"));
        assertEquals("T", migrated.get("data-title"));
        // the node and its own attributes are unchanged
        assertEquals("T", div.attr("title"));
        assertEquals("D", div.attr("data-title"));
        assertEquals("<div title=\"T\" data-title=\"D\"></div>", div.outerHtml());
    }

    @Test void rejectsMergingTwoSourcesIntoOneTarget() {
        Attributes source = attributes("x", "1", "y", "2", "z", "3");
        Attributes.RenamePlan plan = new Attributes.RenamePlan()
            .rename("x", "z")
            .rename("y", "z");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> source.migrate(plan));
        assertTrue(e.getMessage().contains("duplicate target"));

        // batch rejection: the source is untouched
        assertEquals("1", source.get("x"));
        assertEquals("2", source.get("y"));
        assertEquals("3", source.get("z"));
    }

    @Test void rejectsOverwritingAnExistingUnmigratedTarget() {
        Attributes source = attributes("id", "1", "name", "2");
        Attributes.RenamePlan plan = new Attributes.RenamePlan().rename("name", "id");

        assertThrows(IllegalArgumentException.class, () -> source.migrate(plan));
        assertEquals("1", source.get("id"));
        assertEquals("2", source.get("name"));
    }

    @Test void htmlComparesAttributeNamesCaseInsensitively() {
        // HTML: plan source names fold ASCII case to match the normalized lower-case attributes
        Attributes source = attributes("id", "1");
        Attributes.RenamePlan plan = new Attributes.RenamePlan().rename("ID", "name");

        Attributes migrated = source.migrate(plan, Syntax.html);
        assertFalse(migrated.hasKey("id"));
        assertEquals("1", migrated.get("name"));

        // separate assertion for the HTML collision rule: "ID" collides with an existing "id"
        Attributes htmlBoth = attributes("id", "1", "name", "2");
        Attributes.RenamePlan htmlPlan = new Attributes.RenamePlan().rename("name", "ID");
        assertThrows(IllegalArgumentException.class, () -> htmlBoth.migrate(htmlPlan, Syntax.html));
    }

    @Test void xmlComparesAttributeNamesCaseSensitively() {
        // XML: ID and id are distinct names and coexist; a plan referencing "ID" must not match "id"
        Attributes source = attributes("id", "1", "ID", "2");
        Attributes.RenamePlan plan = new Attributes.RenamePlan().rename("ID", "name");

        Attributes migrated = source.migrate(plan, Syntax.xml);
        assertEquals("1", migrated.get("id"));
        assertFalse(migrated.hasKey("ID"));
        assertEquals("2", migrated.get("name"));

        // separate assertion for the XML case-sensitive collision rule: renaming "id" to "x" is fine, but
        // "name" onto "NAME" is a collision; "ID" and "id" never overwrite each other
        Attributes xmlBoth = attributes("id", "1", "ID", "2");
        assertDoesNotThrow(() -> xmlBoth.migrate(new Attributes.RenamePlan().rename("id", "x"), Syntax.xml));
        Attributes xmlCollide = attributes("name", "1", "NAME", "2");
        assertThrows(IllegalArgumentException.class,
            () -> xmlCollide.migrate(new Attributes.RenamePlan().rename("name", "NAME"), Syntax.xml));
    }

    @Test void xmlParsedDocumentDistinguishesIdCases() {
        // end-to-end through the XML parser, as a distinct case from the HTML parser
        Document doc = Parser.xmlParser().parseInput("<node id=\"lower\" ID=\"upper\"/>", "");
        Element node = doc.expectFirst("node");
        Attributes migrated = node.attributes()
            .migrate(new Attributes.RenamePlan().rename("ID", "name"), Syntax.xml);

        assertEquals("lower", migrated.get("id"));
        assertEquals("upper", migrated.get("name"));
        assertEquals("lower", node.attributes().get("id"));
        assertEquals("upper", node.attributes().get("ID"));
    }

    @Test void movesInternalUserDataOwnershipWithRenames() {
        Attributes source = attributes("title", "T", "data-title", "D");
        source.userData("title", "title-data");
        source.userData("data-title", "data-data");
        source.userData("unrelated", "keep");

        Attributes.RenamePlan plan = new Attributes.RenamePlan()
            .rename("title", "data-title")
            .rename("data-title", "title");
        Attributes migrated = source.migrate(plan);

        assertEquals("data-data", migrated.userData("title"));
        assertEquals("title-data", migrated.userData("data-title"));
        assertEquals("keep", migrated.userData("unrelated"));
        // the source's user data is not moved
        assertEquals("title-data", source.userData("title"));
        assertEquals("data-data", source.userData("data-title"));
    }

    @Test void rejectedPlanLeavesInternalUserDataAlone() {
        Attributes source = attributes("x", "1", "y", "2", "z", "3");
        source.userData("x", "xd");
        Attributes.RenamePlan plan = new Attributes.RenamePlan()
            .rename("x", "z")
            .rename("y", "z");

        assertThrows(IllegalArgumentException.class, () -> source.migrate(plan));
        assertEquals("xd", source.userData("x"));
        assertNull(source.userData("z"));
    }

    @Test void internalMetadataIsNotSerializedAfterMigrate() {
        Attributes source = attributes("title", "T");
        source.userData("title", "internal-only");
        Attributes migrated = source.migrate(new Attributes.RenamePlan().rename("title", "name"));

        assertEquals(" name=\"T\"", migrated.html());
        assertFalse(migrated.html().contains("internal-only"));
        assertFalse(migrated.html().contains("jsoup.userdata"));
        assertEquals(1, migrated.size());
        assertEquals("internal-only", migrated.userData("name"));
    }

    @Test void planCanBeCreatedFromMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", "data-title");
        map.put("data-title", "title");
        Attributes.RenamePlan plan = Attributes.RenamePlan.create(map);

        assertEquals(map, plan.renames());
        Attributes migrated = attributes("title", "T", "data-title", "D").migrate(plan);
        assertEquals("D", migrated.get("title"));
        assertEquals("T", migrated.get("data-title"));
    }

    @Test void unchangedNamesAreNoops() {
        Attributes source = attributes("title", "T", "other", "O");
        Attributes migrated = source.migrate(new Attributes.RenamePlan()
            .rename("title", "title")
            .rename("missing", "whatever"));

        assertEquals("T", migrated.get("title"));
        assertEquals("O", migrated.get("other"));
        assertEquals(2, migrated.size());
    }
}