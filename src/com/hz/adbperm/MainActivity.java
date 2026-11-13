package com.hz.adbperm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String OP = "REQUEST_INSTALL_PACKAGES";
    private static final String EXCLUDE_HELPER = "com.hz.adbhelper";

    private LinearLayout listLayout;
    private TextView statusView;
    private AdbClient adb;
    private AlertDialog adbDialog;

    private final ExecutorService serial = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pollBusy = new AtomicBoolean(false);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean connected;
    private volatile boolean promptShown;
    private volatile boolean dialogVisible;
    private boolean suppressEvents;

    private static class Entry {
        String pkg;
        CharSequence label;
        Drawable icon;
        boolean allowed;
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) {
                return;
            }
            if (pollBusy.compareAndSet(false, true)) {
                serial.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (connected) {
                                try {
                                    adb.exec("echo ok");
                                } catch (Exception e) {
                                    connected = false;
                                    closeAdb();
                                    showAdbPromptIfNeeded();
                                }
                            }
                            if (!connected) {
                                connectAndLoad();
                            }
                        } catch (Exception e) {
                            handleConnectFailure(e);
                        } finally {
                            pollBusy.set(false);
                        }
                    }
                });
            }
            ui.postDelayed(pollRunnable, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        setStatus("正在连接本机 ADB…");
        ui.post(pollRunnable);
        refreshNow();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(pollRunnable);
        serial.shutdownNow();
        closeAdb();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 26));
        root.setPadding(dp(24), dp(18), dp(24), dp(18));

        TextView title = new TextView(this);
        title.setText("安装权限");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("正在连接本机 ADB…");
        statusView.setTextColor(Color.rgb(158, 168, 186));
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setPadding(0, dp(8), 0, dp(14));
        root.addView(statusView);

        listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(listLayout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(16), 0, 0);

        Button refresh = makeButton("刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshNow();
            }
        });
        buttons.addView(refresh, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View gap = new View(this);
        buttons.addView(gap, new LinearLayout.LayoutParams(dp(16), 1));

        Button exit = makeButton("退出");
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        buttons.addView(exit, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttons);
        setContentView(root);
        refresh.requestFocus();
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setBackgroundColor(Color.rgb(40, 46, 62));
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setPadding(dp(18), dp(12), dp(18), dp(12));
        button.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    v.setBackgroundColor(Color.rgb(59, 130, 246));
                } else {
                    v.setBackgroundColor(Color.rgb(40, 46, 62));
                }
            }
        });
        return button;
    }

    private void refreshNow() {
        setStatus("正在刷新…");
        serial.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!connected || adb == null) {
                        connectInternal();
                    }
                    loadAndShow();
                } catch (Exception e) {
                    handleConnectFailure(e);
                }
            }
        });
    }

    private void connectAndLoad() throws Exception {
        connectInternal();
        loadAndShow();
    }

    private void connectInternal() throws Exception {
        closeAdb();
        adb = new AdbClient();
        adb.connect("127.0.0.1", 5555, getFilesDir());
        connected = true;
        promptShown = false;
        dismissAdbDialog();
    }

    private void handleConnectFailure(Exception e) {
        connected = false;
        closeAdb();
        setStatus("ADB 未连接，请打开电视 ADB 开关");
        showAdbPromptIfNeeded();
    }

    private void closeAdb() {
        if (adb != null) {
            adb.close();
            adb = null;
        }
    }

    private void loadAndShow() throws Exception {
        List<Entry> entries = loadEntries();
        showEntries(entries);
        setStatus("已连接本机 ADB，共 " + entries.size() + " 个第三方应用");
    }

    private List<Entry> loadEntries() throws Exception {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();

        for (ApplicationInfo info : installed) {
            if (info == null || info.packageName == null) {
                continue;
            }
            if (info.packageName.equals(getPackageName()) || EXCLUDE_HELPER.equals(info.packageName)) {
                continue;
            }
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            apps.add(info);
        }

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override
            public int compare(ApplicationInfo a, ApplicationInfo b) {
                return collator.compare(String.valueOf(a.loadLabel(pm)), String.valueOf(b.loadLabel(pm)));
            }
        });

        Map<String, Boolean> states = loadOpStates();
        List<Entry> entries = new ArrayList<Entry>();
        for (ApplicationInfo info : apps) {
            Entry entry = new Entry();
            entry.pkg = info.packageName;
            entry.label = info.loadLabel(pm);
            entry.icon = info.loadIcon(pm);
            Boolean allowed = states.get(entry.pkg);
            entry.allowed = allowed != null && allowed.booleanValue();
            entries.add(entry);
        }
        return entries;
    }

    private Map<String, Boolean> loadOpStates() throws Exception {
        String out = adb.exec(
                "for p in $(pm list packages -3 | sed 's/package://'); do "
                        + "echo \"PKG=$p\"; appops get \"$p\" " + OP + "; done");
        Map<String, Boolean> states = new HashMap<String, Boolean>();
        String current = null;
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("PKG=")) {
                current = line.substring(4).trim();
            } else if (current != null && line.contains(OP + ": allow")) {
                states.put(current, Boolean.TRUE);
            }
        }
        return states;
    }

    private void showEntries(final List<Entry> entries) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                suppressEvents = true;
                listLayout.removeAllViews();
                for (Entry entry : entries) {
                    listLayout.addView(buildRow(entry));
                    listLayout.addView(buildDivider());
                }
                suppressEvents = false;
            }
        });
    }

    private View buildRow(final Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(8), dp(10));

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(entry.icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(12), 0);

        TextView name = new TextView(this);
        name.setText(entry.label != null && entry.label.length() > 0 ? entry.label : entry.pkg);
        name.setTextColor(Color.WHITE);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setSingleLine(true);

        TextView pkg = new TextView(this);
        pkg.setText(entry.pkg);
        pkg.setTextColor(Color.rgb(128, 138, 156));
        pkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pkg.setSingleLine(true);
        pkg.setPadding(0, dp(3), 0, 0);

        texts.addView(name);
        texts.addView(pkg);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch toggle = new Switch(this);
        toggle.setText("");
        toggle.setTag(entry);
        toggle.setChecked(entry.allowed);
        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (suppressEvents) {
                    return;
                }
                applyOp(entry, toggle, isChecked);
            }
        });
        row.addView(toggle);
        return row;
    }

    private View buildDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(34, 39, 52));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return divider;
    }

    private void applyOp(final Entry entry, final Switch toggle, final boolean allow) {
        toggle.setEnabled(false);
        serial.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!connected || adb == null) {
                        setStatus("ADB 未连接，无法修改权限");
                        revertSwitch(entry, toggle);
                        return;
                    }
                    adb.execChecked("appops set " + entry.pkg + " " + OP + " "
                            + (allow ? "allow" : "default"));
                    entry.allowed = allow;
                    setStatus(entry.pkg + (allow ? " 已允许安装" : " 已还原默认"));
                } catch (Exception e) {
                    setStatus("设置失败：" + e.getMessage());
                    revertSwitch(entry, toggle);
                } finally {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toggle.setEnabled(true);
                        }
                    });
                }
            }
        });
    }

    private void revertSwitch(final Entry entry, final Switch toggle) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                suppressEvents = true;
                toggle.setChecked(entry.allowed);
                toggle.setEnabled(true);
                suppressEvents = false;
            }
        });
    }

    private void showAdbPromptIfNeeded() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (promptShown || dialogVisible || isFinishing()) {
                    return;
                }
                promptShown = true;
                dialogVisible = true;
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle("ADB 未连接");
                builder.setMessage("需要打开电视的 ADB 开关。是否现在进入工厂菜单？");
                builder.setCancelable(false);
                builder.setPositiveButton("打开 ADB", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialogVisible = false;
                        openFactoryMenu();
                    }
                });
                builder.setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialogVisible = false;
                    }
                });
                builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        dialogVisible = false;
                    }
                });
                adbDialog = builder.show();
            }
        });
    }

    private void dismissAdbDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (adbDialog != null && adbDialog.isShowing()) {
                    adbDialog.dismiss();
                }
                adbDialog = null;
                dialogVisible = false;
            }
        });
    }

    private void openFactoryMenu() {
        try {
            Intent intent = new Intent("skyworth.intent.action.FACTORY");
            intent.setComponent(new ComponentName("com.coocaa.factory", "com.coocaa.factory.SkyFactory"));
            intent.putExtra("focusId", "GENERAL_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception first) {
            try {
                Intent intent = new Intent("skyworth.intent.action.FACTORY");
                intent.putExtra("focusId", "GENERAL_SETTINGS");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception second) {
                Toast.makeText(this, "无法打开工厂菜单", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) {
                    statusView.setText(text);
                }
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
