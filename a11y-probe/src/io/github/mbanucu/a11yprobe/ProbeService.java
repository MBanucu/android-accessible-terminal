package io.github.mbanucu.a11yprobe;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;

import io.github.mbanucu.screenstream.ScreenStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The screen reader, for a test: an accessibility service that reads the window
 * in front the way TalkBack does and can perform the actions TalkBack performs.
 *
 * <p>Enabled with adb, which needs no root:
 *
 * <pre>
 * settings put secure enabled_accessibility_services \
 *     io.github.mbanucu.a11yprobe/.ProbeService
 * settings put secure accessibility_enabled 1
 * </pre>
 *
 * <p>Its answers land in {@code files/tree.txt} and {@code files/actions.txt} in
 * this app's external files directory, so what a service can see and do is a
 * file rather than an opinion.
 */
public class ProbeService extends AccessibilityService {

    private static final String TAG = "A11yProbe";
    /** Events kept for the log; a terminal that repaints constantly makes thousands. */
    private static final int MAX_EVENTS = 200;
    /** Nodes written per dump: a WebView's terminal alone can have thousands. */
    private static final int MAX_NODES = 3000;
    /**
     * The longest a {@code wait} may block before answering "timeout".
     *
     * <p>It is a binder call, and a caller that needs longer uses a request id
     * and {@code result} instead of keeping the transaction open.
     */
    static final long MAX_WAIT_MS = 9000;
    /** A command that needs the accessibility service but found it off. */
    static final String NO_SERVICE =
            "probe service is not running; enable it with 'bash probe.sh enable'";
    /**
     * The largest payload a reply may carry, in characters.
     *
     * <p>A binder transaction is about 1 MB; a string crosses it as UTF-16, so
     * 300k characters leaves room for the bundle around it. The file always
     * holds the whole tree; a reply that would not fit says so.
     */
    static final int MAX_REPLY_CHARS = 300_000;

    static volatile ProbeService instance;

    /** When set, actions go to this package's window instead of the active one. */
    private static volatile String targetPackage;

    /**
     * True while the built-in fixture activity is in front.
     *
     * <p>The probe normally ignores its own package's events, which are noise
     * from enabling it; the fixture is the one screen it owns that a test is
     * meant to drive, so its events are recorded and {@code wait_idle} can see
     * it settle.
     */
    static volatile boolean fixtureFront;

    private final Object eventLock = new Object();
    private long nextEventSeq;
    private final Deque<Event> events = new ArrayDeque<>();

    /** One heard event, with the sequence number a wait matches against. */
    private static final class Event {
        final long seq;
        final long at;
        final String line;

        Event(long seq, long at, String line) {
            this.seq = seq;
            this.at = at;
            this.line = line;
        }
    }

    static File files() {
        ProbeService service = instance;
        return service == null ? null : service.getExternalFilesDir(null);
    }

    /**
     * The installed version, read from the package manager rather than from a
     * build-time constant, so a harness can refuse a probe it does not know.
     *
     * <p>Written to {@code files/version.txt} and returned for the broadcast log.
     * The receiver has a context even while the service is disabled, which is why
     * this takes one instead of using {@link #files()}.
     */
    static String version(Context context) {
        String answer;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            answer = "a11y-probe " + info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException missing) {
            answer = "a11y-probe unknown (package manager has no entry)";
        }
        File directory = context.getExternalFilesDir(null);
        if (directory != null) {
            File file = new File(directory, "version.txt");
            directory.mkdirs();
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(answer.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failed) {
                Log.w(TAG, "could not write " + file, failed);
            }
        }
        return answer;
    }

    /**
     * What the provider can report without doing anything: the authority it is
     * listening on, the installed version, whether the service is bound, the
     * target package and the event and window counts.
     */
    static String status(Context context) {
        ProbeService service = instance;
        StringBuilder out = new StringBuilder();
        out.append("authority=").append(context.getPackageName()).append(".control\n");
        out.append("version=").append(version(context)).append('\n');
        out.append("service=").append(service == null ? "disconnected" : "connected").append('\n');
        out.append("target=").append(targetPackage == null ? "active" : targetPackage).append('\n');
        out.append("events=").append(eventCount()).append('\n');
        out.append("windows=").append(service == null ? 0 : service.windowCount()).append('\n');
        return out.toString();
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // The screen stream is a library this app depends on, and this service is
        // what can accept its capture dialog: the module only knows that something
        // may click it, not what. Set here rather than in the activity that asks,
        // because the activity is started by adb and may never run in this app.
        ScreenStream.setDialogClicker(ProbeService::acceptScreenCaptureDialog);
        write("status.txt", "connected\nflags=" + getServiceInfo().flags + "\n");
        Log.i(TAG, "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        String packageName = String.valueOf(event.getPackageName());
        // The probe's own screens are noise; a terminal being read is the point.
        // getPackageName() is the canonical or legacy app id, depending on which
        // APK this class was installed in. The fixture is the exception: a test
        // drives it, so its events are the ones wait_idle settles on.
        if (packageName.startsWith(getPackageName()) && !fixtureFront) {
            return;
        }
        String line = packageName + " " + eventType(event.getEventType())
                + " " + event.getClassName() + " text=" + event.getText()
                + " desc=" + event.getContentDescription();
        long at = System.currentTimeMillis();
        synchronized (eventLock) {
            events.addLast(new Event(++nextEventSeq, at, line));
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
            eventLock.notifyAll();
        }
    }

    @Override
    public void onInterrupt() {
    }

    private static String eventType(int type) {
        switch (type) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: return "TEXT_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: return "SELECTION";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: return "A11Y_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: return "A11Y_UNFOCUSED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: return "FOCUSED";
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "CONTENT_CHANGED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: return "WINDOW_STATE";
            case AccessibilityEvent.TYPE_ANNOUNCEMENT: return "ANNOUNCEMENT";
            case AccessibilityEvent.TYPE_VIEW_SCROLLED: return "SCROLLED";
            default: return "type-" + type;
        }
    }

    /**
     * Writes every window's tree, and the events the service heard, to a file,
     * and returns the same tree for a provider reply.
     *
     * <p>Text is the default; {@code json} writes one JSON object per node in
     * one JSON document, which is what a harness parses instead of the
     * indented text. The file and the reply are written from the same string,
     * except that text mode keeps the whole tree in the file and cuts the reply
     * at {@link #MAX_REPLY_CHARS}.
     */
    static ProbeReply dump(String name, boolean json) {
        ProbeService service = instance;
        if (service == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        name = ProbeArgs.safeFileName(name, "tree");
        if (json) {
            List<ProbeNode> nodes = service.collectNodes();
            String body = ProbeJson.dump(service.getPackageName(), service.windowCount(),
                    nodes, MAX_REPLY_CHARS);
            writeReplace(name + ".json", body);
            return ProbeReply.ok(body, "dumped " + name + ".json (" + body.length() + " chars)");
        }
        StringBuilder out = new StringBuilder();
        out.append("package=").append(service.getPackageName()).append('\n');
        synchronized (service.eventLock) {
            out.append("events(").append(service.events.size()).append(") newest last:\n");
            for (Event event : service.events) {
                out.append("  ").append(event.line).append('\n');
            }
        }
        List<AccessibilityWindowInfo> windows = service.getWindows();
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        out.append("windows=").append(windows == null ? 0 : windows.size()).append('\n');
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                out.append("-- window type=").append(window.getType())
                        .append(" active=").append(window.isActive())
                        .append(" focused=").append(window.isFocused()).append('\n');
                if (root != null) {
                    walk(root, 0, out, new int[] { 0, MAX_NODES });
                    root.recycle();
                }
            }
        }
        if (active != null) {
            out.append("-- active root ").append(active.getPackageName()).append('\n');
            walk(active, 0, out, new int[] { 0, MAX_NODES });
            active.recycle();
        }
        String body = out.toString();
        write(name + ".txt", body);
        String reply = body.length() <= MAX_REPLY_CHARS ? body
                : body.substring(0, MAX_REPLY_CHARS)
                        + "\n[truncated for the reply; the full tree is in " + name + ".txt]\n";
        return ProbeReply.ok(reply, "dumped " + name + ".txt (" + body.length() + " chars)");
    }

    /** Every window's nodes and the active root's, in the dump's order. */
    private List<ProbeNode> collectNodes() {
        List<ProbeNode> nodes = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    collect(root, 0, nodes, new int[] { 0, MAX_NODES });
                    root.recycle();
                }
            }
        }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            collect(active, 0, nodes, new int[] { 0, MAX_NODES });
            active.recycle();
        }
        return nodes;
    }

    private static void collect(AccessibilityNodeInfo node, int depth, List<ProbeNode> out,
            int[] budget) {
        if (node == null || depth > 60 || budget[1]-- <= 0) {
            return;
        }
        out.add(nodeOf(node, depth));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collect(child, depth + 1, out, budget);
                child.recycle();
            }
        }
    }

    private static ProbeNode nodeOf(AccessibilityNodeInfo node, int depth) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        List<String> actions = new ArrayList<>();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            actions.add(actionLabel(action.getId()));
        }
        return new ProbeNode(depth, String.valueOf(node.getClassName()),
                text(node.getText()), text(node.getContentDescription()),
                node.getViewIdResourceName(), bounds.left, bounds.top, bounds.right, bounds.bottom,
                actions, node.isEditable(), node.isClickable(), node.isFocusable(),
                node.isFocused());
    }

    private static String text(CharSequence value) {
        return value == null ? null : value.toString();
    }

    private int windowCount() {
        List<AccessibilityWindowInfo> windows = getWindows();
        return windows == null ? 0 : windows.size();
    }

    private static void walk(AccessibilityNodeInfo node, int depth, StringBuilder out, int[] budget) {
        if (node == null || depth > 60 || budget[1]-- <= 0) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        out.append(indent(depth))
                .append(node.getClassName())
                .append(" editable=").append(node.isEditable())
                .append(" focusable=").append(node.isFocusable())
                .append(" focused=").append(node.isFocused())
                .append(" clickable=").append(node.isClickable())
                .append(" actions=").append(actionNames(node))
                .append(" bounds=").append(bounds.left).append(',').append(bounds.top)
                .append(',').append(bounds.right).append(',').append(bounds.bottom)
                .append(" id=").append(node.getViewIdResourceName())
                .append(" text=").append(quote(node.getText()))
                .append(" desc=").append(quote(node.getContentDescription()))
                .append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                walk(child, depth + 1, out, budget);
                child.recycle();
            }
        }
    }

    private static String indent(int depth) {
        StringBuilder pad = new StringBuilder(depth * 2 + 2);
        for (int i = 0; i < depth; i++) {
            pad.append("  ");
        }
        return pad.toString();
    }

    private static String quote(CharSequence text) {
        return ProbeNode.quote(text);
    }

    private static String bounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
    }

    private static String actionNames(AccessibilityNodeInfo node) {
        StringBuilder names = new StringBuilder();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (names.length() > 0) {
                names.append('|');
            }
            names.append(actionLabel(action.getId()));
        }
        return names.toString();
    }

    private static String actionLabel(int id) {
        switch (id) {
            case AccessibilityNodeInfo.ACTION_SET_TEXT: return "SET_TEXT";
            case AccessibilityNodeInfo.ACTION_FOCUS: return "FOCUS";
            case AccessibilityNodeInfo.ACTION_CLICK: return "CLICK";
            case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS: return "A11Y_FOCUS";
            case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS: return "A11Y_CLEAR";
            case AccessibilityNodeInfo.ACTION_PASTE: return "PASTE";
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD: return "SCROLL_FWD";
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: return "SCROLL_BACK";
            case AccessibilityNodeInfo.ACTION_LONG_CLICK: return "LONG_CLICK";
            case AccessibilityNodeInfo.ACTION_SELECT: return "SELECT";
            default: return "action-" + id;
        }
    }

    /**
     * Waits for a new accessibility event containing the needle, optionally
     * restricted to one event type.
     *
     * <p>Call this off the main thread: {@link #onAccessibilityEvent} is
     * delivered there, so blocking the main thread would wait forever for the
     * event that cannot be delivered.
     */
    static String waitFor(String needle, String type, long timeoutMs, long start) {
        ProbeService service = instance;
        if (service == null) {
            return "probe service is not running\n";
        }
        long timeout = Math.max(0, Math.min(timeoutMs, MAX_WAIT_MS));
        long deadline = System.currentTimeMillis() + timeout;
        synchronized (service.eventLock) {
            while (true) {
                for (Event event : service.events) {
                    if (event.seq <= start) {
                        continue;
                    }
                    if ((needle == null || needle.isEmpty() || event.line.contains(needle))
                            && (type == null || type.isEmpty() || event.line.contains(type))) {
                        return "matched seq=" + event.seq + " " + event.line + "\n";
                    }
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return "timeout after " + timeout + "ms for '" + needle + "'\n";
                }
                try {
                    service.eventLock.wait(left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return "interrupted\n";
                }
            }
        }
    }

    /**
     * Waits until no accessibility event has arrived for {@code idleMs}, bounded
     * by {@code timeoutMs}.
     *
     * <p>Call this off the main thread: events are delivered on the main thread,
     * so a wait there would never see the quiet it is waiting for. A timeout is
     * not an error; the answer names the last event so a caller can tell an
     * animated screen from a screen that never changed.
     */
    static String waitIdle(long idleMs, long timeoutMs) {
        ProbeService service = instance;
        if (service == null) {
            return "probe service is not running\n";
        }
        long idle = Math.max(0, idleMs);
        long timeout = Math.max(0, Math.min(timeoutMs, MAX_WAIT_MS));
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeout;
        synchronized (service.eventLock) {
            while (true) {
                long now = System.currentTimeMillis();
                Event last = service.events.isEmpty() ? null : service.events.peekLast();
                // The quiet time is measured from the event itself, even one
                // that arrived just before this call: a screen that has already
                // been still for longer than idleMs is idle now.
                long quietSince = last == null ? startedAt : last.at;
                long quiet = Math.max(0, now - quietSince);
                if (quiet >= idle) {
                    return "idle quiet=" + quiet + "ms events=" + service.events.size()
                            + (last == null ? " last=none\n"
                                    : " last seq=" + last.seq + " " + last.line + "\n");
                }
                long left = deadline - now;
                if (left <= 0) {
                    return "timeout after " + timeout + "ms quiet=" + quiet + "ms"
                            + (last == null ? " last=none\n"
                                    : " last seq=" + last.seq + " " + last.line + "\n");
                }
                try {
                    service.eventLock.wait(Math.min(left, idle - quiet));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return "interrupted\n";
                }
            }
        }
    }

    /** Gives accessibility focus to the first terminal row, as TalkBack's swipe does. */
    static String focusFirstRow() {
        ProbeService service = instance;
        if (service == null) {
            return "A11Y_FOCUS_FIRST probe service is not running";
        }
        AccessibilityNodeInfo root = rootFor();
        if (root == null) {
            return "A11Y_FOCUS_FIRST no target window for "
                    + (targetPackage == null ? "the active app" : targetPackage);
        }
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            if (isTerminalRow(node)) {
                boolean focused = node.performAction(
                        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                String answer = "A11Y_FOCUS_FIRST " + quote(node.getText())
                        + " -> " + focused;
                write("actions.txt", answer + "\n");
                root.recycle();
                return answer;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        root.recycle();
        return "A11Y_FOCUS_FIRST no terminal row";
    }

    /** The sequence number of the newest event, so a wait can ignore older ones. */
    static long eventSeq() {
        ProbeService service = instance;
        if (service == null) {
            return 0;
        }
        synchronized (service.eventLock) {
            return service.nextEventSeq;
        }
    }

    /** How many events the service is holding, for {@code status}. */
    static int eventCount() {
        ProbeService service = instance;
        if (service == null) {
            return 0;
        }
        synchronized (service.eventLock) {
            return service.events.size();
        }
    }

    /**
     * A compact summary of what a service can read in the target window: row
     * texts and widths, the editable prompt and its actions, the node with
     * accessibility focus, and event counts. The walking and counting happen
     * here, so a test asserts on values rather than parsing a tree.
     */
    static ProbeReply inspect(String name) {
        ProbeService service = instance;
        if (service == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        name = ProbeArgs.safeFileName(name, "inspect");
        StringBuilder out = new StringBuilder();
        out.append("package=").append(service.getPackageName()).append('\n');
        List<AccessibilityWindowInfo> windows = service.getWindows();
        out.append("windows=").append(windows == null ? 0 : windows.size()).append('\n');
        AccessibilityNodeInfo root = rootFor();
        int rows = 0;
        if (root == null) {
            out.append("root=none\n");
        } else {
            Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                AccessibilityNodeInfo node = pending.removeFirst();
                if (node.isEditable()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    out.append("prompt.text=").append(quote(node.getText())).append('\n');
                    out.append("prompt.editable=").append(node.isEditable()).append('\n');
                    out.append("prompt.focusable=").append(node.isFocusable()).append('\n');
                    out.append("prompt.focused=").append(node.isFocused()).append('\n');
                    out.append("prompt.actions=").append(actionNames(node)).append('\n');
                    out.append("prompt.bounds=").append(bounds.left).append(',').append(bounds.top)
                            .append(',').append(bounds.right).append(',').append(bounds.bottom)
                            .append('\n');
                } else if (isTerminalRow(node)) {
                    rows++;
                    out.append("row.").append(rows).append('=').append(quote(node.getText()))
                            .append('\n');
                    out.append("rowwidth.").append(rows).append('=')
                            .append(node.getText().length()).append('\n');
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        pending.addLast(child);
                    }
                }
                node.recycle();
            }
        }
        out.append("rows=").append(rows).append('\n');
        AccessibilityNodeInfo focused =
                service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        out.append("focusedA11y=")
                .append(focused == null ? "-" : quote(focused.getText())).append('\n');
        if (focused != null) {
            focused.recycle();
        }
        int total = 0;
        int contentChanged = 0;
        int announcements = 0;
        int a11yFocused = 0;
        synchronized (service.eventLock) {
            total = service.events.size();
            for (Event event : service.events) {
                if (event.line.contains("CONTENT_CHANGED")) {
                    contentChanged++;
                }
                if (event.line.contains("ANNOUNCEMENT")) {
                    announcements++;
                }
                if (event.line.contains("A11Y_FOCUSED")) {
                    a11yFocused++;
                }
            }
        }
        out.append("events.total=").append(total).append('\n');
        out.append("events.CONTENT_CHANGED=").append(contentChanged).append('\n');
        out.append("events.ANNOUNCEMENT=").append(announcements).append('\n');
        out.append("events.A11Y_FOCUSED=").append(a11yFocused).append('\n');
        writeReplace(name + ".txt", out.toString());
        String body = out.toString();
        String reply = body.length() <= MAX_REPLY_CHARS ? body
                : body.substring(0, MAX_REPLY_CHARS)
                        + "\n[truncated for the reply; the full summary is in " + name + ".txt]\n";
        return ProbeReply.ok(reply,
                "inspected " + name + ".txt (" + body.length() + " chars)");
    }

    /** A row of the terminal, rather than an ordinary label in the same window. */
    private static boolean isTerminalRow(AccessibilityNodeInfo node) {
        return "android.widget.TextView".contentEquals(node.getClassName())
                && node.getText() != null && node.getText().length() > 0
                && node.getActionList().contains(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
    }

    /** Replaces a file, for a summary; {@link #write} appends for a log. */
    static void writeReplace(String name, String body) {
        File directory = files();
        if (directory == null) {
            Log.w(TAG, "no files dir for " + name);
            return;
        }
        File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            Log.w(TAG, "could not write " + file, failed);
        }
    }

    static void setTarget(String packageName) {
        targetPackage = packageName;
    }

    /** The package actions are aimed at, or null for whatever window is in front. */
    static String targetPackage() {
        return targetPackage;
    }

    /**
     * Accepts the system's screen-capture dialog, the way a screen reader user
     * would.
     *
     * <p>Screen capture is not a permission an app can hold: the system shows a
     * dialog every time, and only a person - or something standing in for one -
     * can accept it. That standing-in is this service. The screenstream module's
     * activity asks for the capture and calls this; the dialog is another window,
     * so the service
     * looks for its positive button in the system UI and clicks it, and says in
     * actions.txt what it clicked.
     */
    static void acceptScreenCaptureDialog() {
        final ProbeService service = instance;
        if (service == null) {
            write("actions.txt", "STREAM no probe service to accept the capture dialog\n");
            return;
        }
        new Thread(() -> {
            long deadline = android.os.SystemClock.uptimeMillis() + 8000;
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                String clicked = service.clickCaptureButton();
                if (clicked != null) {
                    write("actions.txt", "STREAM accepted the screen capture dialog: "
                            + clicked + "\n");
                    return;
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            write("actions.txt", "STREAM the screen capture dialog never appeared\n");
        }, "screen-capture-consent").start();
    }

    /** The positive button of the capture dialog, clicked; null while there is none. */
    private String clickCaptureButton() {
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return null;
        }
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                continue;
            }
            String pkg = String.valueOf(root.getPackageName());
            // The dialog belongs to the system, so this never clicks an app's own
            // "start" button: only the system UI's windows are searched.
            if (!pkg.startsWith("com.android.systemui") && !pkg.equals("android")) {
                continue;
            }
            AccessibilityNodeInfo button = captureButton(root);
            if (button != null) {
                boolean clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                String answer = quote(button.getText()) + " id=" + button.getViewIdResourceName()
                        + " clickable=" + button.isClickable();
                return clicked ? answer : null;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo captureButton(AccessibilityNodeInfo node) {
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(node);
        AccessibilityNodeInfo fallback = null;
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo current = pending.removeFirst();
            if (current.isClickable()) {
                String text = String.valueOf(current.getText()).toLowerCase();
                if (text.contains("start") || text.contains("allow") || text.contains("share")) {
                    return current;
                }
                if (fallback == null && "android:id/button1".equals(current.getViewIdResourceName())) {
                    fallback = current;
                }
            }
            for (int i = 0; i < current.getChildCount(); i++) {
                AccessibilityNodeInfo child = current.getChild(i);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        return fallback;
    }

    /**
     * The root of the target package's window, or of the active window when no
     * target was given.
     *
     * <p>A screen reader works on the focused window, which is not always the app
     * under test: with Termux visible behind, Termux's own text field is the first
     * editable in the tree, and a naive SET_TEXT types into Termux's toolbar
     * instead of the app. That is why the probe can be pointed at a package.
     */
    private static AccessibilityNodeInfo rootFor() {
        ProbeService service = instance;
        if (service == null) {
            return null;
        }
        String wanted = targetPackage;
        if (wanted == null) {
            return service.getRootInActiveWindow();
        }
        List<AccessibilityWindowInfo> windows = service.getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                if (wanted.contentEquals(root.getPackageName())) {
                    return root;
                }
                root.recycle();
            }
        }
        // The window list lags a freshly started app: the service could see three
        // windows and none of them this app, while `getRootInActiveWindow()` was
        // already this app's. Using it is still safe, because the package check
        // is what keeps an action from landing in Termux's toolbar; "whatever is
        // in front" is not.
        AccessibilityNodeInfo active = service.getRootInActiveWindow();
        if (active != null) {
            if (wanted.contentEquals(active.getPackageName())) {
                return active;
            }
            active.recycle();
        }
        // Deliberately not *any* active window: an action meant for one app and
        // performed on another is worse than none, and it is what made a test
        // flake - the terminal's text went into Termux's toolbar and never ran.
        return null;
    }

    /** Live nodes of the target window, and their off-device snapshots, in dump order. */
    private static final class Nodes {
        final List<AccessibilityNodeInfo> live = new ArrayList<>();
        final List<ProbeNode> snapshots = new ArrayList<>();

        void recycle() {
            for (AccessibilityNodeInfo node : live) {
                node.recycle();
            }
            live.clear();
            snapshots.clear();
        }
    }

    /**
     * Takes one snapshot of the target window's nodes plus the live nodes at the
     * same positions. The selector logic picks a position in the off-device list,
     * so the parser and the ranking are tested without Android while the action
     * still lands on the live node.
     */
    private static Nodes collectTargets() {
        AccessibilityNodeInfo root = rootFor();
        if (root == null) {
            return null;
        }
        Nodes out = new Nodes();
        Deque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty() && out.live.size() < MAX_NODES) {
            AccessibilityNodeInfo node = pending.removeFirst();
            out.live.add(node);
            out.snapshots.add(nodeOf(node, 0));
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    pending.addLast(child);
                }
            }
        }
        // The budget can stop the walk with children still queued; they were
        // never added to `live`, so they are recycled here or not at all.
        for (AccessibilityNodeInfo leftover : pending) {
            leftover.recycle();
        }
        return out;
    }

    private static int matchIndex(Nodes nodes, ProbeSelector selector) {
        List<Integer> matches = selector.matchingIndexes(nodes.snapshots);
        return matches.isEmpty() ? -1 : matches.get(0);
    }

    private static ProbeReply noWindow() {
        ProbeService service = instance;
        String active = "none";
        int windows = 0;
        if (service != null) {
            AccessibilityNodeInfo front = service.getRootInActiveWindow();
            if (front != null) {
                active = String.valueOf(front.getPackageName());
                front.recycle();
            }
            List<AccessibilityWindowInfo> list = service.getWindows();
            windows = list == null ? 0 : list.size();
        }
        return ProbeReply.error("no window for "
                + (targetPackage == null ? "the active app" : targetPackage)
                + " (windows=" + windows + " active=" + active + ")");
    }

    private static ProbeReply noMatch(ProbeSelector selector, Nodes nodes) {
        return ProbeReply.error("no node matching " + selector.label()
                + "; closest: " + selector.candidates(nodes.snapshots));
    }

    /** The fields an action answer names, so it also says which node it touched. */
    private static String nodeLabel(AccessibilityNodeInfo node) {
        return node.getClassName()
                + " id=" + (node.getViewIdResourceName() == null
                        ? "-" : node.getViewIdResourceName())
                + " desc=" + quote(node.getContentDescription())
                + " text=" + quote(node.getText());
    }

    private static boolean hasAction(AccessibilityNodeInfo node, int actionId) {
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
            if (action.getId() == actionId) {
                return true;
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo focusedNode() {
        ProbeService service = instance;
        if (service == null) {
            return null;
        }
        // Input focus is where Enter belongs; accessibility focus is the
        // screen-reader cursor and is the fallback for a long click.
        AccessibilityNodeInfo node = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (node == null) {
            node = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        }
        return node;
    }

    /** The first node matching a selector, as the line {@code find} answers with. */
    static ProbeReply findReply(String needle) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        ProbeSelector selector = ProbeSelector.parse(needle);
        Nodes nodes = collectTargets();
        if (nodes == null) {
            return noWindow();
        }
        int at = matchIndex(nodes, selector);
        if (at < 0) {
            ProbeReply failure = noMatch(selector, nodes);
            nodes.recycle();
            return failure;
        }
        ProbeNode snapshot = nodes.snapshots.get(at);
        String answer = snapshot.describe() + " matched=" + selector.label()
                + " field=" + selector.matchedField(snapshot);
        nodes.recycle();
        return ProbeReply.ok(answer);
    }

    /** The action a screen reader's "type" and a voice keyboard's dictation use. */
    static ProbeReply setText(String find, String text) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        if (find == null || find.isEmpty()) {
            return setTextOnFirstEditable(text);
        }
        ProbeSelector selector = ProbeSelector.parse(find);
        Nodes nodes = collectTargets();
        if (nodes == null) {
            return noWindow();
        }
        int at = matchIndex(nodes, selector);
        if (at < 0) {
            ProbeReply failure = noMatch(selector, nodes);
            nodes.recycle();
            return failure;
        }
        AccessibilityNodeInfo node = nodes.live.get(at);
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        String answer = "SET_TEXT on " + nodeLabel(node)
                + " editable=" + node.isEditable() + " -> " + performed;
        write("actions.txt", answer + "\n");
        nodes.recycle();
        return performed ? ProbeReply.ok(answer)
                : ProbeReply.error(answer + " (the node did not accept ACTION_SET_TEXT)");
    }

    private static ProbeReply setTextOnFirstEditable(String text) {
        Nodes nodes = collectTargets();
        if (nodes == null) {
            return noWindow();
        }
        for (int i = 0; i < nodes.live.size(); i++) {
            AccessibilityNodeInfo node = nodes.live.get(i);
            if (node.isEditable() && node.getActionList().contains(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                        arguments);
                String answer = "SET_TEXT on first editable " + nodeLabel(node)
                        + " -> " + performed;
                write("actions.txt", answer + "\n");
                nodes.recycle();
                return performed ? ProbeReply.ok(answer)
                        : ProbeReply.error(answer + " (the node did not accept ACTION_SET_TEXT)");
            }
        }
        nodes.recycle();
        return ProbeReply.error("no editable node in the target window");
    }

    /**
     * Clears a field: SET_TEXT with an empty string, then reads the text back.
     * The old advice was "set_text with an empty string, unverified"; this one
     * answers 400 when the field still holds something.
     */
    static ProbeReply clear(String find) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        ProbeSelector selector = find == null || find.isEmpty() ? null
                : ProbeSelector.parse(find);
        Nodes nodes = collectTargets();
        if (nodes == null) {
            return noWindow();
        }
        int at = selector == null ? firstEditable(nodes) : matchIndex(nodes, selector);
        if (at < 0) {
            ProbeReply failure = selector == null
                    ? ProbeReply.error("no editable node in the target window")
                    : noMatch(selector, nodes);
            nodes.recycle();
            return failure;
        }
        AccessibilityNodeInfo node = nodes.live.get(at);
        Bundle arguments = new Bundle();
        arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        boolean performed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        node.refresh();
        CharSequence remaining = node.getText();
        boolean empty = remaining == null || remaining.length() == 0;
        String answer = "CLEAR on " + nodeLabel(node) + " -> " + (performed && empty);
        if (!empty) {
            answer += " (still holds " + quote(remaining) + ")";
        }
        write("actions.txt", answer + "\n");
        nodes.recycle();
        return performed && empty ? ProbeReply.ok(answer)
                : ProbeReply.error(answer + " (the field did not clear)");
    }

    private static int firstEditable(Nodes nodes) {
        for (int i = 0; i < nodes.live.size(); i++) {
            AccessibilityNodeInfo node = nodes.live.get(i);
            if (node.isEditable() && hasAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) {
                return i;
            }
        }
        return -1;
    }

    /** Moves accessibility focus, which is what TalkBack's swipe does. */
    static ProbeReply focus(String find) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        if (find == null || find.isEmpty()) {
            return ProbeReply.error("focus needs a selector: --arg 'text=OK'");
        }
        ProbeSelector selector = ProbeSelector.parse(find);
        Nodes nodes = collectTargets();
        if (nodes == null) {
            return noWindow();
        }
        int at = matchIndex(nodes, selector);
        if (at < 0) {
            ProbeReply failure = noMatch(selector, nodes);
            nodes.recycle();
            return failure;
        }
        AccessibilityNodeInfo node = nodes.live.get(at);
        boolean focused = node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        String answer = "A11Y_FOCUS on " + nodeLabel(node)
                + " visible=" + node.isVisibleToUser()
                + " enabled=" + node.isEnabled()
                + " actions=" + actionNames(node)
                + " clickable=" + node.isClickable()
                + " a11yFocused=" + node.isAccessibilityFocused()
                + " bounds=" + bounds(node)
                + " -> " + focused;
        write("actions.txt", answer + "\n");
        nodes.recycle();
        return focused ? ProbeReply.ok(answer)
                : ProbeReply.error(
                        answer + " (the node did not accept ACTION_ACCESSIBILITY_FOCUS)");
    }

    static ProbeReply click(String find) {
        return tap(find, AccessibilityNodeInfo.ACTION_CLICK, "CLICK");
    }

    static ProbeReply longClick(String find) {
        return tap(find, AccessibilityNodeInfo.ACTION_LONG_CLICK, "LONG_CLICK");
    }

    private static ProbeReply tap(String find, int action, String name) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        if (find == null || find.isEmpty()) {
            if (AccessibilityNodeInfo.ACTION_LONG_CLICK == action) {
                // A long click with no selector is the focused node, which is
                // what a screen reader would act on.
                AccessibilityNodeInfo focused = focusedNode();
                if (focused == null) {
                    return ProbeReply.error(name + " needs a selector or a focused node");
                }
                boolean done = focused.performAction(action);
                String answer = name + " on " + nodeLabel(focused) + " -> " + done;
                write("actions.txt", answer + "\n");
                focused.recycle();
                return done ? ProbeReply.ok(answer)
                        : ProbeReply.error(answer + " (the node did not accept " + name + ")");
            }
            return ProbeReply.error(name.toLowerCase() + " needs a selector: --arg 'text=OK'");
        }
        ProbeSelector selector = ProbeSelector.parse(find);
        Nodes nodes = collectTargets();
        if (nodes == null) {
            return noWindow();
        }
        int at = matchIndex(nodes, selector);
        if (at < 0) {
            ProbeReply failure = noMatch(selector, nodes);
            nodes.recycle();
            return failure;
        }
        AccessibilityNodeInfo node = nodes.live.get(at);
        boolean done = node.performAction(action);
        String answer = name + " on " + nodeLabel(node) + " -> " + done;
        write("actions.txt", answer + "\n");
        nodes.recycle();
        return done ? ProbeReply.ok(answer)
                : ProbeReply.error(answer + " (the node did not accept " + name + ")");
    }

    /**
     * Scrolls the target window for real, optionally until a selector matches.
     *
     * <p>{@code down}/{@code forward} reveal later content and
     * {@code up}/{@code backward} earlier content, using the node's own
     * ACTION_SCROLL_* rather than a reported capability. With {@code until} the
     * tree is dumped again after every scroll; the answer names the node the
     * wait found, so a suite can click it without coordinates or {@code input}.
     */
    static ProbeReply scroll(String direction, String find, String until, long timeoutMs) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        String dir = direction == null ? "" : direction.trim().toLowerCase(java.util.Locale.ROOT);
        int action = scrollAction(dir);
        if (action == 0) {
            return ProbeReply.error("unknown scroll direction '" + direction
                    + "'; directions: down, forward, up, backward");
        }
        ProbeSelector target = find == null || find.isEmpty() ? null
                : ProbeSelector.parse(find);
        ProbeSelector untilSelector = until == null || until.isEmpty() ? null
                : ProbeSelector.parse(until);
        long timeout = Math.max(0, Math.min(timeoutMs, MAX_WAIT_MS));
        long deadline = SystemClock.uptimeMillis() + timeout;
        int attempts = 0;
        String last = "";
        while (true) {
            Nodes nodes = collectTargets();
            if (nodes == null) {
                return noWindow();
            }
            if (untilSelector != null) {
                int found = matchIndex(nodes, untilSelector);
                if (found >= 0) {
                    ProbeNode node = nodes.snapshots.get(found);
                    String answer = "SCROLL " + dir + " found " + untilSelector.label()
                            + (attempts == 0 ? " without scrolling"
                                    : " after " + attempts + " scroll(s)")
                            + ": " + node.brief();
                    nodes.recycle();
                    write("actions.txt", answer + "\n");
                    return ProbeReply.ok(answer);
                }
            }
            int at = target == null ? firstScrollable(nodes, action)
                    : matchIndex(nodes, target);
            if (at < 0) {
                String why = target == null
                        ? "no node accepts ACTION_SCROLL_" + (action
                                == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                        ? "FORWARD" : "BACKWARD")
                        : "no node matching " + target.label();
                String closest = target == null ? ""
                        : "; closest: " + target.candidates(nodes.snapshots);
                nodes.recycle();
                return ProbeReply.error("SCROLL " + dir + " failed: " + why + closest);
            }
            AccessibilityNodeInfo node = nodes.live.get(at);
            boolean performed = node.performAction(action);
            last = "SCROLL " + dir + " on " + nodeLabel(node) + " -> " + performed;
            nodes.recycle();
            write("actions.txt", last + "\n");
            if (!performed) {
                if (untilSelector == null) {
                    return ProbeReply.error(last
                            + " (the node did not accept the scroll action)");
                }
                break;
            }
            attempts++;
            if (untilSelector == null) {
                return ProbeReply.ok(last);
            }
            if (SystemClock.uptimeMillis() >= deadline) {
                break;
            }
            SystemClock.sleep(150);
        }
        Nodes finalNodes = collectTargets();
        String closest = finalNodes == null ? "(no window)"
                : untilSelector.candidates(finalNodes.snapshots);
        if (finalNodes != null) {
            finalNodes.recycle();
        }
        String answer = "SCROLL " + dir + " failed: " + untilSelector.label()
                + " not found after " + attempts + " scroll(s); closest: " + closest;
        write("actions.txt", answer + "\n");
        return ProbeReply.error(answer + (last.isEmpty() ? "" : " (" + last + ")"));
    }

    private static int scrollAction(String direction) {
        switch (direction) {
            case "down":
            case "forward":
            case "next":
                return AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            case "up":
            case "backward":
            case "previous":
                return AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            default:
                return 0;
        }
    }

    private static int firstScrollable(Nodes nodes, int action) {
        for (int i = 0; i < nodes.live.size(); i++) {
            if (hasAction(nodes.live.get(i), action)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * One key from a small allowlist, rather than arbitrary keycodes: back and
     * home are the service's global actions, enter is ACTION_IME_ENTER on the
     * focused node (the accessibility action an IME performs, Android 11+).
     */
    static ProbeReply key(String name) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        String key = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        switch (key) {
            case "back":
            case "home":
                boolean performed = instance.performGlobalAction("back".equals(key)
                        ? AccessibilityService.GLOBAL_ACTION_BACK
                        : AccessibilityService.GLOBAL_ACTION_HOME);
                String answer = "KEY " + key + " -> " + performed;
                write("actions.txt", answer + "\n");
                return performed ? ProbeReply.ok(answer)
                        : ProbeReply.error(answer + " (the service refused the global action)");
            case "enter":
                return pressEnter();
            default:
                return ProbeReply.error("unknown key '" + name
                        + "'; keys: back, home, enter");
        }
    }

    private static ProbeReply pressEnter() {
        AccessibilityNodeInfo node = focusedNode();
        if (node == null) {
            return ProbeReply.error("KEY enter needs a focused node; click or set_text"
                    + " a field first");
        }
        String label = nodeLabel(node);
        int action = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId();
        if (!hasAction(node, action)) {
            node.recycle();
            String answer = "KEY enter on " + label + " -> false"
                    + " (the node does not expose ACTION_IME_ENTER)";
            write("actions.txt", answer + "\n");
            return ProbeReply.error(answer);
        }
        boolean performed = node.performAction(action);
        String answer = "KEY enter on " + label + " -> " + performed;
        write("actions.txt", answer + "\n");
        node.recycle();
        return performed ? ProbeReply.ok(answer)
                : ProbeReply.error(answer + " (the node refused ACTION_IME_ENTER)");
    }

    /**
     * A gesture swipe, in screen coordinates: an accessibility service's own
     * dispatchGesture, not an {@code input swipe}. It waits for the gesture
     * callback so the answer says completed or cancelled rather than dispatched.
     */
    static ProbeReply swipe(String from, String to, long durationMs) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        int[] start = point(from, "from");
        int[] end = point(to, "to");
        long duration = durationMs <= 0 ? 300 : Math.min(durationMs, 10_000);
        Path path = new Path();
        path.moveTo(start[0], start[1]);
        path.lineTo(end[0], end[1]);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] completed = new boolean[1];
        final boolean[] cancelled = new boolean[1];
        boolean dispatched = instance.dispatchGesture(gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription finished) {
                        completed[0] = true;
                        done.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription finished) {
                        cancelled[0] = true;
                        done.countDown();
                    }
                }, null);
        String result = "not dispatched";
        if (dispatched) {
            try {
                if (!done.await(duration + 2000, TimeUnit.MILLISECONDS)) {
                    result = "timed out";
                } else if (completed[0]) {
                    result = "completed";
                } else if (cancelled[0]) {
                    result = "cancelled";
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                result = "interrupted";
            }
        }
        String answer = "SWIPE from=" + start[0] + "," + start[1]
                + " to=" + end[0] + "," + end[1]
                + " duration=" + duration + " -> " + result;
        write("actions.txt", answer + "\n");
        if (!dispatched) {
            return ProbeReply.error(answer
                    + " (dispatchGesture refused; is canPerformGestures set?)");
        }
        return "completed".equals(result) ? ProbeReply.ok(answer)
                : ProbeReply.error(answer);
    }

    private static int[] point(String raw, String which) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException(which + " needs x,y");
        }
        String[] parts = raw.trim().split(",", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(which + " must be x,y, got '" + raw + "'");
        }
        try {
            int x = Integer.parseInt(parts[0].trim());
            int y = Integer.parseInt(parts[1].trim());
            if (x < 0 || y < 0) {
                throw new IllegalArgumentException(which + " coordinates cannot be negative");
            }
            return new int[] { x, y };
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(which + " must be x,y, got '" + raw + "'");
        }
    }

    /**
     * Runs an assertion over the target window and writes the answer to
     * {@code expect.txt}, so a broadcast caller can read the same verdict the
     * provider returned.
     */
    static ProbeReply expect(String assertion, String rawSelector, String value) {
        if (instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        ProbeSelector selector = ProbeSelector.parse(rawSelector);
        Nodes nodes = collectTargets();
        if (nodes == null) {
            return noWindow();
        }
        ProbeReply reply = ProbeExpect.evaluate(assertion, selector, value, nodes.snapshots);
        nodes.recycle();
        writeReplace("expect.txt", "code=" + reply.code + " data=" + reply.data + "\n");
        write("actions.txt", "expect " + (assertion == null ? ProbeExpect.EXISTS : assertion)
                + " " + selector.label() + " -> code=" + reply.code + "\n");
        return reply;
    }

    static void write(String name, String body) {
        File directory = files();
        if (directory == null) {
            Log.w(TAG, "no files dir for " + name);
            return;
        }
        File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) {
            Log.w(TAG, "could not write " + file, failed);
        }
    }
}
