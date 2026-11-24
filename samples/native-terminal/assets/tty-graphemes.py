"""Grapheme clusters on the cell grid, for the device suite.

Each cluster is followed by a marker placed at a fixed column, so the row the
accessibility node carries proves how many cells the cluster took: `e` plus
COMBINING ACUTE ACCENT makes one cell, and the smiley is one glyph two columns
wide. The last pair puts a smiley on column 80, where it has to wrap whole
instead of being cut in half.
"""

import sys

out = sys.stdout
out.write("TTY-GRAPHEMES\n")
out.write("\x1b[H\x1b[2J")

# ANSI CUP is one-based: the cluster starts at column 1, END at column 5.
out.write("\x1b[3;1H" + "e\u0301")
out.write("\x1b[3;5HEND")

# The emoji is U+1F600, outside the BMP: one code point, two columns.
out.write("\x1b[4;1H\U0001F600")
out.write("\x1b[4;5HEND")

# Column 80 cannot hold a two-column glyph: it belongs whole on row 6.
out.write("\x1b[5;80H\U0001F600")
out.write("\x1b[6;5HEND")

out.write("\x1b[8;1H" + "e+U+0301  smiley=U+1F600  q quits")
out.flush()

for typed in sys.stdin:
    if typed.strip() == "q":
        break
out.write("graphemes done\n")
out.flush()
