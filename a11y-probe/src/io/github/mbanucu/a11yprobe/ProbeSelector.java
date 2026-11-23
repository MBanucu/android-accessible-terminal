package io.github.mbanucu.a11yprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a command names a node: {@code text=}, {@code desc=}, {@code id=} or
 * {@code index=}, with a bare needle as the old {@code find}'s form.
 *
 * <pre>
 * text=OK              text contains "OK"
 * desc=Close           content description contains "Close"
 * id=io.example:id/ok  resource id contains "io.example:id/ok"
 * index=3              the fourth node in the target window's dump order
 * OK                   text, description or id contains "OK" (one release)
 * </pre>
 *
 * <p>A named selector is deliberately not a regular expression: a test says
 * what it means, and a miss lists the closest nodes instead of silently
 * matching the wrong one. No Android type is involved, so the parser, matching
 * and candidate ranking are tested by {@code a11y-probe/test.sh}.
 */
public final class ProbeSelector {

    public enum Kind { ANY, TEXT, DESC, ID, INDEX }

    private static final int CANDIDATES = 3;

    private final Kind kind;
    private final String value;
    private final int index;
    private final String raw;

    private ProbeSelector(Kind kind, String value, int index, String raw) {
        this.kind = kind;
        this.value = value;
        this.index = index;
        this.raw = raw;
    }

    /** A text selector, for the text assertions' derived form. */
    public static ProbeSelector text(String value) {
        return new ProbeSelector(Kind.TEXT, value, -1, "text=" + value);
    }

    /**
     * Parses one selector.
     *
     * @throws IllegalArgumentException for nothing, an empty named value, a
     *         non-numeric or negative index, or an unknown {@code word=} prefix
     */
    public static ProbeSelector parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("a selector is required");
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("a selector cannot be empty");
        }
        int equals = text.indexOf('=');
        if (equals > 0) {
            String prefix = text.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String rest = text.substring(equals + 1).trim();
            switch (prefix) {
                case "text":
                    if (rest.isEmpty()) {
                        throw new IllegalArgumentException("text= needs a value");
                    }
                    return new ProbeSelector(Kind.TEXT, rest, -1, "text=" + rest);
                case "desc":
                case "description":
                    if (rest.isEmpty()) {
                        throw new IllegalArgumentException("desc= needs a value");
                    }
                    return new ProbeSelector(Kind.DESC, rest, -1, "desc=" + rest);
                case "id":
                    if (rest.isEmpty()) {
                        throw new IllegalArgumentException("id= needs a value");
                    }
                    return new ProbeSelector(Kind.ID, rest, -1, "id=" + rest);
                case "index":
                    int parsed;
                    try {
                        parsed = Integer.parseInt(rest);
                    } catch (NumberFormatException notANumber) {
                        throw new IllegalArgumentException(
                                "index= needs a whole number, got '" + rest + "'");
                    }
                    if (parsed < 0) {
                        throw new IllegalArgumentException(
                                "index= is zero-based, got '" + rest + "'");
                    }
                    return new ProbeSelector(Kind.INDEX, null, parsed, "index=" + parsed);
                default:
                    if (prefix.matches("[a-z][a-z0-9_-]*")) {
                        throw new IllegalArgumentException("unknown selector '" + prefix
                                + "='; selectors: text=, desc=, id=, index=");
                    }
                    // A needle may contain '='; it is still a bare needle.
                    break;
            }
        }
        return new ProbeSelector(Kind.ANY, text, -1, text);
    }

    public Kind kind() {
        return kind;
    }

    public String value() {
        return value;
    }

    public int index() {
        return index;
    }

    /** The selector as a failure message should name it. */
    public String label() {
        if (kind == Kind.ANY) {
            return "text|desc|id=" + value;
        }
        return raw;
    }

    /** True for the one node an {@code index=} selector picks. */
    public boolean matches(ProbeNode node) {
        if (kind == Kind.INDEX) {
            return false;
        }
        return matchedField(node) != null;
    }

    /**
     * Which field matched: {@code text}, {@code desc}, {@code id} or
     * {@code index}. The dump prints all of them; this says which one the
     * selector used, so a find answer can name it.
     */
    public String matchedField(ProbeNode node) {
        switch (kind) {
            case TEXT:
                return contains(node.text, value) ? "text" : null;
            case DESC:
                return contains(node.contentDescription, value) ? "desc" : null;
            case ID:
                return contains(node.viewId, value) ? "id" : null;
            case INDEX:
                return "index";
            case ANY:
            default:
                if (contains(node.text, value)) {
                    return "text";
                }
                if (contains(node.contentDescription, value)) {
                    return "desc";
                }
                return contains(node.viewId, value) ? "id" : null;
        }
    }

    /** The matching positions in dump order; {@code index=} yields at most one. */
    public List<Integer> matchingIndexes(List<ProbeNode> nodes) {
        List<Integer> matches = new ArrayList<>();
        if (kind == Kind.INDEX) {
            if (index < nodes.size()) {
                matches.add(index);
            }
            return matches;
        }
        for (int i = 0; i < nodes.size(); i++) {
            if (matches(nodes.get(i))) {
                matches.add(i);
            }
        }
        return matches;
    }

    /**
     * The nodes a failure message lists: the highest-scoring matches for a named
     * or bare selector, or the neighbours of an index that is out of range.
     */
    public List<ProbeNode> closest(List<ProbeNode> nodes, int max) {
        List<ProbeNode> picked = new ArrayList<>();
        if (max <= 0 || nodes.isEmpty()) {
            return picked;
        }
        if (kind == Kind.INDEX) {
            int from = Math.max(0, nodes.size() - max);
            for (int i = from; i < nodes.size(); i++) {
                picked.add(nodes.get(i));
            }
            return picked;
        }
        boolean[] taken = new boolean[nodes.size()];
        while (picked.size() < Math.min(max, nodes.size())) {
            int best = -1;
            int bestScore = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (taken[i]) {
                    continue;
                }
                int score = score(nodes.get(i));
                if (score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best < 0) {
                break;
            }
            taken[best] = true;
            picked.add(nodes.get(best));
        }
        return picked;
    }

    private int score(ProbeNode node) {
        switch (kind) {
            case TEXT:
                return similarity(node.text, value);
            case DESC:
                return similarity(node.contentDescription, value);
            case ID:
                return similarity(node.viewId, value);
            case ANY:
            default:
                return Math.max(similarity(node.text, value),
                        Math.max(similarity(node.contentDescription, value),
                                similarity(node.viewId, value)));
        }
    }

    /**
     * A small, explainable similarity: exact, prefix, contained, containing, then
     * a shared prefix. It is not edit distance; it is enough to put "Item 04"
     * before "Unrelated" when the test asked for "Item 40", and the answer is the
     * same on every device.
     */
    static int similarity(String field, String query) {
        if (field == null || field.isEmpty() || query == null || query.isEmpty()) {
            return 0;
        }
        String f = field.toLowerCase(Locale.ROOT);
        String q = query.toLowerCase(Locale.ROOT);
        if (f.equals(q)) {
            return 1000;
        }
        if (f.startsWith(q)) {
            return 800 - Math.min(199, f.length() - q.length());
        }
        if (f.contains(q)) {
            return 600 - Math.min(199, f.length() - q.length());
        }
        if (q.contains(f)) {
            return 400 - Math.min(199, q.length() - f.length());
        }
        int shared = 0;
        int limit = Math.min(f.length(), q.length());
        while (shared < limit && f.charAt(shared) == q.charAt(shared)) {
            shared++;
        }
        return shared * 200 / Math.max(1, q.length());
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && needle != null && haystack.contains(needle);
    }

    /** The candidates a failure lists, as one pipe-separated sentence. */
    public String candidates(List<ProbeNode> nodes) {
        List<ProbeNode> closest = closest(nodes, CANDIDATES);
        if (closest.isEmpty()) {
            return "(none: " + nodes.size() + " nodes scanned)";
        }
        StringBuilder out = new StringBuilder();
        for (ProbeNode node : closest) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(node.brief());
        }
        return out.toString();
    }
}
