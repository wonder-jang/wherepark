package com.wonder.wherepark.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.wonder.wherepark.R;
import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.ParkingPlaceType;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.data.model.ParkingState;
import com.wonder.wherepark.data.repo.ParkingRepository;
import com.wonder.wherepark.data.repo.SettingsRepository;
import com.wonder.wherepark.data.repo.StateRepository;
import com.wonder.wherepark.engine.StateEngine;
import com.wonder.wherepark.engine.Stabilizer;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.util.ParkingFormat;
import com.wonder.wherepark.util.PermissionUtil;
import com.wonder.wherepark.util.WifiHelper;

/**
 * §15 백그라운드 자동 감지 포그라운드 서비스.
 * 차량 BT 연결/해제(ACL), 집 Wi-Fi 연결/해제(NetworkCallback), 집 반경 진입/이탈(위치)을 감지해
 * {@link Stabilizer} 안정화 후 {@link StateEngine}으로 상태를 확정한다.
 * 외부 주차 중에는 이 서비스의 FGS 알림이 §14.2 상시 알림 역할을 한다.
 */
public class DetectionService extends Service implements StateEngine.Listener {

    private static final String ACTION_REFRESH = "com.wonder.wherepark.action.REFRESH";

    private SettingsRepository settingsRepo;
    private StateRepository stateRepo;
    private ParkingRepository parkingRepo;
    private Stabilizer stabilizer;
    private StateEngine engine;

    private ConnectivityManager connectivityManager;
    @Nullable
    private ConnectivityManager.NetworkCallback wifiCallback;
    private boolean started = false;

    // 전이 판단용 baseline (null = 아직 관측 전, 시드만 하고 전이 없음)
    @Nullable
    private volatile Boolean lastWifiHome = null;

    // ----- 외부 진입점 -----

    /** 차량 BT가 설정돼 있으면 감지 서비스를 시작한다. */
    public static void start(@NonNull Context context) {
        if (!new SettingsRepository(context).get().hasVehicleBt()) {
            return; // 자동 감지는 차량 BT 필수(§8.1)
        }
        Intent i = new Intent(context, DetectionService.class);
        ContextCompat.startForegroundService(context, i);
    }

    /** 상태/설정 변경 후 FGS 알림을 갱신한다(서비스가 떠 있을 때만 의미 있음). */
    public static void refresh(@NonNull Context context) {
        if (!new SettingsRepository(context).get().hasVehicleBt()) {
            return;
        }
        Intent i = new Intent(context, DetectionService.class).setAction(ACTION_REFRESH);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, DetectionService.class));
    }

    // ----- 생명주기 -----

    @Override
    public void onCreate() {
        super.onCreate();
        settingsRepo = new SettingsRepository(this);
        stateRepo = new StateRepository(this);
        parkingRepo = new ParkingRepository(this);
        stabilizer = new Stabilizer();
        engine = new StateEngine(this, this);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        int type = foregroundType();
        if (type == 0) {
            // 위치/BT 권한이 모두 없으면 유효한 FGS 타입을 줄 수 없어 종료
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            ServiceCompat.startForeground(this, NotificationHelper.FGS_ID, buildNotification(), type);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!started) {
            started = true;
            registerBtReceiver();
            registerWifiCallback();
        }
        // 재택 중 주차면 방금 띄운 알림을 즉시 내려 상시 알림을 등록하지 않는다.
        applyForegroundState();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (btReceiver != null) {
            try {
                unregisterReceiver(btReceiver);
            } catch (Exception ignored) {
            }
        }
        if (wifiCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(wifiCallback);
            } catch (Exception ignored) {
            }
        }
        if (stabilizer != null) {
            stabilizer.cancelAll();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ----- FGS 알림 렌더링 -----

    @Override
    public void onStateChanged() {
        applyForegroundState();
    }

    /**
     * 현재 상태에 맞춰 FGS 상시 알림 표시 여부를 적용한다.
     * 다음 경우에는 상시 알림을 아예 등록하지 않는다(서비스는 계속 동작하므로 감지/진동은 유지):
     *  - 위치 미입력 입력 대기 중(입력 요청 헤드업 알림이 단독으로 안내하므로 상시 알림과 겹치지 않게 함)
     *  - §14.2 재택(집 Wi-Fi 연결) 중 주차 상태
     *  - 운행 중(DRIVING)
     * 그 외 상태에서는 알림을 갱신/표시한다.
     */
    private void applyForegroundState() {
        ParkingState st = stateRepo.get();
        ParkingRecord current = parkingRepo.getCurrent();
        // 주차 확정 후 위치 미입력 대기 구간: 입력 요청 알림만 띄우고 상시 알림은 숨긴다(중복 방지).
        boolean hideForInputWaiting = st.parkingStatus == ParkingStatus.PARKED && current == null;
        // 외부(OUTSIDE) 주차로 저장된 현재 기록이 있으면, 집 Wi-Fi 연결 중이어도 상시 알림을 표시한다.
        boolean outsideParked = current != null && current.parkingPlaceType == ParkingPlaceType.OUTSIDE;
        boolean hideForHomeParked = st.parkingStatus == ParkingStatus.PARKED
                && st.homeStatus == HomeStatus.HOME
                && !outsideParked;
        boolean hideForDriving = st.parkingStatus == ParkingStatus.DRIVING;
        if (hideForInputWaiting || hideForHomeParked || hideForDriving) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            return;
        }
        int type = foregroundType();
        if (type == 0) {
            return;
        }
        try {
            ServiceCompat.startForeground(this, NotificationHelper.FGS_ID, buildNotification(), type);
        } catch (Exception ignored) {
            // startForeground 제약 등으로 실패해도 서비스 자체는 유지
        }
    }

    private Notification buildNotification() {
        ParkingState st = stateRepo.get();
        String title = getString(R.string.app_name);
        String text;
        String photoPath = null;
        Integer bgColor = null;
        Integer textColor = null;
        if (st.parkingStatus == ParkingStatus.DRIVING) {
            text = getString(R.string.noti_fgs_driving);
        } else if (st.parkingStatus == ParkingStatus.PARKED) {
            ParkingRecord current = parkingRepo.getCurrent();
            if (current != null) {
                // 위치가 저장된 주차(외부 주차/외출 등) → 주차 위치를 상시 알림으로 표시
                title = getString(R.string.noti_away_title); // "주차 위치"
                text = ParkingFormat.summary(current);
                photoPath = current.photoPath; // 사진/그림을 알림에 함께 표시
                bgColor = current.bgColorRgb;  // 분석된 색 견본 표시용
                textColor = current.textColorRgb;
            } else if (st.homeStatus == HomeStatus.HOME) {
                // §14.2 재택(집 Wi-Fi 연결) 중 위치 미입력 → 상시 알림 미표시(사실상 hide됨)
                text = getString(R.string.noti_fgs_at_home);
            } else {
                text = getString(R.string.noti_fgs_parked_waiting);
            }
        } else {
            text = getString(R.string.noti_fgs_detecting);
        }
        return NotificationHelper.buildForeground(this, title, text, photoPath, bgColor, textColor);
    }

    private int foregroundType() {
        int type = 0;
        if (PermissionUtil.location(this) == PermissionUtil.Status.GRANTED) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        if (PermissionUtil.bluetooth(this) == PermissionUtil.Status.GRANTED) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }
        return type;
    }

    // ----- 차량 BT (ACL) -----

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            AppSettings s = settingsRepo.get();
            if (!matchesVehicle(device, s)) {
                return;
            }
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                stabilizer.cancel(Stabilizer.CH_BT);      // §6.4-3 후보 취소
                engine.confirmDriving();                   // §5.2 즉시 운행
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                int delay = s.btDisconnectStabilizeSeconds;
                stabilizer.schedule(Stabilizer.CH_BT, delay,
                        () -> engine.confirmParkedByBt(delay)); // §10.1
            }
        }
    };

    private void registerBtReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        // ACL은 시스템 브로드캐스트 → EXPORTED로 등록
        ContextCompat.registerReceiver(this, btReceiver, f, ContextCompat.RECEIVER_EXPORTED);
    }

    @SuppressLint("MissingPermission")
    private boolean matchesVehicle(BluetoothDevice device, AppSettings s) {
        if (!s.hasVehicleBt()) {
            return false;
        }
        try {
            if (s.vehicleBtAddress != null && s.vehicleBtAddress.equals(device.getAddress())) {
                return true;
            }
            // 식별값 사용이 어려우면 이름 기준 fallback (§8.1.1)
            return s.vehicleBtName != null && s.vehicleBtName.equals(device.getName());
        } catch (SecurityException e) {
            return false;
        }
    }

    // ----- 집 Wi-Fi -----

    private void registerWifiCallback() {
        if (connectivityManager == null) {
            return;
        }
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                evaluateWifi();
            }

            @Override
            public void onLost(@NonNull Network network) {
                handleHomeWifi(false);
            }
        };
        try {
            connectivityManager.registerNetworkCallback(req, wifiCallback);
        } catch (Exception ignored) {
        }
    }

    private void evaluateWifi() {
        AppSettings s = settingsRepo.get();
        if (!s.hasHomeWifi()) {
            return; // 집 Wi-Fi 미설정 → Wi-Fi 판단 안 함
        }
        String ssid = WifiHelper.getCurrentSsid(this);
        handleHomeWifi(s.matchesHomeWifi(ssid));
    }

    private void handleHomeWifi(boolean homeNow) {
        AppSettings s = settingsRepo.get();
        if (!s.hasHomeWifi()) {
            return;
        }
        if (lastWifiHome != null && lastWifiHome == homeNow) {
            return; // 변화 없음
        }
        boolean firstObservation = (lastWifiHome == null);
        lastWifiHome = homeNow;
        if (firstObservation) {
            return; // 초기 시드: 전이 트리거하지 않음
        }
        if (homeNow) {
            int delay = s.wifiConnectStabilizeSeconds;
            stabilizer.schedule(Stabilizer.CH_WIFI, delay, () -> engine.confirmHome(delay)); // §11.1
        } else {
            int delay = s.wifiDisconnectStabilizeSeconds;
            stabilizer.schedule(Stabilizer.CH_WIFI, delay, () -> engine.confirmAway(delay)); // §11.2
        }
    }

    // 집 반경(GPS) 판정은 상시 폴링하지 않고, 주차 확정 시점에 1회성으로 처리한다(StateEngine).
}
