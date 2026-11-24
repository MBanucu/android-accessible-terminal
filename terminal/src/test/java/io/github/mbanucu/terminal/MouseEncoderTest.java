package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the bytes a mouse event sends: every event kind, the three tracking
 * modes, SGR and urxvt coordinates, the default three-byte fallback, and the
 * modifier bits.
 */
public final class MouseEncoderTest {

    public static void main(String[] args) {
        sgrPressAndRelease();
        sgrMotion();
        sgrWheel();
        sgrModifiers();
        urxvtPressAndRelease();
        urxvtMotionAndWheel();
        urxvtModifiers();
        defaultEncoding();
        defaultEncodingClamps();
        modeFiltering();
        modeAndEncodingCombinations();

        TestSupport.report("mouse checks");
    }

    private static void sgrPressAndRelease() {
        check("SGR left press", "\033[<0;5;3M", encode(1000, 1006, MouseEncoder.PRESS,
                MouseEncoder.LEFT, 4, 2));
        check("SGR left release", "\033[<0;5;3m", encode(1000, 1006, MouseEncoder.RELEASE,
                MouseEncoder.LEFT, 4, 2));
        check("SGR middle press", "\033[<1;5;3M", encode(1000, 1006, MouseEncoder.PRESS,
                MouseEncoder.MIDDLE, 4, 2));
        check("SGR right press", "\033[<2;5;3M", encode(1000, 1006, MouseEncoder.PRESS,
                MouseEncoder.RIGHT, 4, 2));
        check("SGR coordinates are one-based", "\033[<0;1;1M",
                encode(1000, 1006, MouseEncoder.PRESS, MouseEncoder.LEFT, 0, 0));
        check("SGR carries a column the default form cannot", "\033[<0;301;101M",
                encode(1000, 1006, MouseEncoder.PRESS, MouseEncoder.LEFT, 300, 100));
    }

    private static void sgrMotion() {
        check("SGR drag keeps the held button and sets the motion bit", "\033[<32;5;3M",
                encode(1002, 1006, MouseEncoder.MOTION, MouseEncoder.LEFT, 4, 2));
        check("SGR any-motion with no button", "\033[<35;5;3M",
                encode(1003, 1006, MouseEncoder.MOTION, MouseEncoder.NO_BUTTON, 4, 2));
        check("SGR release after a drag uses m", "\033[<0;6;4m",
                encode(1002, 1006, MouseEncoder.RELEASE, MouseEncoder.LEFT, 5, 3));
    }

    private static void sgrWheel() {
        check("SGR wheel up", "\033[<64;5;3M", encode(1000, 1006, MouseEncoder.WHEEL,
                MouseEncoder.WHEEL_UP, 4, 2));
        check("SGR wheel down", "\033[<65;5;3M", encode(1000, 1006, MouseEncoder.WHEEL,
                MouseEncoder.WHEEL_DOWN, 4, 2));
        check("SGR wheel left", "\033[<66;5;3M", encode(1002, 1006, MouseEncoder.WHEEL,
                MouseEncoder.WHEEL_LEFT, 4, 2));
        check("SGR wheel right", "\033[<67;5;3M", encode(1003, 1006, MouseEncoder.WHEEL,
                MouseEncoder.WHEEL_RIGHT, 4, 2));
    }

    private static void sgrModifiers() {
        check("SGR shift adds 4", "\033[<4;5;3M", modifiers(1006, true, false, false));
        check("SGR alt adds 8", "\033[<8;5;3M", modifiers(1006, false, true, false));
        check("SGR ctrl adds 16", "\033[<16;5;3M", modifiers(1006, false, false, true));
        check("SGR all modifiers add 28", "\033[<28;5;3M", modifiers(1006, true, true, true));
        check("SGR wheel carries modifiers", "\033[<80;5;3M",
                MouseEncoder.encode(1000, 1006, MouseEncoder.WHEEL,
                        MouseEncoder.WHEEL_UP, 4, 2, false, false, true));
    }

    private static void urxvtPressAndRelease() {
        check("urxvt left press", "\033[32;5;3M", encode(1000, 1015, MouseEncoder.PRESS,
                MouseEncoder.LEFT, 4, 2));
        check("urxvt left release", "\033[35;5;3M", encode(1000, 1015, MouseEncoder.RELEASE,
                MouseEncoder.LEFT, 4, 2));
        check("urxvt middle press", "\033[33;5;3M", encode(1000, 1015, MouseEncoder.PRESS,
                MouseEncoder.MIDDLE, 4, 2));
        check("urxvt right press", "\033[34;5;3M", encode(1000, 1015, MouseEncoder.PRESS,
                MouseEncoder.RIGHT, 4, 2));
        check("urxvt coordinates stay one-based", "\033[32;1;1M",
                encode(1000, 1015, MouseEncoder.PRESS, MouseEncoder.LEFT, 0, 0));
        check("urxvt carries a column the default form cannot", "\033[32;301;101M",
                encode(1000, 1015, MouseEncoder.PRESS, MouseEncoder.LEFT, 300, 100));
    }

    private static void urxvtMotionAndWheel() {
        check("urxvt drag sets the motion bit", "\033[64;5;3M",
                encode(1002, 1015, MouseEncoder.MOTION, MouseEncoder.LEFT, 4, 2));
        check("urxvt any-motion with no button", "\033[67;5;3M",
                encode(1003, 1015, MouseEncoder.MOTION, MouseEncoder.NO_BUTTON, 4, 2));
        check("urxvt wheel up", "\033[96;5;3M", encode(1000, 1015, MouseEncoder.WHEEL,
                MouseEncoder.WHEEL_UP, 4, 2));
        check("urxvt wheel down", "\033[97;5;3M", encode(1000, 1015, MouseEncoder.WHEEL,
                MouseEncoder.WHEEL_DOWN, 4, 2));
    }

    private static void urxvtModifiers() {
        check("urxvt shift adds 4", "\033[36;5;3M", modifiers(1015, true, false, false));
        check("urxvt alt adds 8", "\033[40;5;3M", modifiers(1015, false, true, false));
        check("urxvt ctrl adds 16", "\033[48;5;3M", modifiers(1015, false, false, true));
        check("urxvt all modifiers add 28", "\033[60;5;3M", modifiers(1015, true, true, true));
    }

    private static void defaultEncoding() {
        check("the default form is CSI M and three bytes", "\033[M\u0020\u0021\u0021",
                encode(1000, 0, MouseEncoder.PRESS, MouseEncoder.LEFT, 0, 0));
        check("the default release uses button 3", "\033[M\u0023\"\"",
                encode(1000, 0, MouseEncoder.RELEASE, MouseEncoder.LEFT, 1, 1));
        check("the default wheel is a printable byte", "\033[M\u0060\u0021\u0021",
                encode(1000, 0, MouseEncoder.WHEEL, MouseEncoder.WHEEL_UP, 0, 0));
    }

    private static void defaultEncodingClamps() {
        String past = MouseEncoder.encode(1000, 0, MouseEncoder.PRESS,
                MouseEncoder.LEFT, 500, 500, false, false, false);
        check("the default form clamps a column past 222", "255",
                String.valueOf((int) past.charAt(4)));
        check("the default form clamps a row past 222", "255",
                String.valueOf((int) past.charAt(5)));
    }

    private static void modeFiltering() {
        check("no tracking mode reports no press", "",
                encode(0, 1006, MouseEncoder.PRESS, MouseEncoder.LEFT, 1, 1));
        check("no tracking mode reports no wheel", "",
                encode(0, 1006, MouseEncoder.WHEEL, MouseEncoder.WHEEL_UP, 1, 1));
        for (int mode : new int[] {1000, 1002, 1003}) {
            check("mode " + mode + " reports a press", "true",
                    String.valueOf(MouseEncoder.reports(mode, MouseEncoder.PRESS, MouseEncoder.LEFT)));
            check("mode " + mode + " reports a release", "true",
                    String.valueOf(MouseEncoder.reports(mode, MouseEncoder.RELEASE, MouseEncoder.LEFT)));
            check("mode " + mode + " reports the wheel", "true",
                    String.valueOf(MouseEncoder.reports(mode, MouseEncoder.WHEEL, MouseEncoder.WHEEL_UP)));
        }
        check("mode 1000 ignores motion", "false",
                String.valueOf(MouseEncoder.reports(1000, MouseEncoder.MOTION, MouseEncoder.LEFT)));
        check("mode 1002 reports motion with a button", "true",
                String.valueOf(MouseEncoder.reports(1002, MouseEncoder.MOTION, MouseEncoder.LEFT)));
        check("mode 1002 ignores motion with no button", "false",
                String.valueOf(MouseEncoder.reports(1002, MouseEncoder.MOTION, MouseEncoder.NO_BUTTON)));
        check("mode 1003 reports motion with no button", "true",
                String.valueOf(MouseEncoder.reports(1003, MouseEncoder.MOTION, MouseEncoder.NO_BUTTON)));
        check("an unknown event is not reported", "",
                MouseEncoder.encode(1003, 1006, 99, MouseEncoder.LEFT,
                        1, 1, false, false, false));
    }

    private static void modeAndEncodingCombinations() {
        int[] modes = {1000, 1002, 1003};
        int[] encodings = {0, 1006, 1015};
        for (int mode : modes) {
            for (int encoding : encodings) {
                check("mode " + mode + " encoding " + encoding + " press", expectedPress(encoding),
                        encode(mode, encoding, MouseEncoder.PRESS, MouseEncoder.LEFT, 0, 0));
                check("mode " + mode + " encoding " + encoding + " release",
                        expectedRelease(encoding),
                        encode(mode, encoding, MouseEncoder.RELEASE, MouseEncoder.LEFT, 0, 0));
                check("mode " + mode + " encoding " + encoding + " wheel",
                        expectedWheel(encoding),
                        encode(mode, encoding, MouseEncoder.WHEEL, MouseEncoder.WHEEL_UP, 0, 0));
            }
        }
    }

    private static String expectedPress(int encoding) {
        if (encoding == 1006) {
            return "\033[<0;1;1M";
        }
        if (encoding == 1015) {
            return "\033[32;1;1M";
        }
        return "\033[M\u0020\u0021\u0021";
    }

    private static String expectedRelease(int encoding) {
        if (encoding == 1006) {
            return "\033[<0;1;1m";
        }
        if (encoding == 1015) {
            return "\033[35;1;1M";
        }
        return "\033[M\u0023\u0021\u0021";
    }

    private static String expectedWheel(int encoding) {
        if (encoding == 1006) {
            return "\033[<64;1;1M";
        }
        if (encoding == 1015) {
            return "\033[96;1;1M";
        }
        return "\033[M\u0060\u0021\u0021";
    }

    private static String modifiers(int encoding, boolean shift, boolean alt, boolean ctrl) {
        return MouseEncoder.encode(1000, encoding, MouseEncoder.PRESS,
                MouseEncoder.LEFT, 4, 2, shift, alt, ctrl);
    }

    private static String encode(int mode, int encoding, int event, int button, int col, int row) {
        return MouseEncoder.encode(mode, encoding, event, button, col, row,
                false, false, false);
    }
}
