package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the cell range a reader selects and the text it copies.
 *
 * <p>Rows are viewport rows, so a selection can start in scrollback and end on
 * the live grid. The range is inclusive of both ends, a backwards drag reads
 * the same, and a row the terminal wrapped is joined to its continuation.
 */
public final class SelectionTest {

    public static void main(String[] args) {
        startsEmpty();
        selectsWithinOneRow();
        aBackwardsDragReadsTheSame();
        spansRowsWithHardBreaks();
        joinsWrappedRows();
        reachesIntoHistory();
        clampsToTheGrid();
        clearDropsIt();
        selectsAWord();
        blankSelectsTheLine();
        followsTheContentWhenScrolled();
        clampsAfterAResize();
        clampsWhenHistoryTrims();
        reportsBothEndsForTheHandles();
        aHandleDragReachesHistory();

        TestSupport.report("selection checks");
    }

    private static void startsEmpty() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        check("a new selection is empty", "true", String.valueOf(selection.isEmpty()));
        check("an empty selection copies nothing", "", selection.text());
        check("it contains no cell", "false", String.valueOf(selection.contains(0, 0)));
    }

    private static void selectsWithinOneRow() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 1);
        selection.extendTo(0, 4);
        check("a one-row selection copies its cells", "bcde", selection.text());
        check("the start cell is in the selection", "true",
                String.valueOf(selection.contains(0, 1)));
        check("the end cell is in the selection", "true",
                String.valueOf(selection.contains(0, 4)));
        check("the cell after the end is not", "false",
                String.valueOf(selection.contains(0, 5)));
        check("the cell before the start is not", "false",
                String.valueOf(selection.contains(0, 0)));
    }

    private static void aBackwardsDragReadsTheSame() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 4);
        selection.extendTo(0, 1);
        check("a backwards drag copies the same cells", "bcde", selection.text());
    }

    private static void spansRowsWithHardBreaks() {
        Screen screen = new Screen(10, 3);
        screen.feed("one\r\ntwo\r\nthree");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 1);
        selection.extendTo(2, 2);
        check("a multi-row selection keeps the hard line breaks", "ne\ntwo\nthr",
                selection.text());
    }

    private static void joinsWrappedRows() {
        Screen screen = new Screen(5, 3);
        screen.feed("abcdef\r\ngh");
        Viewport viewport = new Viewport(screen);
        Selection selection = new Selection(screen, viewport);
        check("the viewport reports the wrapped row", "true",
                String.valueOf(viewport.wrapped(0)));
        selection.begin(0, 0);
        selection.extendTo(2, 1);
        check("copying joins a wrapped row without a newline", "abcdef\ngh",
                selection.text());
    }

    private static void reachesIntoHistory() {
        Screen screen = new Screen(5, 2);
        screen.feed("abcdef\r\n");
        Viewport viewport = new Viewport(screen);
        viewport.scrollBy(1);
        Selection selection = new Selection(screen, viewport);
        selection.begin(0, 0);
        selection.extendTo(1, 0);
        check("a selection can begin in scrollback", "abcdef", selection.text());
    }

    private static void clampsToTheGrid() {
        Screen screen = new Screen(10, 3);
        screen.feed("one\r\ntwo\r\nthree");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(-4, -2);
        selection.extendTo(99, 99);
        check("a selection clamps to the grid", "one\ntwo\nthree", selection.text());
        check("and contains its first cell", "true",
                String.valueOf(selection.contains(0, 0)));
        check("and its last cell", "true",
                String.valueOf(selection.contains(2, 9)));
    }

    private static void clearDropsIt() {
        Screen screen = new Screen(10, 3);
        screen.feed("abcdef");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 0);
        selection.extendTo(1, 0);
        selection.clear();
        check("clearing a selection empties it", "true",
                String.valueOf(selection.isEmpty()));
        check("and copies nothing", "", selection.text());
    }

    private static void selectsAWord() {
        Screen screen = new Screen(30, 3);
        screen.feed("one two three");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.selectWordOrLineAt(0, 5);
        check("a long press selects the word under it", "two", selection.text());
        check("the word starts at its first letter", "4",
                String.valueOf(selection.startCol()));
        check("and ends at its last", "6", String.valueOf(selection.endCol()));
        check("a cell inside the word is selected", "true",
                String.valueOf(selection.contains(0, 5)));
        check("the space before it is not", "false",
                String.valueOf(selection.contains(0, 3)));
        check("the word ends at the next space", "false",
                String.valueOf(selection.contains(0, 7)));
    }

    private static void blankSelectsTheLine() {
        Screen screen = new Screen(12, 3);
        screen.feed("hello world");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.selectWordOrLineAt(0, 5);
        check("a long press on a space selects the whole line", "hello world",
                selection.text());
        check("the line starts at column zero", "0",
                String.valueOf(selection.startCol()));
        check("the line ignores the blank cells after it", "10",
                String.valueOf(selection.endCol()));
    }

    private static void followsTheContentWhenScrolled() {
        Screen screen = new Screen(10, 2);
        screen.feed("one\r\ntwo\r\nthree\r\nfour");
        Viewport viewport = new Viewport(screen);
        Selection selection = new Selection(screen, viewport);
        selection.begin(0, 0);
        selection.extendTo(1, 4);
        check("a selection copies the live rows", "three\nfour", selection.text());
        viewport.scrollBy(1);
        check("scrolling does not move the selected cells", "three\nfour",
                selection.text());
        check("the first selected row is now one row down", "1",
                String.valueOf(selection.startRow()));
        check("and the cell there is still selected", "true",
                String.valueOf(selection.contains(1, 0)));
        check("the row that came in above it is not", "false",
                String.valueOf(selection.contains(0, 0)));
        viewport.scrollTo(2);
        check("scrolling to the oldest row still selects the same text", "three\nfour",
                selection.text());
    }

    private static void clampsAfterAResize() {
        Screen screen = new Screen(10, 3);
        screen.feed("one\r\ntwo\r\nthree");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(0, 1);
        selection.extendTo(2, 3);
        check("the selection starts across three rows", "ne\ntwo\nthre",
                selection.text());
        screen.resize(5, 2);
        selection.clamp();
        check("a shorter grid drops the row it no longer has", "ne\ntwo",
                selection.text());
        check("the selected start cell is still inside", "true",
                String.valueOf(selection.contains(0, 1)));
        check("the selected end cell is still inside", "true",
                String.valueOf(selection.contains(1, 2)));
        check("the removed row is not", "false",
                String.valueOf(selection.contains(2, 0)));

        Screen narrow = new Screen(10, 2);
        narrow.feed("abcdefghij");
        Selection clipped = new Selection(narrow, new Viewport(narrow));
        clipped.begin(0, 8);
        clipped.extendTo(0, 9);
        narrow.resize(5, 2);
        clipped.clamp();
        check("a narrower grid clamps the columns", "e", clipped.text());
    }

    private static void clampsWhenHistoryTrims() {
        Screen screen = new Screen(4, 2, 2);
        screen.feed("a\r\nb\r\nc\r\nd\r\ne\r\nf\r\n");
        Viewport viewport = new Viewport(screen);
        viewport.scrollTo(viewport.maxOffset());
        Selection selection = new Selection(screen, viewport);
        selection.begin(0, 0);
        selection.extendTo(0, 0);
        check("the selection starts on the oldest history row", "d",
                selection.text());
        screen.feed("g\r\n");
        viewport.clamp();
        selection.clamp();
        check("a trimmed row does not leave the selection on a replacement", "e",
                selection.text());
        check("and the clamped selection is still visible", "true",
                String.valueOf(selection.contains(0, 0)));
    }

    private static void reportsBothEndsForTheHandles() {
        Screen screen = new Screen(10, 3);
        screen.feed("one\r\ntwo");
        Selection selection = new Selection(screen, new Viewport(screen));
        selection.begin(1, 5);
        selection.extendTo(0, 2);
        check("a backwards range reports its first cell first", "0,2",
                selection.startRow() + "," + selection.startCol());
        check("and its last cell last", "1,5",
                selection.endRow() + "," + selection.endCol());
        check("the stored anchor is the last cell, not the first", "false",
                String.valueOf(selection.anchorIsFirst()));
    }

    private static void aHandleDragReachesHistory() {
        Screen screen = new Screen(10, 2);
        screen.feed("one\r\ntwo\r\nthree\r\nfour");
        Viewport viewport = new Viewport(screen);
        Selection selection = new Selection(screen, viewport);
        selection.begin(1, 3);
        viewport.scrollTo(2);
        selection.setAnchor(0, 0);
        check("a handle dragged up reaches the scrollback", "one\ntwo\nthree\nfour",
                selection.text());
        check("the range starts on the history row", "0",
                String.valueOf(selection.startRow()));
        check("and ends below the visible viewport", "3",
                String.valueOf(selection.endRow()));
    }
}
