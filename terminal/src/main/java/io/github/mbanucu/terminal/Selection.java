package io.github.mbanucu.terminal;

/**
 * A cell range over the primary buffer or the alternate screen, for copying what
 * a reader selected.
 *
 * <p>Rows are absolute positions in the buffer the view is showing: history
 * first, then the live grid. Viewport rows change when the reader scrolls, so an
 * absolute row is what keeps a selection on its cells; the scrollback's dropped
 * count is what keeps it on them when the 3000-row history trims. Columns and
 * the rows the view draws are converted at the edge.
 *
 * <p>Both ends are inclusive. Text is taken cell by cell: a hard line break
 * becomes a newline, while a row the terminal wrapped is joined to its
 * continuation. Drawing the highlight and the handles stays the view's job.
 */
final class Selection {

    private final Screen screen;
    private final Viewport viewport;
    private boolean active;
    private long anchorRow;
    private int anchorCol;
    private long focusRow;
    private int focusCol;

    Selection(Screen screen, Viewport viewport) {
        this.screen = screen;
        this.viewport = viewport;
    }

    /** Starts a range at one viewport cell; a second call replaces it. */
    void begin(int row, int col) {
        long bufferRow = bufferRow(row);
        beginAt(bufferRow, clampCol(col));
    }

    private void beginAt(long bufferRow, int col) {
        active = true;
        anchorRow = clampBufferRow(bufferRow);
        anchorCol = col;
        focusRow = anchorRow;
        focusCol = anchorCol;
    }

    /** Moves the other end of the range; starts one if there is none yet. */
    void extendTo(int row, int col) {
        if (!active) {
            begin(row, col);
            return;
        }
        focusRow = bufferRow(row);
        focusCol = clampCol(col);
    }

    /**
     * Selects the word under a viewport cell, or the whole line when the cell is
     * blank. This is what a long press starts with; the handles extend it.
     */
    void selectWordOrLineAt(int row, int col) {
        long bufferRow = bufferRow(row);
        int column = clampCol(col);
        if (!isWordCell(bufferRow, column)) {
            selectLineAt(row);
            return;
        }
        int first = column;
        int last = column;
        while (first > 0 && isWordCell(bufferRow, first - 1)) {
            first--;
        }
        while (last + 1 < screen.cols() && isWordCell(bufferRow, last + 1)) {
            last++;
        }
        beginAt(bufferRow, first);
        focusCol = last;
    }

    /** Selects one row, from column zero to its last non-blank cell. */
    void selectLineAt(int row) {
        long bufferRow = bufferRow(row);
        beginAt(bufferRow, 0);
        focusCol = Math.max(0, rowLength(bufferRow) - 1);
    }

    void clear() {
        active = false;
    }

    boolean isEmpty() {
        return !active;
    }

    /** True when the cell is inside the range; blank cells count too. */
    boolean contains(int row, int col) {
        if (!active || row < 0 || row >= screen.rows()
                || col < 0 || col >= screen.cols()) {
            return false;
        }
        long first = clampBufferRow(firstBufferRow());
        long last = clampBufferRow(lastBufferRow());
        long cellRow = clampBufferRow(bufferRow(row));
        if (cellRow < first || cellRow > last) {
            return false;
        }
        int firstCol = columnAtStart(first, last);
        int lastCol = columnAtEnd(first, last);
        if (cellRow == first && col < firstCol) {
            return false;
        }
        return cellRow != last || col <= lastCol;
    }

    /** The selected text, with hard breaks kept and wrapped rows joined. */
    String text() {
        if (!active) {
            return "";
        }
        long first = clampBufferRow(firstBufferRow());
        long last = clampBufferRow(lastBufferRow());
        int firstCol = columnAtStart(first, last);
        int lastCol = columnAtEnd(first, last);
        StringBuilder copied = new StringBuilder();
        for (long row = first; row <= last; row++) {
            int length = rowLength(row);
            int from = row == first ? Math.min(firstCol, length) : 0;
            int to = row == last ? Math.min(lastCol + 1, length) : length;
            // Starting on the second half of a wide glyph selects the whole
            // glyph, the way a caret that lands there still names its base.
            if (from > 0 && from < length && Cell.isContinuation(cellAt(row, from))) {
                from--;
            }
            for (int col = from; col < to; col++) {
                String cell = cellAt(row, col);
                if (!Cell.isContinuation(cell)) {
                    copied.append(cell);
                }
            }
            if (row < last && !wrappedAt(row)) {
                copied.append('\n');
            }
        }
        return copied.toString();
    }

    /**
     * Keeps the range on rows and columns the screen still has.
     *
     * <p>A resize can remove rows or columns, and a full scrollback trims the
     * oldest history. Both callers say so here instead of leaving an endpoint
     * that names a row the buffer no longer holds.
     */
    void clamp() {
        if (!active) {
            return;
        }
        anchorRow = clampBufferRow(anchorRow);
        focusRow = clampBufferRow(focusRow);
        anchorCol = clampCol(anchorCol);
        focusCol = clampCol(focusCol);
    }

    /** Which stored end is the range's first cell, for handle-to-endpoint mapping. */
    boolean anchorIsFirst() {
        if (!active) {
            return true;
        }
        long first = clampBufferRow(firstBufferRow());
        long last = clampBufferRow(lastBufferRow());
        if (first == last) {
            return clampCol(anchorCol) <= clampCol(focusCol);
        }
        return anchorRow < focusRow;
    }

    /** The viewport row of the range's first cell; may be above or below the grid. */
    int startRow() {
        return visibleRow(clampBufferRow(firstBufferRow()));
    }

    /** The viewport row of the range's last cell; may be above or below the grid. */
    int endRow() {
        return visibleRow(clampBufferRow(lastBufferRow()));
    }

    int startCol() {
        long first = clampBufferRow(firstBufferRow());
        long last = clampBufferRow(lastBufferRow());
        return columnAtStart(first, last);
    }

    int endCol() {
        long first = clampBufferRow(firstBufferRow());
        long last = clampBufferRow(lastBufferRow());
        return columnAtEnd(first, last);
    }

    /** Sets the stored first endpoint, in viewport coordinates. */
    void setAnchor(int row, int col) {
        if (!active) {
            begin(row, col);
            return;
        }
        anchorRow = bufferRow(row);
        anchorCol = clampCol(col);
    }

    /** Sets the stored second endpoint, in viewport coordinates. */
    void setFocus(int row, int col) {
        if (!active) {
            begin(row, col);
            return;
        }
        focusRow = bufferRow(row);
        focusCol = clampCol(col);
    }

    // --- coordinates -----------------------------------------------------------

    /**
     * A viewport row as an absolute buffer row: history first, then the live
     * grid. On the alternate screen the buffer is just that grid.
     */
    private long bufferRow(int viewportRow) {
        int visible = Math.max(0, Math.min(screen.rows() - 1, viewportRow));
        if (screen.isAlternate()) {
            return visible;
        }
        return screen.historyDropped() + screen.historyRows()
                - viewport.offset() + visible;
    }

    private int visibleRow(long bufferRow) {
        long row;
        if (screen.isAlternate()) {
            row = bufferRow;
        } else {
            row = bufferRow - (screen.historyDropped() + screen.historyRows()
                    - viewport.offset());
        }
        if (row < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (row > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) row;
    }

    private long minBufferRow() {
        return screen.isAlternate() ? 0 : screen.historyDropped();
    }

    private long maxBufferRow() {
        long first = minBufferRow();
        if (screen.isAlternate()) {
            return first + Math.max(0, screen.rows() - 1);
        }
        return first + screen.historyRows() + Math.max(0, screen.rows() - 1);
    }

    private long clampBufferRow(long row) {
        return Math.max(minBufferRow(), Math.min(maxBufferRow(), row));
    }

    private int clampCol(int col) {
        return Math.max(0, Math.min(screen.cols() - 1, col));
    }

    private long firstBufferRow() {
        return Math.min(anchorRow, focusRow);
    }

    private long lastBufferRow() {
        return Math.max(anchorRow, focusRow);
    }

    private int firstCol() {
        return forward() ? anchorCol : focusCol;
    }

    private int lastCol() {
        return forward() ? focusCol : anchorCol;
    }

    /** Reading order: earlier row, then earlier column on the same row. */
    private boolean forward() {
        return anchorRow < focusRow
                || (anchorRow == focusRow && anchorCol <= focusCol);
    }

    /**
     * The first column, with the two ends of a one-row range normalized even if
     * a resize collapsed two rows onto one.
     */
    private int columnAtStart(long first, long last) {
        if (first != last) {
            return clampCol(firstCol());
        }
        return Math.min(clampCol(firstCol()), clampCol(lastCol()));
    }

    private int columnAtEnd(long first, long last) {
        if (first != last) {
            return clampCol(lastCol());
        }
        return Math.max(clampCol(firstCol()), clampCol(lastCol()));
    }

    private String cellAt(long bufferRow, int col) {
        if (screen.isAlternate()) {
            return screen.cell((int) Math.max(0,
                    Math.min(screen.rows() - 1, bufferRow)), col);
        }
        long history = bufferRow - screen.historyDropped();
        if (history < 0) {
            return Cell.BLANK;
        }
        if (history < screen.historyRows()) {
            return screen.historyCell((int) history, col);
        }
        return screen.cell((int) (history - screen.historyRows()), col);
    }

    private boolean wrappedAt(long bufferRow) {
        if (screen.isAlternate()) {
            return screen.wrapped((int) Math.max(0,
                    Math.min(screen.rows() - 1, bufferRow)));
        }
        long history = bufferRow - screen.historyDropped();
        if (history < 0) {
            return false;
        }
        if (history < screen.historyRows()) {
            return screen.historyWrapped((int) history);
        }
        return screen.wrapped((int) (history - screen.historyRows()));
    }

    /** How many columns of a row hold text: the last non-blank cell plus one. */
    private int rowLength(long bufferRow) {
        int end = screen.cols();
        while (end > 0 && Cell.isBlank(cellAt(bufferRow, end - 1))) {
            end--;
        }
        return end;
    }

    /**
     * True for a cell that belongs to a word: a graphic character, including a
     * wide glyph's continuation half. Whitespace and blanks separate words, so a
     * long press on one of those selects the line instead.
     */
    private boolean isWordCell(long bufferRow, int col) {
        String cell = cellAt(bufferRow, col);
        if (Cell.isContinuation(cell)) {
            return true;
        }
        if (Cell.isBlank(cell)) {
            return false;
        }
        return !Character.isWhitespace(cell.codePointAt(0));
    }
}
