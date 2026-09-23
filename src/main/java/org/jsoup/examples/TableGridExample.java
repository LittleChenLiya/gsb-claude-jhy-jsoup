package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TableGrid;

import java.io.File;
import java.util.List;

/**
 * Prints the logical grid, cell spans, and header associations of a table. Defaults to the test fixture at
 * {@code src/test/resources/htmltests/table-grid.html}; pass a file path or URL to inspect another table.
 */
public class TableGridExample {
    public static void main(String... args) throws Exception {
        String source = args.length > 0 ? args[0] : "src/test/resources/htmltests/table-grid.html";
        Document doc = source.startsWith("http") ? Jsoup.connect(source).get() : Jsoup.parse(new File(source), "UTF-8");
        Element table = doc.expectFirst("table");
        TableGrid grid = TableGrid.from(table);

        System.out.printf("Grid: %d rows x %d cols%n", grid.rows(), grid.cols());
        for (int row = 0; row < grid.rows(); row++) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < grid.cols(); col++) {
                TableGrid.Cell cell = grid.slot(row, col);
                line.append('[').append(cell == null ? "-" : cell.element().text()).append("] ");
            }
            System.out.println(line);
        }
        System.out.println();
        System.out.println("Cells (origin and span):");
        for (TableGrid.Cell cell : grid.cells())
            System.out.printf("  (%d,%d) %dx%d  %s%n", cell.row(), cell.col(), cell.rowSpan(), cell.colSpan(),
                cell.element().text());
        System.out.println();
        System.out.println("Headers:");
        for (TableGrid.Cell cell : grid.cells()) {
            List<Element> headers = grid.headersFor(cell.element());
            System.out.printf("  %s -> %s%n", cell.element().text(),
                headers.isEmpty() ? "(none)" : headers.toString());
        }
    }
}
