package io.github.mbanucu.terminal;

import java.util.Arrays;

/**
 * One primary-screen row that has scrolled off the top, with the colours and
 * attributes of each of its cells.
 *
 * <p>Whole cells, not just text: a coloured or underlined row in a TUI's output
 * has to keep its look when it moves into history, and a resize has to keep what
 * still fits together with those cells. A cell is its full grapheme cluster, so
 * an accented letter or an emoji is one cell that survives the move.
 */
final class HistoryRow {

    private final String[] cells;
    private final int[] fg;
    private final int[] bg;
    private final int[] attributes;
    private final boolean wrapped;

    HistoryRow(String[] cells, int[] fg, int[] bg, int[] attributes, boolean wrapped) {
        this.cells = cells;
        this.fg = fg;
        this.bg = bg;
        this.attributes = attributes;
        this.wrapped = wrapped;
    }

    String text() {
        int end = cells.length;
        while (end > 0 && Cell.isBlank(cells[end - 1])) {
            end--;
        }
        StringBuilder text = new StringBuilder(end);
        for (int col = 0; col < end; col++) {
            if (!Cell.isContinuation(cells[col])) {
                text.append(cells[col]);
            }
        }
        return text.toString();
    }

    String cell(int col) {
        return col >= 0 && col < cells.length ? cells[col] : Cell.BLANK;
    }

    int foreground(int col) {
        return col >= 0 && col < fg.length ? fg[col] : 0;
    }

    int background(int col) {
        return col >= 0 && col < bg.length ? bg[col] : 0;
    }

    /** The SGR attributes of one cell, as {@link CellAttributes} bits. */
    int attribute(int col) {
        return col >= 0 && col < attributes.length ? attributes[col] : 0;
    }

    /** True when this row continued on the next one. */
    boolean wrapped() {
        return wrapped;
    }

    HistoryRow resize(int newCols) {
        if (newCols == cells.length) {
            return this;
        }
        String[] nextCells = new String[newCols];
        Arrays.fill(nextCells, Cell.BLANK);
        int[] nextFg = new int[newCols];
        int[] nextBg = new int[newCols];
        int[] nextAttr = new int[newCols];
        int keep = Math.min(newCols, cells.length);
        System.arraycopy(cells, 0, nextCells, 0, keep);
        System.arraycopy(fg, 0, nextFg, 0, keep);
        System.arraycopy(bg, 0, nextBg, 0, keep);
        System.arraycopy(attributes, 0, nextAttr, 0, keep);
        // A narrowed row can end on the base of a wide glyph whose second half
        // no longer fits; the pair cannot be drawn, so the base goes too.
        if (newCols < cells.length && Cell.width(nextCells[newCols - 1]) == 2) {
            nextCells[newCols - 1] = Cell.BLANK;
        }
        return new HistoryRow(nextCells, nextFg, nextBg, nextAttr, wrapped);
    }
}
