"""Horizontal tabs: the terminal's own stops, every eight columns.

Nothing here asks the terminal a question - the rows prove where the cursor
landed, because a tab that was dropped would put the letter one column after
the one before it instead of on the next stop.
"""

import sys

out = sys.stdout
out.write("TTY-TABS\n")
out.write("\x1b[H\x1b[2J")
out.write("A\tB\n")
out.write("ab\tcd\n")
out.flush()

for typed in sys.stdin:
    if typed.strip() == "q":
        break
out.write("tabs done\n")
out.flush()
