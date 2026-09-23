package org.jsoup.examples;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TableGrid;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 Prints the logical grid, cell spans, and header associations for every table in the TableGrid fixture
 ({@code src/test/resources/htmltests/tablegrid.html}).
 */
public class TableGridExample {
    public static void main(String[] args) throws IOException {
        Document doc = Jsoup.parse(new File("src/test/resources/htmltests/tablegrid.html"), "UTF-8");
        for (Element table : doc.select("table")) {
            TableGrid grid = TableGrid.of(table);
            System.out.printf("Table #%s: %d rows x %d cols%n", table.id(), grid.rows(), grid.cols());
            for (int row = 0; row < grid.rows(); row++) {
                StringBuilder line = new StringBuilder();
                for (int col = 0; col < grid.cols(); col++) {
                    TableGrid.Slot slot = grid.slot(row, col);
                    if (slot.isHole()) {
                        line.append("  [hole]");
                    } else {
                        line.append(String.format("  [%s origin=(%d,%d) span=%dx%d]",
                            slot.cell().text(), slot.originRow(), slot.originCol(),
                            slot.rowSpan(), slot.colSpan()));
                    }
                }
                System.out.println(line);
            }
            for (TableGrid.Slot origin : grid.origins()) {
                List<Element> headers = origin.headers();
                if (!headers.isEmpty())
                    System.out.printf("  headers of '%s': %s%n", origin.cell().text(), headers);
            }
            System.out.println();
        }
    }
}
