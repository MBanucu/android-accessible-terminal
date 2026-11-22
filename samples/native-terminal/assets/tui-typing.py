"""A curses form that reports what it received, letter for letter.

The suite types long lines into it back to back. Curses reads the pty a key at a
time, so the work the view does to hand text over is visible here: a dropped
character shortens a row, a repeated one lengthens it, and two lines that changed
places swap the rows. The key counter catches a byte that arrived twice without
changing what a line says - Enter, a stray control key, anything.

The rows are what the accessibility service reads, so they are plain text at the
left edge rather than a box the service would have to walk.
"""

import curses

QUIT = "/quit"
# One more row than the suite submits: a line that arrives but is not drawn looks
# exactly like a line that never arrived, and the suite would blame the wrong
# layer. Thus LINES >= submits + 1.
LINES = 4


def put(screen, y, x, text):
    """One row, clipped: the last cell of the last row raises instead of drawing."""
    try:
        screen.addstr(y, x, text)
    except curses.error:
        pass


def draw(screen, lines, typed, keys, others):
    screen.erase()
    rows, cols = screen.getmaxyx()
    put(screen, 0, 0, "CURSES-TYPING")
    for index in range(LINES):
        text = lines[index] if index < len(lines) else ""
        put(screen, 2 + index, 0, text[:max(0, cols - 1)])
    put(screen, 2 + LINES + 1, 0, "keys: %d" % keys)
    put(screen, 2 + LINES + 2, 0, "other: %s" % others)
    put(screen, 2 + LINES + 3, 0, "> " + typed[:max(0, cols - 3)])
    # Where the next character will land, the way a caret says it.
    try:
        screen.move(2 + LINES + 3, min(2 + len(typed), cols - 1))
    except curses.error:
        pass
    screen.refresh()


def main(screen):
    curses.curs_set(1)
    lines = []
    typed = ""
    keys = 0
    # A resize is delivered by getch as a key (KEY_RESIZE, 410). It is not one
    # that was typed, and the count is about typed keys, so it is skipped: the
    # view changes size once while the page starts, and counting it made the
    # total depend on whether that happened before or after the first frame.
    # Anything that is not a letter, an Enter or a backspace: when a count is one
    # too high, this row says which byte it was.
    others = []
    while True:
        draw(screen, lines, typed, keys, " ".join(others[-8:]))
        key = screen.getch()
        if key == getattr(curses, "KEY_RESIZE", 410):
            continue
        keys += 1
        if key in (10, 13, curses.KEY_ENTER):
            line = typed
            typed = ""
            if line.strip() == QUIT:
                break
            if line:
                lines.append("got %d %d %s" % (len(lines) + 1, len(line), line))
        elif key in (8, 127, curses.KEY_BACKSPACE):
            typed = typed[:-1]
        elif 32 <= key <= 126:
            typed += chr(key)
        else:
            others.append("%d" % key)


curses.wrapper(main)
print("curses typing left the alternate screen")
