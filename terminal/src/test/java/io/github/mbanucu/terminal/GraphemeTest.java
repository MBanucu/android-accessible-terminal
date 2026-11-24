package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;
import static io.github.mbanucu.terminal.TestSupport.cursor;

/**
 * Checks the cell model for grapheme clusters: a base code point plus combining
 * marks, and astral characters such as emoji.
 *
 * <p>The old cell was one {@code char}: an accent became a cell after its letter
 * and an emoji became two cells. These checks pin down the replacement - one
 * cell per cluster, a wide cluster over two cells, and every consumer reading
 * the original code points - through cursors, erases, wrapping, history,
 * resizing, selection and the row text accessibility reads.
 */
public final class GraphemeTest {

    /** `e` followed by COMBINING ACUTE ACCENT. */
    private static final String E_ACUTE = "e\u0301";

    /** U+1F600 GRINNING FACE: outside the BMP, so a surrogate pair in UTF-16. */
    private static final String EMOJI = "\uD83D\uDE00";

    public static void main(String[] args) {
        combiningMarkStaysInItsCell();
        marksDoNotAdvanceTheCursor();
        markAfterAWideGlyph();
        astralCharacterIsOneCell();
        emojiAtTheRightEdge();
        widths();
        surrogatePairSplitAcrossFeeds();
        cursorMovement();
        erase();
        eraseAtAWideGlyphBoundary();
        wrapping();
        history();
        resize();
        selection();
        accessibilityRowText();

        TestSupport.report("grapheme checks");
    }

    private static void combiningMarkStaysInItsCell() {
        Screen screen = new Screen(10, 3);
        screen.feed(E_ACUTE);
        check("a combining mark does not take a column", "0,1", cursor(screen));
        check("the cell holds the base and its mark", E_ACUTE, screen.cell(0, 0));
        check("the cell after it is untouched", " ", screen.cell(0, 1));
        check("the row is the original sequence", E_ACUTE, screen.line(0));
    }

    private static void marksDoNotAdvanceTheCursor() {
        Screen screen = new Screen(10, 3);
        String cluster = "a\u0301\u0327";
        screen.feed(cluster + "b");
        check("several marks attach to one base", cluster + "b", screen.line(0));
        check("only the base takes a column", "0,2", cursor(screen));
        check("the copied sequence has every code point", cluster + "b\n",
                screen.text());
    }

    private static void markAfterAWideGlyph() {
        Screen screen = new Screen(10, 3);
        screen.feed("日\u0301");
        check("a mark after a wide glyph lands on its base",
                "日\u0301", screen.cell(0, 0));
        check("the wide glyph still has a continuation cell", "true",
                String.valueOf(Cell.isContinuation(screen.cell(0, 1))));
        check("the mark took no third column", "0,2", cursor(screen));
    }

    private static void astralCharacterIsOneCell() {
        Screen screen = new Screen(10, 3);
        screen.feed(EMOJI);
        check("an emoji is one cell", EMOJI, screen.cell(0, 0));
        check("its second half is a continuation", "true",
                String.valueOf(Cell.isContinuation(screen.cell(0, 1))));
        check("the cursor moved by the assigned width", "0,2", cursor(screen));
        check("the row joins it as one glyph", EMOJI, screen.line(0));
        check("copy keeps the astral code point", EMOJI + "\n", screen.text());
    }

    private static void emojiAtTheRightEdge() {
        Screen row = new Screen(4, 3);
        row.feed("abc" + EMOJI);
        check("a wide emoji cannot share the last column", "abc", row.line(0));
        check("it moves to the next row whole", EMOJI, row.line(1));
        check("it starts in the first column there", "1,2", cursor(row));
        check("the row it left is wrapped", "true", String.valueOf(row.wrapped(0)));

        Screen moved = new Screen(4, 3);
        moved.feed("\u001b[1;4H" + EMOJI);
        check("an emoji placed at the last column wraps whole", EMOJI, moved.line(1));
        check("nothing is left in the last column it could not use", "",
                moved.line(0));
    }

    private static void widths() {
        check("an ASCII letter is one column", "1",
                String.valueOf(Width.of('e')));
        check("a CJK glyph is two columns", "2",
                String.valueOf(Width.of(0x65E5)));
        check("an emoji outside the BMP is two columns", "2",
                String.valueOf(Width.of(0x1F600)));
        check("a cell's width comes from its base", "2",
                String.valueOf(Cell.width(EMOJI)));
        check("a base with marks keeps the base width", "1",
                String.valueOf(Cell.width(E_ACUTE)));
    }

    private static void surrogatePairSplitAcrossFeeds() {
        Screen screen = new Screen(10, 3);
        screen.feed("a\uD83D");
        check("half a surrogate pair is not drawn", "a", screen.line(0));
        check("and does not move the cursor yet", "0,1", cursor(screen));
        screen.feed("\uDE00b");
        check("the two halves become one cell", "a" + EMOJI + "b", screen.line(0));
        check("the pair takes the width of one emoji", "0,4", cursor(screen));
    }

    private static void cursorMovement() {
        Screen screen = new Screen(10, 3);
        screen.feed(E_ACUTE + "X");
        check("the cursor is after the two cells", "0,2", cursor(screen));
        screen.feed("\u001b[2D");
        check("moving left crosses the cluster's cell as one", "0,0", cursor(screen));
        check("the cluster survived the move", E_ACUTE, screen.cell(0, 0));
        screen.feed("\u001b[1CY");
        check("writing after the move replaces the cell, not the mark",
                E_ACUTE + "Y", screen.line(0));
        check("the cursor follows the replacement", "0,2", cursor(screen));
    }

    private static void erase() {
        Screen chars = new Screen(10, 3);
        chars.feed(E_ACUTE + "X\u001b[1;1H\u001b[1X");
        check("ECH erases a cluster as one cell", " X", chars.line(0));

        Screen emoji = new Screen(10, 3);
        emoji.feed(EMOJI + "\u001b[1;1H\u001b[2X");
        check("ECH erases both cells of an emoji", "", emoji.line(0));
        check("both cells are blank afterwards", "  ",
                emoji.cell(0, 0) + emoji.cell(0, 1));

        Screen line = new Screen(10, 3);
        line.feed(E_ACUTE + EMOJI + "\u001b[2K");
        check("EL 2 erases the whole cluster row", "", line.line(0));

        Screen display = new Screen(10, 3);
        display.feed(E_ACUTE + EMOJI + "\u001b[2J");
        check("ED 2 clears clusters with the screen", "", display.text());
    }

    private static void eraseAtAWideGlyphBoundary() {
        // The cursor can sit between the two cells of a wide glyph. An erase that
        // starts or ends there must not leave the other half drawn on its own.
        Screen line = new Screen(4, 2);
        line.feed("a\u65e5b\u001b[1;3H\u001b[0K");
        check("EL 0 starting on a continuation clears its base", "a", line.line(0));
        check("both halves of the cut glyph are blank", "  ",
                line.cell(0, 1) + line.cell(0, 2));

        Screen lineBack = new Screen(4, 2);
        lineBack.feed("a\u65e5b\u001b[1;2H\u001b[1K");
        check("EL 1 ending on a wide base clears its continuation", "   b",
                lineBack.line(0));
        check("the orphaned continuation is blank", " ", lineBack.cell(0, 2));

        Screen display = new Screen(4, 2);
        display.feed("a\u65e5b\u001b[1;3H\u001b[0J");
        check("ED 0 starting on a continuation clears its base", "a",
                display.line(0));

        Screen displayBack = new Screen(4, 2);
        displayBack.feed("a\u65e5b\u001b[1;2H\u001b[1J");
        check("ED 1 ending on a wide base clears its continuation", "   b",
                displayBack.line(0));
    }

    private static void wrapping() {
        Screen screen = new Screen(3, 3);
        screen.feed("ab" + E_ACUTE);
        check("a mark attaches before the pending wrap", "ab" + E_ACUTE,
                screen.line(0));
        check("the cursor waits on the last column", "0,2", cursor(screen));
        screen.feed("f");
        check("the next base starts a new row", "f", screen.line(1));
        check("the wrapped row keeps its cluster", "true",
                String.valueOf(screen.wrapped(0)));
        check("copy joins the wrapped rows without a newline",
                "ab" + E_ACUTE + "f\n", screen.text());
    }

    private static void history() {
        Screen screen = new Screen(6, 2);
        screen.feed(E_ACUTE + EMOJI + "\r\nx\r\ny");
        check("the scrolled row keeps both clusters", E_ACUTE + EMOJI,
                screen.historyLine(0));
        check("its whole text has no replacement characters", "true",
                String.valueOf(screen.textWithHistory().indexOf('\uFFFD') < 0));

        Screen resized = new Screen(6, 2);
        resized.feed(E_ACUTE + EMOJI + "\r\nx\r\ny");
        resized.resize(8, 2);
        check("a history resize keeps the full sequence", E_ACUTE + EMOJI,
                resized.historyLine(0));
    }

    private static void resize() {
        Screen screen = new Screen(10, 3);
        screen.feed(E_ACUTE + EMOJI + "Z");
        screen.resize(6, 4);
        check("a resize keeps a marked cell", E_ACUTE, screen.cell(0, 0));
        check("a resize keeps an emoji whole", EMOJI, screen.cell(0, 1));
        check("the row is unchanged", E_ACUTE + EMOJI + "Z", screen.line(0));
        screen.resize(12, 4);
        check("widening keeps the clusters", E_ACUTE + EMOJI + "Z", screen.line(0));

        Screen cut = new Screen(6, 3);
        cut.feed("abc日");
        cut.resize(4, 3);
        check("a narrowed row drops the half of a wide glyph that no longer fits",
                "abc", cut.line(0));
    }

    private static void selection() {
        Screen screen = new Screen(10, 3);
        screen.feed(E_ACUTE + "X" + EMOJI + "Z");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 0);
        selection.extendTo(0, 2);
        check("a selection copies a cluster as its original code points",
                E_ACUTE + "X" + EMOJI, selection.text());
        selection.begin(0, 3);
        selection.extendTo(0, 4);
        check("starting on a continuation selects the whole glyph",
                EMOJI + "Z", selection.text());
        check("copied text has no replacement character", "true",
                String.valueOf(selection.text().indexOf('\uFFFD') < 0));

        Screen wrapped = new Screen(4, 3);
        wrapped.feed("ab" + E_ACUTE + "cd");
        Selection joined = new Selection(wrapped, new Viewport(wrapped));
        joined.begin(0, 0);
        joined.extendTo(1, 0);
        check("selection joins a wrapped row without splitting a cluster",
                "ab" + E_ACUTE + "cd", joined.text());
    }

    private static void accessibilityRowText() {
        Screen screen = new Screen(10, 3);
        screen.feed(E_ACUTE + EMOJI);
        // TerminalAccessibility publishes screen.line(row) for every row node;
        // the text has to be the original sequence, not a replacement.
        check("the row a reader hears keeps the base and mark", E_ACUTE + EMOJI,
                screen.line(0));
        check("the row has no replacement character", "true",
                String.valueOf(screen.line(0).indexOf('\uFFFD') < 0));
        check("the row has the original code point count", "3",
                String.valueOf(screen.line(0).codePointCount(0,
                        screen.line(0).length())));
    }
}
