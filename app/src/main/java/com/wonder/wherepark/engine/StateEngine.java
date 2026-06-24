package com.wonder.wherepark.engine;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wonder.wherepark.data.model.Enums.EventType;
import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.LocationStatus;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.data.model.ParkingState;
import com.wonder.wherepark.data.repo.ParkingRepository;
import com.wonder.wherepark.data.repo.StateLogRepository;
import com.wonder.wherepark.data.repo.StateRepository;
import com.wonder.wherepark.notify.NotificationHelper;
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
    @Nullable
    private final Listener listener;

    public StateEngine(@NonNull Context context, @Nullable Listener listener) {
        this.context = context.getApplicationContext();
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

        if (st.parkingStatus == ParkingStatus.PARKED) {
            ParkingRecord current = parkingRepo.getCurrent();
            if (current != null) {
                NotificationHelper.showAwayReminder(context, current);
            }
        }
        notifyChanged();
    }

    // ----- GPS (§5.3 보조) -----

    public void confirmGpsNearHome(int stabilizeSeconds) {
        updateLocationStatus(LocationStatus.NEAR_HOME, EventType.GPS_NEAR_HOME, stabilizeSeconds);
    }

    public void confirmGpsOutside(int stabilizeSeconds) {
        updateLocationStatus(LocationStatus.OUTSIDE, EventType.GPS_OUTSIDE, stabilizeSeconds);
    }

    private void updateLocationStatus(LocationStatus status, String eventType, int sec) {
        ParkingState st = stateRepo.get();
        st.locationStatus = status;
        st.lastGpsStatus = status.name();
        stateRepo.update(st);
        logRepo.append(eventType, null, status.name(), true, sec);
        notifyChanged();
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

        NotificationHelper.showInputRequest(context); // §10.1 주차 위치 입력 요청
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onStateChanged(); // 서비스 FGS 알림 갱신
        }
        StateBus.publish(); // 떠 있는 화면(주차 위치 탭 등) 즉시 갱신
    }
}
