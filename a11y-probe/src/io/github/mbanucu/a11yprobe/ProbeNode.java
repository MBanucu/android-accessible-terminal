package io.github.mbanucu.a11yprobe;

import java.util.List;

/**
 * One accessibility node, free of {@code android.view.accessibility} so the
 * text and JSON encodings can be tested off-device.
 *
 * <p>{@code ProbeService} takes a snapshot of an {@code AccessibilityNodeInfo}
 * into one of these while the node is still alive; nothing here holds a node or
 * needs recycling.
 */
public final class ProbeNode {

    public final int depth;
    public final String className;
    public final String text;
    public final String contentDescription;
    public final String viewId;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public final List<String> actions;
    public final boolean editable;
    public final boolean clickable;
    public final boolean focusable;
    public final boolean focused;

    public ProbeNode(int depth, String className, String text, String contentDescription,
            String viewId, int left, int top, int right, int bottom,
            List<String> actions, boolean editable, boolean clickable,
            boolean focusable, boolean focused) {
        this.depth = depth;
        this.className = className;
        this.text = text;
        this.contentDescription = contentDescription;
        this.viewId = viewId;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.actions = List.copyOf(actions);
        this.editable = editable;
        this.clickable = clickable;
        this.focusable = focusable;
        this.focused = focused;
    }

    /**
     * The short form a candidate list uses: the fields a selector looked at, and
     * the bounds that say where the node was, without the action list a failure
     * message does not need.
     */
    public String brief() {
        return "text=" + quote(text)
                + " desc=" + quote(contentDescription)
                + (viewId == null ? "" : " id=" + viewId)
                + " bounds=" + left + ',' + top + ',' + right + ',' + bottom;
    }

    /** The one-line form {@code find} answers with, in the dump's own spelling. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        out.append("class=").append(className == null ? "-" : className)
                .append(" text=").append(quote(text))
                .append(" desc=").append(quote(contentDescription))
                .append(" bounds=").append(left).append(',').append(top)
                .append(',').append(right).append(',').append(bottom)
                .append(" actions=").append(String.join("|", actions))
                .append(" editable=").append(editable)
                .append(" clickable=").append(clickable)
                .append(" focusable=").append(focusable)
                .append(" focused=").append(focused);
        if (viewId != null) {
            out.append(" id=").append(viewId);
        }
        return out.toString();
    }

    /**
     * The text dump's quoting: {@code -} for null, a literal newline as
     * {@code \n}, and long text cut at 160 characters with its length kept, so a
     * terminal row cannot make one line of the dump megabytes.
     */
    public static String quote(CharSequence text) {
        if (text == null) {
            return "-";
        }
        String flat = text.toString().replace("\n", "\\n");
        return flat.length() > 160 ? flat.substring(0, 160) + "…(" + flat.length() + ")" : flat;
    }
}
