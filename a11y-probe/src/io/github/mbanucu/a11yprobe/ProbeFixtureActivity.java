package io.github.mbanucu.a11yprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * The probe's own fixture screen, for testing the probe rather than an app.
 *
 * <p>It has the three shapes every action needs: a lazily growing list (scroll,
 * swipe, long click, click, selectors), a text field with a Save action (set
 * text, clear, enter), and a dialog (present/absent, back). The list starts with
 * twenty rows and adds twenty more when it is scrolled near the bottom, so
 * {@code scroll until=text=Item 40} has to actually scroll to find itself
 * instead of matching a node that was already in the tree.
 *
 * <p>Started by {@code a11y-probe/probe-test.sh}:
 * {@code am start -n io.github.mbanucu.a11yprobe/.ProbeFixtureActivity}.
 * Ids are looked up by name rather than through {@code R}, because the same
 * sources build the legacy app id with a different generated R class.
 */
public class ProbeFixtureActivity extends Activity {

    private static final int ROWS = 60;
    private static final int BATCH = 20;

    private TextView status;
    private EditText field;
    private ScrollView scroll;
    private LinearLayout list;
    private AlertDialog dialog;
    private int rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
        reset();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        reset();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ProbeService.fixtureFront = true;
    }

    @Override
    protected void onPause() {
        ProbeService.fixtureFront = false;
        super.onPause();
    }

    private void build() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setId(id("fixture_status"));
        status.setText("Ready");
        status.setContentDescription("Fixture status");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);

        field = new EditText(this);
        field.setId(id("fixture_field"));
        field.setHint("Type a value");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });

        Button save = new Button(this);
        save.setId(id("fixture_save"));
        save.setText("Save");
        save.setOnClickListener(view -> save());

        Button open = new Button(this);
        open.setId(id("fixture_dialog_button"));
        open.setText("Open dialog");
        open.setOnClickListener(view -> openDialog());

        scroll = new ScrollView(this);
        scroll.setId(id("fixture_list"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            View content = scroll.getChildAt(0);
            if (content != null && y + scroll.getHeight() >= content.getHeight() - dp(48)) {
                addRows(BATCH);
            }
        });

        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void reset() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
        status.setText("Ready");
        field.setText("");
        scroll.scrollTo(0, 0);
        list.removeAllViews();
        rows = 0;
        addRows(BATCH);
    }

    private void addRows(int count) {
        if (list == null) {
            return;
        }
        for (int i = 0; i < count && rows < ROWS; i++) {
            rows++;
            final String label = String.format(Locale.US, "Item %02d", rows);
            TextView row = new TextView(this);
            row.setId(id("fixture_row"));
            row.setText(label);
            row.setContentDescription(String.format(Locale.US, "Row %02d", rows));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setPadding(dp(16), dp(8), dp(16), dp(8));
            row.setClickable(true);
            row.setOnClickListener(view -> status.setText("Picked " + label));
            row.setOnLongClickListener(view -> {
                status.setText("Long-clicked " + label);
                return true;
            });
            list.addView(row);
        }
    }

    private void save() {
        status.setText("Saved: " + field.getText());
    }

    private void openDialog() {
        if (dialog != null) {
            dialog.dismiss();
        }
        dialog = new AlertDialog.Builder(this)
                .setTitle("Fixture dialog")
                .setMessage("The dialog is open")
                .setPositiveButton("Dismiss",
                        (shown, which) -> status.setText("Dialog dismissed"))
                .create();
        dialog.show();
    }

    private int id(String name) {
        int value = getResources().getIdentifier(name, "id", getPackageName());
        if (value == 0) {
            throw new IllegalStateException("the fixture is missing the id " + name);
        }
        return value;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
