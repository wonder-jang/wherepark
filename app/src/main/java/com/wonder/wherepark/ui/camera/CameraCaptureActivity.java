package com.wonder.wherepark.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.wonder.wherepark.R;
import com.wonder.wherepark.databinding.ActivityCameraCaptureBinding;
import com.wonder.wherepark.photo.PhotoStore;
import com.wonder.wherepark.util.LockScreenUtil;
import com.wonder.wherepark.util.WindowInsetsUtil;

import java.io.File;
import java.util.concurrent.ExecutionException;

/**
 * 앱 내장 카메라 촬영 화면. 셔터를 누르면 시스템 카메라의 재촬영/확인 검토 화면 없이
 * 곧바로 JPEG를 임시 파일로 저장하고 그 경로를 결과로 반환한다.
 * 결과: RESULT_OK + EXTRA_RESULT_PATH(절대 경로). 취소 시 RESULT_CANCELED.
 */
public class CameraCaptureActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT_PATH = "result_path";

    private static final String TAG = "CameraCapture";

    private ActivityCameraCaptureBinding binding;
    private PhotoStore photoStore;
    @Nullable
    private ImageCapture imageCapture;
    private boolean capturing = false;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    toast(R.string.camera_permission_denied);
                    finish();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LockScreenUtil.showWhenLocked(this); // 잠금화면 위로 떠야 하므로(자동 촬영 진입)
        binding = ActivityCameraCaptureBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsUtil.applySystemBars(this);

        photoStore = new PhotoStore(this);

        binding.btnShutter.setOnClickListener(v -> takePhoto());
        binding.btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.preview.getSurfaceProvider());
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "카메라 시작 실패", e);
                toast(R.string.input_photo_fail);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null || capturing) {
            return;
        }
        capturing = true;
        playShutterFeedback();
        binding.btnShutter.setEnabled(false);

        File outFile;
        try {
            outFile = photoStore.createTempCaptureFile();
        } catch (Exception e) {
            toast(R.string.input_photo_fail);
            finish();
            return;
        }

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(outFile).build();
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        Uri saved = results.getSavedUri();
                        String path = saved != null && saved.getPath() != null
                                ? saved.getPath() : outFile.getAbsolutePath();
                        Intent data = new Intent().putExtra(EXTRA_RESULT_PATH, path);
                        setResult(RESULT_OK, data);
                        finish();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "촬영 실패", exception);
                        toast(R.string.input_photo_fail);
                        capturing = false;
                        binding.btnShutter.setEnabled(true);
                    }
                });
    }

    /** 셔터 탭 피드백: 햅틱 진동 + 버튼 눌림 애니메이션 + 화면 플래시. */
    private void playShutterFeedback() {
        // 햅틱 진동
        binding.btnShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        }
        // 버튼 눌림(축소→복귀) 애니메이션
        binding.btnShutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(70)
                .withEndAction(() -> binding.btnShutter.animate()
                        .scaleX(1f).scaleY(1f).setDuration(110).start())
                .start();
        // 화면 플래시(흰색 0→1→0)
        binding.flash.setAlpha(0f);
        binding.flash.animate().alpha(0.85f).setDuration(60)
                .withEndAction(() -> binding.flash.animate().alpha(0f).setDuration(160).start())
                .start();
    }

    /** 볼륨 다운/업 버튼으로도 촬영(삼성 카메라와 동일). 시스템 볼륨 변경은 막는다. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) {
                takePhoto();
            }
            return true; // 볼륨 조절 동작 소비
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 볼륨 키 KeyUp도 소비해 시스템 볼륨 UI/소리가 뜨지 않게 한다. */
    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }
}
