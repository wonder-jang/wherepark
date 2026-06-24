package com.wonder.wherepark.data.model;

import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.LocationStatus;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;

/** §19.3 parking_state 단일 row 모델. 현재 주차/집/위치 상태와 마지막 신호값. */
public class ParkingState {

    /** current_parking_record_id 가 없을 때의 값. */
    public static final long NO_RECORD = -1L;

    public long id = com.wonder.wherepark.data.DbContract.State.ROW_ID;
    public ParkingStatus parkingStatus = ParkingStatus.UNKNOWN;
    public HomeStatus homeStatus = HomeStatus.UNKNOWN;
    public LocationStatus locationStatus = LocationStatus.UNKNOWN;
    public long currentParkingRecordId = NO_RECORD;
    public String lastBtStatus;
    public String lastWifiStatus;
    public String lastGpsStatus;
    public String lastStateChangedAt;
    public String updatedAt;

    public boolean hasCurrentRecord() {
        return currentParkingRecordId != NO_RECORD;
    }
}
