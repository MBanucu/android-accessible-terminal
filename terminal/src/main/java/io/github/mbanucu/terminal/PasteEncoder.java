package io.github.mbanucu.terminal;

/**
 * Wraps pasted text when a program asked for bracketed paste.
 *
 * <p>Mode 2004 tells the terminal to put {@code ESC [ 200 ~} before pasted
 * text and {@code ESC [ 201 ~} after it, so the program can tell a paste from
 * typing. An end marker that came in with the text is removed: otherwise the
 * paste could close its own bracket early and the rest would arrive as typed
 * input.
 */
final class PasteEncoder {

    static final String START = "\u001b[200~";
    static final String END = "\u001b[201~";

    private PasteEncoder() {
    }

    /** The bytes for a paste: unchanged when the mode is off, wrapped when it is on. */
    static String encode(String text, boolean bracketed) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!bracketed) {
            return text;
        }
        return START + text.replace(END, "") + END;
    }
}
