package com.wonder.wherepark.ui.auto;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
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
import com.wonder.wherepark.util.LocationOnceHelper;
import com.wonder.wherepark.util.LockScreenUtil;
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
                    Intent data = result.getResultCode() == RESULT_OK ? result.getData() : null;
                    String path = data != null
                            ? data.getStringExtra(CameraCaptureActivity.EXTRA_RESULT_PATH) : null;
                    if (path != null) {
                        // 촬영 화면에서 실시간으로 본 분석 결과를 그대로 사용(별도 확인 화면 없음).
                        analysis = CameraCaptureActivity.readResult(data);
                        File f = new File(path);
                        onPhotoCaptured(Uri.fromFile(f), f);
                    } else {
                        // 촬영 취소: 자동 흐름 종료(주차 상태는 유지되어 다시 시도 가능)
                        toast(R.string.auto_capture_canceled);
                        finish();
                    }
                });

        captureLocationForSave();

        if (savedInstanceState == null) {
            launchCamera();
        }
    }

    // ----- 카메라 -----

    private void launchCamera() {
        cameraLauncher.launch(new Intent(this, CameraCaptureActivity.class));
    }

    /** 촬영본을 압축 저장한 뒤, 촬영 화면에서 인식한 분석 결과로 곧바로 현재 주차를 저장한다. */
    private void onPhotoCaptured(Uri source, @Nullable File tempToDelete) {
        new Thread(() -> {
            String path = photoStore.saveCompressed(source);
            runOnUiThread(() -> {
                if (tempToDelete != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tempToDelete.delete();
                }
                if (isFinishing()) {
                    return;
                }
                if (path == null) {
                    toast(R.string.input_photo_fail);
                    finish();
                    return;
                }
                savedPhotoPath = path;
                saveDirect();
            });
        }).start();
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

        // 주차 시 자동 저장된 GPS 자리표시 레코드가 있으면 새로 만들지 않고 그 레코드를 채워 갱신한다.
        ParkingRecord placeholder = parkingRepo.getCurrent();
        long id;
        if (placeholder != null && placeholder.isGpsPlaceholder()) {
            rec.id = placeholder.id;
            rec.parkedAt = placeholder.parkedAt; // 주차 시점 유지
            applyGps(rec, placeholder);
            parkingRepo.update(rec); // is_current 유지
            id = placeholder.id;
        } else {
            rec.parkedAt = TimeUtil.now();
            applyGps(rec, null);
            id = parkingRepo.insertAsCurrent(rec);
        }
        markParked(id);
        updateNotificationsForCurrent(rec, place, s);

        toast(R.string.input_saved);
        finish();
    }

    /**
     * 저장 레코드에 GPS를 채운다. 이번에 잡은 위치가 있으면 우선 쓰고, 없으면 자리표시가 갖고 있던
     * 주차 시점 GPS를 유지한다(둘 다 없으면 GPS 없음).
     */
    private void applyGps(ParkingRecord rec, @Nullable ParkingRecord placeholder) {
        if (capturedLocation != null) {
            rec.latitude = capturedLocation.getLatitude();
            rec.longitude = capturedLocation.getLongitude();
            rec.hasGps = true;
        } else if (placeholder != null && placeholder.hasGps) {
            rec.latitude = placeholder.latitude;
            rec.longitude = placeholder.longitude;
            rec.hasGps = true;
        }
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
