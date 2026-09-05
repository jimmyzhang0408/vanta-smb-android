package com.vanta.smb;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Real emulator/device test; exercises the same self-test button a colleague will use. */
@RunWith(AndroidJUnit4.class)
public class ScannerDeviceTest {
    @Rule public GrantPermissionRule camera = GrantPermissionRule.grant(Manifest.permission.CAMERA);

    @Test public void openPreviewCancelAndReopenFromMainScreen() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MainActivity> main = ActivityScenario.launch(MainActivity.class)) {
            for (int attempt = 0; attempt < 3; attempt++) {
                Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                        PortraitCaptureActivity.class.getName(), null, false);
                try {
                    main.onActivity(activity -> {
                        assertTrue(activity.findViewById(R.id.scan_test_button).isEnabled());
                        activity.findViewById(R.id.scan_test_button).performClick();
                    });
                    Activity scanner = instrumentation.waitForMonitorWithTimeout(monitor, 10_000);
                    assertNotNull("Scanner activity did not launch", scanner);
                    AtomicBoolean previewStarted = new AtomicBoolean();
                    long deadline = SystemClock.elapsedRealtime() + 15_000;
                    do {
                        instrumentation.runOnMainSync(() -> {
                            assertFalse("Scanner closed unexpectedly", scanner.isFinishing());
                            DecoratedBarcodeView view = scanner.findViewById(
                                    com.google.zxing.client.android.R.id.zxing_barcode_scanner);
                            previewStarted.set(view != null && view.getBarcodeView().isPreviewActive());
                        });
                        if (!previewStarted.get()) SystemClock.sleep(200);
                    } while (!previewStarted.get() && SystemClock.elapsedRealtime() < deadline);
                    assertTrue("Camera preview did not start", previewStarted.get());
                    instrumentation.runOnMainSync(scanner::finish);
                    instrumentation.waitForIdleSync();
                    SystemClock.sleep(300);
                } finally {
                    instrumentation.removeMonitor(monitor);
                }
            }
        }
    }
}
