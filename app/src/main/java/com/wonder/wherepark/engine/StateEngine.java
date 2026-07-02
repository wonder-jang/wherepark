package com.wonder.wherepark.engine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.model.Enums.EventType;
import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.LocationStatus;
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
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.service.AutoCaptureLauncher;
import com.wonder.wherepark.util.LocationOnceHelper;
import com.wonder.wherepark.util.PermissionUtil;
import com.wonder.wherepark.util.TimeUtil;

/**
 * §5/§10/§11 상태 전이 엔진. 안정화가 끝난 "확정" 이벤트를 받아 DB 상태와 알림을 갱신한다.
 * BT 우선 원칙(§5.2): BT 연결=즉시 DRIVING. BT 해제 확정/집 Wi-Fi 귀가 확정=PARKED.
 *
 * <p>호출은 메인 스레드(Stabilizer 콜백)에서 이뤄진다.
 */
public class StateEngine {

    /** 상태가 바뀌면 호출 — 서비스가 FGS 알림을 다시 렌더링하는 데 사용. */
    public interface Listener {
        void onStateChanged();
    }

    private final Context context;
    private final StateRepository stateRepo;
    private final ParkingRepository parkingRepo;
    private final StateLogRepository logRepo;
    private final SettingsRepository settingsRepo;
    @Nullable
    private final Listener listener;

    public StateEngine(@NonNull Context context, @Nullable Listener listener) {
        this.context = context.getApplicationContext();
        this.settingsRepo = new SettingsRepository(this.context);
        this.stateRepo = new StateRepository(this.context);
        this.parkingRepo = new ParkingRepository(this.context);
        this.logRepo = new StateLogRepository(this.context);
        this.listener = listener;
    }

    // ----- BT (§5.1-1,2 / §10) -----

    /** 차량 BT 연결: 즉시 DRIVING(§5.2, §10.3). 주차 관련 알림 제거, 이력은 유지. */
    public void confirmDriving() {
        ParkingState st = stateRepo.get();
        String prev = st.parkingStatus.name();
        // §10.3 현재 주차 해제(이력은 유지) → 다음 주차를 새로 감지
        parkingRepo.clearCurrent();
        st.parkingStatus = ParkingStatus.DRIVING;
        st.currentParkingRecordId = ParkingState.NO_RECORD;
        st.lastBtStatus = "CONNECTED";
        st.lastStateChangedAt = TimeUtil.now();
        stateRepo.update(st);
        logRepo.append(EventType.DRIVING_DETECTED, prev, ParkingStatus.DRIVING.name(), false, 0);

        NotificationHelper.cancelParkingNotifications(context); // 입력요청 + Ongoing 제거
        notifyChanged();
    }

    /** 차량 BT 해제 확정: PARKED 후보 → PARKED(§10.1). 위치 미입력 상태로 입력 요청. */
    public void confirmParkedByBt(int stabilizeSeconds) {
        ParkingState st = stateRepo.get();
        st.lastBtStatus = "DISCONNECTED";
        stateRepo.update(st);
        enterParked(EventType.PARKING_DETECTED, stabilizeSeconds);
        // §5.3 집 Wi-Fi로 집(HOME) 확정이 안 된 경우, 마지막 GPS로 집 반경 여부를 1회 보완 판정한다.
        if (st.homeStatus != HomeStatus.HOME) {
            judgeHomeByGpsOnce();
        }
    }

    // ----- 집 Wi-Fi (§11) -----

    /** 집 Wi-Fi 연결 확정: HOME. 주차가 아니면 보완 주차(§11.1). */
    public void confirmHome(int stabilizeSeconds) {
        ParkingState st = stateRepo.get();
        st.homeStatus = HomeStatus.HOME;
        st.lastWifiStatus = "HOME";
        st.lastStateChangedAt = TimeUtil.now();
        stateRepo.update(st);
        logRepo.append(EventType.WIFI_CONNECTED, null, HomeStatus.HOME.name(), true, stabilizeSeconds);

        if (st.parkingStatus != ParkingStatus.PARKED) {
            enterParked(EventType.PARKING_DETECTED, stabilizeSeconds);
        } else {
            notifyChanged();
        }
    }

    /** 집 Wi-Fi 해제 확정: AWAY. PARKED + 현재 주차 존재 시 외출 알림(§11.2, §14.3). */
    public void confirmAway(int stabilizeSeconds) {
        ParkingState st = stateRepo.get();
        st.homeStatus = HomeStatus.AWAY;
        st.lastWifiStatus = "AWAY";
        st.lastStateChangedAt = TimeUtil.now();
        stateRepo.update(st);
        logRepo.append(EventType.WIFI_DISCONNECTED, null, HomeStatus.AWAY.name(), true, stabilizeSeconds);

        // 먼저 알림을 갱신해 서비스를 포그라운드로 복귀시킨 뒤 진동한다(백그라운드 진동 억제 방지).
        notifyChanged();
        if (st.parkingStatus == ParkingStatus.PARKED && parkingRepo.getCurrent() != null) {
            // §14.3 외출 시 진동으로 알린다. 주차 위치는 상시(FGS) 알림이 표시한다.
            vibrateAwayAlert();
        }
    }

    // ----- GPS 집 반경 1회 판정 (§5.3 보조, 상시 폴링 대체) -----

    /**
     * 주차 확정 시점에 1회성으로 위치를 받아 집 반경 이내면 HOME, 아니면 AWAY로 보완한다.
     * 집 Wi-Fi가 연결되지 않은 상황에서도 집 주차를 집으로 인식하기 위한 fallback(§5.3).
     * 상시 GPS 폴링을 하지 않으므로 배터리 부담이 거의 없다(최근 위치 우선, 없을 때만 1회 fix).
     */
    @SuppressLint("MissingPermission")
    private void judgeHomeByGpsOnce() {
        AppSettings s = settingsRepo.get();
        if (!s.hasHomeLocation()
                || PermissionUtil.location(context) != PermissionUtil.Status.GRANTED) {
            return;
        }
        LocationOnceHelper.requestOnce(context, location -> {
            if (location == null) {
                return;
            }
            ParkingState cur = stateRepo.get();
            // 판정이 도착했을 때 여전히 주차 중이고, Wi-Fi로 이미 집 확정이 안 된 경우에만 반영
            if (cur.parkingStatus != ParkingStatus.PARKED || cur.homeStatus == HomeStatus.HOME) {
                return;
            }
            float[] out = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                    s.homeLatitude, s.homeLongitude, out);
            boolean nearHome = out[0] <= s.homeRadiusMeters;
            cur.homeStatus = nearHome ? HomeStatus.HOME : HomeStatus.AWAY;
            cur.locationStatus = nearHome ? LocationStatus.NEAR_HOME : LocationStatus.OUTSIDE;
            cur.lastGpsStatus = cur.locationStatus.name();
            stateRepo.update(cur);
            // 집 반경으로 판정됐고 아직 상세 미입력(자리표시)이면 자동 저장 레코드의 구분도 집으로 맞춘다.
            if (nearHome) {
                ParkingRecord ph = parkingRepo.getCurrent();
                if (ph != null && ph.isGpsPlaceholder()
                        && ph.parkingPlaceType != ParkingPlaceType.HOME) {
                    ph.parkingPlaceType = ParkingPlaceType.HOME;
                    parkingRepo.update(ph);
                }
            }
            logRepo.append(nearHome ? EventType.GPS_NEAR_HOME : EventType.GPS_OUTSIDE,
                    null, cur.homeStatus.name(), true, 0);
            notifyChanged(); // 집이면 재택으로 알림 숨김, 외부면 위치 표시 갱신
        });
    }

    // ----- 공통 -----

    /** PARKED 진입(이미 PARKED면 무시). 현재 주차 미입력 상태 + 입력 요청 알림(§10.1). */
    private void enterParked(String eventType, int stabilizeSeconds) {
        ParkingState st = stateRepo.get();
        if (st.parkingStatus == ParkingStatus.PARKED) {
            return;
        }
        String prev = st.parkingStatus.name();
        st.parkingStatus = ParkingStatus.PARKED;
        st.currentParkingRecordId = ParkingState.NO_RECORD; // 위치 미입력
        st.lastStateChangedAt = TimeUtil.now();
        stateRepo.update(st);
        logRepo.append(eventType, prev, ParkingStatus.PARKED.name(), true, stabilizeSeconds);

        // 사용자가 촬영/수동 입력을 하지 않아도 최소한 주차 시점 GPS는 남도록 자리표시 레코드를 만든다.
        // 이후 사용자가 입력하면 이 레코드를 채워 갱신하므로 중복 이력이 생기지 않는다.
        createGpsPlaceholder(st);

        // §10.1 주차 위치 입력 요청.
        boolean autoCapture = settingsRepo.get().autoPhotoAnalysisEnabled;
        // 자동 사진 분석 + 오버레이 권한이 있으면 촬영 화면을 바로(또는 잠금 해제 시) 자동으로 띄운다.
        AutoCaptureLauncher.Result auto = autoCapture
                ? AutoCaptureLauncher.onParkingDetected(context)
                : AutoCaptureLauncher.Result.NEEDS_NOTIFICATION;
        // 지금 바로 촬영 화면을 띄운 경우(LAUNCHED_NOW)만 알림 생략.
        // 그 외에는 입력요청 알림으로 안내(자동 모드면 풀스크린 인텐트라 잠금화면 위로 촬영 화면이 뜬다).
        if (auto != AutoCaptureLauncher.Result.LAUNCHED_NOW) {
            NotificationHelper.showInputRequest(context, autoCapture);
        }
        notifyChanged();
    }

    /**
     * 주차 확정 시점에 GPS만 담은 "자리표시" 주차 레코드를 현재 주차로 만든다(§16.1 보조).
     * <p>목적: 사용자가 촬영/수동 입력을 하지 않아도 차를 다시 찾을 수 있도록 위치를 자동 저장한다.
     * 상태의 {@code currentParkingRecordId}는 NO_RECORD로 유지해 "아직 상세 미입력"임을 나타내며
     * (자동 촬영 실행/입력 요청 알림 로직은 이 값을 기준으로 동작), 화면·알림은 자리표시 여부를
     * {@link ParkingRecord#isGpsPlaceholder()}로 구분한다. 사용자가 이후 입력하면 이 레코드를 갱신한다.
     * <p>위치 권한이 없으면(어차피 GPS를 못 얻으므로) 자리표시를 만들지 않는다.
     */
    @SuppressLint("MissingPermission")
    private void createGpsPlaceholder(ParkingState st) {
        if (PermissionUtil.location(context) != PermissionUtil.Status.GRANTED) {
            return;
        }
        if (parkingRepo.getCurrent() != null) {
            return; // 이미 현재 주차 레코드가 있으면 만들지 않는다(중복 방지).
        }
        ParkingRecord rec = new ParkingRecord();
        rec.saveType = SaveType.AUTO;
        rec.parkingPlaceType = st.homeStatus == HomeStatus.HOME
                ? ParkingPlaceType.HOME : ParkingPlaceType.OUTSIDE;
        rec.parkingLevelType = ParkingLevelType.ETC;
        rec.parkedAt = TimeUtil.now();
        Location last = LocationOnceHelper.lastKnown(context);
        if (last != null) {
            rec.latitude = last.getLatitude();
            rec.longitude = last.getLongitude();
            rec.hasGps = true;
        }
        final long id = parkingRepo.insertAsCurrent(rec);

        // 더 신선한 위치가 잡히면 자리표시가 아직 유효할 때에 한해 GPS를 보정한다.
        LocationOnceHelper.requestOnce(context, location -> {
            if (location == null) {
                return;
            }
            ParkingRecord cur = parkingRepo.getById(id);
            if (cur == null || !cur.isCurrent || !cur.isGpsPlaceholder()) {
                return; // 그 사이 사용자가 입력해 채워졌거나 해제됐으면 건드리지 않는다.
            }
            cur.latitude = location.getLatitude();
            cur.longitude = location.getLongitude();
            cur.hasGps = true;
            parkingRepo.update(cur);
            notifyChanged();
        });
        notifyChanged();
    }

    /** §14.3 외출 알림 진동(상시 알림은 LOW 채널이라 소리/진동이 없으므로 직접 진동). */
    private void vibrateAwayAlert() {
        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{0, 400, 200, 400}, -1);
        // 알림용 usage를 명시해야 화면 꺼짐/백그라운드에서도 진동이 무시되지 않는다.
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        vibrator.vibrate(effect, attrs);
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onStateChanged(); // 서비스 FGS 알림 갱신
        }
        StateBus.publish(); // 떠 있는 화면(주차 위치 탭 등) 즉시 갱신
    }
}
