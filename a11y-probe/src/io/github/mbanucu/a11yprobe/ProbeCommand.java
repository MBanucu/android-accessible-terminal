package io.github.mbanucu.a11yprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * The probe's commands, so a test can act like a screen reader without a UI:
 *
 * <pre>
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND \
 *     --es dump tree --es find "$ " --es text "echo hi"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es pkg APP \
 *     --es pixels colour --es region 0,150,1440,900 \
 *     --es colours "red,150,255,0,109,0,109,200"
 * am broadcast -a io.github.mbanucu.a11yprobe.COMMAND --es version true
 * </pre>
 *
 * {@code dump} names the file, {@code find} picks the node, {@code text} is what
 * SET_TEXT types, {@code pixels} starts a pixel watch that answers in its own
 * file. {@code version} reports the installed version so a harness can refuse a
 * probe it does not know. Answers go to files/actions.txt and to the log; the
 * version also goes to files/version.txt.
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
        String find = intent.getStringExtra("find");
        String text = intent.getStringExtra("text");
        String dump = intent.getStringExtra("dump");
        String inspect = intent.getStringExtra("inspect");
        String wait = intent.getStringExtra("wait");
        String pixels = intent.getStringExtra("pixels");
        boolean focusRow = intent.getBooleanExtra("focusRow", false);
        String eventType = intent.getStringExtra("event");
        String version = intent.getStringExtra("version");
        long waitMs = intent.getLongExtra("waitMs", 5000);
        // Sent from adb as a literal \n, meaning "and press enter".
        if (text != null) {
            text = text.replace("\\n", "\n");
        }
        ProbeService.setTarget(intent.getStringExtra("pkg"));
        final String result;
        if (intent.getBooleanExtra("version", false)
                || "true".equalsIgnoreCase(version) || "version".equalsIgnoreCase(version)) {
            // A harness pins the version the way it pins adb-harness: ask the app,
            // not the APK filename, so a stale install cannot look current.
            result = ProbeService.version(context);
        } else if (pixels != null) {
            // The screenshots, the frame numbering and the counting all happen on
            // this side; the test only polls the file this answers in.
            result = PixelWatch.start(ProbeService.instance, pixels,
                    intent.getStringExtra("region"),
                    intent.getStringExtra("colours"),
                    intent.getStringExtra("cellgrid"),
                    intent.getStringExtra("cells"),
                    intent.getBooleanExtra("across", false),
                    intent.getLongExtra("waitMs", 15000));
        } else if (inspect != null) {
            result = ProbeService.inspect(inspect);
        } else if (focusRow) {
            result = ProbeService.focusFirstRow();
        } else if (wait != null) {
            // Off the main thread: accessibility callbacks are delivered there,
            // and a wait on the main thread could never see its event.
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
            result = "waiting for '" + wait + "'";
        } else if (dump != null) {
            result = ProbeService.dump(dump);
        } else if (text != null) {
            result = ProbeService.setText(find, text);
        } else if (intent.getBooleanExtra("click", false)) {
            result = ProbeService.click(find);
        } else {
            result = ProbeService.focus(find);
        }
        Log.i(TAG, result);
        ProbeService.write("actions.txt", "command find=" + find + " text=" + text + " -> "
                + result + "\n");
        Toast.makeText(context, result, Toast.LENGTH_SHORT).show();
    }
}
