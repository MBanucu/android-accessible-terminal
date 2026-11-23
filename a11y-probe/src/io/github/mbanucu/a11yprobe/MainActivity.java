package io.github.mbanucu.a11yprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/** Says how to switch the probe on, and where its answers are written. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setTextIsSelectable(true);
        File files = getExternalFilesDir(null);
        String pkg = getPackageName();
        text.setText("A11y probe\n\n"
                + "This app is only an accessibility service. Enable it from adb:\n\n"
                + "settings put secure enabled_accessibility_services "
                + pkg + "/.ProbeService\n"
                + "settings put secure accessibility_enabled 1\n\n"
                + "The probe.sh wrapper saves and restores the previous setting:\n\n"
                + "bash probe.sh enable\n"
                + "bash probe.sh status\n\n"
                + "Then, with the screen you want to inspect in front:\n\n"
                + "content call --uri content://" + pkg + ".control \\\n"
                + "    --method dump --arg tree\n\n"
                + "Or the old broadcast, kept for one release:\n\n"
                + "am broadcast -a " + pkg + ".COMMAND "
                + "--es dump tree\n"
                + "am broadcast -a " + pkg + ".COMMAND "
                + "--es find \"$ \" --es text \"echo hi\"\n"
                + "am broadcast -a " + pkg + ".COMMAND "
                + "--es version true\n\n"
                + "Answers are written to:\n" + (files == null ? "?" : files.getAbsolutePath())
                + "/tree.txt, actions.txt and version.txt\n");
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16,
                getResources().getDisplayMetrics()));
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }
}
