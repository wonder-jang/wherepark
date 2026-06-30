package com.wonder.wherepark.ui.main;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.wonder.wherepark.R;
import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.model.Enums.EventType;
import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.ParkingPlaceType;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.data.model.ParkingState;
import com.wonder.wherepark.data.repo.ParkingRepository;
import com.wonder.wherepark.data.repo.SettingsRepository;
import com.wonder.wherepark.data.repo.StateLogRepository;
import com.wonder.wherepark.data.repo.StateRepository;
import com.wonder.wherepark.databinding.FragmentParkingBinding;
import com.wonder.wherepark.engine.StateBus;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.service.AutoCaptureLauncher;
import com.wonder.wherepark.service.DetectionService;
import com.wonder.wherepark.ui.auto.AutoCaptureActivity;
import com.wonder.wherepark.ui.input.ParkingInputActivity;
import com.wonder.wherepark.ui.photo.PhotoViewActivity;
import com.wonder.wherepark.util.MapLauncher;
import com.wonder.wherepark.util.ParkingFormat;
import com.wonder.wherepark.util.TimeUtil;

/**
 * §9 주차 위치 탭(메인). 현재 주차 상태/위치를 표시하고 입력·수정·초기화·지도 동작을 제공한다.
 */
public class ParkingFragment extends Fragment {

    private FragmentParkingBinding binding;
    private SettingsRepository settingsRepo;
    private ParkingRepository parkingRepo;
    private StateRepository stateRepo;
    private StateLogRepository logRepo;

    // 백그라운드 감지로 상태가 바뀌면 화면이 떠 있어도 즉시 갱신
    private final StateBus.Listener stateListener = () -> {
        if (binding != null) {
            render();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentParkingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context ctx = requireContext();
        settingsRepo = new SettingsRepository(ctx);
        parkingRepo = new ParkingRepository(ctx);
        stateRepo = new StateRepository(ctx);
        logRepo = new StateLogRepository(ctx);

        binding.btnGoSettings.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToSettingsTab();
            }
        });
        binding.btnManualSave.setOnClickListener(v -> openInput(ParkingRecord.NO_ID));
        binding.btnInput.setOnClickListener(v -> openInput(ParkingRecord.NO_ID));
    }

    @Override
    public void onResume() {
        super.onResume();
        StateBus.register(stateListener);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        StateBus.unregister(stateListener);
    }

    private void render() {
        AppSettings settings = settingsRepo.get();

        // §9.3 차량 BT 미설정 안내
        if (!settings.hasVehicleBt()) {
            binding.txtStatus.setText(R.string.parking_none);
            binding.blockBtNotSet.setVisibility(View.VISIBLE);
            binding.blockMain.setVisibility(View.GONE);
            return;
        }
        binding.blockBtNotSet.setVisibility(View.GONE);
        binding.blockMain.setVisibility(View.VISIBLE);

        ParkingState state = stateRepo.get();
        ParkingRecord current = parkingRepo.getCurrent();

        hideAllButtons();
        binding.imgPhoto.setVisibility(View.GONE);
        binding.imgColorSwatch.setVisibility(View.GONE);

        if (state.parkingStatus == ParkingStatus.DRIVING) {
            // §10.3 운행 중
            binding.txtStatus.setText(R.string.parking_driving);
            binding.txtLocation.setText(R.string.parking_driving);
            show(binding.btnManualSave);
        } else if (current != null) {
            // 주차 + 위치 입력됨 (§9.2) — 입력된 주차 구분(재택/외출)으로 표시
            binding.txtStatus.setText(parkedStatusRes(current.parkingPlaceType == ParkingPlaceType.HOME));
            binding.txtLocation.setText(ParkingFormat.summary(current));
            showColorSwatch(current);
            showPhoto(current);
            show(binding.btnEdit);
            if (current.hasGps) {
                show(binding.btnMap);
                binding.btnMap.setOnClickListener(v -> MapLauncher.open(requireContext(),
                        current.latitude, current.longitude, ParkingFormat.summary(current)));
            }
            show(binding.btnClear);
            binding.btnEdit.setOnClickListener(v -> openInput(current.id));
            binding.btnClear.setOnClickListener(v -> confirmClear());
        } else if (state.parkingStatus == ParkingStatus.PARKED) {
            // 주차 + 위치 미입력 (§9.2, §10.2) — 레코드가 없으므로 집 상태(재택/외출)로 구분
            binding.txtStatus.setText(parkedStatusRes(state.homeStatus == HomeStatus.HOME));
            binding.txtLocation.setText(R.string.parking_no_location);
            show(binding.btnInput);
            if (settings.autoPhotoAnalysisEnabled) {
                // 자동 모드: 버튼을 촬영으로 바꾼다.
                binding.btnInput.setText(R.string.parking_auto_capture);
                binding.btnInput.setOnClickListener(v -> openAutoCapture());
                // 오버레이 권한이 있으면 AutoCaptureLauncher가 자동 실행을 책임지므로 여기선 띄우지 않는다(중복 방지).
                // 권한이 없을 때만 화면이 떠 있는 동안 1회 자동으로 띄운다(포그라운드 폴백).
                if (!AutoCaptureLauncher.canAutoLaunch(requireContext())) {
                    maybeAutoLaunchCapture(state);
                }
            } else {
                binding.btnInput.setText(R.string.parking_input);
                binding.btnInput.setOnClickListener(v -> openInput(ParkingRecord.NO_ID));
            }
            show(binding.btnClear);
            binding.btnClear.setOnClickListener(v -> confirmClear());
        } else {
            // 정보 없음 (§9.2)
            binding.txtStatus.setText(R.string.parking_none);
            binding.txtLocation.setText(R.string.parking_none);
            show(binding.btnManualSave);
        }
    }

    /** 주차 상태 문구: 집(재택)이면 "주차 (재택)", 아니면 "주차 (외출)". */
    private int parkedStatusRes(boolean atHome) {
        return atHome ? R.string.parking_parked_home : R.string.parking_parked_outside;
    }

    /** 분석된 배경/글자 색을 결합 스와치로 표시(없으면 숨김). */
    private void showColorSwatch(ParkingRecord r) {
        android.graphics.Bitmap sw = com.wonder.wherepark.util.ColorMemo.swatch(r.bgColorRgb, r.textColorRgb);
        if (sw != null) {
            binding.imgColorSwatch.setImageBitmap(sw);
            binding.imgColorSwatch.setVisibility(View.VISIBLE);
        } else {
            binding.imgColorSwatch.setVisibility(View.GONE);
        }
    }

    private void showPhoto(ParkingRecord r) {
        if (!r.hasPhoto()) {
            binding.imgPhoto.setVisibility(View.GONE);
            return;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2;
        Bitmap bmp = BitmapFactory.decodeFile(r.photoPath, opts);
        if (bmp != null) {
            binding.imgPhoto.setImageBitmap(bmp);
            binding.imgPhoto.setVisibility(View.VISIBLE);
            binding.imgPhoto.setOnClickListener(v -> PhotoViewActivity.open(requireContext(), r.photoPath));
        } else {
            binding.imgPhoto.setVisibility(View.GONE);
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.parking_clear_confirm)
                .setPositiveButton(R.string.settings_confirm, (d, w) -> clearCurrent())
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    /** §9.7 현재 주차 정보 초기화: is_current 해제, 상태 UNKNOWN. 이력은 유지. */
    private void clearCurrent() {
        parkingRepo.clearCurrent();
        ParkingState st = stateRepo.get();
        st.parkingStatus = ParkingStatus.UNKNOWN;
        st.currentParkingRecordId = ParkingState.NO_RECORD;
        st.lastStateChangedAt = TimeUtil.now();
        stateRepo.update(st);
        logRepo.append(EventType.CURRENT_PARKING_CLEARED, null, ParkingStatus.UNKNOWN.name(), false, null);
        NotificationHelper.cancelParkingNotifications(requireContext()); // §14.2 초기화 시 제거
        DetectionService.refresh(requireContext()); // FGS 알림 갱신(감지중으로)
        render();
    }

    private void openInput(long recordId) {
        Intent intent = new Intent(requireContext(), ParkingInputActivity.class);
        if (recordId != ParkingRecord.NO_ID) {
            intent.putExtra(ParkingInputActivity.EXTRA_RECORD_ID, recordId);
        }
        startActivity(intent);
    }

    private void openAutoCapture() {
        startActivity(new Intent(requireContext(), AutoCaptureActivity.class));
    }

    // 같은 주차 세션(상태 변경 시각 기준)에 대해 자동 촬영을 한 번만 띄우기 위한 가드.
    @Nullable
    private static String lastAutoCaptureToken;

    /**
     * 주차 미입력 상태가 화면에 나타나면 해당 세션 1회 자동으로 촬영 화면을 띄운다.
     * 사용자가 촬영을 취소해도 같은 세션에서는 다시 자동으로 뜨지 않는다(수동 버튼으로 재시도).
     */
    private void maybeAutoLaunchCapture(ParkingState state) {
        String token = state.lastStateChangedAt != null ? state.lastStateChangedAt : "";
        if (token.equals(lastAutoCaptureToken)) {
            return;
        }
        lastAutoCaptureToken = token;
        openAutoCapture();
    }

    private void hideAllButtons() {
        binding.btnManualSave.setVisibility(View.GONE);
        binding.btnInput.setVisibility(View.GONE);
        binding.btnEdit.setVisibility(View.GONE);
        binding.btnMap.setVisibility(View.GONE);
        binding.btnClear.setVisibility(View.GONE);
    }

    private void show(View v) {
        v.setVisibility(View.VISIBLE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
