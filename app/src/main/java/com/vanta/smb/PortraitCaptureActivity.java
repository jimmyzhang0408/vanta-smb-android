package com.vanta.smb;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * Uses ZXing's camera/decoder, with guarded lifecycle and explicit errors returned to the caller.
 * The class name is retained for APK compatibility; it now supports tablet rotation.
 */
public final class PortraitCaptureActivity extends Activity {
    public static final String EXTRA_ERROR = "com.vanta.smb.SCAN_ERROR";
    private CaptureManager capture;
    private DecoratedBarcodeView scanner;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            setContentView(com.google.zxing.client.android.R.layout.zxing_capture);
            scanner = findViewById(com.google.zxing.client.android.R.id.zxing_barcode_scanner);
            capture = new CaptureManager(this, scanner) {
                @Override protected void displayFrameworkBugMessageAndExit(String message) {
                    fail("相机暂不可用，请关闭占用相机的应用后重试。", null);
                }
            };
            capture.initializeFromIntent(getIntent(), state);
            capture.setShowMissingCameraPermissionDialog(false);
            capture.decode();
        } catch (RuntimeException error) {
            fail("扫码初始化失败，请复制系统崩溃日志供开发人员排查。", error);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (capture == null || isFinishing()) return;
        try {
            capture.onResume();
        } catch (RuntimeException error) {
            fail("无法启动相机，请检查相机权限并重试。", error);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == CaptureManager.getCameraPermissionReqCode()
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fail("未获得相机权限，请在系统设置中允许 Vanta SMB 使用相机后重试。", null);
        } else if (capture != null) {
            try { capture.onRequestPermissionsResult(request, permissions, results); }
            catch (RuntimeException error) { fail("相机启动失败，请稍后重试。", error); }
        }
    }

    @Override protected void onPause() {
        if (capture != null) {
            try { capture.onPause(); }
            catch (RuntimeException error) { Log.e("VantaScanner", "Camera pause failed", error); }
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (capture != null) {
            try { capture.onDestroy(); }
            catch (RuntimeException error) { Log.e("VantaScanner", "Camera cleanup failed", error); }
        }
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (capture != null) capture.onSaveInstanceState(state);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        return (scanner != null && scanner.onKeyDown(keyCode, event)) || super.onKeyDown(keyCode, event);
    }

    private void fail(String message, RuntimeException error) {
        if (error != null) Log.e("VantaScanner", message, error);
        setResult(RESULT_CANCELED, new Intent().putExtra(EXTRA_ERROR, message));
        finish();
    }
}
