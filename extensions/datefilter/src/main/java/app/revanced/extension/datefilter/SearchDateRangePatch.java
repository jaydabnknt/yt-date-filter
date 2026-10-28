package app.revanced.extension.datefilter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows a small "date range" pill under the YouTube search box while it is visible.
 * Tapping it lets the user pick From/To dates, then rewrites the query with YouTube's
 * {@code after:yyyy-MM-dd before:yyyy-MM-dd} search operators and runs the search.
 */
@SuppressWarnings("unused")
public final class SearchDateRangePatch {
    private static final String TAG = "revanced: DateRange";

    /** Matches existing date operators, so they can be read back and replaced. */
    private static final Pattern OPERATOR = Pattern.compile(
            "(?i)(?:^|\\s)(after|before):(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?=\\s|$)");

    private static final long SCAN_DELAY_MS = 200;

    private static boolean callbacksRegistered;

    private static final WeakHashMap<Activity, Controller> controllers = new WeakHashMap<>();

    /**
     * Injection point: start of MainActivity.onCreate.
     * Nothing touches the window here (that could break feature requests made later in
     * onCreate); the UI is attached once the activity is resumed.
     */
    public static void onCreate(Activity activity) {
        try {
            if (callbacksRegistered) return;
            callbacksRegistered = true;

            activity.getApplication().registerActivityLifecycleCallbacks(new LifecycleCallbacks(activity.getClass()));
        } catch (Throwable ex) {
            Log.e(TAG, "onCreate failure", ex);
        }
    }

    private static final class LifecycleCallbacks implements Application.ActivityLifecycleCallbacks {
        private final Class<?> mainActivityClass;

        LifecycleCallbacks(Class<?> mainActivityClass) {
            this.mainActivityClass = mainActivityClass;
        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (activity.getClass() != mainActivityClass) return;
            try {
                Controller controller = controllers.get(activity);
                if (controller == null) {
                    controller = new Controller(activity);
                    controllers.put(activity, controller);
                }
                controller.attach();
            } catch (Throwable ex) {
                Log.e(TAG, "attach failure", ex);
            }
        }

        @Override
        public void onActivityPaused(Activity activity) {
            Controller controller = controllers.get(activity);
            if (controller != null) controller.hidePill();
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            Controller controller = controllers.remove(activity);
            if (controller != null) controller.detach();
        }

        @Override public void onActivityCreated(Activity a, Bundle b) {}
        @Override public void onActivityStarted(Activity a) {}
        @Override public void onActivityStopped(Activity a) {}
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    }

    /** Selected dates. Month is 1-based. Null means "no limit". */
    private static final class Range {
        int[] from;
        int[] to;

        boolean isEmpty() {
            return from == null && to == null;
        }
    }

    private static final class Controller implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> activityRef;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable scanRunnable = this::scan;

        private WeakReference<EditText> searchBoxRef = new WeakReference<>(null);
        private TextView pill;
        private boolean attached;
        private boolean scanPending;

        Controller(Activity activity) {
            activityRef = new WeakReference<>(activity);
        }

        void attach() {
            Activity activity = activityRef.get();
            if (activity == null) return;
            View decor = activity.getWindow().getDecorView();
            if (!attached) {
                decor.getViewTreeObserver().addOnGlobalLayoutListener(this);
                attached = true;
            }
            scheduleScan();
        }

        void detach() {
            handler.removeCallbacksAndMessages(null);
            Activity activity = activityRef.get();
            if (activity == null) return;
            try {
                activity.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(this);
            } catch (Throwable ignored) {
            }
            attached = false;
        }

        @Override
        public void onGlobalLayout() {
            scheduleScan();
        }

        private void scheduleScan() {
            if (scanPending) return;
            scanPending = true;
            handler.postDelayed(scanRunnable, SCAN_DELAY_MS);
        }

        private void scan() {
            scanPending = false;
            try {
                Activity activity = activityRef.get();
                if (activity == null || activity.isFinishing()) return;
                ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

                EditText searchBox = searchBoxRef.get();
                if (!isUsable(searchBox)) {
                    searchBox = findSearchBox(decor);
                    searchBoxRef = new WeakReference<>(searchBox);
                }

                if (searchBox == null) {
                    hidePill();
                } else {
                    showPill(activity, decor, searchBox);
                }
            } catch (Throwable ex) {
                Log.e(TAG, "scan failure", ex);
            }
        }

        void hidePill() {
            if (pill != null) pill.setVisibility(View.GONE);
        }

        private void showPill(Activity activity, ViewGroup decor, EditText searchBox) {
            if (pill == null || pill.getParent() != decor) {
                pill = createPill(activity);
                decor.addView(pill, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START));
            }

            Range range = parse(searchBox.getText());
            String label = range.isEmpty()
                    ? "📅 Date range"
                    : "📅 " + format(range.from, "…") + " → " + format(range.to, "…");
            if (!label.contentEquals(pill.getText())) pill.setText(label);
            pill.setOnClickListener(v -> openDialog(activity, searchBox));

            // Place the pill just below the search box, aligned to its right edge.
            int[] loc = new int[2];
            searchBox.getLocationInWindow(loc);
            int pillWidth = pill.getWidth();
            if (pillWidth == 0) {
                pill.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                pillWidth = pill.getMeasuredWidth();
            }
            int margin = dp(activity, 6);
            float x = Math.max(margin, loc[0] + searchBox.getWidth() - pillWidth);
            x = Math.min(x, decor.getWidth() - pillWidth - margin);
            float y = loc[1] + searchBox.getHeight() + margin;

            if (pill.getTranslationX() != x) pill.setTranslationX(x);
            if (pill.getTranslationY() != y) pill.setTranslationY(y);
            if (pill.getVisibility() != View.VISIBLE) pill.setVisibility(View.VISIBLE);
            pill.bringToFront();
        }

        private static boolean isUsable(EditText editText) {
            return editText != null && editText.isAttachedToWindow() && editText.isShown()
                    && editText.getWidth() > 0;
        }

        /** Finds the visible YouTube search text field. */
        private static EditText findSearchBox(View view) {
            if (view == null || view.getVisibility() != View.VISIBLE) return null;
            if (view instanceof EditText) {
                EditText editText = (EditText) view;
                if (isUsable(editText) && looksLikeSearchBox(editText)) return editText;
                return null;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0, n = group.getChildCount(); i < n; i++) {
                    EditText found = findSearchBox(group.getChildAt(i));
                    if (found != null) return found;
                }
            }
            return null;
        }

        private static boolean looksLikeSearchBox(EditText editText) {
            if ((editText.getImeOptions() & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH) {
                return true;
            }
            int id = editText.getId();
            if (id != View.NO_ID) {
                try {
                    String name = editText.getResources().getResourceEntryName(id);
                    if (name != null && name.toLowerCase(Locale.ROOT).contains("search")) return true;
                } catch (Resources.NotFoundException ignored) {
                }
            }
            return false;
        }

        private TextView createPill(Context context) {
            boolean dark = isDark(context);
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            view.setTextColor(dark ? Color.WHITE : Color.BLACK);
            view.setSingleLine(true);
            int h = dp(context, 12), v = dp(context, 6);
            view.setPadding(h, v, h, v);

            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(context, 16));
            background.setColor(dark ? 0xFF3A3A3A : 0xFFE8E8E8);
            view.setBackground(background);
            view.setElevation(dp(context, 4));
            view.setClickable(true);
            view.setFocusable(false);
            return view;
        }
    }

    // region Dialog

    private static void openDialog(Activity activity, EditText searchBox) {
        try {
            Range range = parse(searchBox.getText());
            boolean dark = isDark(activity);
            int dialogTheme = dark
                    ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;

            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 20);
            layout.setPadding(pad, dp(activity, 8), pad, 0);

            Button fromButton = new Button(activity);
            Button toButton = new Button(activity);
            fromButton.setAllCaps(false);
            toButton.setAllCaps(false);
            layout.addView(fromButton);
            layout.addView(toButton);

            Runnable refresh = () -> {
                fromButton.setText("From: " + format(range.from, "any date"));
                toButton.setText("To: " + format(range.to, "any date"));
            };
            refresh.run();

            fromButton.setOnClickListener(v -> pickDate(activity, dialogTheme, range.from, picked -> {
                range.from = picked;
                if (range.to != null && compare(range.from, range.to) > 0) range.to = null;
                refresh.run();
            }));
            toButton.setOnClickListener(v -> pickDate(activity, dialogTheme, range.to, picked -> {
                range.to = picked;
                if (range.from != null && compare(range.from, range.to) > 0) range.from = null;
                refresh.run();
            }));

            new AlertDialog.Builder(activity, dialogTheme)
                    .setTitle("Upload date range")
                    .setView(layout)
                    .setPositiveButton("Search", (d, w) -> apply(activity, searchBox, range))
                    .setNeutralButton("Clear", (d, w) -> {
                        range.from = null;
                        range.to = null;
                        apply(activity, searchBox, range);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable ex) {
            Log.e(TAG, "openDialog failure", ex);
        }
    }

    private interface DateCallback {
        void onPicked(int[] date);
    }

    private static void pickDate(Activity activity, int theme, int[] initial, DateCallback callback) {
        Calendar now = Calendar.getInstance();
        int year = initial != null ? initial[0] : now.get(Calendar.YEAR);
        int month = initial != null ? initial[1] - 1 : now.get(Calendar.MONTH);
        int day = initial != null ? initial[2] : now.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog dialog = new DatePickerDialog(activity, theme,
                (view, y, m, d) -> callback.onPicked(new int[]{y, m + 1, d}), year, month, day);
        dialog.getDatePicker().setMaxDate(now.getTimeInMillis());
        // YouTube launched in 2005.
        Calendar min = Calendar.getInstance();
        min.set(2005, Calendar.JANUARY, 1);
        dialog.getDatePicker().setMinDate(min.getTimeInMillis());
        dialog.show();
    }

    private static void apply(Activity activity, EditText searchBox, Range range) {
        try {
            String base = OPERATOR.matcher(searchBox.getText().toString()).replaceAll(" ").trim();
            if (base.isEmpty()) {
                Toast.makeText(activity, "Type something to search for first", Toast.LENGTH_SHORT).show();
                return;
            }

            StringBuilder query = new StringBuilder(base);
            if (range.from != null) query.append(" after:").append(format(range.from, ""));
            if (range.to != null) query.append(" before:").append(format(range.to, ""));
            String text = query.toString();

            searchBox.requestFocus();
            searchBox.setText(text);
            searchBox.setSelection(text.length());
            submit(searchBox);
        } catch (Throwable ex) {
            Log.e(TAG, "apply failure", ex);
        }
    }

    /** Runs the search the same way pressing the keyboard's search key would. */
    private static void submit(EditText searchBox) {
        searchBox.onEditorAction(EditorInfo.IME_ACTION_SEARCH);

        // Fallback for builds that listen for the Enter key instead of the IME action:
        // if the search box is still on screen shortly after, press Enter.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (searchBox.isAttachedToWindow() && searchBox.isShown() && searchBox.hasFocus()) {
                searchBox.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                searchBox.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            }
        }, 400);
    }

    // endregion

    // region Helpers

    private static Range parse(Editable text) {
        Range range = new Range();
        if (text == null) return range;
        Matcher matcher = OPERATOR.matcher(text);
        while (matcher.find()) {
            try {
                int[] date = {
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4))
                };
                if ("after".equalsIgnoreCase(matcher.group(1))) range.from = date;
                else range.to = date;
            } catch (NumberFormatException ignored) {
            }
        }
        return range;
    }

    private static String format(int[] date, String fallback) {
        if (date == null) return fallback;
        return String.format(Locale.ROOT, "%04d-%02d-%02d", date[0], date[1], date[2]);
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }

    private static boolean isDark(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int dp(Context context, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics()));
    }

    // endregion
}
