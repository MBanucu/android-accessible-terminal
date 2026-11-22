"""A curses form that keeps repainting while it is typed into.

`tui-typing.py` reads one key at a time and is otherwise quiet. A real agent TUI
redraws itself many times a second, and a terminal that hands keys over on the
main thread does that work between the keys of a fast line - the moment a
keystroke is easiest to drop or to overtake the character before it.

So this page draws the same rows, but returns from `getch` every tenth of a
second, drains whatever keys arrived, and repaints. The frame counter says the
repainting kept happening while the lines were typed; the rows and the key count
say nothing was lost while it did.

A resize is delivered by getch as a key of its own; it is skipped, because the
count is of keys that were typed and the view changes size once while the page
starts.
"""

import curses
import time

QUIT = "/quit"
# One row more than the suite submits, so a line that arrived but was not drawn
# cannot pass for a line that never arrived.
LINES = 4
# A repaint every 100 ms: fast enough to overlap the typing, slow enough that the
# evidence file stays small.
FRAME = 0.1


def put(screen, y, x, text):
    """One row, clipped: the last cell of the last row raises instead of drawing."""
    try:
        screen.addstr(y, x, text)
    except curses.error:
        pass


def draw(screen, lines, typed, keys, frame):
    screen.erase()
    rows, cols = screen.getmaxyx()
    put(screen, 0, 0, "CURSES-LOAD-TYPING")
    for index in range(LINES):
        text = lines[index] if index < len(lines) else ""
        put(screen, 2 + index, 0, text[:max(0, cols - 1)])
    put(screen, 2 + LINES + 1, 0, "keys: %d" % keys)
    put(screen, 2 + LINES + 2, 0, "frame: %d" % frame)
    put(screen, 2 + LINES + 3, 0, "> " + typed[:max(0, cols - 3)])
    try:
        screen.move(2 + LINES + 3, min(2 + len(typed), cols - 1))
    except curses.error:
        pass
    screen.refresh()


def main(screen):
    curses.curs_set(1)
    screen.nodelay(True)
    lines = []
    typed = ""
    keys = 0
    frame = 0
    while True:
        draw(screen, lines, typed, keys, frame)
        frame += 1
        # Keys for one frame's worth of time, then draw again: the page is never
        # blocked in getch, which is the state the suite is testing against.
        until = time.monotonic() + FRAME
        while time.monotonic() < until:
            key = screen.getch()
            if key == -1:
                time.sleep(0.004)
                continue
            # A resize arrives as a key (KEY_RESIZE, 410) and was never typed.
            if key == getattr(curses, "KEY_RESIZE", 410):
                continue
            keys += 1
            if key in (10, 13, curses.KEY_ENTER):
                line = typed
                typed = ""
                if line.strip() == QUIT:
                    return
                if line:
                    lines.append("got %d %d %s" % (len(lines) + 1, len(line), line))
            elif key in (8, 127, curses.KEY_BACKSPACE):
                typed = typed[:-1]
            elif 32 <= key <= 126:
                typed += chr(key)


curses.wrapper(main)
print("curses typing under load left the alternate screen")
