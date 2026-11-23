package io.github.mbanucu.a11yprobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch commands: several commands in one {@code content call}, one per line,
 * executed in order with one result line each.
 *
 * <pre>
 * find OK
 * click OK
 * wait done timeout=3000
 * </pre>
 *
 * <p>A line is {@code METHOD [KEY=VALUE ...]}; the text before the first
 * {@code key=} is the positional argument, so {@code find OK} and
 * {@code find text=OK} are the same. A value runs to the next {@code key=} or
 * the end of the line, which lets it contain spaces; wrap it in double quotes if
 * it contains something that looks like another key. Blank lines and lines
 * starting with {@code #} are skipped.
 */
public final class ProbeBatch {

    /** One parsed line: a method and its arguments. */
    public static final class Line {
        public final String method;
        public final ProbeArgs args;

        Line(String method, ProbeArgs args) {
            this.method = method;
            this.args = args;
        }
    }

    private ProbeBatch() {
    }

    public static List<Line> parse(String body) {
        List<Line> lines = new ArrayList<>();
        if (body == null) {
            return lines;
        }
        for (String raw : body.split("\\r?\\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int split = 0;
            while (split < line.length() && !Character.isWhitespace(line.charAt(split))) {
                split++;
            }
            String method = line.substring(0, split);
            String rest = line.substring(split).strip();
            lines.add(new Line(method, parseArgs(rest)));
        }
        return lines;
    }

    private static ProbeArgs parseArgs(String rest) {
        if (rest.isEmpty()) {
            return ProbeArgs.of(null);
        }
        int first = findKey(rest, 0);
        String positional = first < 0 ? unquote(rest) : unquote(rest.substring(0, first).strip());
        Map<String, String> values = new LinkedHashMap<>();
        int at = first;
        while (at >= 0) {
            int equals = rest.indexOf('=', at);
            String key = rest.substring(at, equals);
            int valueStart = equals + 1;
            int next = findKey(rest, valueStart);
            String value = (next < 0
                    ? rest.substring(valueStart)
                    : rest.substring(valueStart, next)).strip();
            values.put(key, unquote(value));
            at = next;
        }
        return new ProbeArgs(positional, values);
    }

    /**
     * The start of the next {@code key=} at a word boundary, skipping anything
     * inside double quotes.
     */
    private static int findKey(String text, int from) {
        boolean quoted = false;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            boolean boundary = i == 0 || Character.isWhitespace(text.charAt(i - 1));
            if (boundary && isKey(text, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isKey(String text, int start) {
        char first = text.charAt(start);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                break;
            }
            i++;
        }
        return i < text.length() && text.charAt(i) == '=';
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            String inner = value.substring(1, value.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    /**
     * One result line for a batch reply: {@code N code=C data=... state=...}.
     *
     * <p>Newlines in the payload are escaped, so a batch of three commands is
     * three lines even when one of them is a dump.
     */
    public static String resultLine(int number, ProbeReply reply) {
        StringBuilder out = new StringBuilder();
        out.append(number).append(" code=").append(reply.code)
                .append(" data=").append(escape(reply.data));
        if (reply.state != null) {
            out.append(" state=").append(reply.state);
        }
        return out.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
