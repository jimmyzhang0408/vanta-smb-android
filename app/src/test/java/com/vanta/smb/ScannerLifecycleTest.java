package com.vanta.smb;

import android.Manifest;
import android.content.Intent;
import android.hardware.Camera;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {30, 35})
public class ScannerLifecycleTest {
    @Before public void configureEmulatedCamera() {
        org.robolectric.shadows.ShadowLog.stream = System.out;
        Camera.CameraInfo camera = new Camera.CameraInfo();
        camera.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
        camera.orientation = 90;
        org.robolectric.shadows.ShadowCamera.addCameraInfo(0, camera);
    }
    @Test public void scannerStartsAndReleasesCameraWithPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
        Intent intent = new Intent("com.google.zxing.client.android.SCAN")
                .putExtra("SCAN_FORMATS", "QR_CODE")
                .putExtra("SCAN_ORIENTATION_LOCKED", false);
        try (ActivityController<PortraitCaptureActivity> controller =
                     Robolectric.buildActivity(PortraitCaptureActivity.class, intent).setup()) {
            assertNotNull(controller.get().findViewById(com.google.zxing.client.android.R.id.zxing_barcode_scanner));
            assertFalse("Scanner must remain open, not silently handle a startup failure", controller.get().isFinishing());
        }
    }

    @Test public void permissionDenialReturnsErrorWithoutCrashing() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        try (ActivityController<PortraitCaptureActivity> controller =
                     Robolectric.buildActivity(PortraitCaptureActivity.class).setup()) {
            controller.get().onRequestPermissionsResult(
                    com.journeyapps.barcodescanner.CaptureManager.getCameraPermissionReqCode(),
                    new String[]{Manifest.permission.CAMERA},
                    new int[]{android.content.pm.PackageManager.PERMISSION_DENIED});
            assertTrue(controller.get().isFinishing());
            assertNotNull(Shadows.shadowOf(controller.get()).getResultIntent()
                    .getStringExtra(PortraitCaptureActivity.EXTRA_ERROR));
        }
    }
}
