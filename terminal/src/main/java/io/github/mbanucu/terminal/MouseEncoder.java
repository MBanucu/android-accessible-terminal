package io.github.mbanucu.terminal;

/**
 * The bytes a mouse event sends to a program that asked for mouse tracking.
 *
 * <p>Three tracking modes decide what is reported: 1000 sends presses,
 * releases and the wheel; 1002 also sends motion while a button is down; 1003
 * sends all motion. Two extended encodings are supported, SGR (1006) and
 * urxvt (1015), and a program that enables a tracking mode without an encoding
 * gets xterm's default three-byte form.
 *
 * <p>Coordinates here are zero-based cells, as the view has them; each form
 * adds the one-based or 32-based offset its protocol requires. The wheel is a
 * press with the button code 64-67 and has no release, as xterm sends it.
 */
final class MouseEncoder {

    /** The three buttons a touch or a mouse can press. */
    static final int LEFT = 0;
    static final int MIDDLE = 1;
    static final int RIGHT = 2;
    /** A report with no button down: a release, or motion in mode 1003. */
    static final int NO_BUTTON = 3;

    /** Wheel buttons, already in xterm's event-code space. */
    static final int WHEEL_UP = 64;
    static final int WHEEL_DOWN = 65;
    static final int WHEEL_LEFT = 66;
    static final int WHEEL_RIGHT = 67;

    /** The kinds of report, before the button and modifiers are folded in. */
    static final int PRESS = 0;
    static final int RELEASE = 1;
    static final int MOTION = 2;
    static final int WHEEL = 3;

    private static final int MOTION_FLAG = 32;
    private static final int SHIFT_BIT = 4;
    private static final int ALT_BIT = 8;
    private static final int CTRL_BIT = 16;
    /** The default, three-byte protocol cannot name a cell past this one. */
    private static final int MAX_X10_CELL = 222;

    private MouseEncoder() {
    }

    /**
     * Whether a tracking mode reports an event, given the button in hand.
     *
     * <p>Motion in mode 1002 is a drag and needs a held button; motion in 1003
     * is reported whether or not one is down.
     */
    static boolean reports(int mode, int event, int button) {
        switch (event) {
            case PRESS:
            case RELEASE:
            case WHEEL:
                return mode == 1000 || mode == 1002 || mode == 1003;
            case MOTION:
                return mode == 1003 || (mode == 1002 && button != NO_BUTTON);
            default:
                return false;
        }
    }

    /** The sequence for one event, or "" when the mode does not report it. */
    static String encode(int mode, int encoding, int event, int button,
            int col, int row, boolean shift, boolean alt, boolean ctrl) {
        if (!reports(mode, event, button)) {
            return "";
        }
        int modifiers = (shift ? SHIFT_BIT : 0) + (alt ? ALT_BIT : 0)
                + (ctrl ? CTRL_BIT : 0);
        // SGR names the released button and uses the final m; the older forms
        // only have the button-3 code, so the button is lost there.
        int code = (event == RELEASE && encoding != 1006 ? NO_BUTTON : button) + modifiers;
        if (event == MOTION) {
            code += MOTION_FLAG;
        }
        if (encoding == 1006) {
            return sgr(code, col, row, event == RELEASE);
        }
        if (encoding == 1015) {
            return urxvt(code, col, row);
        }
        return x10(code, col, row);
    }

    /** SGR (1006): CSI < Cb ; Cx ; Cy M, with m for a release. */
    private static String sgr(int code, int col, int row, boolean release) {
        return "\u001b[<" + code + ";" + (col + 1) + ";" + (row + 1)
                + (release ? 'm' : 'M');
    }

    /**
     * urxvt (1015): CSI Cb ; Cx ; Cy M.
     *
     * <p>The button value keeps the 32 the X10 byte needs, but the coordinates
     * are decimal and one-based, so printing them needs no offset.
     */
    private static String urxvt(int code, int col, int row) {
        return "\u001b[" + (code + 32) + ";" + (col + 1) + ";" + (row + 1) + "M";
    }

    /** The default protocol: CSI M followed by three bytes, each value + 32. */
    private static String x10(int code, int col, int row) {
        int clampedCol = Math.max(0, Math.min(MAX_X10_CELL, col));
        int clampedRow = Math.max(0, Math.min(MAX_X10_CELL, row));
        return "\u001b[M" + (char) (code + 32) + (char) (clampedCol + 33)
                + (char) (clampedRow + 33);
    }
}
