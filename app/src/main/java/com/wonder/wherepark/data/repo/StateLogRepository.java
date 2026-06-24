package com.wonder.wherepark.data.repo;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.wonder.wherepark.data.DbContract.Logs;
import com.wonder.wherepark.data.DbHelper;
import com.wonder.wherepark.data.model.StateLog;
import com.wonder.wherepark.util.TimeUtil;

/** state_logs 쓰기. §19.4 보관 정책(최근 1,000개)을 삽입 시마다 보장한다. */
public class StateLogRepository {

    private final DbHelper db;

    public StateLogRepository(@NonNull Context context) {
        this.db = DbHelper.getInstance(context);
    }

    /** 로그 한 건을 추가하고, 1,000개 초과 시 오래된 것부터 삭제한다. */
    public long append(@NonNull StateLog log) {
        SQLiteDatabase d = db.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put(Logs.COL_EVENT_TYPE, log.eventType);
        v.put(Logs.COL_PREVIOUS_STATE, log.previousState);
        v.put(Logs.COL_NEW_STATE, log.newState);
        v.put(Logs.COL_EVENT_TIME, log.eventTime != null ? log.eventTime : TimeUtil.now());
        v.put(Logs.COL_IS_STABILIZED, log.isStabilized ? 1 : 0);
        if (log.stabilizationSeconds != null) {
            v.put(Logs.COL_STABILIZATION_SECONDS, log.stabilizationSeconds);
        } else {
            v.putNull(Logs.COL_STABILIZATION_SECONDS);
        }
        v.put(Logs.COL_MEMO, log.memo);

        long id = d.insert(Logs.TABLE, null, v);
        log.id = id;
        trim(d);
        return id;
    }

    /** 편의 메서드: 이벤트 유형/상태 전이만으로 로그를 남긴다. */
    public long append(String eventType, String previousState, String newState,
                       boolean stabilized, Integer stabilizationSeconds) {
        StateLog log = new StateLog();
        log.eventType = eventType;
        log.previousState = previousState;
        log.newState = newState;
        log.isStabilized = stabilized;
        log.stabilizationSeconds = stabilizationSeconds;
        return append(log);
    }

    /** §8.6.4 전체 데이터 초기화 시 로그도 비운다. */
    public void deleteAll() {
        db.getWritableDatabase().delete(Logs.TABLE, null, null);
    }

    /** 1,000개 초과 시 가장 오래된(id 작은) 로그부터 삭제한다. */
    private void trim(SQLiteDatabase d) {
        d.execSQL("DELETE FROM " + Logs.TABLE
                + " WHERE " + Logs.COL_ID + " NOT IN ("
                + "SELECT " + Logs.COL_ID + " FROM " + Logs.TABLE
                + " ORDER BY " + Logs.COL_ID + " DESC LIMIT " + Logs.MAX_ROWS + ")");
    }
}
