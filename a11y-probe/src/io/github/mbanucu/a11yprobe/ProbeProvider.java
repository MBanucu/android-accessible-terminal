package io.github.mbanucu.a11yprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The probe's synchronous, scriptable surface:
 *
 * <pre>
 * adb shell content call --uri content://io.github.mbanucu.a11yprobe.control \
 *     --method dump --arg tree
 * </pre>
 *
 * <p>A command arrives as one binder call and answers with {@code code=0} (it
 * did what was asked) or {@code code=400} (a sentence in {@code data} says what
 * was wrong). {@code dump} also writes its file and returns its tree, and
 * {@code wait} blocks in the call and returns {@code matched ...} or
 * {@code timeout ...}, so a harness does not have to poll a file. A wait that
 * needs longer than the binder limit, or several at once, uses
 * {@code --extra id:s:<uuid>} (with {@code async:z:true} for the background
 * form) and reads the answer with {@code --method result}.
 *
 * <p><b>Security.</b> This provider is exported with no permission, exactly
 * like the command receiver beside it: the accessibility service being enabled
 * is the real switch, and while it is enabled anything that can reach this
 * provider can read the tree and perform the actions a screen reader performs.
 * It is a test tool for a test device. It writes only under the app's own
 * external files directory, and the file-name argument is cleaned so it cannot
 * name a path outside it.
 */
public class ProbeProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.mbanucu.a11yprobe.control";
    private static final String TAG = "A11yProbe";
    /** Enough for a harness's in-flight waits and a few of its last answers. */
    private static final int RESULT_CAPACITY = 64;
    private static final ProbeResults results = new ProbeResults(RESULT_CAPACITY);

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        String name = method == null ? "" : method.trim();
        ProbeReply reply;
        try {
            reply = execute(getContext(), name, args(arg, extras));
        } catch (IllegalArgumentException bad) {
            String message = bad.getMessage();
            reply = ProbeReply.error(message == null ? "invalid argument" : message);
        } catch (RuntimeException failed) {
            // A command that is malformed must answer 400, not take the probe
            // (and the accessibility service beside it) down with it.
            Log.e(TAG, "provider command " + name + " failed", failed);
            reply = ProbeReply.error("the probe failed: " + failed);
        }
        Bundle bundle = new Bundle();
        bundle.putInt("code", reply.code);
        bundle.putString("data", reply.data);
        if (reply.summary != null) {
            bundle.putString("summary", reply.summary);
        }
        if (reply.state != null) {
            bundle.putString("state", reply.state);
        }
        return bundle;
    }

    /** The provider's bundle as the argument shape the pure code expects. */
    private static ProbeArgs args(String arg, Bundle extras) {
        Map<String, String> values = new LinkedHashMap<>();
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value != null) {
                    values.put(key, String.valueOf(value));
                }
            }
        }
        return new ProbeArgs(arg, values);
    }

    private static ProbeReply execute(Context context, String method, ProbeArgs args) {
        String pkg = args.get("pkg");
        if (pkg != null && !pkg.isEmpty()) {
            ProbeService.setTarget(pkg);
        } else if (actsOnNodes(method)) {
            // A command without pkg is for whatever is in front, not for the
            // package an earlier call happened to leave in the target.
            ProbeService.setTarget(null);
        }
        switch (method) {
            case "dump":
                return dump(args);
            case "find":
                return find(args);
            case "set_text":
                return setText(args);
            case "click":
                return click(args);
            case "wait":
                return wait(args);
            case "inspect":
                return inspect(args);
            case "status":
                return ProbeReply.ok(ProbeService.status(context));
            case "version":
                return ProbeReply.ok(ProbeService.version(context));
            case "result":
                return result(args);
            case "batch":
                return batch(context, args);
            default:
                return ProbeReply.error("unknown method '" + method
                        + "'; methods: dump, find, set_text, click, wait, inspect, status,"
                        + " result, batch");
        }
    }

    private static ProbeReply dump(ProbeArgs args) {
        String name = args.firstOrArg("name", "dump");
        return ProbeService.dump(name == null ? "tree" : name, args.bool("json"));
    }

    /** The methods that read or act on the target window, not on the whole phone. */
    private static boolean actsOnNodes(String method) {
        return "find".equals(method) || "set_text".equals(method)
                || "click".equals(method) || "inspect".equals(method);
    }

    private static ProbeReply find(ProbeArgs args) {
        String needle = args.firstOrArg("find", "text", "needle");
        if (needle == null) {
            return ProbeReply.error("find needs a needle: --arg <text> or --extra find:s:<text>");
        }
        return ProbeService.findReply(needle);
    }

    private static ProbeReply setText(ProbeArgs args) {
        String text = args.get("text");
        if (text == null) {
            text = args.arg();
        }
        if (text == null) {
            return ProbeReply.error("set_text needs text: --extra text:s:<text> or --arg <text>");
        }
        // The same literal \n the broadcast accepts, for "and press enter".
        text = text.replace("\\n", "\n");
        String find = args.first("find", "node");
        return ProbeService.setText(find, text);
    }

    private static ProbeReply click(ProbeArgs args) {
        String needle = args.firstOrArg("find", "text", "node");
        if (needle == null) {
            return ProbeReply.error("click needs a node: --arg <text> or --extra find:s:<text>");
        }
        return ProbeService.click(needle);
    }

    private static ProbeReply inspect(ProbeArgs args) {
        String name = args.firstOrArg("name", "inspect");
        return ProbeService.inspect(name == null ? "inspect" : name);
    }

    /**
     * Waits for an accessibility event, synchronously by default.
     *
     * <p>The call blocks on the provider's binder thread, which is not the main
     * thread, so the accessibility event that ends the wait can still be
     * delivered. {@code timeout} is honoured up to {@link ProbeService#MAX_WAIT_MS};
     * a longer wait is a background wait with an id and {@code result}.
     *
     * <p>With an id the answer is also kept for {@code result}. With
     * {@code async:z:true} the call returns as soon as the wait is armed, and
     * the answer can be collected later; without it the answer is returned now.
     */
    private static ProbeReply wait(ProbeArgs args) {
        if (ProbeService.instance == null) {
            return ProbeReply.error(ProbeService.NO_SERVICE);
        }
        String needle = args.firstOrArg("wait", "find", "text", "needle");
        if (needle == null) {
            return ProbeReply.error("wait needs a needle: --arg <text> or --extra wait:s:<text>");
        }
        String type = args.first("event", "type");
        long timeout = Math.max(0, Math.min(args.longValue(5000, "timeout", "waitMs"),
                ProbeService.MAX_WAIT_MS));
        String id = args.first("id");
        boolean async = args.bool("async");
        if (async && id == null) {
            return ProbeReply.error("async needs an id: --extra id:s:<uuid>");
        }
        if (id != null && !results.reserve(id)) {
            return ProbeReply.error("request id '" + id + "' is already in use");
        }
        // Captured before the wait starts: the action that follows can then be
        // sure the event it causes is newer than this. In a batch, the batch's
        // own start is used, so `click` followed by `wait` can see the event
        // the click caused - the wait line runs after the action.
        long start = args.longValue(ProbeService.eventSeq(), "since");
        if (async) {
            new Thread(() -> finishWait(id, needle, type, timeout, start),
                    "probe-wait-" + id).start();
            return ProbeReply.started("started id=" + id, "started");
        }
        String answer = ProbeService.waitFor(needle, type, timeout, start);
        ProbeService.write("wait.txt", answer);
        if (id != null) {
            results.complete(id, answer);
        }
        return waitReply(answer);
    }

    /** The background half of an asynchronous wait. */
    private static void finishWait(String id, String needle, String type, long timeout,
            long start) {
        String answer = ProbeService.waitFor(needle, type, timeout, start);
        results.complete(id, answer);
        // The old file name, for one release: a harness still polling it sees
        // this wait too. Id callers never read it, so a concurrent wait cannot
        // overwrite what they see.
        ProbeService.write("wait.txt", answer);
    }

    private static ProbeReply waitReply(String answer) {
        if (answer.startsWith("matched")) {
            return ProbeReply.state(answer, "matched");
        }
        if (answer.startsWith("timeout")) {
            return ProbeReply.state(answer, "timeout");
        }
        if (answer.startsWith("interrupted")) {
            return ProbeReply.error("the wait was interrupted");
        }
        return ProbeReply.error(answer.strip());
    }

    private static ProbeReply result(ProbeArgs args) {
        String id = args.firstOrArg("id");
        if (id == null) {
            return ProbeReply.error("result needs an id: --extra id:s:<uuid>");
        }
        if (!results.has(id)) {
            return ProbeReply.error("no such request id '" + id + "'");
        }
        String answer = results.get(id);
        if (answer == null) {
            return ProbeReply.state("pending id=" + id, "pending");
        }
        return ProbeReply.state(answer, "done");
    }

    /**
     * Several commands in one call, in order, one result line each.
     *
     * <p>A line that fails answers its own {@code code=400} and the batch goes
     * on, so a caller always gets as many lines as it sent commands; the
     * batch's own code is 400 when any line was.
     */
    private static ProbeReply batch(Context context, ProbeArgs args) {
        String body = args.firstOrArg("commands", "batch");
        if (body == null || body.strip().isEmpty()) {
            return ProbeReply.error("batch needs commands: --arg with one command per line");
        }
        List<ProbeBatch.Line> lines = ProbeBatch.parse(body);
        if (lines.isEmpty()) {
            return ProbeReply.error("batch has no commands");
        }
        StringBuilder data = new StringBuilder();
        boolean failed = false;
        int number = 0;
        // The sequence one batch sees as its beginning. A wait line runs after
        // the actions above it, so anchoring it here is what lets a batch do
        // find + click + wait and still match the click's event.
        String batchStart = Long.toString(ProbeService.eventSeq());
        for (ProbeBatch.Line line : lines) {
            number++;
            ProbeReply reply;
            if ("batch".equals(line.method)) {
                reply = ProbeReply.error("batch cannot run inside a batch");
            } else {
                try {
                    ProbeArgs lineArgs = line.args.inherit(args,
                            "pkg", "json", "timeout", "waitMs", "event")
                            .withDefault("since", batchStart);
                    reply = execute(context, line.method, lineArgs);
                } catch (IllegalArgumentException bad) {
                    reply = ProbeReply.error("line " + number + ": "
                            + (bad.getMessage() == null ? "invalid argument" : bad.getMessage()));
                } catch (RuntimeException failedCommand) {
                    Log.e(TAG, "batch line " + number + " failed", failedCommand);
                    reply = ProbeReply.error("line " + number + " failed: " + failedCommand);
                }
            }
            if (!reply.ok()) {
                failed = true;
            }
            if (number > 1) {
                data.append('\n');
            }
            data.append(ProbeBatch.resultLine(number, reply));
        }
        return failed ? ProbeReply.error(data.toString()) : ProbeReply.ok(data.toString());
    }

    // A provider is not a database: the command surface is call(), and every
    // other entry point stays empty rather than inventing a schema.
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
