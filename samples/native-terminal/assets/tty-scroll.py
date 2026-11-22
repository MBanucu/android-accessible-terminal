"""A scrolling region: only the rows inside it move.

Rows 1 and 4 sit outside the region set by DECSTBM, so they are the control:
if the line feed at the bottom of the region scrolled the whole screen, they
would move too.
"""

import sys

out = sys.stdout
out.write("TTY-SCROLL\n")
out.write("\x1b[H\x1b[2J")
out.write("\x1b[1;1Htop")
out.write("\x1b[2;1Hin-1")
out.write("\x1b[3;1Hin-2")
out.write("\x1b[4;1Hbottom")
out.write("\x1b[2;3r")   # rows 2 and 3 are the region
out.write("\x1b[3;1H")   # the bottom of it
out.write("\n")          # one line feed: in-2 moves up, the region scrolls
out.write("\x1b[r")      # the whole screen again
out.flush()

for typed in sys.stdin:
    if typed.strip() == "q":
        break
out.write("scroll done\n")
out.flush()
