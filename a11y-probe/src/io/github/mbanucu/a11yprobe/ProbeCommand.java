package io.github.mbanucu.a11yprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import java.util.function.Supplier;

/**
 * The probe's original broadcast commands, kept for one release.
 *
 * <pre>
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND \
 *     --es dump tree --es find "text=$ " --es text "echo hi"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es pkg APP \
 *     --es scroll down --es until "text=Item 40"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es key back
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --ez longClick true \
 *     --es find "text=Item 03"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --ez clear true \
 *     --es find "id=io.example:id/field"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND \
 *     --es swipe 100,500,100,200
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND \
 *     --es expect text_contains --es find "text=saved"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --el waitIdle 500
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es pixels colour \
 *     --es region 0,150,1440,900 \
 *     --es colours "red,150,255,0,109,0,109,200"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es version true
 * </pre>
 *
 * <p>The synchronous surface is {@link ProbeProvider}; it answers a command in
 * the call instead of in a file. This receiver stays because a harness may still
 * send the old action: {@code dump} names the file, {@code find} picks the node,
 * {@code text} is what SET_TEXT types, {@code pixels} starts a pixel watch that
 * answers in its own file, and {@code version} reports the installed version.
 * The actions the provider gained - scroll, key, long_click, clear, swipe,
 * expect and wait_idle - are accepted here too, in their camelCase form.
 * Answers go to files/actions.txt and to the log; the version also goes to
 * files/version.txt, and an assertion to files/expect.txt.
 *
 * <p>Blocking commands (a scroll with until, a swipe, a wait for idle) run off
 * the main thread: accessibility callbacks and the gesture result are delivered
 * there, so waiting on it would wait forever. Their answers are written to the
 * file and the log; a broadcast has no return value to carry them.
 *
 * <p>The old {@code io.github.mbanucu.samples.a11yprobe.COMMAND} action is still
 * declared by the manifest for one release.
 */
public class ProbeCommand extends BroadcastReceiver {

    private static final String TAG = "A11yProbe";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        try {
            onCommand(context, intent);
        } catch (IllegalArgumentException bad) {
            String message = bad.getMessage();
            report(context, intent.getStringExtra("find"), intent.getStringExtra("text"),
                    ProbeReply.error(message == null ? "invalid argument" : message));
        } catch (RuntimeException failed) {
            Log.e(TAG, "broadcast command failed", failed);
            report(context, intent.getStringExtra("find"), intent.getStringExtra("text"),
                    ProbeReply.error("the probe failed: " + failed));
        }
    }

    private void onCommand(Context context, Intent intent) {
        String find = intent.getStringExtra("find");
        String text = intent.getStringExtra("text");
        String dump = intent.getStringExtra("dump");
        String inspect = intent.getStringExtra("inspect");
        String wait = intent.getStringExtra("wait");
        String pixels = intent.getStringExtra("pixels");
        boolean focusRow = intent.getBooleanExtra("focusRow", false);
        String eventType = intent.getStringExtra("event");
        String version = intent.getStringExtra("version");
        String scroll = intent.getStringExtra("scroll");
        String until = intent.getStringExtra("until");
        String key = intent.getStringExtra("key");
        boolean longClick = intent.getBooleanExtra("longClick", false);
        boolean clear = intent.getBooleanExtra("clear", false);
        String swipe = intent.getStringExtra("swipe");
        String from = intent.getStringExtra("from");
        String to = intent.getStringExtra("to");
        String expect = intent.getStringExtra("expect");
        long waitIdle = intent.getLongExtra("waitIdle", -1);
        long waitMs = intent.getLongExtra("waitMs", 5000);
        // Sent from adb as a literal \n, meaning "and press enter".
        if (text != null) {
            text = text.replace("\\n", "\n");
        }
        ProbeService.setTarget(intent.getStringExtra("pkg"));
        ProbeReply reply;
        if (intent.getBooleanExtra("version", false)
                || "true".equalsIgnoreCase(version) || "version".equalsIgnoreCase(version)) {
            // A harness pins the version the way it pins adb-harness: ask the app,
            // not the APK filename, so a stale install cannot look current.
            reply = ProbeReply.ok(ProbeService.version(context));
        } else if (pixels != null) {
            // The screenshots, the frame numbering and the counting all happen on
            // this side; the test only polls the file this answers in.
            reply = ProbeReply.ok(PixelWatch.start(ProbeService.instance, pixels,
                    intent.getStringExtra("region"),
                    intent.getStringExtra("colours"),
                    intent.getStringExtra("cellgrid"),
                    intent.getStringExtra("cells"),
                    intent.getBooleanExtra("across", false),
                    intent.getLongExtra("waitMs", 15000)));
        } else if (inspect != null) {
            reply = ProbeService.inspect(inspect);
        } else if (focusRow) {
            reply = ProbeReply.ok(ProbeService.focusFirstRow());
        } else if (wait != null) {
            // Off the main thread: accessibility callbacks are delivered there,
            // and a wait on the main thread could never see its event. This is
            // the old fire-and-poll form; ProbeProvider's wait returns in the
            // call and its id form keeps concurrent waits apart.
            final String needle = wait;
            final String type = eventType;
            final long timeoutMs = waitMs;
            // Captured here, on the main thread, before this broadcast returns:
            // the action that follows can then be sure its event is newer.
            final long startSeq = ProbeService.eventSeq();
            new Thread(() -> {
                String answer = ProbeService.waitFor(needle, type, timeoutMs, startSeq);
                ProbeService.write("wait.txt", answer);
                Log.i(TAG, answer);
            }, "probe-wait").start();
            String result = "waiting for '" + wait + "'";
            Log.i(TAG, result);
            ProbeService.write("actions.txt", "command find=" + find + " text=" + text + " -> "
                    + result + "\n");
            Toast.makeText(context, result, Toast.LENGTH_SHORT).show();
            return;
        } else if (dump != null) {
            reply = ProbeService.dump(dump, intent.getBooleanExtra("json", false));
        } else if (scroll != null) {
            final String direction = scroll;
            final String target = find;
            final String untilText = until;
            if (untilText != null && !untilText.isEmpty()) {
                later("scroll " + direction + " until " + untilText,
                        () -> ProbeService.scroll(direction, target, untilText, waitMs));
                return;
            }
            reply = ProbeService.scroll(direction, target, null, waitMs);
        } else if (key != null) {
            reply = ProbeService.key(key);
        } else if (longClick) {
            reply = ProbeService.longClick(find);
        } else if (clear) {
            reply = ProbeService.clear(find);
        } else if (swipe != null || (from != null && to != null)) {
            String start = from;
            String end = to;
            if (swipe != null && start == null && end == null) {
                String[] points = swipe.split(",", -1);
                if (points.length == 4) {
                    start = points[0].trim() + "," + points[1].trim();
                    end = points[2].trim() + "," + points[3].trim();
                }
            }
            if (start == null || end == null) {
                reply = ProbeReply.error("swipe needs x1,y1,x2,y2");
            } else {
                final String swipeFrom = start;
                final String swipeTo = end;
                later("swipe " + swipeFrom + " " + swipeTo,
                        () -> ProbeService.swipe(swipeFrom, swipeTo, waitMs));
                return;
            }
        } else if (expect != null) {
            if (ProbeExpect.isAssertion(expect) && find != null) {
                ProbeExpect.Request request = ProbeExpect.request(find, expect);
                reply = ProbeService.expect(request.assertion, request.selector,
                        intent.getStringExtra("value"));
            } else if (ProbeExpect.isAssertion(expect)) {
                reply = ProbeReply.error("expect " + expect
                        + " needs --es find '<selector>'");
            } else {
                ProbeExpect.Request request = ProbeExpect.request(expect, null);
                reply = ProbeService.expect(request.assertion, request.selector,
                        intent.getStringExtra("value"));
            }
        } else if (waitIdle >= 0) {
            final long idle = waitIdle;
            final long timeoutMs = waitMs;
            later("wait_idle " + idle, () -> {
                String answer = ProbeService.waitIdle(idle, timeoutMs);
                ProbeService.writeReplace("idle.txt", answer);
                return answer.startsWith("timeout")
                        ? ProbeReply.state(answer, "timeout")
                        : ProbeReply.state(answer, "idle");
            });
            return;
        } else if (text != null) {
            reply = ProbeService.setText(find, text);
        } else if (intent.getBooleanExtra("click", false)) {
            reply = ProbeService.click(find);
        } else {
            reply = ProbeService.focus(find);
        }
        report(context, find, text, reply);
    }

    private static void report(Context context, String find, String text, ProbeReply reply) {
        String result = reply.logLine();
        Log.i(TAG, result);
        ProbeService.write("actions.txt", "command find=" + find + " text=" + text + " -> "
                + result + "\n");
        Toast.makeText(context, result, Toast.LENGTH_SHORT).show();
    }

    /**
     * Runs a blocking command off the main thread; its answer goes to the same
     * files a synchronous call would write, because a broadcast cannot return it.
     */
    private static void later(String label, Supplier<ProbeReply> action) {
        new Thread(() -> {
            ProbeReply reply;
            try {
                reply = action.get();
            } catch (RuntimeException failed) {
                reply = ProbeReply.error("the probe failed: " + failed);
            }
            String result = reply.logLine();
            Log.i(TAG, result);
            ProbeService.write("actions.txt", "command " + label + " -> " + result + "\n");
        }, "probe-" + label.replace(' ', '-')).start();
    }
}
