package org.jsoup.nodes;

import org.jsoup.Jsoup;
import org.jsoup.integration.ParseTest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TableGridTest {

    private static Element tableOf(String html) {
        return Jsoup.parse(html).expectFirst("table");
    }

    private static String textAt(TableGrid grid, int row, int col) {
        TableGrid.Cell cell = grid.slot(row, col);
        return cell == null ? null : cell.element().text();
    }

    @Test
    public void spansResolveToLogicalGrid() {
        // First row: A spans two rows, B spans two columns; second row: C and D.
        Element table = tableOf(
            "<table><tr><td rowspan=2>A</td><td colspan=2>B</td></tr><tr><td>C</td><td>D</td></tr></table>");
        TableGrid grid = TableGrid.from(table);

        assertEquals(2, grid.rows());
        assertEquals(3, grid.cols());

        assertEquals("A", textAt(grid, 0, 0));
        assertEquals("B", textAt(grid, 0, 1));
        assertEquals("B", textAt(grid, 0, 2));
        assertEquals("A", textAt(grid, 1, 0));
        assertEquals("C", textAt(grid, 1, 1));
        assertEquals("D", textAt(grid, 1, 2));

        // every covered slot resolves to the same Cell instance and the same source element (no cloning)
        TableGrid.Cell a = grid.slot(0, 0);
        assertSame(a, grid.slot(1, 0));
        assertSame(a.element(), grid.slot(1, 0).element());
        assertSame(table.expectFirst("td"), a.element());
        assertEquals(0, a.row());
        assertEquals(0, a.col());
        assertEquals(2, a.rowSpan());
        assertEquals(1, a.colSpan());

        TableGrid.Cell b = grid.slot(0, 2);
        assertSame(b, grid.slot(0, 1));
        assertEquals(0, b.row());
        assertEquals(1, b.col());
        assertEquals(1, b.rowSpan());
        assertEquals(2, b.colSpan());

        TableGrid.Cell c = grid.slot(1, 1);
        assertEquals(1, c.row());
        assertEquals(1, c.col());
        TableGrid.Cell d = grid.slot(1, 2);
        assertEquals(1, d.row());
        assertEquals(2, d.col());

        assertEquals(4, grid.cells().size());
    }

    @Test
    public void fixtureGridShape() throws IOException {
        File in = ParseTest.getFile("/htmltests/table-grid.html");
        Document doc = Jsoup.parse(in, "UTF-8");
        Element table = doc.expectFirst("table#main");

        String before = doc.html();
        TableGrid grid = TableGrid.from(table);
        assertEquals(before, doc.html()); // extraction is read-only; the DOM is unchanged

        assertEquals(6, grid.rows());
        assertEquals(3, grid.cols());
        assertEquals(15, grid.cells().size()); // the nested table's cells are not merged into the outer grid

        // rowspan=0 extends to the end of its tbody only (rows 2..3), not into the next tbody
        TableGrid.Cell tailed = grid.slot(2, 0);
        assertEquals("tailed", tailed.element().text());
        assertEquals(2, tailed.rowSpan());
        assertSame(tailed, grid.slot(3, 0));
        assertNotSame(tailed, grid.slot(4, 0));

        // invalid colspan normalizes to 1; cells after a span land in the first free column
        TableGrid.Cell badspan = grid.slot(2, 1);
        assertEquals("badspan", badspan.element().text());
        assertEquals(1, badspan.colSpan());
        assertEquals("c", textAt(grid, 2, 2));
        assertEquals("d", textAt(grid, 3, 1)); // (3,0) is occupied by tailed
        assertEquals("e", textAt(grid, 3, 2));

        // holes are preserved
        assertNull(grid.slot(4, 2));
        assertEquals("x", textAt(grid, 4, 1));
        assertEquals("f1", textAt(grid, 5, 1));
    }

    @Test
    public void nestedTablesAreIsolated() throws IOException {
        File in = ParseTest.getFile("/htmltests/table-grid.html");
        Document doc = Jsoup.parse(in, "UTF-8");
        TableGrid outer = TableGrid.from(doc.expectFirst("table#main"));
        Element innerTd = doc.expectFirst("table#inner td");
        for (TableGrid.Cell cell : outer.cells())
            assertNotSame(innerTd, cell.element()); // nested rows never leak into the outer grid

        TableGrid inner = TableGrid.from(doc.expectFirst("table#inner"));
        assertEquals(1, inner.rows());
        assertEquals(1, inner.cols());
        assertEquals("inner", inner.slot(0, 0).element().text());
    }

    @Test
    public void headersAttributeResolvesWithinTable() throws IOException {
        File in = ParseTest.getFile("/htmltests/table-grid.html");
        Document doc = Jsoup.parse(in, "UTF-8");
        Element table = doc.expectFirst("table#main");
        TableGrid grid = TableGrid.from(table);

        // duplicate tokens are removed, dangling IDs are skipped, attribute order is preserved
        List<Element> headers = grid.headersFor(doc.expectFirst("#d10"));
        assertEquals(2, headers.size());
        assertSame(doc.expectFirst("#h-q"), headers.get(0));
        assertSame(doc.expectFirst("#r-alpha"), headers.get(1));
    }

    @Test
    public void scopeHeadersCoverByGrid() throws IOException {
        File in = ParseTest.getFile("/htmltests/table-grid.html");
        Document doc = Jsoup.parse(in, "UTF-8");
        Element table = doc.expectFirst("table#main");
        TableGrid grid = TableGrid.from(table);

        List<Element> d20 = grid.headersFor(doc.expectFirst("#d20"));
        assertEquals(2, d20.size()); // scope=col from h-q, scope=row from r-alpha
        assertSame(doc.expectFirst("#h-q"), d20.get(0));
        assertSame(doc.expectFirst("#r-alpha"), d20.get(1));

        List<Element> f1 = grid.headersFor(doc.expectFirst("#f1"));
        assertEquals(1, f1.size()); // only h-q; the tfoot th has no scope and is never inferred
        assertSame(doc.expectFirst("#h-q"), f1.get(0));

        List<Element> tailed = grid.headersFor(doc.expectFirst("#tailed"));
        assertEquals(1, tailed.size());
        assertSame(doc.expectFirst("#h-name"), tailed.get(0));
    }

    @Test
    public void undeclaredScopeIsNotInferred() {
        Element table = tableOf("<table><tr><th>H</th><td>a</td></tr><tr><td>b</td><td>c</td></tr></table>");
        TableGrid grid = TableGrid.from(table);
        Element td = table.expectFirst("td");
        assertTrue(grid.headersFor(td).isEmpty()); // no headers attr, th has no scope -> no association
        assertTrue(grid.headersFor(table.expectFirst("th")).isEmpty());
    }

    @Test
    public void rejectsOversizedGrids() throws IOException {
        File in = ParseTest.getFile("/htmltests/table-grid.html");
        Document doc = Jsoup.parse(in, "UTF-8");
        Element table = doc.expectFirst("table#main");

        assertThrows(IllegalArgumentException.class, () -> TableGrid.from(table, 5, 100, 10_000)); // 6 rows > 5
        assertThrows(IllegalArgumentException.class, () -> TableGrid.from(table, 100, 2, 10_000)); // 3 cols > 2
        assertThrows(IllegalArgumentException.class, () -> TableGrid.from(table, 100, 100, 17)); // 6*3 slots > 17
        assertThrows(IllegalArgumentException.class, () -> TableGrid.from(doc.body())); // not a table

        TableGrid ok = TableGrid.from(table, 6, 3, 18); // exact fit is accepted
        assertEquals(6, ok.rows());
    }

    @Test
    public void rowspanTruncatedAtRowGroupEnd() {
        Element table = tableOf(
            "<table><tbody><tr><td rowspan=99>a</td></tr><tr><td>b</td></tr></tbody>" +
            "<tbody><tr><td>c</td></tr></tbody></table>");
        TableGrid grid = TableGrid.from(table);
        TableGrid.Cell a = grid.slot(0, 0);
        assertEquals(2, a.rowSpan()); // truncated to the first tbody, does not leak into the second
        assertSame(a, grid.slot(1, 0));
        assertEquals("c", textAt(grid, 2, 0));
    }
}
