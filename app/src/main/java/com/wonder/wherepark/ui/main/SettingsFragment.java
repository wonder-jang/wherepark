package com.wonder.wherepark.ui.main;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.wonder.wherepark.R;
import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.repo.ParkingRepository;
import com.wonder.wherepark.data.repo.SettingsRepository;
import com.wonder.wherepark.data.repo.StateLogRepository;
import com.wonder.wherepark.data.repo.StateRepository;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.photo.PhotoStore;
import com.wonder.wherepark.service.DetectionService;
import com.wonder.wherepark.util.LocationOnceHelper;
import com.wonder.wherepark.util.PermissionUtil;
import com.wonder.wherepark.util.PermissionUtil.Status;
import com.wonder.wherepark.util.WifiHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * §8 설정 탭. 필수 설정(차량 BT) / 집 설정 / 감지 안정화 / 권한 상태 /
 * 백그라운드 안내 / 데이터 관리 6개 섹션을 제공한다.
 */
public class SettingsFragment extends Fragment {

    // §6.3 안정화 선택 가능값
    private static final int[] STAB_BT = {0, 10, 30, 60, 120};
    private static final int[] STAB_WIFI = {0, 5, 10, 30, 60};
    private static final int[] STAB_GPS = {0, 10, 30, 60, 120};
    // §8.2.3 집 위치 반경 선택값
    private static final int[] RADIUS = {50, 100, 200, 300};

    private static final int STATUS_GRANTED_COLOR = Color.parseColor("#2E7D32");
    private static final int STATUS_NEEDED_COLOR = Color.parseColor("#C62828");
    private static final int STATUS_CHECK_COLOR = Color.parseColor("#E65100");

    private SettingsRepository settingsRepo;
    private ParkingRepository parkingRepo;
    private StateRepository stateRepo;
    private StateLogRepository logRepo;
    private PhotoStore photoStore;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<String> backgroundLocationLauncher;
    @Nullable
    private Runnable pendingAfterPermission;

    private View root;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    refreshDisplay();
                    Runnable a = pendingAfterPermission;
                    pendingAfterPermission = null;
                    if (a != null) a.run();
                });
        backgroundLocationLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> refreshDisplay());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        root = view;
        Context ctx = requireContext();
        settingsRepo = new SettingsRepository(ctx);
        parkingRepo = new ParkingRepository(ctx);
        stateRepo = new StateRepository(ctx);
        logRepo = new StateLogRepository(ctx);
        photoStore = new PhotoStore(ctx);

        // 필수 설정
        view.findViewById(R.id.row_vehicle_bt).setOnClickListener(v -> onVehicleBtClicked());

        // 집 설정
        view.findViewById(R.id.btn_save_wifi).setOnClickListener(v -> onSaveWifiClicked());
        view.findViewById(R.id.btn_save_location).setOnClickListener(v -> onSaveLocationClicked());
        view.findViewById(R.id.row_home_radius).setOnClickListener(v -> onRadiusClicked());
        view.findViewById(R.id.row_floor_under).setOnClickListener(v -> onFloorClicked(true));
        view.findViewById(R.id.row_floor_ground).setOnClickListener(v -> onFloorClicked(false));

        // 안정화
        view.findViewById(R.id.row_stab_bt).setOnClickListener(v ->
                onStabClicked(R.string.settings_stab_bt, STAB_BT, Field.BT));
        view.findViewById(R.id.row_stab_wifi_con).setOnClickListener(v ->
                onStabClicked(R.string.settings_stab_wifi_connect, STAB_WIFI, Field.WIFI_CON));
        view.findViewById(R.id.row_stab_wifi_dis).setOnClickListener(v ->
                onStabClicked(R.string.settings_stab_wifi_disconnect, STAB_WIFI, Field.WIFI_DIS));
        view.findViewById(R.id.row_stab_gps_enter).setOnClickListener(v ->
                onStabClicked(R.string.settings_stab_gps_enter, STAB_GPS, Field.GPS_ENTER));
        view.findViewById(R.id.row_stab_gps_exit).setOnClickListener(v ->
                onStabClicked(R.string.settings_stab_gps_exit, STAB_GPS, Field.GPS_EXIT));

        // 권한 상태
        view.findViewById(R.id.btn_request_perms).setOnClickListener(v -> requestAllPermissions());
        view.findViewById(R.id.btn_app_settings).setOnClickListener(v -> openAppSettings());

        // 배터리
        view.findViewById(R.id.btn_open_battery).setOnClickListener(v -> openBatterySettings());

        // 데이터 관리
        view.findViewById(R.id.btn_delete_records).setOnClickListener(v ->
                confirm(R.string.settings_delete_records, this::deleteAllRecords));
        view.findViewById(R.id.btn_delete_photos).setOnClickListener(v ->
                confirm(R.string.settings_delete_photos, this::deleteAllPhotos));
        view.findViewById(R.id.btn_reset_settings).setOnClickListener(v ->
                confirm(R.string.settings_reset_settings, this::resetSettings));
        view.findViewById(R.id.btn_reset_all).setOnClickListener(v ->
                confirm(R.string.settings_reset_all, this::resetAll));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshDisplay(); // §8.4 진입 시 최신 권한/설정 상태로 갱신
    }

    // ----- 표시 갱신 -----

    private void refreshDisplay() {
        if (root == null) return;
        AppSettings s = settingsRepo.get();
        String notSet = getString(R.string.settings_not_set);

        text(R.id.val_vehicle_bt, s.vehicleBtName != null ? s.vehicleBtName : notSet);
        text(R.id.val_home_wifi, s.homeWifiSsid != null ? s.homeWifiSsid : notSet);
        text(R.id.val_home_location, s.hasHomeLocation()
                ? String.format("%.5f, %.5f", s.homeLatitude, s.homeLongitude) : notSet);
        text(R.id.val_home_radius, s.homeRadiusMeters + "m");
        text(R.id.val_floor_ground, floorCountLabel(s.homeGroundFloorCount, false));
        text(R.id.val_floor_under, floorCountLabel(s.homeUndergroundFloorCount, true));

        text(R.id.val_stab_bt, s.btDisconnectStabilizeSeconds + "초");
        text(R.id.val_stab_wifi_con, s.wifiConnectStabilizeSeconds + "초");
        text(R.id.val_stab_wifi_dis, s.wifiDisconnectStabilizeSeconds + "초");
        text(R.id.val_stab_gps_enter, s.gpsEnterStabilizeSeconds + "초");
        text(R.id.val_stab_gps_exit, s.gpsExitStabilizeSeconds + "초");

        Context ctx = requireContext();
        status(R.id.val_perm_bt, PermissionUtil.bluetooth(ctx));
        status(R.id.val_perm_loc, PermissionUtil.location(ctx));
        status(R.id.val_perm_bgloc, PermissionUtil.backgroundLocation(ctx));
        status(R.id.val_perm_noti, PermissionUtil.notification(ctx));
        status(R.id.val_perm_wifi, PermissionUtil.wifiAccess(ctx));
        status(R.id.val_perm_battery, PermissionUtil.batteryOptimization(ctx));
    }

    private void status(int viewId, Status st) {
        TextView tv = root.findViewById(viewId);
        switch (st) {
            case GRANTED:
                tv.setText(R.string.settings_status_granted);
                tv.setTextColor(STATUS_GRANTED_COLOR);
                break;
            case CHECK_NEEDED:
                tv.setText(R.string.settings_status_check);
                tv.setTextColor(STATUS_CHECK_COLOR);
                break;
            default:
                tv.setText(R.string.settings_status_needed);
                tv.setTextColor(STATUS_NEEDED_COLOR);
                break;
        }
    }

    private void text(int viewId, String value) {
        ((TextView) root.findViewById(viewId)).setText(value);
    }

    // ----- 필수 설정: 차량 BT (§8.1.1) -----

    private void onVehicleBtClicked() {
        runWithPermission(btPermissionArray(), this::showPairedDevicesDialog);
    }

    private String[] btPermissionArray() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[0];
    }

    @SuppressLint("MissingPermission")
    private void showPairedDevicesDialog() {
        if (PermissionUtil.bluetooth(requireContext()) != Status.GRANTED) {
            toast(R.string.settings_bt_perm_needed);
            return;
        }
        BluetoothManager bm = (BluetoothManager) requireContext()
                .getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            toast(R.string.settings_bt_empty);
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    devices.add(d);
                    String name = d.getName();
                    names.add(name != null ? name : d.getAddress());
                }
            }
        } catch (SecurityException e) {
            toast(R.string.settings_bt_perm_needed);
            return;
        }
        if (devices.isEmpty()) {
            toast(R.string.settings_bt_empty);
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_vehicle_bt)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    BluetoothDevice picked = devices.get(which);
                    AppSettings s = settingsRepo.get();
                    s.vehicleBtName = names.get(which);
                    s.vehicleBtAddress = picked.getAddress();
                    settingsRepo.update(s);
                    refreshDisplay();
                    DetectionService.start(requireContext()); // 차량 BT 설정됨 → 감지 시작
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    // ----- 집 설정 (§8.2) -----

    private void onSaveWifiClicked() {
        String ssid = WifiHelper.getCurrentSsid(requireContext());
        if (ssid == null) {
            toast(R.string.settings_wifi_fail);
            return;
        }
        AppSettings s = settingsRepo.get();
        s.homeWifiSsid = ssid;
        settingsRepo.update(s);
        refreshDisplay();
        toast(R.string.settings_done);
    }

    private void onSaveLocationClicked() {
        runWithPermission(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, this::saveCurrentLocation);
    }

    @SuppressLint("MissingPermission")
    private void saveCurrentLocation() {
        if (PermissionUtil.location(requireContext()) != Status.GRANTED) {
            toast(R.string.settings_location_fail);
            return;
        }
        toast(R.string.settings_save_current_location);
        LocationOnceHelper.requestOnce(requireContext(), location -> {
            if (!isAdded()) return;
            if (location == null) {
                toast(R.string.settings_location_fail);
                return;
            }
            saveHomeLocation(location);
        });
    }

    private void saveHomeLocation(Location location) {
        AppSettings s = settingsRepo.get();
        s.homeLatitude = location.getLatitude();
        s.homeLongitude = location.getLongitude();
        settingsRepo.update(s);
        refreshDisplay();
        toast(R.string.settings_location_saved);
    }

    private void onRadiusClicked() {
        AppSettings s = settingsRepo.get();
        int checked = indexOf(RADIUS, s.homeRadiusMeters);
        String[] labels = new String[RADIUS.length];
        for (int i = 0; i < RADIUS.length; i++) labels[i] = RADIUS[i] + "m";
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_home_radius)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AppSettings cur = settingsRepo.get();
                    cur.homeRadiusMeters = RADIUS[which];
                    settingsRepo.update(cur);
                    refreshDisplay();
                    d.dismiss();
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    /**
     * 층수 선택. 0=없음 ~ 100층, 입력 화면처럼 라벨(지상 1F~100F / 지하 B1F~B100F) 표시.
     * 지상/지하를 모두 '없음'으로 두는 것은 허용하지 않는다.
     */
    private void onFloorClicked(boolean underground) {
        AppSettings s = settingsRepo.get();
        int current = underground ? s.homeUndergroundFloorCount : s.homeGroundFloorCount;

        String[] labels = new String[101]; // 0..100
        labels[0] = getString(R.string.settings_floor_none);
        for (int i = 1; i <= 100; i++) {
            labels[i] = underground ? ("B" + i + "F") : (i + "F");
        }
        NumberPicker picker = new NumberPicker(requireContext());
        picker.setMinValue(0);
        picker.setMaxValue(100);
        picker.setDisplayedValues(labels);
        picker.setWrapSelectorWheel(false);
        picker.setValue(Math.max(0, Math.min(100, current)));

        new AlertDialog.Builder(requireContext())
                .setTitle(underground ? R.string.settings_floor_under : R.string.settings_floor_ground)
                .setView(wrapCentered(picker))
                .setPositiveButton(R.string.settings_confirm, (d, w) -> {
                    int val = picker.getValue();
                    AppSettings cur = settingsRepo.get();
                    int other = underground ? cur.homeGroundFloorCount : cur.homeUndergroundFloorCount;
                    if (val == 0 && other == 0) { // 둘 다 없음 불가
                        toast(R.string.settings_floor_both_none);
                        return;
                    }
                    if (underground) {
                        cur.homeUndergroundFloorCount = val;
                    } else {
                        cur.homeGroundFloorCount = val;
                    }
                    settingsRepo.update(cur);
                    refreshDisplay();
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    /** 층수 값을 표시 라벨로. 0→없음, n→"B1F ~ B{n}F"(지하) / "1F ~ {n}F"(지상). */
    private String floorCountLabel(int count, boolean underground) {
        if (count <= 0) {
            return getString(R.string.settings_floor_none);
        }
        String top = underground ? ("B" + count + "F") : (count + "F");
        if (count == 1) {
            return top;
        }
        String bottom = underground ? "B1F" : "1F";
        return bottom + " ~ " + top;
    }

    // ----- 감지 안정화 (§8.3) -----

    private enum Field {BT, WIFI_CON, WIFI_DIS, GPS_ENTER, GPS_EXIT}

    private void onStabClicked(int titleRes, int[] values, Field field) {
        AppSettings s = settingsRepo.get();
        int current = currentStab(s, field);
        int checked = indexOf(values, current);
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = values[i] + "초";
        new AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    AppSettings cur = settingsRepo.get();
                    applyStab(cur, field, values[which]);
                    settingsRepo.update(cur);
                    refreshDisplay();
                    d.dismiss();
                })
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    private int currentStab(AppSettings s, Field f) {
        switch (f) {
            case BT: return s.btDisconnectStabilizeSeconds;
            case WIFI_CON: return s.wifiConnectStabilizeSeconds;
            case WIFI_DIS: return s.wifiDisconnectStabilizeSeconds;
            case GPS_ENTER: return s.gpsEnterStabilizeSeconds;
            default: return s.gpsExitStabilizeSeconds;
        }
    }

    private void applyStab(AppSettings s, Field f, int value) {
        switch (f) {
            case BT: s.btDisconnectStabilizeSeconds = value; break;
            case WIFI_CON: s.wifiConnectStabilizeSeconds = value; break;
            case WIFI_DIS: s.wifiDisconnectStabilizeSeconds = value; break;
            case GPS_ENTER: s.gpsEnterStabilizeSeconds = value; break;
            default: s.gpsExitStabilizeSeconds = value; break;
        }
    }

    // ----- 권한 (§8.4) -----

    private void requestAllPermissions() {
        String[] perms = PermissionUtil.foregroundRequestList(requireContext());
        if (perms.length > 0) {
            pendingAfterPermission = this::requestBackgroundLocationIfPossible;
            permissionLauncher.launch(perms);
        } else {
            requestBackgroundLocationIfPossible();
        }
    }

    private void requestBackgroundLocationIfPossible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && PermissionUtil.location(requireContext()) == Status.GRANTED
                && PermissionUtil.backgroundLocation(requireContext()) != Status.GRANTED) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().getPackageName(), null));
        startActivity(intent);
    }

    // ----- 배터리 최적화 (§8.5) -----

    @SuppressLint("BatteryLife")
    private void openBatterySettings() {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + requireContext().getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ----- 데이터 관리 (§8.6) -----

    private void deleteAllRecords() {
        parkingRepo.deleteAll();
        // §8.6.1 현재 주차 정보도 없음으로.
        com.wonder.wherepark.data.model.ParkingState st = stateRepo.get();
        st.currentParkingRecordId = com.wonder.wherepark.data.model.ParkingState.NO_RECORD;
        stateRepo.update(st);
        NotificationHelper.cancelParkingNotifications(requireContext()); // §8.6.1 Ongoing 제거
        DetectionService.refresh(requireContext()); // FGS 알림 갱신(BT 설정은 유지)
        done();
    }

    private void deleteAllPhotos() {
        photoStore.deleteAll();
        parkingRepo.clearAllPhotoPaths(); // §8.6.2 DB 사진 경로 비움
        done();
    }

    private void resetSettings() {
        // §8.6.3 기본값 복원. 첫 실행 완료 여부는 유지(온보딩 재노출 방지).
        AppSettings def = new AppSettings();
        def.isOnboardingCompleted = true;
        settingsRepo.update(def);
        DetectionService.stop(requireContext()); // 차량 BT 미설정 → 감지 중지
        done();
    }

    private void resetAll() {
        // §8.6.4 이력/사진/설정/현재상태 모두 초기화.
        parkingRepo.deleteAll();
        photoStore.deleteAll();
        AppSettings def = new AppSettings();
        def.isOnboardingCompleted = true;
        settingsRepo.update(def);
        stateRepo.resetToUnknown();
        logRepo.deleteAll();
        NotificationHelper.cancelParkingNotifications(requireContext()); // §8.6.4 Ongoing 제거
        DetectionService.stop(requireContext()); // 차량 BT 미설정 → 감지 중지
        done();
    }

    private void done() {
        refreshDisplay();
        toast(R.string.settings_done);
    }

    // ----- 공통 헬퍼 -----

    private void confirm(int titleRes, Runnable action) {
        new AlertDialog.Builder(requireContext())
                .setTitle(titleRes)
                .setMessage(R.string.settings_irreversible)
                .setPositiveButton(R.string.settings_confirm, (d, w) -> action.run())
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    private void runWithPermission(String[] perms, Runnable action) {
        boolean allGranted = true;
        for (String p : perms) {
            if (requireContext().checkSelfPermission(p)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            action.run();
        } else {
            pendingAfterPermission = action;
            permissionLauncher.launch(perms);
        }
    }

    private static int indexOf(int[] arr, int value) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == value) return i;
        }
        return -1;
    }

    /** NumberPicker를 좌우 여백을 준 컨테이너에 담아 다이얼로그 중앙 정렬로 보이게 한다. */
    private View wrapCentered(View child) {
        android.widget.FrameLayout fl = new android.widget.FrameLayout(requireContext());
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.CENTER;
        lp.topMargin = 24;
        lp.bottomMargin = 24;
        fl.addView(child, lp);
        return fl;
    }

    private void toast(int res) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
    }
}
