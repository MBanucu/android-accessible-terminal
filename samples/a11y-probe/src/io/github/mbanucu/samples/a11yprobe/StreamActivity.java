package io.github.mbanucu.samples.a11yprobe;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Asks for the screen, and hands the answer to {@link StreamService}.
 *
 * <p>Capturing the display is not a permission an app can hold: the system puts a
 * dialog in front of it every time, and only a person - or something standing in
 * for one - can accept. This repository's standing-in is the probe's own
 * accessibility service, so the flow is the one a screen reader user gets: this
 * activity asks, the dialog appears, the service reads it and clicks "Start now",
 * and the capture begins. Start it from adb:
 *
 * <pre>
 * am start -n io.github.mbanucu.samples.a11yprobe/.StreamActivity
 * </pre>
 *
 * <p>With the probe enabled, nothing else is needed: no tap, no root, and adb
 * only starts the activity. What comes out is {@link StreamService}.
 */
public final class StreamActivity extends Activity {

    private static final String TAG = "A11yProbe";
    private static final int REQUEST_CAPTURE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // `--ez stop true` ends a capture that is already running. Stopping the
        // service from here rather than force-stopping the app is deliberate: the
        // accessibility service stays bound, so a run that ends this way does not
        // cost the next one a rebind.
        if (getIntent().getBooleanExtra("stop", false)) {
            stopService(new Intent(this, StreamService.class));
            finish();
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            finish("no MediaProjectionManager on this device");
            return;
        }
        Intent capture;
        if (Build.VERSION.SDK_INT >= 34) {
            // The whole display, not one app: what a test records is the phone's
            // screen, including whatever dialog is in front of it.
            capture = manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            capture = manager.createScreenCaptureIntent();
        }
        startActivityForResult(capture, REQUEST_CAPTURE);
        // The system dialog is another window; the service is what clicks it.
        ProbeService.acceptScreenCaptureDialog();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_CAPTURE) {
            super.onActivityResult(request, result, data);
            return;
        }
        if (result != RESULT_OK || data == null) {
            finish("the screen capture dialog was not accepted");
            return;
        }
        // A machine on the far side of adb cannot take 18 MB a frame over a
        // wireless link; --ei scale 2 makes the compositor hand over half-size
        // frames instead of this class downscaling them afterwards.
        int scale = Math.max(1, getIntent().getIntExtra(StreamService.EXTRA_SCALE, 1));
        Intent service = new Intent(this, StreamService.class)
                .setAction(StreamService.ACTION_START)
                .putExtra(StreamService.EXTRA_RESULT, data)
                .putExtra(StreamService.EXTRA_SCALE, scale);
        startForegroundService(service);
        Log.i(TAG, "screen capture granted; the stream service is starting");
        finish();
    }

    private void finish(String why) {
        Status.replace(this, "stream.txt", "state=failed\nwhy=" + why + "\n");
        Log.w(TAG, why);
        finish();
    }
}
