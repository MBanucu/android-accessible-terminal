"""Every line the pty delivers, echoed back with its number and length.

Nothing is buffered here beyond what the line discipline does: the program
prints what it read, so a dropped letter, a repeated one or two lines arriving
out of order all show up as rows. The harness sends long lines back to back and
reads these rows.
"""

import sys

out = sys.stdout
out.write("TTY-TYPING\n")
out.flush()

seen = 0
for typed in sys.stdin:
    text = typed.rstrip("\n")
    if text == "q":
        break
    seen += 1
    out.write("got %d %d %s\n" % (seen, len(text), text))
    out.flush()

out.write("typing done after %d\n" % seen)
out.flush()
