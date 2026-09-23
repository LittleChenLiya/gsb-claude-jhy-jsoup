package org.jsoup.nodes;

import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A read-only logical grid view of an HTML {@code <table>} element, resolving {@code rowspan} / {@code colspan}
 * spans into absolute cell coordinates.
 *
 * <p>The grid is built by walking the table's row groups ({@code thead}, {@code tbody}, {@code tfoot}, and implicit
 * groups formed by {@code tr} children directly under the table) in document order, and placing each {@code td} /
 * {@code th} at the first free column of its row. Cells from nested tables (tables contained within a cell of this
 * table) are never merged into this grid. Positions not covered by any cell are preserved as holes
 * ({@link #slot(int, int)} returns {@code null}).</p>
 *
 * <p>Span normalization follows the HTML table model:</p>
 * <ul>
 *   <li>A missing, unparsable, or non-positive {@code colspan} is treated as 1.</li>
 *   <li>A missing, unparsable, or negative {@code rowspan} is treated as 1.</li>
 *   <li>{@code rowspan="0"} extends the cell to the end of its current row group.</li>
 *   <li>A {@code rowspan} that would cross the end of its row group is truncated to the group boundary; spans never
 *   cross row groups.</li>
 * </ul>
 *
 * <p>Every grid position covered by a spanning cell maps back to the <em>same</em> {@link Cell} instance, which in
 * turn holds the original source {@link Element} — the DOM is never cloned, so identity comparisons against the
 * parsed document hold.</p>
 *
 * <p>Extraction is bounded by an explicit maximum row count, column count, and total slot budget
 * ({@code rows * cols}); oversized grids are rejected with an {@link IllegalArgumentException} before allocation.</p>
 *
 * <p>Header association via {@link #headersFor(Element)} supports two explicit mechanisms only:</p>
 * <ul>
 *   <li>the {@code headers} attribute, resolved against {@code th} elements within the same table, de-duplicated in
 *   document order of the attribute tokens; dangling references are skipped;</li>
 *   <li>{@code scope="row"} / {@code scope="col"} on {@code th} cells, matched by grid coverage (same row to the
 *   right, same column below).</li>
 * </ul>
 * Heuristic inference for {@code th} elements without a declared {@code scope} (and for
 * {@code rowgroup} / {@code colgroup} scopes) is intentionally not performed; such cells yield an empty list.
 */
public final class TableGrid {
    /** Default maximum number of logical rows accepted. */
    public static final int DEFAULT_MAX_ROWS = 10_000;
    /** Default maximum number of logical columns accepted. */
    public static final int DEFAULT_MAX_COLS = 1_000;
    /** Default maximum number of logical grid slots ({@code rows * cols}) accepted. */
    public static final long DEFAULT_MAX_SLOTS = 1_000_000L;

    private final Element table;
    private final ArrayList<ArrayList<Cell>> grid; // [row][col], null = hole
    private final ArrayList<Cell> cells; // unique cells, document order
    private final int rows;
    private final int cols;

    private TableGrid(Element table, int maxRows, int maxCols, long maxSlots) {
        if (!"table".equals(table.normalName()))
            Validate.fail("TableGrid can only be built from a <table> element, not <%s>", table.normalName());
        Validate.isTrue(maxRows > 0 && maxCols > 0 && maxSlots > 0, "Limits must be positive");
        this.table = table;

        // Collect row groups in document order: thead / tbody / tfoot, plus implicit groups of direct tr children.
        ArrayList<ArrayList<Element>> groups = new ArrayList<>();
        ArrayList<Element> implicit = null;
        for (Element child : table.children()) {
            String name = child.normalName();
            if (name.equals("thead") || name.equals("tbody") || name.equals("tfoot")) {
                implicit = null;
                ArrayList<Element> rows = new ArrayList<>();
                for (Element maybeTr : child.children())
                    if (maybeTr.normalName().equals("tr")) rows.add(maybeTr);
                if (!rows.isEmpty()) groups.add(rows);
            } else if (name.equals("tr")) {
                if (implicit == null) {
                    implicit = new ArrayList<>();
                    groups.add(implicit);
                }
                implicit.add(child);
            }
        }

        int totalRows = 0;
        for (ArrayList<Element> group : groups) totalRows += group.size();
        if (totalRows > maxRows)
            Validate.fail("Table has %d logical rows, exceeding the maximum of %d", totalRows, maxRows);

        grid = new ArrayList<>(totalRows);
        cells = new ArrayList<>();
        for (int i = 0; i < totalRows; i++) grid.add(new ArrayList<Cell>());

        int rowOffset = 0;
        int width = 0;
        for (ArrayList<Element> group : groups) {
            int groupRows = group.size();
            for (int i = 0; i < groupRows; i++) {
                Element tr = group.get(i);
                int absRow = rowOffset + i;
                int col = 0;
                for (Element cellEl : tr.children()) {
                    String name = cellEl.normalName();
                    if (!name.equals("td") && !name.equals("th")) continue;

                    int colspan = span(cellEl, "colspan", 1); // missing / invalid / non-positive -> 1
                    int rowspan = rowspan(cellEl, groupRows - i); // 0 -> group end; truncated to group

                    ArrayList<Cell> row = grid.get(absRow);
                    while (col < row.size() && row.get(col) != null) col++; // skip occupied positions

                    int needed = col + colspan;
                    if (needed > maxCols)
                        Validate.fail("Cell <%s> at row %d needs column %d, exceeding the maximum of %d columns",
                            name, absRow, needed, maxCols);
                    if ((long) totalRows * needed > maxSlots)
                        Validate.fail("Grid of %d x %d slots exceeds the budget of %d", totalRows, needed, maxSlots);
                    if (needed > width) width = needed;

                    Cell cell = new Cell(cellEl, absRow, col, rowspan, colspan);
                    cells.add(cell);
                    for (int r = absRow; r < absRow + rowspan; r++) {
                        ArrayList<Cell> target = grid.get(r);
                        while (target.size() < col + colspan) target.add(null);
                        for (int c = col; c < col + colspan; c++)
                            if (target.get(c) == null) target.set(c, cell); // keep earlier occupants on overlap
                    }
                    col += colspan;
                }
            }
            rowOffset += groupRows;
        }
        this.rows = totalRows;
        this.cols = width;
    }

    /**
     * Builds a grid from {@code table} using the {@linkplain #DEFAULT_MAX_ROWS default} {@linkplain #DEFAULT_MAX_COLS
     * limits}.
     * @param table the table element to extract
     * @return the logical grid
     * @throws IllegalArgumentException if {@code table} is not a {@code <table>} or the grid exceeds the limits
     */
    public static TableGrid from(Element table) {
        return new TableGrid(table, DEFAULT_MAX_ROWS, DEFAULT_MAX_COLS, DEFAULT_MAX_SLOTS);
    }

    /**
     * Builds a grid from {@code table} with explicit limits.
     * @param table the table element to extract
     * @param maxRows maximum number of logical rows accepted
     * @param maxCols maximum number of logical columns accepted
     * @param maxSlots maximum number of logical slots ({@code rows * cols}) accepted
     * @return the logical grid
     * @throws IllegalArgumentException if {@code table} is not a {@code <table>} or the grid exceeds the limits
     */
    public static TableGrid from(Element table, int maxRows, int maxCols, long maxSlots) {
        return new TableGrid(table, maxRows, maxCols, maxSlots);
    }

    /** The source {@code <table>} element this grid was extracted from. */
    public Element table() {
        return table;
    }

    /** Number of logical rows in the grid. */
    public int rows() {
        return rows;
    }

    /** Number of logical columns in the grid (the widest row). */
    public int cols() {
        return cols;
    }

    /**
     * Returns the cell covering logical position {@code (row, col)}, or {@code null} if the position is a hole (not
     * covered by any cell). Spanning cells are returned from every position they cover, always as the same
     * {@link Cell} instance.
     */
    public @Nullable Cell slot(int row, int col) {
        if (row < 0 || row >= rows || col < 0 || col >= cols)
            Validate.fail("Position (%d, %d) out of grid bounds %d x %d", row, col, rows, cols);
        ArrayList<Cell> rowCells = grid.get(row);
        return col < rowCells.size() ? rowCells.get(col) : null;
    }

    /** All cells of the grid, in document order. Each spanning cell appears exactly once. */
    public List<Cell> cells() {
        return new ArrayList<>(cells);
    }

    /**
     * Resolves the header cells associated with {@code cell} within this grid's table.
     *
     * <p>If {@code cell} declares a {@code headers} attribute, each referenced ID is resolved within the same table;
     * references that are missing or do not point to a {@code th} are skipped, and duplicates are removed while
     * preserving attribute order. Otherwise, {@code th} cells with an explicit {@code scope="row"} (same logical row,
     * to the left) or {@code scope="col"} (same logical column, above) covering {@code cell} are returned in document
     * order. No inference is made for {@code th} elements without a declared
     * {@code scope}; if neither mechanism applies, an empty list is returned.</p>
     *
     * @param cell a {@code td} or {@code th} element of this grid's table
     * @return the associated header elements; never null, possibly empty
     */
    public List<Element> headersFor(Element cell) {
        List<Element> headers = new ArrayList<>();
        if (cell.hasAttr("headers")) {
            Set<String> seen = new LinkedHashSet<>();
            for (String id : cell.attr("headers").trim().split("\\s+")) {
                if (id.isEmpty() || !seen.add(id)) continue;
                Element ref = table.getElementById(id);
                if (ref != null && ref.normalName().equals("th")) headers.add(ref);
            }
            return headers;
        }

        Cell target = find(cell);
        if (target == null) return headers;
        for (Cell candidate : cells) {
            if (!candidate.element.normalName().equals("th")) continue;
            String scope = candidate.element.attr("scope");
            if (scope.equals("row")) {
                if (target.row >= candidate.row && target.row < candidate.row + candidate.rowspan
                    && target.col > candidate.col)
                    headers.add(candidate.element);
            } else if (scope.equals("col")) {
                if (target.col >= candidate.col && target.col < candidate.col + candidate.colspan
                    && target.row > candidate.row)
                    headers.add(candidate.element);
            }
            // scope=rowgroup / colgroup and undeclared scope: intentionally not inferred.
        }
        return headers;
    }

    private @Nullable Cell find(Element element) {
        for (Cell cell : cells)
            if (cell.element == element) return cell;
        return null;
    }

    private static int span(Element cell, String attr, int fallback) {
        if (!cell.hasAttr(attr)) return fallback;
        try {
            int value = Integer.parseInt(cell.attr(attr).trim());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int rowspan(Element cell, int remainingGroupRows) {
        if (cell.hasAttr("rowspan")) {
            try {
                int value = Integer.parseInt(cell.attr("rowspan").trim());
                if (value == 0) return remainingGroupRows; // rowspan=0 extends to the end of the row group
                if (value > 0) return Math.min(value, remainingGroupRows); // never crosses the row group
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    /**
     * A logical table cell: the original source element, its top-left coordinate, and its normalized span. The same
     * instance is shared by every grid position the cell covers.
     */
    public static final class Cell {
        private final Element element;
        private final int row;
        private final int col;
        private final int rowspan;
        private final int colspan;

        private Cell(Element element, int row, int col, int rowspan, int colspan) {
            this.element = element;
            this.row = row;
            this.col = col;
            this.rowspan = rowspan;
            this.colspan = colspan;
        }

        /** The original {@code td} / {@code th} element in the source document (never a clone). */
        public Element element() {
            return element;
        }

        /** The top-left logical row of this cell. */
        public int row() {
            return row;
        }

        /** The top-left logical column of this cell. */
        public int col() {
            return col;
        }

        /** The normalized number of rows this cell spans (at least 1, never crossing its row group). */
        public int rowSpan() {
            return rowspan;
        }

        /** The normalized number of columns this cell spans (at least 1). */
        public int colSpan() {
            return colspan;
        }

        @Override
        public String toString() {
            return String.format("%s(%d,%d %dx%d)", element.normalName(), row, col, rowspan, colspan);
        }
    }
}
