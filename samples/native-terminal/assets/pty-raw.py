"""One byte, with no line discipline in the way.

`tty.setraw` clears ICANON, ECHO and ISIG, so a key arrives the moment it is
typed instead of waiting for Enter and being echoed by the kernel. It is the
difference between a program that can read arrow keys and one that only ever
sees complete lines.

Raw mode also turns off output processing, which is why every line here ends in
CR LF rather than a bare newline.
"""

import os
import sys
import termios
import tty

out = sys.stdout
out.write("PTY-RAW\n")
out.flush()

tty.setraw(0)
flags = termios.tcgetattr(0)[3]
out.write("raw: icanon=%d echo=%d isig=%d\r\n" % (
    1 if flags & termios.ICANON else 0,
    1 if flags & termios.ECHO else 0,
    1 if flags & termios.ISIG else 0))
out.flush()

byte = os.read(0, 1)
out.write("raw-got: %d\r\n" % byte[0])
out.flush()
