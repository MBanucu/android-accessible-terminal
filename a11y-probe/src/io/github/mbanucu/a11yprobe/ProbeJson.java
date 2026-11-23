package io.github.mbanucu.a11yprobe;

import java.util.List;

/**
 * The JSON form of a dump: one object per node, in a single line so it survives
 * a {@code content call} bundle unchanged.
 *
 * <pre>
 * {"package":"...","windows":3,"truncated":false,"nodes":[
 *   {"text":"...","contentDescription":null,"class":"android.widget.TextView",
 *    "bounds":{"left":0,"top":0,"right":1080,"bottom":100},
 *    "actions":["CLICK","A11Y_FOCUS"],"editable":false,"clickable":true,
 *    "focusable":true,"focused":false,"id":null,"depth":1}
 * ]}
 * </pre>
 *
 * <p>Only {@code nodes} is the schema a reader usually needs; {@code package},
 * {@code windows} and {@code truncated} describe the dump around it. A reply
 * has to fit in a binder transaction, so {@code maxChars} stops the array and
 * sets {@code truncated}; the named file is written from the same string, which
 * is why the two always agree.
 */
public final class ProbeJson {

    private ProbeJson() {
    }

    /**
     * Encodes the dump. {@code maxChars <= 0} means no limit.
     *
     * <p>The returned string is always valid JSON: it is closed properly even
     * when a node does not fit, and {@code truncated} says one was left out.
     */
    public static String dump(String packageName, int windows, List<ProbeNode> nodes,
            int maxChars) {
        String head = "{\"package\":" + string(packageName)
                + ",\"windows\":" + windows
                + ",\"truncated\":";
        String middle = ",\"nodes\":[";
        String tail = "]}";
        // The flag's own width can differ (false/true); reserve the longer one.
        int fixed = head.length() + 5 + middle.length() + tail.length();
        StringBuilder body = new StringBuilder();
        boolean truncated = false;
        for (ProbeNode node : nodes) {
            String encoded = node(node);
            int size = fixed + body.length() + (body.length() == 0 ? 0 : 1)
                    + encoded.length();
            if (maxChars > 0 && size > maxChars) {
                truncated = true;
                break;
            }
            if (body.length() > 0) {
                body.append(',');
            }
            body.append(encoded);
        }
        return head + truncated + middle + body + tail;
    }

    /** One node object, on one line. */
    public static String node(ProbeNode node) {
        StringBuilder out = new StringBuilder(128);
        out.append('{');
        field(out, "text", node.text);
        out.append(',');
        field(out, "contentDescription", node.contentDescription);
        out.append(',');
        field(out, "class", node.className);
        out.append(",\"bounds\":{\"left\":").append(node.left)
                .append(",\"top\":").append(node.top)
                .append(",\"right\":").append(node.right)
                .append(",\"bottom\":").append(node.bottom).append('}');
        out.append(",\"actions\":[");
        for (int i = 0; i < node.actions.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            string(out, node.actions.get(i));
        }
        out.append(']');
        out.append(",\"editable\":").append(node.editable);
        out.append(",\"clickable\":").append(node.clickable);
        out.append(",\"focusable\":").append(node.focusable);
        out.append(",\"focused\":").append(node.focused);
        out.append(',');
        field(out, "id", node.viewId);
        out.append(",\"depth\":").append(node.depth);
        out.append('}');
        return out.toString();
    }

    private static void field(StringBuilder out, String name, String value) {
        string(out, name);
        out.append(':');
        if (value == null) {
            out.append("null");
        } else {
            string(out, value);
        }
    }

    /** A JSON string, with the escapes JSON requires and nothing hand-rolled. */
    public static void string(StringBuilder out, String value) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static String string(String value) {
        StringBuilder out = new StringBuilder();
        string(out, value);
        return out.toString();
    }
}
