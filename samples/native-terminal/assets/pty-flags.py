"""What the pty gave this program: its device, its descriptors and its flags.

The view owns no terminal of its own - the program runs under `script` inside
Termux - so every answer here comes from the pty that `script` allocated. The
screen shows them as rows the accessibility service can read.
"""

import fcntl
import os
import struct
import sys
import termios

out = sys.stdout


def line(text):
    out.write(text + "\n")
    out.flush()


line("PTY-FLAGS")
line("tty: %s" % os.ttyname(0))
line("isatty: %d %d %d" % (os.isatty(0), os.isatty(1), os.isatty(2)))

flags = termios.tcgetattr(0)[3]
line("termios: icanon=%d echo=%d isig=%d" % (
    1 if flags & termios.ICANON else 0,
    1 if flags & termios.ECHO else 0,
    1 if flags & termios.ISIG else 0))

# TIOCGWINSZ: rows and columns first, then the pixel size nobody set.
rows, cols = struct.unpack("hhhh", fcntl.ioctl(0, termios.TIOCGWINSZ, b"\0" * 8))[:2]
line("winsize: %dx%d" % (cols, rows))

# A line at a time, so the harness can stop the program the way a user would.
for typed in sys.stdin:
    if typed.strip() == "q":
        break
line("pty flags done")
