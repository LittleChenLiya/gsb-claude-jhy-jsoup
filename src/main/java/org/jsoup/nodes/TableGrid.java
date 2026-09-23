package org.jsoup.nodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 A read-only logical grid view of an HTML {@code <table>} element, resolving {@code rowspan} / {@code colspan}
 into absolute coordinates.

 <p>Each logical slot maps back to the <b>original</b> cell element that covers it (no DOM cloning, so identity
 comparisons against the source document hold), along with the cell's top-left (origin) coordinate and its span.
 Slots not covered by any cell are <i>holes</i> and are preserved as such.</p>

 <p>Row groups ({@code thead}, {@code tbody}, {@code tfoot}, and implicit groups formed by {@code tr} children
 directly under the table) are processed in document order. Rows of nested tables never leak into the outer grid.
 A {@code rowspan="0"} extends to the end of its current row group only, and a rowspan that would cross a row
 group boundary is clamped to the group end. A missing, unparsable, or non-positive {@code colspan} normalizes
 to 1.</p>

 <p>Header association ({@link Slot#headers()} / {@link #headersFor(Element)}):</p>
 <ul>
 <li>If the cell has a {@code headers} attribute, the referenced {@code id}s are resolved to {@code th} elements
 within the same table, deduplicated in attribute order. Dangling ids are skipped. When the attribute is present
 it wins, even if it resolves to nothing.</li>
 <li>Otherwise, {@code th} elements with an explicit {@code scope="col"} cover cells below them in the columns
 they span, and {@code scope="row"} covers cells to their right in the rows they span.</li>
 <li>No further inference is performed: cells whose headers are only implied by position (e.g. a {@code th} with
 no {@code scope} in the first row) are <b>not</b> associated, and an empty list is returned.</li>
 </ul>

 <p>Grids are bounded by an explicit maximum row count, column count, and total slot budget; oversized tables
 are rejected with an {@link IllegalArgumentException}.</p>
 */
public class TableGrid {
    public static final int DEFAULT_MAX_ROWS = 1000;
    public static final int DEFAULT_MAX_COLS = 1000;
    public static final int DEFAULT_MAX_SLOTS = 100_000;

    private final Element table;
    private final int maxRows;
    private final int maxCols;
    private final int maxSlots;

    private final ArrayList<ArrayList<CellInfo>> grid = new ArrayList<>(); // null entries are holes
    private final ArrayList<CellInfo> origins = new ArrayList<>(); // in document order
    private final IdentityHashMap<Element, CellInfo> byCell = new IdentityHashMap<>();
    private int cols;

    /** A view of one logical slot in the grid. */
    public static final class Slot {
        private final CellInfo info; // null for a hole
        private final int row;
        private final int col;
        private final TableGrid gridRef;

        private Slot(TableGrid gridRef, CellInfo info, int row, int col) {
            this.gridRef = gridRef;
            this.info = info;
            this.row = row;
            this.col = col;
        }

        /** The original {@code td} / {@code th} element covering this slot, or {@code null} for a hole. */
        public Element cell() {
            return info == null ? null : info.cell;
        }

        /** Row index of this slot. */
        public int row() {
            return row;
        }

        /** Column index of this slot. */
        public int col() {
            return col;
        }

        /** Row of the covering cell's top-left (origin) slot; -1 for a hole. */
        public int originRow() {
            return info == null ? -1 : info.row;
        }

        /** Column of the covering cell's top-left (origin) slot; -1 for a hole. */
        public int originCol() {
            return info == null ? -1 : info.col;
        }

        /** Row span of the covering cell; 0 for a hole. */
        public int rowSpan() {
            return info == null ? 0 : info.rowSpan;
        }

        /** Column span of the covering cell; 0 for a hole. */
        public int colSpan() {
            return info == null ? 0 : info.colSpan;
        }

        /** True if this slot is the top-left origin of its cell. */
        public boolean isOrigin() {
            return info != null && info.row == row && info.col == col;
        }

        /** True if no cell covers this slot. */
        public boolean isHole() {
            return info == null;
        }

        /** Header {@code th} elements associated with the cell covering this slot; empty for holes. */
        public List<Element> headers() {
            return info == null ? Collections.emptyList() : gridRef.headersFor(info.cell);
        }

        @Override
        public String toString() {
            if (info == null) return String.format("(%d,%d): hole", row, col);
            return String.format("(%d,%d): %s origin=(%d,%d) span=%dx%d",
                row, col, info.cell.tagName(), info.row, info.col, info.rowSpan, info.colSpan);
        }
    }

    private static final class CellInfo {
        final Element cell;
        final int row;
        final int col;
        final int rowSpan;
        final int colSpan;

        CellInfo(Element cell, int row, int col, int rowSpan, int colSpan) {
            this.cell = cell;
            this.row = row;
            this.col = col;
            this.rowSpan = rowSpan;
            this.colSpan = colSpan;
        }
    }

    private TableGrid(Element table, int maxRows, int maxCols, int maxSlots) {
        this.table = table;
        this.maxRows = maxRows;
        this.maxCols = maxCols;
        this.maxSlots = maxSlots;
        build();
    }

    /**
     Builds a grid for {@code table} with the default budgets ({@value #DEFAULT_MAX_ROWS} rows,
     {@value #DEFAULT_MAX_COLS} cols, {@value #DEFAULT_MAX_SLOTS} slots).
     @param table a {@code <table>} element
     @return the logical grid
     */
    public static TableGrid of(Element table) {
        return of(table, DEFAULT_MAX_ROWS, DEFAULT_MAX_COLS, DEFAULT_MAX_SLOTS);
    }

    /**
     Builds a grid for {@code table} with explicit budgets.
     @param table a {@code <table>} element
     @param maxRows maximum number of logical rows
     @param maxCols maximum number of logical columns
     @param maxSlots maximum total logical slots (rows &times; cols)
     @return the logical grid
     @throws IllegalArgumentException if {@code table} is not a {@code <table>}, or the grid exceeds a budget
     */
    public static TableGrid of(Element table, int maxRows, int maxCols, int maxSlots) {
        if (table == null || !table.normalName().equals("table"))
            throw new IllegalArgumentException("Element must be a <table>");
        if (maxRows < 1 || maxCols < 1 || maxSlots < 1)
            throw new IllegalArgumentException("Budgets must be positive");
        return new TableGrid(table, maxRows, maxCols, maxSlots);
    }

    /** The source table element. */
    public Element table() {
        return table;
    }

    /** Number of logical rows. */
    public int rows() {
        return grid.size();
    }

    /** Number of logical columns. */
    public int cols() {
        return cols;
    }

    /**
     The slot at {@code (row, col)}.
     @throws IndexOutOfBoundsException if the coordinate is outside the grid
     */
    public Slot slot(int row, int col) {
        if (row < 0 || row >= rows() || col < 0 || col >= cols)
            throw new IndexOutOfBoundsException("No slot at (" + row + "," + col + ") in " + rows() + "x" + cols + " grid");
        return new Slot(this, grid.get(row).get(col), row, col);
    }

    /** Origin slots of every cell, in document order. */
    public List<Slot> origins() {
        ArrayList<Slot> out = new ArrayList<>(origins.size());
        for (CellInfo info : origins) out.add(new Slot(this, info, info.row, info.col));
        return Collections.unmodifiableList(out);
    }

    /**
     Header {@code th} elements associated with {@code cell}, as described in the class documentation. Returns an
     empty list when the cell is not part of this grid, has no {@code headers} attribute, and is not covered by any
     explicit {@code scope="row"} / {@code scope="col"} header.
     */
    public List<Element> headersFor(Element cell) {
        if (cell == null) return Collections.emptyList();

        String headersAttr = cell.attr("headers").trim();
        if (!headersAttr.isEmpty()) {
            LinkedHashSet<Element> found = new LinkedHashSet<>();
            for (String id : headersAttr.split("\\s+")) {
                Element th = table.getElementById(id);
                if (th != null && th.normalName().equals("th")) found.add(th);
            }
            return Collections.unmodifiableList(new ArrayList<>(found));
        }

        CellInfo target = byCell.get(cell);
        if (target == null) return Collections.emptyList();

        ArrayList<Element> out = new ArrayList<>();
        Set<Element> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CellInfo th : origins) {
            if (th == target || !th.cell.normalName().equals("th")) continue;
            String scope = th.cell.attr("scope").toLowerCase();
            boolean covers;
            if (scope.equals("col")) {
                covers = th.col <= target.col && target.col < th.col + th.colSpan
                    && th.row + th.rowSpan <= target.row;
            } else if (scope.equals("row")) {
                covers = th.row <= target.row && target.row < th.row + th.rowSpan
                    && th.col + th.colSpan <= target.col;
            } else {
                continue; // no inference for undeclared scope
            }
            if (covers && seen.add(th.cell)) out.add(th.cell);
        }
        return Collections.unmodifiableList(out);
    }

    private void build() {
        for (List<Element> group : rowGroups()) {
            int groupStart = grid.size();
            int groupEnd = groupStart + group.size();
            ensureCapacity(groupEnd, cols);

            for (int r = groupStart; r < groupEnd; r++) {
                Element tr = group.get(r - groupStart);
                int c = 0;
                for (Element cell : tr.children()) {
                    String name = cell.normalName();
                    if (!name.equals("td") && !name.equals("th")) continue;

                    while (occupied(r, c)) c++;

                    int colSpan = span(cell.attr("colspan"), 1); // missing / invalid / non-positive -> 1
                    int rowSpan;
                    String rowspanAttr = cell.attr("rowspan").trim();
                    if (rowspanAttr.equals("0")) {
                        rowSpan = groupEnd - r; // extend to end of current row group only
                    } else {
                        rowSpan = Math.min(span(rowspanAttr, 1), groupEnd - r); // clamp to group end
                    }

                    ensureCapacity(r + rowSpan, c + colSpan);
                    CellInfo info = new CellInfo(cell, r, c, rowSpan, colSpan);
                    origins.add(info);
                    byCell.put(cell, info);
                    for (int dr = 0; dr < rowSpan; dr++)
                        for (int dc = 0; dc < colSpan; dc++)
                            grid.get(r + dr).set(c + dc, info);
                    c += colSpan;
                }
            }
        }
    }

    /** Row groups in document order: explicit thead/tbody/tfoot, plus implicit groups of direct tr children. */
    private List<List<Element>> rowGroups() {
        ArrayList<List<Element>> groups = new ArrayList<>();
        ArrayList<Element> implicit = null;
        for (Element child : table.children()) {
            String name = child.normalName();
            if (name.equals("thead") || name.equals("tbody") || name.equals("tfoot")) {
                implicit = null;
                ArrayList<Element> rows = new ArrayList<>();
                for (Element tr : child.children())
                    if (tr.normalName().equals("tr")) rows.add(tr);
                if (!rows.isEmpty()) groups.add(rows);
            } else if (name.equals("tr")) {
                if (implicit == null) {
                    implicit = new ArrayList<>();
                    groups.add(implicit);
                }
                implicit.add(child);
            } else {
                implicit = null;
            }
        }
        return groups;
    }

    private static int span(String value, int fallback) {
        if (value == null) return fallback;
        value = value.trim();
        if (value.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean occupied(int row, int col) {
        return row < grid.size() && col < cols && grid.get(row).get(col) != null;
    }

    private void ensureCapacity(int rowsNeeded, int colsNeeded) {
        if (rowsNeeded > maxRows)
            throw new IllegalArgumentException("Table grid exceeds maximum rows: " + rowsNeeded + " > " + maxRows);
        if (colsNeeded > maxCols)
            throw new IllegalArgumentException("Table grid exceeds maximum columns: " + colsNeeded + " > " + maxCols);
        if ((long) rowsNeeded * colsNeeded > maxSlots)
            throw new IllegalArgumentException("Table grid exceeds maximum slots: " + rowsNeeded + "x" + colsNeeded + " > " + maxSlots);

        while (grid.size() < rowsNeeded) {
            ArrayList<CellInfo> row = new ArrayList<>(colsNeeded);
            for (int i = 0; i < cols; i++) row.add(null);
            grid.add(row);
        }
        if (colsNeeded > cols) {
            for (ArrayList<CellInfo> row : grid)
                for (int i = cols; i < colsNeeded; i++) row.add(null);
            cols = colsNeeded;
        }
    }
}
