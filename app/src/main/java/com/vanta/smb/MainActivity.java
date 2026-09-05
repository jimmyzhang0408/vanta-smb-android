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
import android.util.Log;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final long POLL_INTERVAL_MS = 4_000;
    private static final String PREFS = "vanta_smb_connection";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> pathHistory = new ArrayDeque<>();
    private final JsonTracker jsonTracker = new JsonTracker();
    private final List<SmbEntry> entries = new ArrayList<>();

    private EditText hostInput;
    private EditText pathInput;
    private EditText userInput;
    private EditText passwordInput;
    private Button connectButton;
    private Button upButton;
    private Button refreshButton;
    private Button scanTestButton;
    private TextView pathLabel;
    private TextView statusLabel;
    private ProgressBar progress;
    private ListView fileList;
    private EntryAdapter adapter;

    private SmbClient smbClient;
    private String currentUrl;
    private SmbEntry pendingScanFile;
    private boolean busy;
    private boolean resumed;
    private boolean destroyed;
    private boolean scanFlowActive;
    private boolean scanTest;
    private boolean scannerLaunched;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (resumed && !scanFlowActive && !busy) loadDirectory(false);
            if (resumed) main.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        restoreInputs();
        if (state != null) {
            currentUrl = state.getString("currentUrl");
            scanFlowActive = state.getBoolean("scanFlowActive");
            scannerLaunched = scanFlowActive;
            scanTest = state.getBoolean("scanTest");
            if (state.containsKey("pendingUrl")) {
                pendingScanFile = new SmbEntry(state.getString("pendingName"),
                        state.getString("pendingUrl"), false, state.getLong("pendingLength"),
                        state.getLong("pendingModified"));
            }
        }
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
        scanTestButton = button("扫码自检（无需连接设备）");
        scanTestButton.setId(R.id.scan_test_button);
        scanTestButton.setOnClickListener(v -> startScan(null));
        connection.addView(scanTestButton, new LinearLayout.LayoutParams(-1, dp(48)));
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
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
    }

    private void restoreInputs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostInput.setText(prefs.getString("host", "192.168.10.1"));
        pathInput.setText(prefs.getString("path", ""));
        userInput.setText(prefs.getString("user", "vanta"));
        passwordInput.setText(prefs.getString("password", "vanta"));
    }

    private void connect() {
        if (busy || scanFlowActive) return;
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
        final SmbClient old = smbClient;
        io.execute(() -> {
            SmbClient candidate = null;
            try {
                candidate = new SmbClient(user, password);
                List<SmbEntry> firstPage = candidate.list(url);
                SmbClient connected = candidate;
                candidate = null;
                if (old != null) try { old.close(); } catch (Exception ignored) {}
                main.post(() -> {
                    if (destroyed) {
                        new Thread(() -> closeQuietly(connected), "smb-close").start();
                        return;
                    }
                    smbClient = connected;
                    currentUrl = url;
                    pathHistory.clear();
                    jsonTracker.reset();
                    applyListing(firstPage, true);
                });
            } catch (Exception error) {
                if (candidate != null) try { candidate.close(); } catch (Exception ignored) {}
                postFailure("连接失败", error);
            }
        });
    }

    private void loadDirectory(boolean userInitiated) {
        SmbClient client = smbClient;
        String url = currentUrl;
        if (destroyed || busy || scanFlowActive || client == null || url == null) return;
        setBusy(true, userInitiated ? "正在刷新…" : "正在检查新 JSON…");
        io.execute(() -> {
            try {
                List<SmbEntry> listing = client.list(url);
                main.post(() -> {
                    if (!destroyed && url.equals(currentUrl)) applyListing(listing, userInitiated);
                });
            } catch (Exception error) {
                if (userInitiated) postFailure("读取目录失败", error);
                else main.post(() -> {
                    if (!destroyed) setBusy(false, "连接中断，将自动重试");
                });
            }
        });
    }

    private void applyListing(List<SmbEntry> listing, boolean announce) {
        entries.clear();
        entries.addAll(listing);
        adapter.notifyDataSetChanged();
        pathLabel.setText(displayUrl(currentUrl));
        setBusy(false, listing.isEmpty() ? "目录为空 · 每 4 秒自动检查 JSON" :
                listing.size() + " 个项目 · 每 4 秒自动检查 JSON");

        jsonTracker.observe(listing);
        promptNextJson();
    }

    private void openEntry(SmbEntry entry) {
        if (busy || scanFlowActive) return;
        if (entry.directory) {
            pathHistory.push(currentUrl);
            currentUrl = entry.url.endsWith("/") ? entry.url : entry.url + "/";
            jsonTracker.reset();
            entries.clear();
            adapter.notifyDataSetChanged();
            loadDirectory(true);
        } else if (entry.isJson()) {
            scanFlowActive = true;
            updateControls();
            new AlertDialog.Builder(this)
                    .setTitle("为 JSON 扫码命名")
                    .setMessage("文件：" + entry.name + "\n\n扫码后将使用二维码按 | 分隔的第二段重命名，并保留 .json 扩展名。")
                    .setNegativeButton("取消", (dialog, which) -> finishScanFlow())
                    .setOnCancelListener(dialog -> finishScanFlow())
                    .setPositiveButton("开始扫码", (dialog, which) -> startScan(entry))
                    .show();
        } else {
            Toast.makeText(this, "当前仅对 JSON 文件提供扫码重命名", Toast.LENGTH_SHORT).show();
        }
    }

    private void navigateUp() {
        if (busy || scanFlowActive || pathHistory.isEmpty()) return;
        currentUrl = pathHistory.pop();
        jsonTracker.reset();
        entries.clear();
        adapter.notifyDataSetChanged();
        loadDirectory(true);
    }

    private void promptNextJson() {
        if (!resumed || busy || scanFlowActive || isFinishing() || destroyed) return;
        SmbEntry entry = jsonTracker.next();
        if (entry != null) promptForNewJson(entry);
    }

    private void promptForNewJson(SmbEntry entry) {
        scanFlowActive = true;
        updateControls();
        new AlertDialog.Builder(this)
                .setTitle("检测到新的 JSON 文件")
                .setMessage(entry.name + "\n\n现在扫码并用二维码第二段为它命名吗？")
                .setNegativeButton("稍后", (dialog, which) -> finishScanFlow())
                .setOnCancelListener(dialog -> finishScanFlow())
                .setPositiveButton("扫码命名", (dialog, which) -> {
                    startScan(entry);
                })
                .show();
    }

    private void startScan(SmbEntry entry) {
        scanFlowActive = true;
        scanTest = entry == null;
        pendingScanFile = entry;
        updateControls();
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setCaptureActivity(PortraitCaptureActivity.class);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("扫描样品二维码");
        integrator.setOrientationLocked(false);
        integrator.setBeepEnabled(false);
        try {
            scannerLaunched = true;
            integrator.initiateScan();
        } catch (RuntimeException error) {
            Log.e("VantaScanner", "Unable to launch scanner", error);
            finishScanFlow();
            showError("无法打开扫码界面：" + usefulMessage(error));
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        scannerLaunched = false;
        if (result.getContents() == null) {
            String error = data == null ? null : data.getStringExtra(PortraitCaptureActivity.EXTRA_ERROR);
            finishScanFlow();
            if (error != null) {
                showError(error);
            } else {
                Toast.makeText(this, "已取消扫码", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        SmbEntry file = pendingScanFile;
        if (file == null && !scanTest) {
            finishScanFlow();
            showError("没有待重命名的 JSON 文件");
            return;
        }
        final List<byte[]> segments = new ArrayList<>();
        if (data != null) {
            for (int i = 0; data.hasExtra("SCAN_RESULT_BYTE_SEGMENTS_" + i); i++) {
                byte[] bytes = data.getByteArrayExtra("SCAN_RESULT_BYTE_SEGMENTS_" + i);
                if (bytes != null) segments.add(bytes);
            }
        }
        final String normalized = ScanTextParser.decodeQrText(result.getContents(), segments);
        final String stem;
        try {
            stem = ScanTextParser.secondFieldAsFileStem(normalized);
        } catch (IllegalArgumentException error) {
            new AlertDialog.Builder(this).setTitle("二维码格式不正确")
                    .setMessage(error.getMessage() + "\n\n识别内容：\n" + normalized)
                    .setNegativeButton("取消", (dialog, which) -> finishScanFlow())
                    .setOnCancelListener(dialog -> finishScanFlow())
                    .setPositiveButton("重新扫码", (dialog, which) -> startScan(file)).show();
            return;
        }
        String newName = stem + ".json";
        if (scanTest) {
            new AlertDialog.Builder(this).setTitle("扫码自检成功")
                    .setMessage("识别内容：\n" + normalized + "\n\n解析文件名：" + newName
                            + "\n\n自检不会重命名任何文件。")
                    .setPositiveButton("完成", (dialog, which) -> finishScanFlow())
                    .setOnCancelListener(dialog -> finishScanFlow()).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认重命名")
                .setMessage(file.name + "\n→\n" + newName + "\n\n识别内容：\n" + normalized)
                .setNegativeButton("取消", (dialog, which) -> finishScanFlow())
                .setOnCancelListener(dialog -> finishScanFlow())
                .setPositiveButton("确认", (dialog, which) -> rename(file, newName))
                .show();
    }

    private void rename(SmbEntry file, String newName) {
        SmbClient client = smbClient;
        // After process recreation, reconnect explicitly before modifying a remote file.
        if (client == null) {
            finishScanFlow();
            showError("连接已被系统释放，请重新连接设备后对该文件扫码。");
            return;
        }
        setBusy(true, "正在重命名…");
        io.execute(() -> {
            try {
                client.rename(file, newName);
                main.post(() -> {
                    if (destroyed) return;
                    jsonTracker.ignore(SmbClient.renamedUrl(file.url, newName));
                    Toast.makeText(this, "已重命名为 " + newName, Toast.LENGTH_LONG).show();
                    setBusy(false, "重命名成功");
                    finishScanFlow();
                    loadDirectory(true);
                });
            } catch (Exception error) {
                postFailure("重命名失败", error);
            }
        });
    }

    private void setBusy(boolean busy, String status) {
        this.busy = busy;
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        statusLabel.setText(status);
        updateControls();
    }

    private void updateControls() {
        boolean enabled = !busy && !scanFlowActive;
        connectButton.setEnabled(enabled);
        scanTestButton.setEnabled(enabled);
        upButton.setEnabled(enabled && !pathHistory.isEmpty());
        refreshButton.setEnabled(enabled && smbClient != null);
        fileList.setEnabled(enabled);
        hostInput.setEnabled(enabled);
        pathInput.setEnabled(enabled);
        userInput.setEnabled(enabled);
        passwordInput.setEnabled(enabled);
    }

    private void finishScanFlow() {
        scanFlowActive = false;
        pendingScanFile = null;
        scanTest = false;
        scannerLaunched = false;
        updateControls();
        main.post(this::promptNextJson);
    }

    private void postFailure(String title, Exception error) {
        String detail = usefulMessage(error);
        main.post(() -> {
            if (destroyed || isFinishing()) return;
            setBusy(false, title);
            scanFlowActive = true;
            updateControls();
            // Keep the flow blocked until the error is acknowledged.
            new AlertDialog.Builder(this).setTitle(title)
                    .setMessage(detail + "\n\n请确认手机已连接设备热点/同一局域网、共享路径正确，并检查账号密码。")
                    .setPositiveButton("知道了", (dialog, which) -> finishScanFlow())
                    .setOnCancelListener(dialog -> finishScanFlow()).show();
        });
    }

    private void showError(String message) {
        if (destroyed || isFinishing()) return;
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
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        SmbClient client = smbClient;
        smbClient = null;
        io.execute(() -> closeQuietly(client));
        io.shutdown();
        super.onDestroy();
    }

    private static void closeQuietly(SmbClient client) {
        if (client != null) try { client.close(); } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        main.removeCallbacks(pollTask);
        main.postDelayed(pollTask, POLL_INTERVAL_MS);
        main.post(this::promptNextJson);
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(pollTask);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("currentUrl", currentUrl);
        state.putBoolean("scanFlowActive", scannerLaunched);
        state.putBoolean("scanTest", scanTest);
        if (pendingScanFile != null) {
            state.putString("pendingUrl", pendingScanFile.url);
            state.putString("pendingName", pendingScanFile.name);
            state.putLong("pendingLength", pendingScanFile.length);
            state.putLong("pendingModified", pendingScanFile.modifiedAt);
        }
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
