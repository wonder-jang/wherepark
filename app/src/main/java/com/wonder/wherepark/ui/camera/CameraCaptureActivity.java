package com.wonder.wherepark.ui.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.wonder.wherepark.R;
import com.wonder.wherepark.analyze.ParkingPhotoAnalyzer;
import com.wonder.wherepark.data.model.Enums.ParkingLevelType;
import com.wonder.wherepark.databinding.ActivityCameraCaptureBinding;
import com.wonder.wherepark.photo.PhotoStore;
import com.wonder.wherepark.util.LockScreenUtil;
import com.wonder.wherepark.util.ParkingFormat;
import com.wonder.wherepark.util.WindowInsetsUtil;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 앱 내장 카메라 촬영 화면. 프리뷰 프레임을 실시간으로 분석해 화면 하단 1/3에 층·위치·배경색·글자색을
 * 계속 갱신 표시하고, 사용자가 원하는 값이 나왔을 때 셔터를 누르면 그 순간의 분석 결과를 사진과 함께 반환한다.
 * (별도의 촬영 후 "분석 결과 확인" 화면 없이 한 화면에서 끝난다.)
 * 결과: RESULT_OK + EXTRA_RESULT_PATH(절대 경로) + 분석 결과 extras. 취소 시 RESULT_CANCELED.
 */
public class CameraCaptureActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT_PATH = "result_path";

    // 촬영 순간의 실시간 분석 결과를 결과 Intent로 실어 보낸다(모두 선택). readResult로 복원한다.
    private static final String EXTRA_LEVEL = "an_level";        // ParkingLevelType.name()
    private static final String EXTRA_FLOOR = "an_floor";        // int 층 번호
    private static final String EXTRA_FLOOR_LABEL = "an_floor_label";
    private static final String EXTRA_POSITION = "an_position";
    private static final String EXTRA_BG_RGB = "an_bg_rgb";
    private static final String EXTRA_TEXT_RGB = "an_text_rgb";
    private static final String EXTRA_RAW = "an_raw";

    private static final String TAG = "CameraCapture";
    // 프레임 분석 최소 간격(ms). 프리뷰는 부드럽게 두고 OCR/색 분석만 이 주기로 샘플링한다.
    private static final long ANALYZE_INTERVAL_MS = 500;
    // 최근 몇 프레임을 모아 필드별 최빈값으로 수렴시킬지(누적 투표 윈도우). 500ms 간격이라 최근 5초.
    private static final int VOTE_WINDOW = 10;
    // 색 투표에서 같은 색으로 묶을 유사도 임계(RGB 유클리드 거리 제곱). 60 이내면 같은 색으로 본다.
    private static final int COLOR_MERGE_DIST_SQ = 60 * 60;

    private ActivityCameraCaptureBinding binding;
    private PhotoStore photoStore;
    @Nullable
    private ImageCapture imageCapture;
    private boolean capturing = false;

    private ExecutorService analysisExecutor;
    private final AtomicBoolean analyzing = new AtomicBoolean(false);
    private volatile long lastAnalyzeAt = 0L;
    // 누적 투표로 수렴된 분석 결과(촬영 시 이 값을 사진과 함께 반환). 최근 프레임들의 다수결.
    @Nullable
    private volatile ParkingPhotoAnalyzer.Result latestResult;
    // 최근 프레임 결과 윈도우(메인 스레드에서만 접근). 필드별 최빈값 계산용.
    private final Deque<ParkingPhotoAnalyzer.Result> recent = new ArrayDeque<>();
    // 마지막으로 계산한 누적 정렬 디버그 문구(renderLive에서 표시).
    @Nullable
    private String voteDebugLine;

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
        analysisExecutor = Executors.newSingleThreadExecutor();

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
                // 실시간 분석 프레임 해상도를 기본(약 640×480)보다 높여(720p 목표) OCR 인식률을 높인다.
                ResolutionSelector resolution = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setResolutionSelector(resolution)
                        .build();
                analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);
                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture, analysis);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "카메라 시작 실패", e);
                toast(R.string.input_photo_fail);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ----- 실시간 프레임 분석 -----

    /** ImageAnalysis 콜백(백그라운드 스레드). 주기적으로만 한 프레임을 정방향 비트맵으로 만들어 분석한다. */
    private void analyzeFrame(@NonNull ImageProxy image) {
        long now = SystemClock.uptimeMillis();
        if (analyzing.get() || now - lastAnalyzeAt < ANALYZE_INTERVAL_MS) {
            image.close();
            return;
        }
        Bitmap upright = null;
        try {
            upright = toUprightBitmap(image);
        } catch (Exception e) {
            Log.w(TAG, "프레임 변환 실패", e);
        } finally {
            image.close();
        }
        if (upright == null) {
            return;
        }
        analyzing.set(true);
        lastAnalyzeAt = now;
        final Bitmap frame = upright;
        // analyzeFrame의 콜백은 ML Kit 규약상 메인 스레드로 전달된다.
        ParkingPhotoAnalyzer.analyzeFrame(frame, result -> {
            if (binding != null && !isFinishing()) {
                ParkingPhotoAnalyzer.Result converged = accumulate(result);
                latestResult = converged;
                renderLive(converged);
            }
            frame.recycle();
            analyzing.set(false);
        });
    }

    // ----- 프레임 누적 투표(수렴) -----

    /**
     * 새 프레임 결과를 최근 윈도우에 넣고, 필드별 최빈값으로 수렴한 결과를 돌려준다.
     * 한두 프레임의 OCR 튐(층을 놓치거나 A12를 Al2로 오인식 등)을 다수결로 눌러 표시/저장값을 안정화한다.
     * (색은 연속값이라 최빈값 대신 가장 최근 non-null 값을 쓰고, OCR 원문은 현재 프레임 것을 그대로 노출.)
     */
    @NonNull
    private ParkingPhotoAnalyzer.Result accumulate(@NonNull ParkingPhotoAnalyzer.Result frame) {
        recent.addLast(frame);
        while (recent.size() > VOTE_WINDOW) {
            recent.removeFirst();
        }

        Map<String, Integer> floorCounts = new LinkedHashMap<>();
        Map<String, ParkingPhotoAnalyzer.Result> floorRep = new LinkedHashMap<>();
        Map<String, Integer> posCounts = new LinkedHashMap<>();
        List<Integer> bgColors = new ArrayList<>();
        List<Integer> textColors = new ArrayList<>();
        for (ParkingPhotoAnalyzer.Result r : recent) {
            if (r.hasFloor()) {
                floorCounts.merge(r.floorLabel, 1, Integer::sum);
                floorRep.put(r.floorLabel, r); // 라벨→대표 결과(level/floor 복원용)
            }
            if (r.positionText != null && !r.positionText.isEmpty()) {
                posCounts.merge(r.positionText, 1, Integer::sum);
            }
            if (r.bgColorRgb != null) {
                bgColors.add(r.bgColorRgb);
            }
            if (r.textColorRgb != null) {
                textColors.add(r.textColorRgb);
            }
        }

        ParkingPhotoAnalyzer.Result out = new ParkingPhotoAnalyzer.Result();
        String topFloor = topKey(floorCounts);
        if (topFloor != null) {
            ParkingPhotoAnalyzer.Result rep = floorRep.get(topFloor);
            out.levelType = rep.levelType;
            out.floor = rep.floor;
            out.floorLabel = rep.floorLabel;
        }
        out.positionText = topKey(posCounts);
        // 색은 연속값이라 최빈값 대신 유사색 클러스터의 대표(가장 큰 군집 평균)로 수렴.
        out.bgColorRgb = voteColor(bgColors);
        out.textColorRgb = voteColor(textColors);
        out.rawText = frame.rawText;           // OCR 원문은 현재 프레임 기준

        voteDebugLine = buildVoteDebug(floorCounts, posCounts);
        return out;
    }

    /**
     * 유사색 클러스터링 투표. 색은 조명·프레임마다 미세하게 달라 정확히 같은 값이 거의 없으므로,
     * RGB 거리 임계(COLOR_MERGE_DIST_SQ) 안의 색끼리 그리디로 묶고 **가장 큰 군집의 평균색**을 채택한다.
     * (층/위치의 최빈값 투표를 색에 맞게 "비슷한 색끼리" 세는 방식으로 확장한 것.)
     */
    @Nullable
    private static Integer voteColor(@NonNull List<Integer> colors) {
        if (colors.isEmpty()) {
            return null;
        }
        // 각 군집: {합R, 합G, 합B, 개수}. 대표색은 합/개수(평균)로 그때그때 계산.
        List<long[]> clusters = new ArrayList<>();
        for (int c : colors) {
            long r = Color.red(c);
            long g = Color.green(c);
            long b = Color.blue(c);
            long[] best = null;
            long bestDist = Long.MAX_VALUE;
            for (long[] cl : clusters) {
                long dr = cl[0] / cl[3] - r;
                long dg = cl[1] / cl[3] - g;
                long db = cl[2] / cl[3] - b;
                long dist = dr * dr + dg * dg + db * db;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = cl;
                }
            }
            if (best != null && bestDist <= COLOR_MERGE_DIST_SQ) {
                best[0] += r;
                best[1] += g;
                best[2] += b;
                best[3]++;
            } else {
                clusters.add(new long[]{r, g, b, 1});
            }
        }
        long[] top = null;
        for (long[] cl : clusters) {
            if (top == null || cl[3] > top[3]) {
                top = cl;
            }
        }
        return Color.rgb((int) (top[0] / top[3]), (int) (top[1] / top[3]), (int) (top[2] / top[3]));
    }

    /** 카운트 맵에서 최댓값 키(동률이면 먼저 등장한 값). 비어 있으면 null. */
    @Nullable
    private static String topKey(@NonNull Map<String, Integer> counts) {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** "[디버그] 누적 정렬 : 층 B2F×5 B3F×1 · 위치 A12×4 Al2×2" 형태의 문구. 둘 다 비면 빈 문자열. */
    @NonNull
    private String buildVoteDebug(@NonNull Map<String, Integer> floorCounts,
                                  @NonNull Map<String, Integer> posCounts) {
        String floors = formatCounts(floorCounts);
        String positions = formatCounts(posCounts);
        if (floors.isEmpty() && positions.isEmpty()) {
            return "";
        }
        return getString(R.string.analysis_debug_votes,
                floors.isEmpty() ? "-" : floors,
                positions.isEmpty() ? "-" : positions);
    }

    /** 카운트 맵을 많이 나온 순으로 "값×N 값×N" 문자열로. */
    @NonNull
    private static String formatCounts(@NonNull Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : list) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(e.getKey()).append('×').append(e.getValue());
        }
        return sb.toString();
    }

    /** RGBA_8888 ImageProxy를 회전 보정(정방향)한 ARGB 비트맵으로 변환한다. */
    @NonNull
    private Bitmap toUprightBitmap(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);

        int rotation = image.getImageInfo().getRotationDegrees();
        Matrix m = new Matrix();
        if (rotation != 0) {
            m.postRotate(rotation);
        }
        // 좌측 width만 취해 행 패딩을 잘라내고 회전까지 한 번에 적용.
        Bitmap out = Bitmap.createBitmap(padded, 0, 0, width, height, m, true);
        if (out != padded) {
            padded.recycle();
        }
        return out;
    }

    /** 하단 패널의 실시간 값(층/위치/배경색/글자색)을 갱신한다. */
    private void renderLive(@NonNull ParkingPhotoAnalyzer.Result r) {
        if (r.hasFloor()) {
            binding.valFloor.setText(ParkingFormat.levelLabel(r.levelType) + " " + r.floorLabel);
        } else {
            binding.valFloor.setText(R.string.auto_detected_none);
        }
        String pos = r.positionText;
        binding.valPosition.setText(pos != null && !pos.isEmpty()
                ? pos : getString(R.string.auto_detected_none));
        applySwatch(binding.swatchBg, r.bgColorRgb);
        applySwatch(binding.swatchText, r.textColorRgb);
        String raw = r.rawText != null && !r.rawText.trim().isEmpty()
                ? r.rawText.replace('\n', ' ').trim()
                : getString(R.string.analysis_debug_none);
        binding.valDebug.setText(getString(R.string.analysis_debug_raw, raw));
        binding.valVotes.setText(voteDebugLine != null ? voteDebugLine : "");
    }

    /** 색이 있으면 스와치를 그 색으로, 없으면 빈 스와치 배경으로. */
    private void applySwatch(@NonNull View swatch, @Nullable Integer rgb) {
        if (rgb == null) {
            swatch.setBackgroundResource(R.drawable.bg_swatch_empty);
            return;
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xFF000000 | rgb);
        gd.setCornerRadius(dp(4));
        gd.setStroke((int) dp(1), 0x66FFFFFF);
        swatch.setBackground(gd);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    // ----- 촬영 -----

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
                        putResult(data, latestResult); // 촬영 순간의 실시간 분석 결과 동봉
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
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
        }
        binding = null;
    }

    // ----- 결과 Intent 직렬화 -----

    /** 분석 결과를 결과 Intent extras로 쓴다(null 필드는 생략). */
    private static void putResult(@NonNull Intent i, @Nullable ParkingPhotoAnalyzer.Result r) {
        if (r == null) {
            return;
        }
        if (r.levelType != null) {
            i.putExtra(EXTRA_LEVEL, r.levelType.name());
        }
        if (r.floor != null) {
            i.putExtra(EXTRA_FLOOR, (int) r.floor);
        }
        if (r.floorLabel != null) {
            i.putExtra(EXTRA_FLOOR_LABEL, r.floorLabel);
        }
        if (r.positionText != null) {
            i.putExtra(EXTRA_POSITION, r.positionText);
        }
        if (r.bgColorRgb != null) {
            i.putExtra(EXTRA_BG_RGB, (int) r.bgColorRgb);
        }
        if (r.textColorRgb != null) {
            i.putExtra(EXTRA_TEXT_RGB, (int) r.textColorRgb);
        }
        if (r.rawText != null) {
            i.putExtra(EXTRA_RAW, r.rawText);
        }
    }

    /** 카메라 결과 Intent에서 분석 결과를 복원한다(없으면 빈 Result). */
    @NonNull
    public static ParkingPhotoAnalyzer.Result readResult(@Nullable Intent i) {
        ParkingPhotoAnalyzer.Result r = new ParkingPhotoAnalyzer.Result();
        if (i == null) {
            return r;
        }
        String level = i.getStringExtra(EXTRA_LEVEL);
        if (level != null) {
            try {
                r.levelType = ParkingLevelType.valueOf(level);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (i.hasExtra(EXTRA_FLOOR)) {
            r.floor = i.getIntExtra(EXTRA_FLOOR, 0);
        }
        r.floorLabel = i.getStringExtra(EXTRA_FLOOR_LABEL);
        r.positionText = i.getStringExtra(EXTRA_POSITION);
        if (i.hasExtra(EXTRA_BG_RGB)) {
            r.bgColorRgb = i.getIntExtra(EXTRA_BG_RGB, 0);
        }
        if (i.hasExtra(EXTRA_TEXT_RGB)) {
            r.textColorRgb = i.getIntExtra(EXTRA_TEXT_RGB, 0);
        }
        r.rawText = i.getStringExtra(EXTRA_RAW);
        return r;
    }
}
