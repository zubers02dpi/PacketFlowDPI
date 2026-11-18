package dev.packetflow.dpi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * PacketFlowDPI: one big button in the middle, a settings sheet with the single
 * working strategy, accent colour choice and a placeholder for the future
 * automatic strategy picker.
 */
public class MainActivity extends Activity implements TunService.Ui {

    static final String APP_NAME = "PacketFlowDPI";

    /** The only recipe that works on this network. */
    static final String STRATEGY = Strategy.DEFAULT;

    /** accent, light; the dark tone is derived from the accent. */
    static final int[][] THEMES = {
            {0xFF35E08A, 0xFF9BFFCD},   // мята
            {0xFF35C8FF, 0xFFB4ECFF},   // аква
            {0xFF9B7BFF, 0xFFD9CBFF},   // фиолет
            {0xFFFFA23A, 0xFFFFD9A8},   // янтарь
            {0xFFFF5C6C, 0xFFFFC5CB},   // коралл
            {0xFF5B8CFF, 0xFFC6D6FF}    // синий
    };

    private static final String PREFS = "packetflow";
    private static final String PREF_ACCENT = "accent";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logLines = new StringBuilder();

    private PowerView power;
    private TextView stateText;
    private TextView footer;
    private SpectrumView gauge;

    private FrameLayout root;
    private FrameLayout settings;
    private TextView closeBtn;
    private TextView settingsLog;
    private TextView settingsStats;
    private ScrollView settingsLogScroll;

    private boolean logDirty;
    private boolean settingsOpen;

    /** True on phones: tighter margins, scrolling settings, smaller controls. */
    private boolean compact;

    private int themeIndex;
    private int accent;
    private int light;

    private long lastDown;
    private float level;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Log.i("PACKETFLOWUI", "activity: create");
        compact = !isTelevision();
        loadTheme();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070A);
        root.addView(buildMain());
        settings = buildSettings();
        settings.setVisibility(View.GONE);
        root.addView(settings);
        setContentView(root);

        applyIntent(getIntent());
        refresh();
        ui.post(ticker);
        power.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i("PACKETFLOWUI", "activity: resume, settingsOpen=" + settingsOpen);
        TunService.ui = this;
        settingsOpen = false;
        settings.setVisibility(View.GONE);
        refresh();
    }

    @Override
    protected void onPause() {
        TunService.ui = null;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    @Override
    public void onBackPressed() {
        Log.i("PACKETFLOWUI", "activity: back, settingsOpen=" + settingsOpen);
        if (settingsOpen) {
            closeSettings();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ main screen

    private boolean isTelevision() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
    }

    private View buildMain() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(compact ? 20 : 64), dp(compact ? 20 : 36),
                dp(compact ? 20 : 64), dp(compact ? 16 : 28));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        SpannableString name = new SpannableString(APP_NAME);
        int split = APP_NAME.length() - 3;
        name.setSpan(new ForegroundColorSpan(0xFFEAF2F8), 0, split, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        name.setSpan(new ForegroundColorSpan(accent), split, APP_NAME.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(name);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 16 : 18);
        titles.addView(title);

        TextView sub = new TextView(this);
        sub.setText("свой VPN · каркас для обхода DPI");
        sub.setTextColor(0xFF66727E);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 11 : 12);
        sub.setPadding(0, dp(3), 0, 0);
        titles.addView(sub);

        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(settingsButton(), wrap());
        col.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout center = new FrameLayout(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        center.addView(box, boxLp);

        power = new PowerView(this);
        power.setColors(accent, light);
        power.setClickable(true);
        power.setFocusable(true);
        power.setOnClickListener(v -> toggle());
        box.addView(power, new LinearLayout.LayoutParams(
                dp(compact ? 180 : 230), dp(compact ? 180 : 230)));

        stateText = new TextView(this);
        stateText.setGravity(Gravity.CENTER);
        stateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 17 : 20);
        stateText.setTypeface(Typeface.DEFAULT_BOLD);
        stateText.setPadding(0, dp(compact ? 12 : 18), 0, 0);
        box.addView(stateText, wrap());

        col.addView(center, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        gauge = new SpectrumView(this);
        gauge.setColors(accent, light);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(compact ? 56 : 74));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(gauge, glp);

        footer = new TextView(this);
        footer.setGravity(Gravity.CENTER);
        footer.setTextColor(0xFF8FA0AE);
        footer.setTypeface(Typeface.MONOSPACE);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 11 : 13);
        footer.setPadding(0, dp(compact ? 8 : 10), 0, 0);
        col.addView(footer, wrap());

        return col;
    }

    private View settingsButton() {
        TextView b = compact
                ? pillButton("⚙  НАСТРОЙКИ", 12, 14, 10)
                : pillButton("⚙  НАСТРОЙКИ", 14, 20, 12);
        b.setOnClickListener(v -> openSettings());
        return b;
    }

    private TextView pillButton(String text, int sizeSp, int padH, int padV) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        b.setFocusable(true);
        b.setClickable(true);
        b.setTextColor(0xFFC4D2DE);
        b.setBackground(pill(0xFF121821, 0xFF26313D, 14));
        b.setOnFocusChangeListener((v, has) -> {
            b.setTextColor(has ? 0xFF06120C : 0xFFC4D2DE);
            b.setBackground(has ? pill(accent, light, 14) : pill(0xFF121821, 0xFF26313D, 14));
        });
        return b;
    }

    private void toggle() {
        if (TunService.running()) {
            stopService(new Intent(this, TunService.class));
            ui.postDelayed(this::refresh, 350);
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, 1);
        } else {
            startTunnel();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK) startTunnel();
        else refresh();
    }

    private void startTunnel() {
        TunService.setStrategy(STRATEGY);
        startService(new Intent(this, TunService.class));
        ui.postDelayed(this::refresh, 600);
    }

    private void refresh() {
        boolean on = TunService.running();
        power.setOn(on);
        gauge.setActive(on);
        stateText.setText(on ? "ОБХОД ВКЛЮЧЁН" : "НАЖМИТЕ OK, ЧТОБЫ ВКЛЮЧИТЬ");
        stateText.setTextColor(on ? accent : 0xFFC4D2DE);
        updateStats();
        updateSettings();
    }

    private void updateStats() {
        boolean on = TunService.running();
        if (compact) {
            footer.setText(String.format(java.util.Locale.US,
                    "%s   ·   соединений: %d%nпринято %s   ·   отправлено %s",
                    on ? "VPN активен" : "VPN выключен",
                    TunService.connections(),
                    TunService.human(TunService.bytesUp()),
                    TunService.human(TunService.bytesDown())));
            return;
        }
        footer.setText(String.format(java.util.Locale.US,
                "%s   ·   соединений: %d   ·   принято %s   ·   отправлено %s",
                on ? "VPN активен" : "VPN выключен",
                TunService.connections(),
                TunService.human(TunService.bytesUp()),
                TunService.human(TunService.bytesDown())));
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long down = TunService.bytesDown();
            float rate = Math.max(0, down - lastDown) / 1024f;
            lastDown = down;
            level = Math.min(1f, level * 0.6f + Math.min(1f, rate / 700f) * 0.4f);
            if (gauge != null) gauge.setLevel(level);
            updateStats();
            if (settingsOpen) updateSettings();
            if (logDirty) {
                logDirty = false;
                flushLog();
            }
            ui.postDelayed(this, 700);
        }
    };

    // ------------------------------------------------------------------ settings sheet

    private FrameLayout buildSettings() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0xC0040609);
        layer.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(compact ? 18 : 44), dp(compact ? 18 : 30),
                dp(compact ? 18 : 44), dp(compact ? 16 : 26));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B1017);
        bg.setCornerRadius(dp(compact ? 16 : 22));
        bg.setStroke(dp(1), 0xFF243040);
        card.setBackground(bg);

        if (compact) {
            ScrollView sheet = new ScrollView(this);
            sheet.setFillViewport(true);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            slp.setMargins(dp(10), dp(16), dp(10), dp(16));
            layer.addView(sheet, slp);
            sheet.addView(card, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            clp.setMargins(dp(110), dp(40), dp(110), dp(40));
            layer.addView(card, clp);
        }

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText("НАСТРОЙКИ");
        t.setTextColor(accent);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 16 : 18);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = compact
                ? pillButton("ЗАКРЫТЬ", 12, 14, 9)
                : pillButton("ЗАКРЫТЬ", 13, 18, 10);
        closeBtn = close;
        close.setOnClickListener(v -> closeSettings());
        head.addView(close, wrap());
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- accent colour
        card.addView(sectionLabel("Цвет приложения"), wrap());
        card.addView(colorRow(), wrap());

        // ---- strategy
        card.addView(sectionLabel("Движок обхода"), wrap());

        TextView strategy = new TextView(this);
        strategy.setText(strategyPretty());
        strategy.setTextColor(light);
        strategy.setTypeface(Typeface.MONOSPACE);
        strategy.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 11 : 12);
        strategy.setLineSpacing(dp(4), 1f);
        card.addView(strategy, wrap());

        // ---- auto picker placeholder
        card.addView(autoRow(), wrap());

        // ---- stats
        settingsStats = new TextView(this);
        settingsStats.setTextColor(0xFFB9C6D2);
        settingsStats.setTypeface(Typeface.MONOSPACE);
        settingsStats.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 13);
        settingsStats.setPadding(0, dp(14), 0, dp(6));
        card.addView(settingsStats, wrap());

        // ---- log
        TextView logTitle = new TextView(this);
        logTitle.setText("ЖУРНАЛ");
        logTitle.setTextColor(0xFF6E7C8A);
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logTitle.setPadding(0, dp(2), 0, dp(6));
        card.addView(logTitle, wrap());

        settingsLogScroll = new ScrollView(this);
        settingsLogScroll.setFocusable(false);
        settingsLogScroll.setBackground(pill(0xFF090E14, 0xFF1B2430, 10));
        settingsLogScroll.setPadding(dp(14), dp(10), dp(14), dp(10));
        settingsLogScroll.setMinimumHeight(dp(120));
        settingsLog = new TextView(this);
        settingsLog.setTypeface(Typeface.MONOSPACE);
        settingsLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        settingsLog.setTextColor(0xFFAEBBC7);
        settingsLog.setLineSpacing(dp(2), 1f);
        settingsLogScroll.addView(settingsLog, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (compact) {
            card.addView(settingsLogScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        } else {
            card.addView(settingsLogScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        return layer;
    }

    private TextView sectionLabel(String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextColor(0xFF6E7C8A);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 11 : 12);
        l.setPadding(0, dp(compact ? 10 : 12), 0, dp(5));
        return l;
    }

    private View colorRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        for (int i = 0; i < THEMES.length; i++) {
            final int index = i;
            int c = THEMES[i][0];

            View sw = new View(this);
            sw.setFocusable(true);
            sw.setClickable(true);
            sw.setBackground(swatch(c, i == themeIndex));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(compact ? 34 : 44), dp(compact ? 34 : 44));
            lp.rightMargin = dp(compact ? 9 : 14);
            row.addView(sw, lp);

            sw.setOnClickListener(v -> {
                if (index == themeIndex) {
                    Toast.makeText(this, "Цвет уже выбран", Toast.LENGTH_SHORT).show();
                    return;
                }
                applyTheme(index);
                Toast.makeText(this, "Цвет применён", Toast.LENGTH_SHORT).show();
            });
            sw.setOnFocusChangeListener((v, has) ->
                    v.setBackground(swatch(c, index == themeIndex || has)));
        }
        return row;
    }

    private GradientDrawable swatch(int color, boolean highlight) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(highlight ? 3 : 1), highlight ? 0xFFEAF2F8 : 0xFF243040);
        return d;
    }

    private View autoRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, dp(2));

        TextView btn = new TextView(this);
        btn.setText(compact ? "АВТО-ПОДБОР" : "ПОДБОР СТРАТЕГИИ  ·  АВТО");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(compact ? 14 : 18), dp(compact ? 10 : 11),
                dp(compact ? 14 : 18), dp(compact ? 10 : 11));
        btn.setTextColor(0xFF5A6673);
        btn.setBackground(pill(0xFF0D131A, 0xFF1C2530, 12));
        btn.setEnabled(false);
        btn.setFocusable(false);
        btn.setClickable(false);
        row.addView(btn, wrap());

        TextView badge = new TextView(this);
        badge.setText("В РАЗРАБОТКЕ");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 9 : 10);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(compact ? 8 : 10), dp(compact ? 4 : 5),
                dp(compact ? 8 : 10), dp(compact ? 4 : 5));
        badge.setTextColor(0xFFFFC24B);
        badge.setBackground(pill(0xFF241C0C, 0xFF5A4620, 8));
        LinearLayout.LayoutParams blp = wrap();
        blp.leftMargin = dp(compact ? 8 : 12);
        row.addView(badge, blp);

        return row;
    }

    private String strategyPretty() {
        return "Открытая сборка: движок обхода не подключён.\n"
                + "Трафик идёт без десинка — см. README.";
    }

    private void openSettings() {
        Log.i("PACKETFLOWUI", "settings: open");
        settingsOpen = true;
        settings.setVisibility(View.VISIBLE);
        updateSettings();
        flushLog();
        if (closeBtn != null) closeBtn.requestFocus();
    }

    private void closeSettings() {
        Log.i("PACKETFLOWUI", "settings: close");
        settingsOpen = false;
        settings.setVisibility(View.GONE);
        power.requestFocus();
    }

    private void updateSettings() {
        if (settingsStats == null || !settingsOpen) return;
        settingsStats.setText(String.format(java.util.Locale.US,
                "статус: %s   ·   соединений: %d\nпринято: %s   ·   отправлено: %s",
                TunService.running() ? "АКТИВЕН" : "выключен",
                TunService.connections(),
                TunService.human(TunService.bytesUp()),
                TunService.human(TunService.bytesDown())));
    }

    // ------------------------------------------------------------------ theme

    private void loadTheme() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        themeIndex = p.getInt(PREF_ACCENT, 0);
        if (themeIndex < 0 || themeIndex >= THEMES.length) themeIndex = 0;
        accent = THEMES[themeIndex][0];
        light = THEMES[themeIndex][1];
    }

    /** Rebuilds the screens with the new accent, keeping the log and the sheet. */
    private void applyTheme(int index) {
        boolean reopen = settingsOpen;
        themeIndex = index;
        accent = THEMES[index][0];
        light = THEMES[index][1];
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_ACCENT, index).apply();

        root.removeAllViews();
        root.addView(buildMain());
        settings = buildSettings();
        settings.setVisibility(View.GONE);
        root.addView(settings);
        settingsOpen = false;
        refresh();
        if (reopen) openSettings();
        else power.requestFocus();
    }

    // ------------------------------------------------------------------ log

    @Override
    public void onLog(String line) {
        Log.i("PACKETFLOWUI", line);
        ui.post(() -> {
            logLines.append(line).append('\n');
            if (logLines.length() > 12000) {
                logLines.delete(0, logLines.length() - 9000);
            }
            logDirty = true;
        });
    }

    private void flushLog() {
        if (settingsLog == null) return;
        settingsLog.setText(logLines.toString());
        settingsLogScroll.post(() -> settingsLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------------------ intent api

    private void applyIntent(Intent i) {
        if (i == null) return;
        String spec = i.getStringExtra("strategy64");
        if (spec != null && !spec.isEmpty()) {
            try {
                spec = new String(android.util.Base64.decode(spec,
                        android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE), "UTF-8");
            } catch (Throwable t) {
                spec = null;
            }
        }
        if (spec == null) spec = i.getStringExtra("strategy");
        if (spec != null && !spec.isEmpty()) {
            TunService.setStrategy(spec.replace(',', '|'));
            refresh();
        }
        if (i.getBooleanExtra("start", false) && !TunService.running()) startTunnel();
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable pill(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), stroke);
        return d;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    static int tint(int color, float factor) {
        return Color.rgb((int) (Color.red(color) * factor),
                (int) (Color.green(color) * factor),
                (int) (Color.blue(color) * factor));
    }

    // ------------------------------------------------------------------ big power button

    static final class PowerView extends View {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private boolean on;
        private boolean focused;
        private int accent = 0xFF35E08A;
        private int light = 0xFF9BFFCD;

        PowerView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(c, 2.5f));
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(dp(c, 9));
            icon.setStrokeCap(Paint.Cap.ROUND);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setOnFocusChangeListener((v, has) -> {
                focused = has;
                invalidate();
            });
        }

        private static float dp(Context c, float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    c.getResources().getDisplayMetrics());
        }

        void setColors(int accent, int light) {
            this.accent = accent;
            this.light = light;
            invalidate();
        }

        void setOn(boolean value) {
            on = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - dp(getContext(), 14);
            float t = (System.nanoTime() % 3_000_000_000L) / 3_000_000_000f;
            float pulse = 1f + 0.035f * (float) Math.sin(t * Math.PI * 2);

            if (on) {
                int alpha = 26 + (int) (16 * (0.5f + 0.5f * Math.sin(t * Math.PI * 2)));
                glow.setShader(new RadialGradient(cx, cy, r * 1.32f,
                        new int[]{Color.argb(alpha + 40, Color.red(accent),
                                Color.green(accent), Color.blue(accent)), Color.TRANSPARENT},
                        null, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, r * 1.32f, glow);
            }

            int fillColor, innerColor, ringColor, iconColor;
            if (on) {
                innerColor = focused ? Color.WHITE : light;
                fillColor = tint(accent, 0.82f);
                ringColor = focused ? 0xFFFFFFFF : tint(accent, 1f);
                iconColor = 0xFF06120C;
            } else {
                innerColor = focused ? tint(accent, 0.26f) : 0xFF1A222C;
                fillColor = focused ? tint(accent, 0.14f) : 0xFF11161D;
                ringColor = focused ? accent : 0xFF28323E;
                iconColor = focused ? light : 0xFF7E8C9A;
            }

            fill.setShader(new RadialGradient(cx, cy - r * 0.35f, r * 1.6f,
                    innerColor, fillColor, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r * (on ? pulse : 1f), fill);

            ring.setColor(ringColor);
            canvas.drawCircle(cx, cy, r * (on ? pulse : 1f), ring);

            icon.setColor(iconColor);
            float ir = r * 0.34f;
            arc.set(cx - ir, cy - ir, cx + ir, cy + ir);
            canvas.drawArc(arc, 300, 300, false, icon);
            canvas.drawLine(cx, cy - ir * 1.55f, cx, cy - ir * 0.15f, icon);
        }
    }

    // ------------------------------------------------------------------ live gauge

    static final class SpectrumView extends View {

        private static final int BARS = 44;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] heights = new float[BARS];
        private boolean active;
        private float level = 0.35f;
        private long start = System.nanoTime();
        private int accent = 0xFF35E08A;
        private int light = 0xFF9BFFCD;

        SpectrumView(Context c) {
            super(c);
            bar.setStyle(Paint.Style.FILL);
        }

        void setColors(int accent, int light) {
            this.accent = accent;
            this.light = light;
            invalidate();
        }

        void setActive(boolean value) {
            active = value;
            if (value) start = System.nanoTime();
            invalidate();
        }

        void setLevel(float v) {
            level = Math.max(0.15f, Math.min(1f, v));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float gap = dp(getContext(), 5);
            float bw = (w - gap * (BARS - 1)) / BARS;
            float t = (System.nanoTime() - start) / 1_000_000_000f;
            float radius = Math.min(bw / 2f, dp(getContext(), 4));

            bar.setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{tint(accent, 0.62f), accent, light, accent, tint(accent, 0.62f)},
                    null, Shader.TileMode.CLAMP));

            for (int i = 0; i < BARS; i++) {
                float target;
                if (active) {
                    float phase = i * 0.42f;
                    float wave = 0.5f + 0.5f * (float) Math.sin(t * 3.1f + phase);
                    float wave2 = 0.5f + 0.5f * (float) Math.sin(t * 1.7f - phase * 1.3f);
                    target = 0.18f + (0.35f * wave + 0.25f * wave2) * (0.45f + level);
                    float d = Math.abs(i - (BARS - 1) / 2f) / ((BARS - 1) / 2f);
                    target *= 1.05f - 0.35f * d * d;
                } else {
                    target = 0.12f;
                }
                heights[i] += (Math.min(1f, target) - heights[i]) * 0.28f;
                float bh = Math.max(dp(getContext(), 4), heights[i] * h);
                float left = i * (bw + gap);
                float top = (h - bh) / 2f;
                bar.setAlpha(active ? 235 : 40);
                canvas.drawRoundRect(left, top, left + bw, top + bh, radius, radius, bar);
            }
            if (active) postInvalidateOnAnimation();
        }

        private static float dp(Context c, float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    c.getResources().getDisplayMetrics());
        }
    }
}
