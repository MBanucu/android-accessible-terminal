package io.github.mbanucu.terminal;

import static io.github.mbanucu.terminal.TestSupport.check;

/**
 * Checks the bytes a paste sends with bracketed-paste mode on and off, and
 * that a program's end marker cannot close the paste early.
 */
public final class PasteEncoderTest {

    public static void main(String[] args) {
        modeOffSendsTextUnchanged();
        modeOnWrapsOneBlock();
        multilinePasteIsOneBlock();
        embeddedEndMarkerIsRemoved();
        emptyTextSendsNothing();
        theScreenModeChoosesTheForm();

        TestSupport.report("paste checks");
    }

    private static void modeOffSendsTextUnchanged() {
        check("mode 2004 off sends the text unchanged", "one\ntwo",
                PasteEncoder.encode("one\ntwo", false));
        check("mode 2004 off leaves an end marker alone", "a\033[201~b",
                PasteEncoder.encode("a\033[201~b", false));
    }

    private static void modeOnWrapsOneBlock() {
        check("mode 2004 on wraps the text", "\033[200~hello\033[201~",
                PasteEncoder.encode("hello", true));
        check("the wrapped block starts once", "1",
                String.valueOf(count(PasteEncoder.encode("hello", true), PasteEncoder.START)));
        check("the wrapped block ends once", "1",
                String.valueOf(count(PasteEncoder.encode("hello", true), PasteEncoder.END)));
    }

    private static void multilinePasteIsOneBlock() {
        String bytes = PasteEncoder.encode("one\r\ntwo\nthree", true);
        check("a multiline paste is one block", "\033[200~one\r\ntwo\nthree\033[201~", bytes);
        check("a multiline paste has one end marker", "1",
                String.valueOf(count(bytes, PasteEncoder.END)));
    }

    private static void embeddedEndMarkerIsRemoved() {
        String bytes = PasteEncoder.encode("safe\033[201~rm -rf /", true);
        check("an end marker in the text is removed", "\033[200~saferm -rf /\033[201~", bytes);
        check("the paste still has one end marker", "1",
                String.valueOf(count(bytes, PasteEncoder.END)));
    }

    private static void emptyTextSendsNothing() {
        check("an empty paste sends nothing", "", PasteEncoder.encode("", true));
        check("a null paste sends nothing", "", PasteEncoder.encode(null, true));
    }

    private static void theScreenModeChoosesTheForm() {
        Screen screen = new Screen(20, 4);
        check("the screen starts with bracketed paste off", "text",
                PasteEncoder.encode("text", screen.bracketedPaste()));
        screen.feed("\u001b[?2004h");
        check("the screen's mode wraps a paste", "\033[200~text\033[201~",
                PasteEncoder.encode("text", screen.bracketedPaste()));
    }

    private static int count(String text, String part) {
        int count = 0;
        int at = 0;
        while ((at = text.indexOf(part, at)) >= 0) {
            count++;
            at += part.length();
        }
        return count;
    }
}
