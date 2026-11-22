"""Insert and delete, lines and characters, the way an editor does it.

The four results coexist on the screen, so one dump proves all of them: two
inserted lines push `line-two` down to row 4, two deleted lines pull `d-four`
up to row 11, ICH 2 opens two cells inside ABCDE, and DCH 1 closes one.
"""

import sys

out = sys.stdout
out.write("TTY-EDIT\n")
out.write("\x1b[H\x1b[2J")

# IL: two blank rows at row 2 move everything below down.
out.write("\x1b[1;1Hline-one")
out.write("\x1b[2;1Hline-two")
out.write("\x1b[3;1Hline-three")
out.write("\x1b[4;1Hline-four")
out.write("\x1b[2;1H\x1b[2L")

# DL: two lines gone at row 11 pull the rest up.
out.write("\x1b[10;1Hd-one")
out.write("\x1b[11;1Hd-two")
out.write("\x1b[12;1Hd-three")
out.write("\x1b[13;1Hd-four")
out.write("\x1b[11;1H\x1b[2M")

# ICH and DCH: two cells opened at column 2, then one closed again.
out.write("\x1b[16;1HABCDE")
out.write("\x1b[16;2H\x1b[2@")
out.write("\x1b[17;1HABCDE")
out.write("\x1b[17;2H\x1b[1P")
out.flush()

for typed in sys.stdin:
    if typed.strip() == "q":
        break
out.write("edit done\n")
out.flush()
