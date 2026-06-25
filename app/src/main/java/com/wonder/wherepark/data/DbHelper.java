package com.wonder.wherepark.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.model.Enums.HomeStatus;
import com.wonder.wherepark.data.model.Enums.LocationStatus;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.util.TimeUtil;

/**
 * 순수 SQLite(SQLiteOpenHelper) 기반 DB 헬퍼. 요구사항 §19의 4개 테이블을 생성하고,
 * 첫 설치 시 app_settings / parking_state 단일 row를 기본값으로 시딩한다.
 *
 * <p>앱 전체에서 하나의 인스턴스를 공유하도록 {@link #getInstance(Context)} 싱글턴을 사용한다.
 */
public class DbHelper extends SQLiteOpenHelper {

    private static volatile DbHelper instance;

    public static DbHelper getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (DbHelper.class) {
                if (instance == null) {
                    // ApplicationContext로 보관해 Activity 누수를 방지한다.
                    instance = new DbHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private DbHelper(Context context) {
        super(context, DbContract.DB_NAME, null, DbContract.DB_VERSION);
    }

    @Override
    public void onConfigure(@NonNull SQLiteDatabase db) {
        super.onConfigure(db);
        // 외래키 제약은 사용하지 않지만(파괴적 삭제 정책), 안정성을 위해 WAL을 켠다.
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        db.execSQL(CREATE_SETTINGS);
        db.execSQL(CREATE_RECORDS);
        db.execSQL(CREATE_STATE);
        db.execSQL(CREATE_LOGS);
        db.execSQL(CREATE_RECORDS_CURRENT_INDEX);
        db.execSQL(CREATE_LOGS_TIME_INDEX);

        seedSettings(db);
        seedState(db);
    }

    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        // MVP(version 1)에서는 마이그레이션 경로가 없다. 향후 버전에서 ALTER 추가.
    }

    /** 첫 설치 시 app_settings 단일 row(id=1)를 기본값으로 생성한다. */
    private void seedSettings(@NonNull SQLiteDatabase db) {
        String now = TimeUtil.now();
        ContentValues v = new ContentValues();
        v.put(DbContract.Settings.COL_ID, DbContract.Settings.ROW_ID);
        v.put(DbContract.Settings.COL_HOME_RADIUS_METERS, AppSettings.DEFAULT_HOME_RADIUS_METERS);
        v.put(DbContract.Settings.COL_HOME_UNDERGROUND_FLOOR_COUNT, AppSettings.DEFAULT_FLOOR_COUNT);
        v.put(DbContract.Settings.COL_HOME_GROUND_FLOOR_COUNT, AppSettings.DEFAULT_FLOOR_COUNT);
        v.put(DbContract.Settings.COL_BT_DISCONNECT_STABILIZE_SECONDS,
                AppSettings.DEFAULT_BT_DISCONNECT_STABILIZE_SECONDS);
        v.put(DbContract.Settings.COL_WIFI_CONNECT_STABILIZE_SECONDS,
                AppSettings.DEFAULT_WIFI_CONNECT_STABILIZE_SECONDS);
        v.put(DbContract.Settings.COL_WIFI_DISCONNECT_STABILIZE_SECONDS,
                AppSettings.DEFAULT_WIFI_DISCONNECT_STABILIZE_SECONDS);
        v.put(DbContract.Settings.COL_IS_ONBOARDING_COMPLETED, 0);
        v.put(DbContract.Settings.COL_CREATED_AT, now);
        v.put(DbContract.Settings.COL_UPDATED_AT, now);
        db.insert(DbContract.Settings.TABLE, null, v);
    }

    /** 첫 설치 시 parking_state 단일 row(id=1)를 UNKNOWN 상태로 생성한다. */
    private void seedState(@NonNull SQLiteDatabase db) {
        ContentValues v = new ContentValues();
        v.put(DbContract.State.COL_ID, DbContract.State.ROW_ID);
        v.put(DbContract.State.COL_PARKING_STATUS, ParkingStatus.UNKNOWN.name());
        v.put(DbContract.State.COL_HOME_STATUS, HomeStatus.UNKNOWN.name());
        v.put(DbContract.State.COL_LOCATION_STATUS, LocationStatus.UNKNOWN.name());
        v.putNull(DbContract.State.COL_CURRENT_PARKING_RECORD_ID);
        v.put(DbContract.State.COL_UPDATED_AT, TimeUtil.now());
        db.insert(DbContract.State.TABLE, null, v);
    }

    // ----- DDL -----

    private static final String CREATE_SETTINGS =
            "CREATE TABLE " + DbContract.Settings.TABLE + " ("
                    + DbContract.Settings.COL_ID + " INTEGER PRIMARY KEY, "
                    + DbContract.Settings.COL_VEHICLE_BT_NAME + " TEXT, "
                    + DbContract.Settings.COL_VEHICLE_BT_ADDRESS + " TEXT, "
                    + DbContract.Settings.COL_HOME_WIFI_SSID + " TEXT, "
                    + DbContract.Settings.COL_HOME_LATITUDE + " REAL, "
                    + DbContract.Settings.COL_HOME_LONGITUDE + " REAL, "
                    + DbContract.Settings.COL_HOME_RADIUS_METERS + " INTEGER, "
                    + DbContract.Settings.COL_HOME_UNDERGROUND_FLOOR_COUNT + " INTEGER, "
                    + DbContract.Settings.COL_HOME_GROUND_FLOOR_COUNT + " INTEGER, "
                    + DbContract.Settings.COL_BT_DISCONNECT_STABILIZE_SECONDS + " INTEGER, "
                    + DbContract.Settings.COL_WIFI_CONNECT_STABILIZE_SECONDS + " INTEGER, "
                    + DbContract.Settings.COL_WIFI_DISCONNECT_STABILIZE_SECONDS + " INTEGER, "
                    + DbContract.Settings.COL_IS_ONBOARDING_COMPLETED + " INTEGER, "
                    + DbContract.Settings.COL_CREATED_AT + " TEXT, "
                    + DbContract.Settings.COL_UPDATED_AT + " TEXT"
                    + ")";

    private static final String CREATE_RECORDS =
            "CREATE TABLE " + DbContract.Records.TABLE + " ("
                    + DbContract.Records.COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + DbContract.Records.COL_PARKED_AT + " TEXT, "
                    + DbContract.Records.COL_IS_CURRENT + " INTEGER NOT NULL DEFAULT 0, "
                    + DbContract.Records.COL_SAVE_TYPE + " TEXT, "
                    + DbContract.Records.COL_PARKING_PLACE_TYPE + " TEXT, "
                    + DbContract.Records.COL_PARKING_LEVEL_TYPE + " TEXT, "
                    + DbContract.Records.COL_FLOOR_LABEL + " TEXT, "
                    + DbContract.Records.COL_MEMO + " TEXT, "
                    + DbContract.Records.COL_LATITUDE + " REAL, "
                    + DbContract.Records.COL_LONGITUDE + " REAL, "
                    + DbContract.Records.COL_HAS_GPS + " INTEGER NOT NULL DEFAULT 0, "
                    + DbContract.Records.COL_PHOTO_PATH + " TEXT, "
                    + DbContract.Records.COL_CREATED_AT + " TEXT, "
                    + DbContract.Records.COL_UPDATED_AT + " TEXT"
                    + ")";

    private static final String CREATE_STATE =
            "CREATE TABLE " + DbContract.State.TABLE + " ("
                    + DbContract.State.COL_ID + " INTEGER PRIMARY KEY, "
                    + DbContract.State.COL_PARKING_STATUS + " TEXT, "
                    + DbContract.State.COL_HOME_STATUS + " TEXT, "
                    + DbContract.State.COL_LOCATION_STATUS + " TEXT, "
                    + DbContract.State.COL_CURRENT_PARKING_RECORD_ID + " INTEGER, "
                    + DbContract.State.COL_LAST_BT_STATUS + " TEXT, "
                    + DbContract.State.COL_LAST_WIFI_STATUS + " TEXT, "
                    + DbContract.State.COL_LAST_GPS_STATUS + " TEXT, "
                    + DbContract.State.COL_LAST_STATE_CHANGED_AT + " TEXT, "
                    + DbContract.State.COL_UPDATED_AT + " TEXT"
                    + ")";

    private static final String CREATE_LOGS =
            "CREATE TABLE " + DbContract.Logs.TABLE + " ("
                    + DbContract.Logs.COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + DbContract.Logs.COL_EVENT_TYPE + " TEXT, "
                    + DbContract.Logs.COL_PREVIOUS_STATE + " TEXT, "
                    + DbContract.Logs.COL_NEW_STATE + " TEXT, "
                    + DbContract.Logs.COL_EVENT_TIME + " TEXT, "
                    + DbContract.Logs.COL_IS_STABILIZED + " INTEGER NOT NULL DEFAULT 0, "
                    + DbContract.Logs.COL_STABILIZATION_SECONDS + " INTEGER, "
                    + DbContract.Logs.COL_MEMO + " TEXT"
                    + ")";

    /** is_current=1 조회를 빠르게. (current 레코드는 최대 1개) */
    private static final String CREATE_RECORDS_CURRENT_INDEX =
            "CREATE INDEX idx_records_is_current ON "
                    + DbContract.Records.TABLE + "(" + DbContract.Records.COL_IS_CURRENT + ")";

    /** 로그 트림(오래된 것부터 삭제)을 위한 시간/ID 정렬 인덱스. */
    private static final String CREATE_LOGS_TIME_INDEX =
            "CREATE INDEX idx_logs_id ON "
                    + DbContract.Logs.TABLE + "(" + DbContract.Logs.COL_ID + ")";
}
