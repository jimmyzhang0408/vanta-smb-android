package com.vanta.smb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final long POLL_INTERVAL_MS = 4_000;
    private static final String PREFS = "vanta_smb_connection";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private final ArrayDeque<String> pathHistory = new ArrayDeque<>();
    private final Set<String> knownJsonUrls = new HashSet<>();
    private final List<SmbEntry> entries = new ArrayList<>();

    private EditText hostInput;
    private EditText pathInput;
    private EditText userInput;
    private EditText passwordInput;
    private Button connectButton;
    private Button upButton;
    private Button refreshButton;
    private TextView pathLabel;
    private TextView statusLabel;
    private ProgressBar progress;
    private ListView fileList;
    private EntryAdapter adapter;

    private volatile SmbClient smbClient;
    private String currentUrl;
    private String rootUrl;
    private SmbEntry pendingScanFile;
    private boolean baselineEstablished;
    private boolean jsonDialogShowing;
    private long suppressAutoPromptUntil;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (smbClient != null && currentUrl != null) loadDirectory(false);
            main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        restoreInputs();
        main.postDelayed(pollTask, POLL_INTERVAL_MS);
    }

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 248, 251));

        TextView toolbar = new TextView(this);
        toolbar.setText("Vanta SMB 远程文件");
        toolbar.setTextColor(Color.WHITE);
        toolbar.setTextSize(20);
        toolbar.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(pad, dp(12), pad, dp(12));
        toolbar.setBackgroundColor(Color.rgb(16, 42, 67));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));

        ScrollView connectionScroll = new ScrollView(this);
        LinearLayout connection = new LinearLayout(this);
        connection.setOrientation(LinearLayout.VERTICAL);
        connection.setPadding(pad, dp(12), pad, dp(10));
        connection.setBackgroundColor(Color.WHITE);

        TextView section = label("连接光谱仪", 17, true);
        connection.addView(section);

        LinearLayout addressRow = new LinearLayout(this);
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        hostInput = input("设备 IP", InputType.TYPE_CLASS_TEXT);
        pathInput = input("共享名/目录（可空）", InputType.TYPE_CLASS_TEXT);
        addressRow.addView(hostInput, new LinearLayout.LayoutParams(0, dp(52), 1.1f));
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(0, dp(52), 1.4f);
        pathParams.setMarginStart(dp(8));
        addressRow.addView(pathInput, pathParams);
        connection.addView(addressRow);

        LinearLayout credentialRow = new LinearLayout(this);
        credentialRow.setOrientation(LinearLayout.HORIZONTAL);
        userInput = input("用户名", InputType.TYPE_CLASS_TEXT);
        passwordInput = input("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        credentialRow.addView(userInput, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams passParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        passParams.setMarginStart(dp(8));
        credentialRow.addView(passwordInput, passParams);
        connection.addView(credentialRow);

        TextView helper = label("先让手机连接光谱仪热点，或确保两者在同一局域网。共享名未知时留空，可从服务器根目录进入。", 12, false);
        helper.setTextColor(Color.rgb(82, 104, 125));
        helper.setPadding(0, dp(3), 0, dp(8));
        connection.addView(helper);

        connectButton = button("连接并浏览");
        connectButton.setOnClickListener(v -> connect());
        connection.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(48)));
        connectionScroll.addView(connection);
        root.addView(connectionScroll, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(pad, dp(6), pad, dp(6));
        upButton = button("上一级");
        upButton.setEnabled(false);
        upButton.setOnClickListener(v -> navigateUp());
        refreshButton = button("刷新");
        refreshButton.setEnabled(false);
        refreshButton.setOnClickListener(v -> loadDirectory(true));
        pathLabel = label("尚未连接", 13, false);
        pathLabel.setSingleLine(true);
        pathLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        navigation.addView(upButton, new LinearLayout.LayoutParams(dp(82), dp(42)));
        LinearLayout.LayoutParams pathLabelParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        pathLabelParams.setMarginStart(dp(8));
        pathLabelParams.setMarginEnd(dp(8));
        navigation.addView(pathLabel, pathLabelParams);
        navigation.addView(refreshButton, new LinearLayout.LayoutParams(dp(72), dp(42)));
        root.addView(navigation);

        fileList = new ListView(this);
        fileList.setDividerHeight(1);
        fileList.setBackgroundColor(Color.WHITE);
        adapter = new EntryAdapter();
        fileList.setAdapter(adapter);
        fileList.setOnItemClickListener((parent, view, position, id) -> openEntry(entries.get(position)));
        root.addView(fileList, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(pad, dp(7), pad, dp(7));
        footer.setBackgroundColor(Color.WHITE);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        progress.setVisibility(View.GONE);
        footer.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
        statusLabel = label("等待连接", 13, false);
        statusLabel.setTextColor(Color.rgb(82, 104, 125));
        footer.addView(statusLabel, new LinearLayout.LayoutParams(0, dp(36), 1));
        root.addView(footer);

        setContentView(root);
    }

    private void restoreInputs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostInput.setText(prefs.getString("host", "192.168.10.1"));
        pathInput.setText(prefs.getString("path", ""));
        userInput.setText(prefs.getString("user", "vanta"));
        passwordInput.setText(prefs.getString("password", "vanta"));
    }

    private void connect() {
        final String host = hostInput.getText().toString();
        final String remotePath = pathInput.getText().toString();
        final String user = userInput.getText().toString().trim();
        final String password = passwordInput.getText().toString();
        final String url;
        try {
            url = SmbClient.buildRootUrl(host, remotePath);
        } catch (IllegalArgumentException error) {
            showError(error.getMessage());
            return;
        }
        if (!hasWifiOrEthernet()) {
            Toast.makeText(this, "当前未检测到 Wi-Fi/局域网连接，仍将尝试连接", Toast.LENGTH_LONG).show();
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", host.trim()).putString("path", remotePath.trim())
                .putString("user", user).putString("password", password).apply();
        setBusy(true, "正在连接 " + host.trim() + "…");
        connectButton.setEnabled(false);
        io.execute(() -> {
            SmbClient candidate = null;
            try {
                candidate = new SmbClient(user, password);
                List<SmbEntry> firstPage = candidate.list(url);
                SmbClient old = smbClient;
                smbClient = candidate;
                candidate = null;
                if (old != null) try { old.close(); } catch (Exception ignored) {}
                List<SmbEntry> finalFirstPage = firstPage;
                main.post(() -> {
                    rootUrl = url;
                    currentUrl = url;
                    pathHistory.clear();
                    knownJsonUrls.clear();
                    baselineEstablished = false;
                    applyListing(finalFirstPage, true);
                    refreshButton.setEnabled(true);
                    connectButton.setEnabled(true);
                });
            } catch (Exception error) {
                if (candidate != null) try { candidate.close(); } catch (Exception ignored) {}
                postFailure("连接失败", error);
                main.post(() -> connectButton.setEnabled(true));
            }
        });
    }

    private void loadDirectory(boolean userInitiated) {
        SmbClient client = smbClient;
        String url = currentUrl;
        if (client == null || url == null || !requestInFlight.compareAndSet(false, true)) return;
        if (userInitiated) setBusy(true, "正在刷新…");
        io.execute(() -> {
            try {
                List<SmbEntry> listing = client.list(url);
                main.post(() -> {
                    if (url.equals(currentUrl)) applyListing(listing, userInitiated);
                    requestInFlight.set(false);
                });
            } catch (Exception error) {
                requestInFlight.set(false);
                if (userInitiated) postFailure("读取目录失败", error);
            }
        });
    }

    private void applyListing(List<SmbEntry> listing, boolean announce) {
        entries.clear();
        entries.addAll(listing);
        adapter.notifyDataSetChanged();
        pathLabel.setText(displayUrl(currentUrl));
        upButton.setEnabled(!pathHistory.isEmpty());
        setBusy(false, listing.isEmpty() ? "目录为空 · 每 4 秒自动检查 JSON" :
                listing.size() + " 个项目 · 每 4 秒自动检查 JSON");

        List<SmbEntry> newJson = new ArrayList<>();
        Set<String> latest = new HashSet<>();
        for (SmbEntry entry : listing) {
            if (entry.isJson()) {
                latest.add(entry.url);
                if (baselineEstablished && !knownJsonUrls.contains(entry.url)) newJson.add(entry);
            }
        }
        knownJsonUrls.clear();
        knownJsonUrls.addAll(latest);
        baselineEstablished = true;
        if (SystemClock.elapsedRealtime() >= suppressAutoPromptUntil && !newJson.isEmpty()) {
            promptForNewJson(newJson.get(0));
        } else if (announce && !listing.isEmpty()) {
            Toast.makeText(this, "目录已更新", Toast.LENGTH_SHORT).show();
        }
    }

    private void openEntry(SmbEntry entry) {
        if (entry.directory) {
            pathHistory.push(currentUrl);
            currentUrl = entry.url.endsWith("/") ? entry.url : entry.url + "/";
            baselineEstablished = false;
            knownJsonUrls.clear();
            setBusy(true, "正在打开 " + entry.name + "…");
            loadDirectory(true);
        } else if (entry.isJson()) {
            new AlertDialog.Builder(this)
                    .setTitle("为 JSON 扫码命名")
                    .setMessage("文件：" + entry.name + "\n\n扫码后将使用二维码按 | 分隔的第二段重命名，并保留 .json 扩展名。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始扫码", (dialog, which) -> startScan(entry))
                    .show();
        } else {
            Toast.makeText(this, "当前仅对 JSON 文件提供扫码重命名", Toast.LENGTH_SHORT).show();
        }
    }

    private void navigateUp() {
        if (pathHistory.isEmpty()) return;
        currentUrl = pathHistory.pop();
        baselineEstablished = false;
        knownJsonUrls.clear();
        setBusy(true, "正在返回上一级…");
        loadDirectory(true);
    }

    private void promptForNewJson(SmbEntry entry) {
        if (jsonDialogShowing || isFinishing()) return;
        jsonDialogShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("检测到新的 JSON 文件")
                .setMessage(entry.name + "\n\n现在扫码并用二维码第二段为它命名吗？")
                .setNegativeButton("稍后", (dialog, which) -> jsonDialogShowing = false)
                .setOnCancelListener(dialog -> jsonDialogShowing = false)
                .setPositiveButton("扫码命名", (dialog, which) -> {
                    jsonDialogShowing = false;
                    startScan(entry);
                })
                .show();
    }

    private void startScan(SmbEntry entry) {
        pendingScanFile = entry;
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setCaptureActivity(PortraitCaptureActivity.class);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("扫描样品二维码");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (result.getContents() == null) {
            Toast.makeText(this, "已取消扫码", Toast.LENGTH_SHORT).show();
            return;
        }
        SmbEntry file = pendingScanFile;
        if (file == null) {
            showError("没有待重命名的 JSON 文件");
            return;
        }
        final String normalized = ScanTextParser.normalize(result.getContents());
        final String stem;
        try {
            stem = ScanTextParser.secondFieldAsFileStem(normalized);
        } catch (IllegalArgumentException error) {
            new AlertDialog.Builder(this).setTitle("二维码格式不正确")
                    .setMessage(error.getMessage() + "\n\n识别内容：\n" + normalized)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("重新扫码", (dialog, which) -> startScan(file)).show();
            return;
        }
        String newName = stem + ".json";
        new AlertDialog.Builder(this)
                .setTitle("确认重命名")
                .setMessage(file.name + "\n→\n" + newName + "\n\n识别内容：\n" + normalized)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", (dialog, which) -> rename(file, newName))
                .show();
    }

    private void rename(SmbEntry file, String newName) {
        SmbClient client = smbClient;
        if (client == null) return;
        setBusy(true, "正在重命名…");
        io.execute(() -> {
            try {
                client.rename(file, newName);
                suppressAutoPromptUntil = SystemClock.elapsedRealtime() + 6_000;
                main.post(() -> {
                    Toast.makeText(this, "已重命名为 " + newName, Toast.LENGTH_LONG).show();
                    loadDirectory(true);
                });
            } catch (Exception error) {
                postFailure("重命名失败", error);
            }
        });
    }

    private void setBusy(boolean busy, String status) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusLabel.setText(status);
    }

    private void postFailure(String title, Exception error) {
        String detail = usefulMessage(error);
        main.post(() -> {
            setBusy(false, title);
            new AlertDialog.Builder(this).setTitle(title)
                    .setMessage(detail + "\n\n请确认手机已连接设备热点/同一局域网、共享路径正确，并检查账号密码。")
                    .setPositiveButton("知道了", null).show();
        });
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("提示").setMessage(message).setPositiveButton("知道了", null).show();
    }

    private boolean hasWifiOrEthernet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private static String usefulMessage(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) message = current.getMessage();
            current = current.getCause();
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private static String displayUrl(String url) {
        if (url == null) return "尚未连接";
        return url.replaceFirst("(?i)^smb://", "");
    }

    private EditText input(String hint, int inputType) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setTextSize(15);
        view.setSingleLine(true);
        view.setInputType(inputType);
        view.setPadding(dp(10), 0, dp(10), 0);
        return view;
    }

    private TextView label(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(16, 42, 67));
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setAllCaps(false);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onBackPressed() {
        if (!pathHistory.isEmpty()) navigateUp();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(pollTask);
        SmbClient client = smbClient;
        smbClient = null;
        io.shutdownNow();
        if (client != null) try { client.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public SmbEntry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView icon;
            TextView title;
            TextView subtitle;
            if (convertView == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(8), dp(16), dp(8));
                icon = label("", 27, false);
                icon.setGravity(Gravity.CENTER);
                row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(54)));
                LinearLayout text = new LinearLayout(MainActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                title = label("", 16, true);
                subtitle = label("", 12, false);
                subtitle.setTextColor(Color.rgb(98, 119, 138));
                text.addView(title, new LinearLayout.LayoutParams(-1, dp(30)));
                text.addView(subtitle, new LinearLayout.LayoutParams(-1, dp(24)));
                row.addView(text, new LinearLayout.LayoutParams(0, dp(54), 1));
                row.setTag(new ViewHolder(icon, title, subtitle));
            } else {
                row = (LinearLayout) convertView;
            }
            ViewHolder holder = (ViewHolder) row.getTag();
            SmbEntry entry = entries.get(position);
            holder.icon.setText(entry.directory ? "▣" : entry.isJson() ? "{ }" : "□");
            holder.icon.setTextColor(entry.isJson() ? Color.rgb(24, 119, 209) : Color.rgb(82, 104, 125));
            holder.title.setText(entry.name);
            if (entry.directory) {
                holder.subtitle.setText("文件夹");
            } else {
                String size = entry.length < 1024 ? entry.length + " B" :
                        entry.length < 1024 * 1024 ? String.format(java.util.Locale.CHINA, "%.1f KB", entry.length / 1024.0) :
                                String.format(java.util.Locale.CHINA, "%.1f MB", entry.length / 1048576.0);
                holder.subtitle.setText(size + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(entry.modifiedAt)));
            }
            return row;
        }
    }

    private static final class ViewHolder {
        final TextView icon;
        final TextView title;
        final TextView subtitle;
        ViewHolder(TextView icon, TextView title, TextView subtitle) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
        }
    }
}
