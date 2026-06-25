package com.wonder.wherepark.ui.input;

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
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.model.Enums.EventType;
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
import com.wonder.wherepark.databinding.ActivityParkingInputBinding;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.photo.PhotoStore;
import com.wonder.wherepark.service.DetectionService;
import com.wonder.wherepark.util.LocationOnceHelper;
import com.wonder.wherepark.util.ParkingFormat;
import com.wonder.wherepark.util.TimeUtil;
import com.wonder.wherepark.util.WifiHelper;
import com.wonder.wherepark.util.WindowInsetsUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * §12 주차 위치 입력 전용 화면. 신규(수동 저장)와 기존 이력 수정을 모두 처리한다.
 * 진입 시 EXTRA_RECORD_ID가 있으면 수정 모드, 없으면 신규 수동 저장 모드.
 */
public class ParkingInputActivity extends AppCompatActivity {

    /** 수정 모드 진입 시 전달할 주차 기록 id. 없으면 신규. */
    public static final String EXTRA_RECORD_ID = "record_id";

    private ActivityParkingInputBinding binding;

    private SettingsRepository settingsRepo;
    private ParkingRepository parkingRepo;
    private StateRepository stateRepo;
    private StateLogRepository logRepo;
    private PhotoStore photoStore;

    private long recordId = ParkingRecord.NO_ID;
    @Nullable
    private ParkingRecord editing;          // 수정 모드의 기존 레코드
    @Nullable
    private String originalPhotoPath;       // 진입 시점 사진 경로(교체/삭제 시 정리 대상)
    @Nullable
    private String pendingPhotoPath;        // 현재 화면에 반영된 사진 경로
    @Nullable
    private Location capturedLocation;      // 신규 저장용 GPS
    private boolean autoDetectedPark = false; // 진입 시 이미 PARKED면 자동 감지된 주차

    @Nullable
    private File captureTempFile;
    @Nullable
    private Uri captureUri;

    private ActivityResultLauncher<Uri> takePictureLauncher;
    private ActivityResultLauncher<Intent> drawLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityParkingInputBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsUtil.applySystemBars(this);

        Context ctx = this;
        settingsRepo = new SettingsRepository(ctx);
        parkingRepo = new ParkingRepository(ctx);
        stateRepo = new StateRepository(ctx);
        logRepo = new StateLogRepository(ctx);
        photoStore = new PhotoStore(ctx);

        registerPhotoLaunchers();

        recordId = getIntent().getLongExtra(EXTRA_RECORD_ID, ParkingRecord.NO_ID);

        binding.togglePlace.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) {
                updateLevelAvailability();
                refreshFloorOptions(true);
            }
        });
        binding.toggleLevel.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) refreshFloorOptions(true);
        });

        binding.btnTakePhoto.setOnClickListener(v -> launchCamera());
        binding.btnDraw.setOnClickListener(v ->
                drawLauncher.launch(new Intent(this, DrawActivity.class)));
        binding.btnRemovePhoto.setOnClickListener(v -> removePhoto());
        binding.btnSave.setOnClickListener(v -> onSave());
        binding.btnCancel.setOnClickListener(v -> onCancel());

        if (recordId != ParkingRecord.NO_ID) {
            loadForEdit();
        } else {
            initForNew();
        }
    }

    private void registerPhotoLaunchers() {
        takePictureLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), success -> {
                    if (success && captureUri != null) {
                        compressAndSet(captureUri, captureTempFile);
                    } else {
                        deleteTempCapture();
                    }
                });
        drawLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String path = result.getData().getStringExtra(DrawActivity.EXTRA_RESULT_PATH);
                        if (path != null) {
                            setNewPhoto(path); // 그리기 결과는 이미 압축 저장됨
                        }
                    }
                });
    }

    // ----- 초기화 -----

    private void initForNew() {
        // 진입 시 이미 PARKED면 자동 감지로 잡힌 주차를 입력하는 것 → AUTO로 저장(§13.10)
        autoDetectedPark = stateRepo.get().parkingStatus == ParkingStatus.PARKED;
        AppSettings s = settingsRepo.get();
        ParkingPlaceType defaultPlace = judgeDefaultPlace(s);
        checkPlace(defaultPlace);
        checkLevel(defaultLevelFor(defaultPlace, s)); // §12.5/12.6 기본 지하(없으면 지상/기타)
        updateLevelAvailability();
        refreshFloorOptions(false);
        captureLocationForSave(s);
    }

    /** 집 주차의 기본 유형: 지하가 있으면 지하, 없으면 지상, 둘 다 없으면 기타. 외부는 지하. */
    private ParkingLevelType defaultLevelFor(ParkingPlaceType place, AppSettings s) {
        if (place == ParkingPlaceType.OUTSIDE) {
            return ParkingLevelType.UNDERGROUND;
        }
        if (s.homeUndergroundFloorCount > 0) {
            return ParkingLevelType.UNDERGROUND;
        }
        if (s.homeGroundFloorCount > 0) {
            return ParkingLevelType.GROUND;
        }
        return ParkingLevelType.ETC;
    }

    /**
     * 집 주차는 층수가 0(없음)인 유형의 토글을 비활성화한다. 현재 선택이 비활성이면 가능한 유형으로 옮긴다.
     * 외부 주차는 모든 유형을 사용할 수 있다(지하/지상 1~100층).
     */
    private void updateLevelAvailability() {
        if (selectedPlace() == ParkingPlaceType.HOME) {
            AppSettings s = settingsRepo.get();
            boolean underOk = s.homeUndergroundFloorCount > 0;
            boolean groundOk = s.homeGroundFloorCount > 0;
            binding.btnLevelUnderground.setEnabled(underOk);
            binding.btnLevelGround.setEnabled(groundOk);
            binding.btnLevelEtc.setEnabled(true);

            ParkingLevelType cur = selectedLevel();
            if ((cur == ParkingLevelType.UNDERGROUND && !underOk)
                    || (cur == ParkingLevelType.GROUND && !groundOk)) {
                if (underOk) {
                    checkLevel(ParkingLevelType.UNDERGROUND);
                } else if (groundOk) {
                    checkLevel(ParkingLevelType.GROUND);
                } else {
                    checkLevel(ParkingLevelType.ETC);
                }
            }
        } else {
            binding.btnLevelUnderground.setEnabled(true);
            binding.btnLevelGround.setEnabled(true);
            binding.btnLevelEtc.setEnabled(true);
        }
    }

    private void loadForEdit() {
        ParkingRecord r = parkingRepo.getById(recordId);
        if (r == null) {
            initForNew();
            return;
        }
        editing = r;
        originalPhotoPath = r.photoPath;
        pendingPhotoPath = r.photoPath;

        checkPlace(r.parkingPlaceType);
        checkLevel(r.parkingLevelType);
        updateLevelAvailability();
        refreshFloorOptions(false);
        // 기존 층 선택 복원
        if (r.floorLabel != null) {
            selectFloorLabel(r.floorLabel);
        }
        binding.inputMemo.setText(r.memo);
        if (r.hasPhoto()) {
            showPreviewFromPath(r.photoPath);
        }
    }

    /** §9.6 수동 저장 시 집/외부 기본 판단: 집 Wi-Fi 일치 또는 집 반경 이내면 집. */
    private ParkingPlaceType judgeDefaultPlace(AppSettings s) {
        if (s.hasHomeWifi()) {
            String cur = WifiHelper.getCurrentSsid(this);
            if (s.matchesHomeWifi(cur)) {
                return ParkingPlaceType.HOME;
            }
        }
        if (s.hasHomeLocation() && hasLocationPermission()) {
            Location last = lastKnown();
            if (last != null) {
                float[] out = new float[1];
                Location.distanceBetween(last.getLatitude(), last.getLongitude(),
                        s.homeLatitude, s.homeLongitude, out);
                if (out[0] <= s.homeRadiusMeters) {
                    return ParkingPlaceType.HOME;
                }
            }
        }
        return ParkingPlaceType.OUTSIDE;
    }

    private void captureLocationForSave(AppSettings s) {
        if (!hasLocationPermission()) {
            return;
        }
        capturedLocation = lastKnown(); // 우선 마지막 위치
        LocationOnceHelper.requestOnce(this, location -> {
            if (location != null) {
                capturedLocation = location; // 더 신선한 위치로 갱신
            }
        });
    }

    // ----- 토글/층 -----

    private void checkPlace(ParkingPlaceType type) {
        binding.togglePlace.check(type == ParkingPlaceType.HOME
                ? binding.btnPlaceHome.getId() : binding.btnPlaceOutside.getId());
    }

    private void checkLevel(ParkingLevelType type) {
        int id;
        switch (type) {
            case GROUND: id = binding.btnLevelGround.getId(); break;
            case ETC: id = binding.btnLevelEtc.getId(); break;
            default: id = binding.btnLevelUnderground.getId(); break;
        }
        binding.toggleLevel.check(id);
    }

    private ParkingPlaceType selectedPlace() {
        return binding.togglePlace.getCheckedButtonId() == binding.btnPlaceHome.getId()
                ? ParkingPlaceType.HOME : ParkingPlaceType.OUTSIDE;
    }

    private ParkingLevelType selectedLevel() {
        int id = binding.toggleLevel.getCheckedButtonId();
        if (id == binding.btnLevelGround.getId()) return ParkingLevelType.GROUND;
        if (id == binding.btnLevelEtc.getId()) return ParkingLevelType.ETC;
        return ParkingLevelType.UNDERGROUND;
    }

    /** 현재 구분/유형에 맞춰 층 스피너를 채운다. resetSelection이면 첫 항목으로. */
    private void refreshFloorOptions(boolean resetSelection) {
        ParkingPlaceType place = selectedPlace();
        ParkingLevelType level = selectedLevel();

        if (level == ParkingLevelType.ETC) {
            binding.layoutFloor.setVisibility(View.GONE);
            return;
        }
        binding.layoutFloor.setVisibility(View.VISIBLE);

        int max;
        AppSettings s = settingsRepo.get();
        if (place == ParkingPlaceType.HOME) {
            max = level == ParkingLevelType.UNDERGROUND
                    ? s.homeUndergroundFloorCount : s.homeGroundFloorCount; // §12.5
        } else {
            max = 100; // §12.6 외부는 B1F~B100F / 1F~100F
        }
        if (max < 1) {
            // 집 주차에서 해당 유형의 층수가 '없음'인 경우(토글도 비활성) — 층 선택 숨김
            binding.layoutFloor.setVisibility(View.GONE);
            return;
        }

        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= max; i++) {
            labels.add(ParkingFormat.floorLabel(level, i));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        int prev = binding.spinnerFloor.getSelectedItemPosition();
        binding.spinnerFloor.setAdapter(adapter);
        if (!resetSelection && prev >= 0 && prev < labels.size()) {
            binding.spinnerFloor.setSelection(prev);
        }
    }

    private void selectFloorLabel(String label) {
        ArrayAdapter<?> adapter = (ArrayAdapter<?>) binding.spinnerFloor.getAdapter();
        if (adapter == null) return;
        for (int i = 0; i < adapter.getCount(); i++) {
            if (label.equals(adapter.getItem(i))) {
                binding.spinnerFloor.setSelection(i);
                return;
            }
        }
    }

    @Nullable
    private String selectedFloorLabel() {
        if (selectedLevel() == ParkingLevelType.ETC) {
            return null;
        }
        Object item = binding.spinnerFloor.getSelectedItem();
        return item != null ? item.toString() : null;
    }

    // ----- 사진 -----

    private void launchCamera() {
        try {
            captureTempFile = photoStore.createTempCaptureFile();
            captureUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", captureTempFile);
            takePictureLauncher.launch(captureUri);
        } catch (Exception e) {
            toast(getString(com.wonder.wherepark.R.string.input_photo_fail));
        }
    }

    /** 소스 Uri를 압축 저장하고 미리보기에 반영. 백그라운드에서 처리. */
    private void compressAndSet(Uri source, @Nullable File tempToDelete) {
        new Thread(() -> {
            String path = photoStore.saveCompressed(source);
            runOnUiThread(() -> {
                if (tempToDelete != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tempToDelete.delete();
                }
                if (path == null) {
                    toast(getString(com.wonder.wherepark.R.string.input_photo_fail));
                    return;
                }
                setNewPhoto(path);
            });
        }).start();
    }

    /** 새 이미지(촬영/갤러리/그리기)를 반영한다. 기존 임시 이미지는 교체 시 정리. */
    private void setNewPhoto(String path) {
        if (pendingPhotoPath != null && !pendingPhotoPath.equals(originalPhotoPath)) {
            photoStore.deleteByPath(pendingPhotoPath);
        }
        pendingPhotoPath = path;
        showPreviewFromPath(path);
    }

    private void removePhoto() {
        if (pendingPhotoPath != null && !pendingPhotoPath.equals(originalPhotoPath)) {
            photoStore.deleteByPath(pendingPhotoPath);
        }
        pendingPhotoPath = null;
        binding.imgPreview.setVisibility(View.GONE);
        binding.btnRemovePhoto.setVisibility(View.GONE);
    }

    private void showPreviewFromPath(String path) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2; // 미리보기는 절반 해상도면 충분
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp != null) {
            binding.imgPreview.setImageBitmap(bmp);
            binding.imgPreview.setVisibility(View.VISIBLE);
            binding.btnRemovePhoto.setVisibility(View.VISIBLE);
        }
    }

    // ----- 저장/취소 -----

    private void onSave() {
        ParkingLevelType level = selectedLevel();
        String memo = binding.inputMemo.getText() != null
                ? binding.inputMemo.getText().toString().trim() : "";

        // §12.7 필수값 검증
        if (level == ParkingLevelType.ETC) {
            if (TextUtils.isEmpty(memo) && pendingPhotoPath == null) {
                toast(getString(com.wonder.wherepark.R.string.input_err_etc));
                return;
            }
        } else {
            if (selectedFloorLabel() == null) {
                toast(getString(com.wonder.wherepark.R.string.input_err_floor));
                return;
            }
        }

        boolean isNew = (editing == null);
        ParkingRecord r = isNew ? new ParkingRecord() : editing;
        r.parkingPlaceType = selectedPlace();
        r.parkingLevelType = level;
        r.floorLabel = selectedFloorLabel();
        r.memo = TextUtils.isEmpty(memo) ? null : memo;
        r.photoPath = pendingPhotoPath;

        if (isNew) {
            // 자동 감지된 주차를 입력하면 AUTO, 순수 수동 저장이면 MANUAL (§13.10)
            r.saveType = autoDetectedPark ? SaveType.AUTO : SaveType.MANUAL;
            r.parkedAt = TimeUtil.now();
            if (capturedLocation != null) { // §16.1 가능하면 GPS 저장
                r.latitude = capturedLocation.getLatitude();
                r.longitude = capturedLocation.getLongitude();
                r.hasGps = true;
            }
            long id = parkingRepo.insertAsCurrent(r);
            markParked(id);
        } else {
            parkingRepo.update(r);
        }

        // §17.5 교체/삭제된 기존 사진 파일 정리
        if (originalPhotoPath != null && !originalPhotoPath.equals(pendingPhotoPath)) {
            photoStore.deleteByPath(originalPhotoPath);
        }

        // §12.8/§14 현재 주차 알림 갱신
        boolean isCurrentRecord = isNew || (editing != null && editing.isCurrent);
        updateNotificationsForCurrent(r, isCurrentRecord);

        toast(getString(com.wonder.wherepark.R.string.input_saved));
        setResult(RESULT_OK);
        finish();
    }

    /**
     * 현재 주차 레코드 저장/수정 시 알림을 갱신한다.
     * 입력이 완료됐으므로 입력 요청 알림은 제거하고, 외부 주차면 상시 알림을 등록/갱신, 집 주차면 상시 알림 제거.
     */
    private void updateNotificationsForCurrent(ParkingRecord r, boolean isCurrentRecord) {
        if (!isCurrentRecord) {
            return;
        }
        NotificationHelper.cancelInputRequest(this); // §14.1 입력 완료
        if (settingsRepo.get().hasVehicleBt()) {
            // 감지 서비스가 FGS 알림으로 상시 알림을 렌더링하므로 별도 1002는 쓰지 않는다.
            NotificationHelper.cancelOngoingParking(this);
            DetectionService.refresh(this);
        } else if (r.parkingPlaceType == ParkingPlaceType.OUTSIDE) {
            NotificationHelper.showOngoingParking(this, r); // §14.2 (서비스 없을 때 fallback)
        } else {
            NotificationHelper.cancelOngoingParking(this); // §12.8 집 주차는 Ongoing 미등록
        }
    }

    /** 신규 수동 저장 시 상태를 PARKED로 전환하고 현재 주차로 지정한다(§9.6, §10.1). */
    private void markParked(long recordId) {
        ParkingState st = stateRepo.get();
        st.parkingStatus = ParkingStatus.PARKED;
        st.currentParkingRecordId = recordId;
        st.lastStateChangedAt = TimeUtil.now();
        stateRepo.update(st);
        logRepo.append(EventType.PARKING_INPUT_SAVED, null,
                ParkingStatus.PARKED.name(), false, null);
        // 외부 주차 Ongoing 등록은 onSave의 updateNotificationsForCurrent에서 처리한다.
    }

    private void onCancel() {
        // §12.9 취소: 새로 만든(원본이 아닌) 압축 사진만 정리. 원본은 유지.
        if (pendingPhotoPath != null && !pendingPhotoPath.equals(originalPhotoPath)) {
            photoStore.deleteByPath(pendingPhotoPath);
        }
        deleteTempCapture();
        finish();
    }

    @Override
    public void onBackPressed() {
        onCancel();
        super.onBackPressed();
    }

    private void deleteTempCapture() {
        if (captureTempFile != null && captureTempFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            captureTempFile.delete();
        }
    }

    // ----- 위치 헬퍼 -----

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Nullable
    private Location lastKnown() {
        if (!hasLocationPermission()) return null;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
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

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
