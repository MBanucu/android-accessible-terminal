package io.github.mbanucu.a11yprobe;

/**
 * What one probe command answers: a code and a payload.
 *
 * <p>{@link ProbeProvider} returns these as the {@code code} and {@code data}
 * members of its reply bundle; the broadcast receiver logs {@link #logLine()},
 * which is the short form for a command that also returns something long (a
 * dump returns the tree, but its log line stays "dumped tree.txt (1234 chars)").
 *
 * <p>No Android type is involved, so {@code a11y-probe/test.sh} exercises the
 * codes and sentences offline.
 */
public final class ProbeReply {

    /** The command did what it was asked to. */
    public static final int OK = 0;
    /** The command was malformed, could not run, or did not do what it was asked. */
    public static final int BAD_REQUEST = 400;

    public final int code;
    public final String data;
    /** The one-line form for a log; null means {@link #data}. */
    public final String summary;
    /** For a wait or a result: matched, timeout, started, pending; else null. */
    public final String state;

    private ProbeReply(int code, String data, String summary, String state) {
        if (data == null) {
            throw new IllegalArgumentException("a reply needs a payload");
        }
        this.code = code;
        this.data = data;
        this.summary = summary;
        this.state = state;
    }

    public static ProbeReply ok(String data) {
        return new ProbeReply(OK, data, null, null);
    }

    /** A successful reply whose log line is shorter than its payload. */
    public static ProbeReply ok(String data, String summary) {
        return new ProbeReply(OK, data, summary, null);
    }

    /** A successful wait whose state the caller can read without parsing the text. */
    public static ProbeReply state(String data, String state) {
        return new ProbeReply(OK, data, null, state);
    }

    public static ProbeReply started(String data, String state) {
        return new ProbeReply(OK, data, null, state);
    }

    /** A failure: code 400 and one sentence saying what was wrong. */
    public static ProbeReply error(String sentence) {
        return new ProbeReply(BAD_REQUEST, sentence, null, null);
    }

    public boolean ok() {
        return code == OK;
    }

    public String logLine() {
        return summary == null ? data : summary;
    }

    @Override
    public String toString() {
        return "code=" + code + " data=" + data;
    }
}
