package com.wonder.wherepark.ui.auto;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wonder.wherepark.R;
import com.wonder.wherepark.analyze.ParkingPhotoAnalyzer;
import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.model.Enums.EventType;
import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.ParkingLevelType;
import com.wonder.wherepark.data.model.Enums.ParkingPlaceType;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.data.model.Enums.SaveType;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.data.model.ParkingState;
import com.wonder.wherepark.data.repo.ParkingRepository;
import com.wonder.wherepark.data.repo.SettingsRepository;
import com.wonder.wherepark.data.repo.StateLogRepository;
import com.wonder.wherepark.data.repo.StateRepository;
import com.wonder.wherepark.databinding.ActivityAutoCaptureBinding;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.photo.PhotoStore;
import com.wonder.wherepark.service.DetectionService;
import com.wonder.wherepark.ui.camera.CameraCaptureActivity;
import com.wonder.wherepark.ui.input.ParkingInputActivity;
import com.wonder.wherepark.util.LocationOnceHelper;
import com.wonder.wherepark.util.LockScreenUtil;
import com.wonder.wherepark.util.ParkingFormat;
import com.wonder.wherepark.util.TimeUtil;
import com.wonder.wherepark.util.WindowInsetsUtil;

import java.io.File;

/**
 * 자동 촬영·분석 흐름 화면. 진입하면 곧바로 카메라를 띄워 주차 표지판을 촬영하게 하고,
 * 찍은 사진을 온디바이스로 분석해 층·위치·배경색을 사용자에게 확인받는다.
 * 사용자가 "이대로 저장"하면 그대로 현재 주차로 저장(AUTO)하고,
 * "수정"하면 인식 값을 미리 채운 주차 위치 입력 화면으로 이동한다.
 */
public class AutoCaptureActivity extends AppCompatActivity {

    private ActivityAutoCaptureBinding binding;

    private SettingsRepository settingsRepo;
    private ParkingRepository parkingRepo;
    private StateRepository stateRepo;
    private StateLogRepository logRepo;
    private PhotoStore photoStore;

    @Nullable
    private String savedPhotoPath;          // 압축 저장된 주차 사진 경로
    @Nullable
    private ParkingPhotoAnalyzer.Result analysis;
    @Nullable
    private Location capturedLocation;

    private ActivityResultLauncher<Intent> cameraLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LockScreenUtil.showWhenLocked(this); // 잠금화면 위로 떠야 하므로(풀스크린 인텐트 진입)
        binding = ActivityAutoCaptureBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsUtil.applySystemBars(this);

        Context ctx = this;
        settingsRepo = new SettingsRepository(ctx);
        parkingRepo = new ParkingRepository(ctx);
        stateRepo = new StateRepository(ctx);
        logRepo = new StateLogRepository(ctx);
        photoStore = new PhotoStore(ctx);

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    String path = result.getResultCode() == RESULT_OK && result.getData() != null
                            ? result.getData().getStringExtra(CameraCaptureActivity.EXTRA_RESULT_PATH)
                            : null;
                    if (path != null) {
                        File f = new File(path);
                        onPhotoCaptured(Uri.fromFile(f), f);
                    } else {
                        // 촬영 취소: 자동 흐름 종료(주차 상태는 유지되어 다시 시도 가능)
                        toast(R.string.auto_capture_canceled);
                        finish();
                    }
                });

        binding.btnConfirm.setOnClickListener(v -> saveDirect());
        binding.btnEdit.setOnClickListener(v -> openEditPrefilled());
        binding.btnRetake.setOnClickListener(v -> launchCamera());

        captureLocationForSave();

        if (savedInstanceState == null) {
            launchCamera();
        }
    }

    // ----- 카메라 -----

    private void launchCamera() {
        showProgress(true);
        cameraLauncher.launch(new Intent(this, CameraCaptureActivity.class));
    }

    private void onPhotoCaptured(Uri source, @Nullable File tempToDelete) {
        showProgress(true);
        new Thread(() -> {
            String path = photoStore.saveCompressed(source);
            // 분석용 비트맵은 저장본에서 디코드(촬영 임시파일은 제거)
            Bitmap bitmap = path != null ? BitmapFactory.decodeFile(path) : null;
            runOnUiThread(() -> {
                if (tempToDelete != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tempToDelete.delete();
                }
                if (path == null || bitmap == null) {
                    toast(R.string.input_photo_fail);
                    finish();
                    return;
                }
                savedPhotoPath = path;
                ParkingPhotoAnalyzer.analyze(bitmap, result -> {
                    bitmap.recycle();
                    analysis = result;
                    showResult(result);
                });
            });
        }).start();
    }

    // ----- 결과 표시 -----

    private void showResult(ParkingPhotoAnalyzer.Result r) {
        if (binding == null) {
            return;
        }
        showProgress(false);
        if (savedPhotoPath != null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            Bitmap preview = BitmapFactory.decodeFile(savedPhotoPath, opts);
            if (preview != null) {
                binding.imgPreview.setImageBitmap(preview);
            }
        }
        binding.valFloor.setText(floorDisplay(r));
        setOrNone(binding.valPosition, r.positionText);
        setColorRow(binding.valColorSwatch, binding.valColor, r.bgColorRgb);
        setColorRow(binding.valTextColorSwatch, binding.valTextColor, r.textColorRgb);
        String raw = r.rawText != null && !r.rawText.trim().isEmpty()
                ? r.rawText.replace('\n', ' ').trim()
                : getString(R.string.analysis_debug_none);
        binding.valDebug.setText(getString(R.string.analysis_debug_raw, raw));
    }

    /** 색 견본(스와치)만 표시한다(HEX는 사용자에게 노출하지 않음). 색이 없으면 "인식 안 됨". */
    private void setColorRow(View swatch, TextView label, @Nullable Integer rgb) {
        if (rgb == null) {
            swatch.setVisibility(View.GONE);
            label.setText(R.string.auto_detected_none);
            return;
        }
        swatch.setVisibility(View.VISIBLE);
        label.setText("");
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xFF000000 | rgb);
        gd.setCornerRadius(8f);
        gd.setStroke(2, 0xFF888888);
        swatch.setBackground(gd);
    }

    private String floorDisplay(ParkingPhotoAnalyzer.Result r) {
        if (r.levelType != null && r.floorLabel != null) {
            return ParkingFormat.levelLabel(r.levelType) + " " + r.floorLabel;
        }
        return getString(R.string.auto_detected_none);
    }

    private void setOrNone(TextView tv, @Nullable String value) {
        tv.setText(value != null && !value.isEmpty() ? value : getString(R.string.auto_detected_none));
    }

    private void showProgress(boolean progress) {
        binding.groupProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
        binding.groupResult.setVisibility(progress ? View.GONE : View.VISIBLE);
    }

    // ----- 저장(이대로) -----

    private void saveDirect() {
        ParkingPhotoAnalyzer.Result r = analysis != null ? analysis : new ParkingPhotoAnalyzer.Result();
        AppSettings s = settingsRepo.get();
        ParkingPlaceType place = judgePlace();

        ParkingRecord rec = new ParkingRecord();
        rec.saveType = SaveType.AUTO;
        rec.parkingPlaceType = place;
        if (r.hasFloor()) {
            rec.parkingLevelType = r.levelType;
            rec.floorLabel = r.floorLabel;
        } else {
            rec.parkingLevelType = ParkingLevelType.ETC;
            rec.floorLabel = null;
        }
        rec.memo = r.memoText();           // 위치만(색 hex 제외)
        rec.bgColorRgb = r.bgColorRgb;     // 색은 별도 컬럼
        rec.textColorRgb = r.textColorRgb;
        rec.photoPath = savedPhotoPath;
        rec.parkedAt = TimeUtil.now();
        if (capturedLocation != null) {
            rec.latitude = capturedLocation.getLatitude();
            rec.longitude = capturedLocation.getLongitude();
            rec.hasGps = true;
        }

        long id = parkingRepo.insertAsCurrent(rec);
        markParked(id);
        updateNotificationsForCurrent(rec, place, s);

        toast(R.string.input_saved);
        finish();
    }

    /** 현재 주차로 상태 전환(§9.6, §10.1). */
    private void markParked(long recordId) {
        ParkingState st = stateRepo.get();
        st.parkingStatus = ParkingStatus.PARKED;
        st.currentParkingRecordId = recordId;
        st.lastStateChangedAt = TimeUtil.now();
        stateRepo.update(st);
        logRepo.append(EventType.PARKING_INPUT_SAVED, null, ParkingStatus.PARKED.name(), false, null);
    }

    /** 입력 완료 처리: 입력 요청 알림 제거 + 외부 주차 상시 알림 갱신(§14). */
    private void updateNotificationsForCurrent(ParkingRecord r, ParkingPlaceType place, AppSettings s) {
        NotificationHelper.cancelInputRequest(this);
        if (s.hasVehicleBt()) {
            NotificationHelper.cancelOngoingParking(this);
            DetectionService.refresh(this);
        } else if (place == ParkingPlaceType.OUTSIDE) {
            NotificationHelper.showOngoingParking(this, r);
        } else {
            NotificationHelper.cancelOngoingParking(this);
        }
    }

    // ----- 수정(입력 화면으로) -----

    private void openEditPrefilled() {
        ParkingPhotoAnalyzer.Result r = analysis != null ? analysis : new ParkingPhotoAnalyzer.Result();
        Intent intent = new Intent(this, ParkingInputActivity.class)
                .putExtra(ParkingInputActivity.EXTRA_PREFILL_PLACE, judgePlace().name())
                .putExtra(ParkingInputActivity.EXTRA_PREFILL_MEMO, r.memoText())
                .putExtra(ParkingInputActivity.EXTRA_PREFILL_PHOTO, savedPhotoPath);
        if (r.bgColorRgb != null) {
            intent.putExtra(ParkingInputActivity.EXTRA_PREFILL_BG_RGB, (int) r.bgColorRgb);
        }
        if (r.textColorRgb != null) {
            intent.putExtra(ParkingInputActivity.EXTRA_PREFILL_TEXT_RGB, (int) r.textColorRgb);
        }
        if (r.hasFloor()) {
            intent.putExtra(ParkingInputActivity.EXTRA_PREFILL_LEVEL, r.levelType.name());
            intent.putExtra(ParkingInputActivity.EXTRA_PREFILL_FLOOR_LABEL, r.floorLabel);
        } else {
            intent.putExtra(ParkingInputActivity.EXTRA_PREFILL_LEVEL, ParkingLevelType.ETC.name());
        }
        startActivity(intent);
        finish();
    }

    // ----- 보조 -----

    /** 현재 집/외부 판단: 감지 상태가 재택이면 집, 아니면 외부. */
    private ParkingPlaceType judgePlace() {
        return stateRepo.get().homeStatus == HomeStatus.HOME
                ? ParkingPlaceType.HOME : ParkingPlaceType.OUTSIDE;
    }

    private void captureLocationForSave() {
        if (!hasLocationPermission()) {
            return;
        }
        capturedLocation = lastKnown();
        LocationOnceHelper.requestOnce(this, location -> {
            if (location != null) {
                capturedLocation = location;
            }
        });
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    private Location lastKnown() {
        if (!hasLocationPermission()) {
            return null;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return null;
        }
        Location gps = null;
        Location net = null;
        try {
            gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException ignored) {
        }
        if (gps == null) return net;
        if (net == null) return gps;
        return gps.getTime() >= net.getTime() ? gps : net;
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
