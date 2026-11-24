package io.github.mbanucu.terminal;

/**
 * One grid cell as text: a base code point plus any combining marks.
 *
 * <p>A cell used to be a {@code char}, so a surrogate pair took two cells and a
 * combining accent took its own cell after the letter it belonged to. A cell is
 * now the whole grapheme cluster as a {@code String}: the base character is the
 * first code point, the marks that follow are appended to it. Every consumer -
 * {@link Screen}, {@link HistoryRow}, {@link Selection} and the row text the
 * accessibility tree publishes - reads the same string, so no path can split a
 * cluster back into code units.
 *
 * <p>Memory stays close to the old {@code char[][]}: the grid holds references to
 * immutable strings, ASCII cells come from a shared table, and blank and
 * continuation cells are shared sentinels. A written cell allocates only when
 * its text is not ASCII.
 */
final class Cell {

    /** An untouched cell, and the space a program writes. */
    static final String BLANK = " ";

    /**
     * The second half of a wide glyph. It is a sentinel, not text: rows skip it
     * when they are joined, and the view draws the glyph once from the base cell.
     */
    static final String CONTINUATION = "\u0000";

    /** One shared string per printable ASCII character, so plain text allocates none. */
    private static final String[] ASCII = new String[0x80];

    static {
        for (int c = 0; c < ASCII.length; c++) {
            ASCII[c] = String.valueOf((char) c);
        }
    }

    private Cell() {
    }

    /** The cell text for a code point with no marks yet. */
    static String of(int codePoint) {
        if (codePoint >= 0x20 && codePoint < ASCII.length) {
            return ASCII[codePoint];
        }
        return new String(Character.toChars(codePoint));
    }

    /** The cell text with one combining mark appended to it. */
    static String withMark(String cell, int mark) {
        return cell + new String(Character.toChars(mark));
    }

    /** True when this cell is the right half of a wide glyph. */
    static boolean isContinuation(String cell) {
        return CONTINUATION.equals(cell);
    }

    /** True when this cell is a space with nothing attached to it. */
    static boolean isBlank(String cell) {
        return BLANK.equals(cell);
    }

    /** The display width of the cell, from its base code point. */
    static int width(String cell) {
        if (cell == null || cell.isEmpty() || isContinuation(cell)) {
            return 0;
        }
        return Width.of(cell.codePointAt(0));
    }

    /** The base code point of the cell; a space for an empty cell. */
    static int codePoint(String cell) {
        if (cell == null || cell.isEmpty()) {
            return ' ';
        }
        return cell.codePointAt(0);
    }

    /**
     * True for a mark that belongs to the cell before it: non-spacing,
     * combining-spacing and enclosing marks, as {@link Character#getType} names
     * them. None of them takes a column of its own.
     */
    static boolean isCombining(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}
