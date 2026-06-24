package com.wonder.wherepark.data.repo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.wonder.wherepark.data.DbContract.State;
import com.wonder.wherepark.data.DbHelper;
import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.LocationStatus;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.data.model.ParkingState;
import com.wonder.wherepark.util.TimeUtil;

/** parking_state(단일 row id=1) 읽기/쓰기. */
public class StateRepository {

    private final DbHelper db;

    public StateRepository(@NonNull Context context) {
        this.db = DbHelper.getInstance(context);
    }

    /** 단일 상태 row를 반환한다. 시딩되어 있으므로 항상 존재한다. */
    @NonNull
    public ParkingState get() {
        SQLiteDatabase d = db.getReadableDatabase();
        try (Cursor c = d.query(State.TABLE, null,
                State.COL_ID + "=?", new String[]{String.valueOf(State.ROW_ID)},
                null, null, null)) {
            if (c.moveToFirst()) {
                return map(c);
            }
        }
        return new ParkingState();
    }

    /** 상태 전체를 갱신한다. updated_at은 자동 설정. */
    public void update(@NonNull ParkingState s) {
        ContentValues v = new ContentValues();
        v.put(State.COL_PARKING_STATUS, s.parkingStatus.name());
        v.put(State.COL_HOME_STATUS, s.homeStatus.name());
        v.put(State.COL_LOCATION_STATUS, s.locationStatus.name());
        if (s.hasCurrentRecord()) {
            v.put(State.COL_CURRENT_PARKING_RECORD_ID, s.currentParkingRecordId);
        } else {
            v.putNull(State.COL_CURRENT_PARKING_RECORD_ID);
        }
        v.put(State.COL_LAST_BT_STATUS, s.lastBtStatus);
        v.put(State.COL_LAST_WIFI_STATUS, s.lastWifiStatus);
        v.put(State.COL_LAST_GPS_STATUS, s.lastGpsStatus);
        v.put(State.COL_LAST_STATE_CHANGED_AT, s.lastStateChangedAt);
        v.put(State.COL_UPDATED_AT, TimeUtil.now());

        db.getWritableDatabase().update(State.TABLE, v,
                State.COL_ID + "=?", new String[]{String.valueOf(State.ROW_ID)});
    }

    /** §8.6.4 등에서 상태를 UNKNOWN 초기값으로 되돌린다. */
    public void resetToUnknown() {
        update(new ParkingState());
    }

    private static ParkingState map(Cursor c) {
        ParkingState s = new ParkingState();
        s.id = Cursors.getLong(c, State.COL_ID);
        s.parkingStatus = ParkingStatus.fromDb(Cursors.getString(c, State.COL_PARKING_STATUS));
        s.homeStatus = HomeStatus.fromDb(Cursors.getString(c, State.COL_HOME_STATUS));
        s.locationStatus = LocationStatus.fromDb(Cursors.getString(c, State.COL_LOCATION_STATUS));
        Long recId = Cursors.getNullableLong(c, State.COL_CURRENT_PARKING_RECORD_ID);
        s.currentParkingRecordId = recId != null ? recId : ParkingState.NO_RECORD;
        s.lastBtStatus = Cursors.getString(c, State.COL_LAST_BT_STATUS);
        s.lastWifiStatus = Cursors.getString(c, State.COL_LAST_WIFI_STATUS);
        s.lastGpsStatus = Cursors.getString(c, State.COL_LAST_GPS_STATUS);
        s.lastStateChangedAt = Cursors.getString(c, State.COL_LAST_STATE_CHANGED_AT);
        s.updatedAt = Cursors.getString(c, State.COL_UPDATED_AT);
        return s;
    }
}
