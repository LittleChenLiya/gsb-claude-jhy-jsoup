package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TableGridTest {

    private static Element table(Document doc, String id) {
        return doc.getElementById(id);
    }

    private static Document fixture() throws IOException {
        File in = new File("src/test/resources/htmltests/tablegrid.html");
        String html = new String(Files.readAllBytes(in.toPath()), StandardCharsets.UTF_8);
        return Jsoup.parse(html);
    }

    private static String text(TableGrid grid, int row, int col) {
        Element cell = grid.slot(row, col).cell();
        return cell == null ? "<hole>" : cell.text();
    }

    @Test
    public void acceptanceGrid() {
        Document doc = Jsoup.parse(
            "<table><tr><td rowspan=2>A</td><td colspan=2>B</td></tr><tr><td>C</td><td>D</td></tr></table>");
        TableGrid grid = TableGrid.of(doc.selectFirst("table"));

        assertEquals(2, grid.rows());
        assertEquals(3, grid.cols());

        // [A, B, B] / [A, C, D]
        assertEquals("A", text(grid, 0, 0));
        assertEquals("B", text(grid, 0, 1));
        assertEquals("B", text(grid, 0, 2));
        assertEquals("A", text(grid, 1, 0));
        assertEquals("C", text(grid, 1, 1));
        assertEquals("D", text(grid, 1, 2));

        // every slot of a spanning cell resolves to the same source element (identity, no cloning)
        Element a = grid.slot(0, 0).cell();
        Element b = grid.slot(0, 1).cell();
        assertSame(a, grid.slot(1, 0).cell());
        assertSame(b, grid.slot(0, 2).cell());
        assertSame(doc.selectFirst("td"), a);

        // origin coordinates and spans
        TableGrid.Slot a0 = grid.slot(0, 0);
        assertTrue(a0.isOrigin());
        assertEquals(0, a0.originRow());
        assertEquals(0, a0.originCol());
        assertEquals(2, a0.rowSpan());
        assertEquals(1, a0.colSpan());

        TableGrid.Slot a1 = grid.slot(1, 0);
        assertFalse(a1.isOrigin());
        assertEquals(0, a1.originRow());
        assertEquals(0, a1.originCol());
        assertEquals(2, a1.rowSpan());

        TableGrid.Slot b2 = grid.slot(0, 2);
        assertFalse(b2.isOrigin());
        assertEquals(0, b2.originRow());
        assertEquals(1, b2.originCol());
        assertEquals(1, b2.rowSpan());
        assertEquals(2, b2.colSpan());

        TableGrid.Slot c = grid.slot(1, 1);
        assertTrue(c.isOrigin());
        assertEquals(1, c.rowSpan());
        assertEquals(1, c.colSpan());
    }

    @Test
    public void multipleBodiesRowspanZeroAndClamp() throws IOException {
        TableGrid grid = TableGrid.of(table(fixture(), "groups"));

        // 1 thead + 2 + 2 tbody rows + 1 tfoot = 6 rows, 2 cols
        assertEquals(6, grid.rows());
        assertEquals(2, grid.cols());

        // rowspan=0 extends to the end of its own tbody only (rows 1..2), not into the next tbody
        Element g1zero = grid.slot(1, 0).cell();
        assertEquals("g1zero", g1zero.text());
        assertSame(g1zero, grid.slot(2, 0).cell());
        assertEquals(2, grid.slot(1, 0).rowSpan());
        assertEquals("g2clamp", text(grid, 3, 0)); // next group starts fresh at col 0

        // rowspan=5 clamped to the end of its tbody (rows 3..4)
        Element g2 = grid.slot(3, 0).cell();
        assertSame(g2, grid.slot(4, 0).cell());
        assertEquals(2, grid.slot(3, 0).rowSpan());
        assertEquals("foot", text(grid, 5, 0)); // tfoot not covered by the clamped span

        // tfoot colspan fills the row
        assertSame(grid.slot(5, 0).cell(), grid.slot(5, 1).cell());
    }

    @Test
    public void implicitRowGroups() {
        Document doc = Jsoup.parse("<table><tr><td>a</td></tr><tr><td>b</td></tr></table>");
        TableGrid grid = TableGrid.of(doc.selectFirst("table"));
        assertEquals(2, grid.rows());
        assertEquals(1, grid.cols());
        assertEquals("a", text(grid, 0, 0));
        assertEquals("b", text(grid, 1, 0));
    }

    @Test
    public void nestedTableRowsDoNotLeakAndHolesPreserved() throws IOException {
        Document doc = fixture();
        TableGrid outer = TableGrid.of(table(doc, "nested"));

        assertEquals(2, outer.rows());
        assertEquals(2, outer.cols());
        assertEquals("outer-a", text(outer, 0, 0));
        assertEquals("outer-c", text(outer, 1, 0));

        // hole at (1,1): no outer cell covers it
        TableGrid.Slot hole = outer.slot(1, 1);
        assertTrue(hole.isHole());
        assertNull(hole.cell());
        assertEquals(0, hole.rowSpan());
        assertTrue(hole.headers().isEmpty());

        // every outer cell belongs to the outer table, none to the nested one
        Element outerTable = table(doc, "nested");
        for (TableGrid.Slot origin : outer.origins())
            assertSame(outerTable, origin.cell().closest("table"));

        // inner table grids independently
        TableGrid inner = TableGrid.of(table(doc, "inner"));
        assertEquals(2, inner.rows());
        assertEquals(2, inner.cols());
        assertEquals("inner-4", text(inner, 1, 1));
    }

    @Test
    public void headersAttributeDanglingAndDuplicates() throws IOException {
        Document doc = fixture();
        TableGrid grid = TableGrid.of(table(doc, "headers"));

        Element cell = grid.slot(1, 1).cell(); // headers="c1 r1 c1 missing-id"
        assertEquals("10", cell.text());
        List<Element> headers = grid.headersFor(cell);
        assertEquals(2, headers.size()); // deduplicated, dangling id skipped
        assertSame(doc.getElementById("c1"), headers.get(0));
        assertSame(doc.getElementById("r1"), headers.get(1));

        // dangling-only headers attribute: attribute present, resolves to nothing, no scope fallback
        Document dangling = Jsoup.parse(
            "<table><tr><th scope=col id=x>H</th></tr><tr><td headers='nope'>v</td></tr></table>");
        TableGrid danglingGrid = TableGrid.of(dangling.selectFirst("table"));
        assertTrue(danglingGrid.headersFor(danglingGrid.slot(1, 0).cell()).isEmpty());
    }

    @Test
    public void scopeRowAndColCoverage() throws IOException {
        Document doc = fixture();
        TableGrid grid = TableGrid.of(table(doc, "headers"));

        Element c1 = doc.getElementById("c1");
        Element c2 = doc.getElementById("c2");
        Element r1 = doc.getElementById("r1");
        Element r2 = doc.getElementById("r2");

        // scope=col covers cells below in the same column; scope=row covers cells to the right in the same row
        List<Element> h30 = grid.headersFor(grid.slot(2, 1).cell()); // "30"
        assertEquals(2, h30.size());
        assertTrue(h30.contains(c2));
        assertTrue(h30.contains(r2));

        // "20" is in the undeclared-scope column: only row header r1 applies
        List<Element> h20 = grid.headersFor(grid.slot(1, 2).cell());
        assertEquals(1, h20.size());
        assertSame(r1, h20.get(0));

        // header cells themselves are not covered by their own scope
        assertTrue(grid.headersFor(r1).stream().noneMatch(h -> h == r1));
        assertTrue(grid.headersFor(c1).isEmpty());
    }

    @Test
    public void noInferenceForUndeclaredScope() {
        // a th with no scope attribute must not be inferred as a header
        Document doc = Jsoup.parse("<table><tr><th>H</th></tr><tr><td>v</td></tr></table>");
        TableGrid grid = TableGrid.of(doc.selectFirst("table"));
        assertTrue(grid.headersFor(grid.slot(1, 0).cell()).isEmpty());
        assertTrue(grid.slot(1, 0).headers().isEmpty());
        assertTrue(grid.headersFor(doc.selectFirst("th")).isEmpty());
        assertTrue(grid.headersFor(null).isEmpty());
    }

    @Test
    public void spanNormalization() {
        Document doc = Jsoup.parse(
            "<table><tr><td colspan=0>a</td><td colspan=-3>b</td><td colspan=bogus>c</td>" +
            "<td rowspan=-2>d</td><td rowspan=bogus>e</td></tr></table>");
        TableGrid grid = TableGrid.of(doc.selectFirst("table"));
        assertEquals(5, grid.cols());
        for (int col = 0; col < 5; col++) {
            assertEquals(1, grid.slot(0, col).colSpan());
            assertEquals(1, grid.slot(0, col).rowSpan());
        }
    }

    @Test
    public void budgetRejection() {
        Element wide = Jsoup.parse("<table><tr><td colspan=2000>x</td></tr></table>").selectFirst("table");
        assertThrows(IllegalArgumentException.class, () -> TableGrid.of(wide));

        StringBuilder tallHtml = new StringBuilder("<table>");
        for (int i = 0; i < 1100; i++) tallHtml.append("<tr><td>x</td></tr>");
        Element tall = Jsoup.parse(tallHtml + "</table>").selectFirst("table");
        assertThrows(IllegalArgumentException.class, () -> TableGrid.of(tall));

        // explicit budgets
        Element small = Jsoup.parse("<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>")
            .selectFirst("table");
        assertThrows(IllegalArgumentException.class, () -> TableGrid.of(small, 1, 10, 100)); // rows
        assertThrows(IllegalArgumentException.class, () -> TableGrid.of(small, 10, 1, 100)); // cols
        assertThrows(IllegalArgumentException.class, () -> TableGrid.of(small, 10, 10, 3)); // slots
        assertThrows(IllegalArgumentException.class, () -> TableGrid.of(small, 0, 10, 100)); // invalid budget
        assertDoesNotThrow(() -> TableGrid.of(small, 2, 2, 4));

        // not a table
        assertThrows(IllegalArgumentException.class,
            () -> TableGrid.of(Jsoup.parse("<div>x</div>").selectFirst("div")));
    }

    @Test
    public void domIsNotModified() throws IOException {
        Document doc = fixture();
        String before = doc.html();
        TableGrid acceptance = TableGrid.of(table(doc, "acceptance"));
        TableGrid.of(table(doc, "groups"));
        TableGrid.of(table(doc, "nested"));
        TableGrid headersGrid = TableGrid.of(table(doc, "headers"));
        for (TableGrid.Slot origin : headersGrid.origins()) origin.headers();
        for (TableGrid.Slot origin : acceptance.origins()) origin.headers();
        assertEquals(before, doc.html());
    }

    @Test
    public void outOfBoundsSlot() {
        TableGrid grid = TableGrid.of(Jsoup.parse("<table><tr><td>a</td></tr></table>").selectFirst("table"));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.slot(1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.slot(0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.slot(-1, 0));
    }
}
