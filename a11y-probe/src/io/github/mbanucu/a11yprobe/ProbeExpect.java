package io.github.mbanucu.a11yprobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The assertions a shell suite uses instead of grepping a dump.
 *
 * <pre>
 * assertion      code=0 when
 * exists         the selector matches at least one node
 * absent         the selector matches no node
 * text_is        a selected node's text equals the expected value
 * text_contains  a selected node's text contains the expected value
 * </pre>
 *
 * <p>The reply is the probe's ordinary {@code 0}/{@code 400}: a failed
 * assertion is a failed command, so a suite can stop on it. A failure names the
 * selector and lists the closest nodes (with their text, description, resource
 * id and bounds), and a success returns the matched node line, so there is no
 * dump to grep and no second source of truth.
 *
 * <p>For the text assertions the expected value is {@code value=}; without it a
 * {@code text=} or bare selector supplies it. That makes the compact form
 * {@code --arg 'text_contains=saved'} mean "some node's text contains saved".
 *
 * <p>No Android type is involved, so {@code a11y-probe/test.sh} checks every
 * verdict and failure sentence off-device.
 */
public final class ProbeExpect {

    public static final String EXISTS = "exists";
    public static final String ABSENT = "absent";
    public static final String TEXT_IS = "text_is";
    public static final String TEXT_CONTAINS = "text_contains";

    private static final String ASSERTIONS =
            "exists, absent, text_is, text_contains";
    private static final int CANDIDATES = 3;

    private ProbeExpect() {
    }

    /** One request after the compact {@code assertion=selector} form is split. */
    public static final class Request {
        public final String assertion;
        public final String selector;

        Request(String assertion, String selector) {
            this.assertion = assertion;
            this.selector = selector;
        }
    }

    public static boolean isAssertion(String name) {
        if (name == null) {
            return false;
        }
        switch (name.trim().toLowerCase(Locale.ROOT)) {
            case EXISTS:
            case ABSENT:
            case TEXT_IS:
            case TEXT_CONTAINS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Splits {@code text_contains=saved} into its assertion and selector.
     *
     * <p>An explicit assertion wins, and the whole argument is then the selector
     * ({@code --extra assertion:s:text_contains --arg 'text=saved'}). Without
     * one, an argument whose prefix is an assertion supplies it; otherwise the
     * assertion is {@code exists} and the argument is a selector.
     */
    public static Request request(String argument, String explicitAssertion) {
        if (argument == null || argument.trim().isEmpty()) {
            throw new IllegalArgumentException("expect needs a selector: --arg 'text=OK'"
                    + " or --arg 'text_contains=OK'");
        }
        String raw = argument.trim();
        String assertion = explicitAssertion == null || explicitAssertion.trim().isEmpty()
                ? null : explicitAssertion.trim().toLowerCase(Locale.ROOT);
        if (assertion != null && !isAssertion(assertion)) {
            throw new IllegalArgumentException("unknown assertion '" + explicitAssertion
                    + "'; assertions: " + ASSERTIONS);
        }
        if (assertion == null) {
            int equals = raw.indexOf('=');
            if (equals > 0) {
                String prefix = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                if (isAssertion(prefix)) {
                    return new Request(prefix, raw.substring(equals + 1).trim());
                }
            }
            assertion = EXISTS;
        }
        return new Request(assertion, raw);
    }

    /** Runs one assertion over the target window's nodes. */
    public static ProbeReply evaluate(String assertion, ProbeSelector selector,
            String value, List<ProbeNode> nodes) {
        if (nodes == null) {
            nodes = List.of();
        }
        String kind = assertion == null || assertion.trim().isEmpty()
                ? EXISTS : assertion.trim().toLowerCase(Locale.ROOT);
        if (!isAssertion(kind)) {
            return ProbeReply.error("unknown assertion '" + assertion + "'; assertions: "
                    + ASSERTIONS);
        }
        switch (kind) {
            case EXISTS:
                return exists(selector, nodes);
            case ABSENT:
                return absent(selector, nodes);
            case TEXT_IS:
            case TEXT_CONTAINS:
                return text(kind, selector, value, nodes);
            default:
                return ProbeReply.error("unknown assertion '" + assertion + "'");
        }
    }

    private static ProbeReply exists(ProbeSelector selector, List<ProbeNode> nodes) {
        List<Integer> hits = selector.matchingIndexes(nodes);
        if (hits.isEmpty()) {
            return ProbeReply.error("expect exists " + selector.label()
                    + " failed: no node matches; closest: " + selector.candidates(nodes));
        }
        ProbeNode node = nodes.get(hits.get(0));
        return ProbeReply.ok("expect exists " + selector.label() + " matched "
                + hits.size() + " node(s); first: " + matchedLine(selector, node));
    }

    private static ProbeReply absent(ProbeSelector selector, List<ProbeNode> nodes) {
        List<Integer> hits = selector.matchingIndexes(nodes);
        if (hits.isEmpty()) {
            return ProbeReply.ok("expect absent " + selector.label()
                    + ": no node matched (" + nodes.size() + " scanned)");
        }
        ProbeNode node = nodes.get(hits.get(0));
        return ProbeReply.error("expect absent " + selector.label() + " failed: "
                + hits.size() + " node(s) match; first: " + matchedLine(selector, node));
    }

    private static ProbeReply text(String kind, ProbeSelector selector, String value,
            List<ProbeNode> nodes) {
        boolean derived = value == null || value.isEmpty();
        String wanted = derived ? selectorValue(selector) : value;
        if (wanted == null) {
            return ProbeReply.error("expect " + kind + " " + selector.label()
                    + " needs --extra value:s:<text> unless the selector is text= or bare");
        }
        // A bare selector means any field; for a text assertion it should mean
        // text, or a description that merely contains the word would be reported
        // as a text match.
        ProbeSelector by = derived && selector.kind() == ProbeSelector.Kind.ANY
                ? ProbeSelector.text(wanted) : selector;
        List<Integer> hits = by.matchingIndexes(nodes);
        ProbeNode matched = null;
        for (int hit : hits) {
            ProbeNode node = nodes.get(hit);
            if (node.text != null && (kind.equals(TEXT_IS)
                    ? node.text.equals(wanted) : node.text.contains(wanted))) {
                matched = node;
                break;
            }
        }
        String expectation = kind.equals(TEXT_IS)
                ? "text exactly '" + wanted + "'" : "text containing '" + wanted + "'";
        if (matched != null) {
            return ProbeReply.ok("expect " + kind + " " + selector.label()
                    + " matched " + expectation + ": " + matchedLine(by, matched));
        }
        String why = hits.isEmpty()
                ? "no node matches " + selector.label()
                : "no selected node has " + expectation;
        return ProbeReply.error("expect " + kind + " " + selector.label()
                + " failed: " + why + "; closest: " + candidates(by, nodes, hits));
    }

    private static String selectorValue(ProbeSelector selector) {
        switch (selector.kind()) {
            case TEXT:
            case ANY:
                return selector.value();
            default:
                return null;
        }
    }

    private static String matchedLine(ProbeSelector selector, ProbeNode node) {
        String field = selector.matchedField(node);
        return (field == null ? "" : field + " matched ") + node.describe();
    }

    private static String candidates(ProbeSelector selector, List<ProbeNode> nodes,
            List<Integer> preferred) {
        List<ProbeNode> picked = new ArrayList<>();
        for (int hit : preferred) {
            if (picked.size() >= CANDIDATES) {
                break;
            }
            picked.add(nodes.get(hit));
        }
        for (ProbeNode node : selector.closest(nodes, CANDIDATES)) {
            if (picked.size() >= CANDIDATES) {
                break;
            }
            if (!picked.contains(node)) {
                picked.add(node);
            }
        }
        if (picked.isEmpty()) {
            return "(none: " + nodes.size() + " nodes scanned)";
        }
        StringBuilder out = new StringBuilder();
        for (ProbeNode node : picked) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(node.brief());
        }
        return out.toString();
    }
}
