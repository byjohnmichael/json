package com.clearos.clearlife.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.clearos.clearlife.R;
import com.clearos.clearlife.databinding.ActivityLoginQRCodeBinding;
import com.clearos.clearlife.family.DashboardActivity;
import com.clearos.clearlife.helper.ColoredQRGenerator;
import com.clearos.clearlife.networking.BaseApplication;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class LoginQRCodeActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 200;
    private static final String TAG = "LoginQrCode";
    private static final float QR_IMAGE_WIDTH_RATIO = 0.9f;
    private ActivityLoginQRCodeBinding binding;
    private String url;
    private String verkey;
    private boolean isUUID = false;
    private boolean androidLogin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginQRCodeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (BaseApplication.getInstance().userKeys == null) {
            Intent intent = new Intent(this, SplashActivity.class);
            startActivity(intent);
            finish();
        } else {
            Uri data = getIntent().getData();
            if (data != null) {
                androidLogin = true;
                String scheme = data.getScheme(); // "clearlife"
                String host = data.getHost(); // "login or uuid"
                List<String> params = data.getPathSegments();
                if (host != null) {
                    if (host.equals("uuid")) {
                        isUUID = true;
                    }
                    String urlPart = "";
                    for (int i = 0; i < params.size(); i++) {
                        if (i == params.size() - 1) {
                            urlPart += params.get(i).split("\\|")[0];
                            continue;
                        }
                        urlPart += params.get(i) + "/";

                        if (i == 0) {
                            urlPart += "/";
                        }
                    }
                    String verkeyPart = params.get(params.size() - 1).split("\\|")[1];
                    Log.d(TAG, String.format("Scheme = %s\nHost = %s\nUrl Parameter = %s\nVerkey Parameter = %s", scheme, host, urlPart, verkeyPart));

                    binding.progressBar.setVisibility(View.VISIBLE);
                    createQRImage(String.format("%s|%s", urlPart, verkeyPart));
                    url = urlPart;
                    verkey = verkeyPart;
                    doApiRequest();
                } else {
                    BaseApplication.getInstance().threadSafeToast("Something went wrong. Please try again.");
                }
            }
        }

        binding.btnScanQrCode.setOnClickListener(v -> {
            if (checkPermission()) {
                openCamera();
            } else {
                requestPermission();
            }
        });
    }

    //To scan QR Code
    private void openCamera() {
        Log.d(TAG, "Opening camera to scan code.");
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setPrompt("Scan a QR code");
        integrator.setBeepEnabled(true);
        integrator.setCaptureActivity(CaptureActivityPortrait.class);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    //To check runtime permission for accessing camera
    private boolean checkPermission() {
        Log.d(TAG, "Checking camera permissions.");
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        Log.d(TAG, "Requesting camera permission and r/w storage permission");
        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                PERMISSION_REQUEST_CODE);
    }

    private void showMessageOKCancel(String message, DialogInterface.OnClickListener okListener) {
        runOnUiThread(() ->
            new AlertDialog.Builder(this)
                    .setMessage(message)
                    .setPositiveButton("OK", okListener)
                    .setNegativeButton("Cancel", null)
                    .create()
                    .show()
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions, @NotNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission granted to use camera.");
                openCamera();
            } else {
                BaseApplication.getInstance().threadSafeToast(getString(R.string.permissionDenied));
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    showMessageOKCancel(getString(R.string.oypPermissionsFailure),
                            (dialog, which) -> requestPermission());
                }
            }
        }
    }

    /**
     * Creates a QR code that produces `contents` when scanned.
     * @param contents String to encode in the QR code.
     * @return Null if there was a problem generating the bitmap.
     */
    private Bitmap createQRImage(String contents) {
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        int imageSize = (int) (size.x * QR_IMAGE_WIDTH_RATIO);
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    contents,
                    BarcodeFormat.QR_CODE,
                    imageSize,
                    imageSize,
                    null);

            return ColoredQRGenerator.createBitmap(bitMatrix, ColoredQRGenerator.Colors.CLEAR);
        } catch (Exception e) {
            BaseApplication.getInstance().threadSafeToast(getString(R.string.error_fail_generate_qr));
        }
        return null;
    }

    private void doApiRequest() {
        BaseApplication.getInstance().getApi().passwordlessQRLogin(verkey, url, isUUID, BaseApplication.getInstance().getWalletAddressesMap(), sr -> {
            if (sr.isSuccess()) {
                BaseApplication.getInstance().threadSafeToast(getString(R.string.loginQRSuccess));
                if (androidLogin) {
                    finishAffinity();
                } else {
                    Intent intent = new Intent(this, DashboardActivity.class);
                    startActivity(intent);
                    finish();
                }
            } else {
                BaseApplication.getInstance().threadSafeToast(getString(R.string.notificationApproveError));
                binding.progressBar.setVisibility(View.INVISIBLE);
            }
            return null;
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        isUUID = false;
        if (result != null) {
            if (result.getContents() == null) {
                binding.btnScanQrCode.setVisibility(View.VISIBLE);
                BaseApplication.getInstance().threadSafeToast(getString(R.string.oypScanNoResult));
            } else {
                Log.d(TAG, "onActivityResult: " + result.getContents());
                String contents = result.getContents();

                if (contents != null) {
                    String[] splitContents = contents.split("\\|");
                    if (splitContents[0].contains("register") && splitContents.length >= 2) {
                        url = splitContents[0];
                        verkey = splitContents[1];
                    } else if (splitContents[0].contains("uuid") && splitContents.length >= 3) {
                        url = splitContents[0];
                        verkey = splitContents[1];
                        isUUID = true;
                    } else {
                        BaseApplication.getInstance().threadSafeToast("Invalid QR Code");
                        return;
                    }

                    if (url != null && verkey != null) {
                        binding.qrCodeIV.setImageBitmap(createQRImage(contents));
                        binding.progressBar.setVisibility(View.VISIBLE);
                        doApiRequest();
                    } else {
                        BaseApplication.getInstance().threadSafeToast("Something went wrong. Please try again.");
                    }
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}
