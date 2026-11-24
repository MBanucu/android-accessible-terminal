package io.github.mbanucu.terminal;

/**
 * The display width of one code point in cells.
 *
 * <p>Enough of `wcwidth` for the scripts a TUI draws: the East Asian wide and
 * fullwidth ranges, the CJK extensions outside the BMP, and the emoji blocks
 * take two cells; everything else takes one. Combining marks take none, but a
 * mark never reaches here as a cell of its own: {@link Cell} attaches it to the
 * cell before it, and a cell's width is its base character's width.
 */
final class Width {

    private Width() {
    }

    /** 2 for a wide or fullwidth glyph, 1 for everything else. */
    static int of(int codePoint) {
        return isWide(codePoint) ? 2 : 1;
    }

    private static boolean isWide(int c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6)
                // Emoji, including the astral blocks a surrogate pair names in
                // UTF-16: one glyph, two cells, wherever its code point sits.
                || (c >= 0x1F000 && c <= 0x1FAFF)
                // CJK unified ideographs, extensions B and later.
                || (c >= 0x20000 && c <= 0x3FFFD);
    }
}
