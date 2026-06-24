package com.wonder.wherepark.data.repo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import com.wonder.wherepark.data.DbContract.Settings;
import com.wonder.wherepark.data.DbHelper;
import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.util.TimeUtil;

/** app_settings(단일 row id=1) 읽기/쓰기. */
public class SettingsRepository {

    private final DbHelper db;

    public SettingsRepository(@NonNull Context context) {
        this.db = DbHelper.getInstance(context);
    }

    /** 단일 설정 row를 반환한다. 시딩되어 있으므로 항상 존재한다. */
    @NonNull
    public AppSettings get() {
        SQLiteDatabase d = db.getReadableDatabase();
        try (Cursor c = d.query(Settings.TABLE, null,
                Settings.COL_ID + "=?", new String[]{String.valueOf(Settings.ROW_ID)},
                null, null, null)) {
            if (c.moveToFirst()) {
                return map(c);
            }
        }
        // 방어적: 시딩이 없으면 기본값 객체 반환.
        return new AppSettings();
    }

    /** 설정 전체를 갱신한다. updated_at은 자동 설정. */
    public void update(@NonNull AppSettings s) {
        ContentValues v = new ContentValues();
        v.put(Settings.COL_VEHICLE_BT_NAME, s.vehicleBtName);
        v.put(Settings.COL_VEHICLE_BT_ADDRESS, s.vehicleBtAddress);
        v.put(Settings.COL_HOME_WIFI_SSID, s.homeWifiSsid);
        putNullableDouble(v, Settings.COL_HOME_LATITUDE, s.homeLatitude);
        putNullableDouble(v, Settings.COL_HOME_LONGITUDE, s.homeLongitude);
        v.put(Settings.COL_HOME_RADIUS_METERS, s.homeRadiusMeters);
        v.put(Settings.COL_HOME_UNDERGROUND_FLOOR_COUNT, s.homeUndergroundFloorCount);
        v.put(Settings.COL_HOME_GROUND_FLOOR_COUNT, s.homeGroundFloorCount);
        v.put(Settings.COL_BT_DISCONNECT_STABILIZE_SECONDS, s.btDisconnectStabilizeSeconds);
        v.put(Settings.COL_WIFI_CONNECT_STABILIZE_SECONDS, s.wifiConnectStabilizeSeconds);
        v.put(Settings.COL_WIFI_DISCONNECT_STABILIZE_SECONDS, s.wifiDisconnectStabilizeSeconds);
        v.put(Settings.COL_GPS_ENTER_STABILIZE_SECONDS, s.gpsEnterStabilizeSeconds);
        v.put(Settings.COL_GPS_EXIT_STABILIZE_SECONDS, s.gpsExitStabilizeSeconds);
        v.put(Settings.COL_IS_ONBOARDING_COMPLETED, s.isOnboardingCompleted ? 1 : 0);
        v.put(Settings.COL_UPDATED_AT, TimeUtil.now());

        db.getWritableDatabase().update(Settings.TABLE, v,
                Settings.COL_ID + "=?", new String[]{String.valueOf(Settings.ROW_ID)});
    }

    /** §8.6.3 앱 설정 초기화 — 기본값으로 되돌린다(BT/집/위치/온보딩 포함). */
    public void resetToDefaults() {
        AppSettings def = new AppSettings();
        update(def);
    }

    private static void putNullableDouble(ContentValues v, String col, Double value) {
        if (value == null) {
            v.putNull(col);
        } else {
            v.put(col, value);
        }
    }

    private static AppSettings map(Cursor c) {
        AppSettings s = new AppSettings();
        s.id = Cursors.getLong(c, Settings.COL_ID);
        s.vehicleBtName = Cursors.getString(c, Settings.COL_VEHICLE_BT_NAME);
        s.vehicleBtAddress = Cursors.getString(c, Settings.COL_VEHICLE_BT_ADDRESS);
        s.homeWifiSsid = Cursors.getString(c, Settings.COL_HOME_WIFI_SSID);
        s.homeLatitude = Cursors.getNullableDouble(c, Settings.COL_HOME_LATITUDE);
        s.homeLongitude = Cursors.getNullableDouble(c, Settings.COL_HOME_LONGITUDE);
        s.homeRadiusMeters = Cursors.getInt(c, Settings.COL_HOME_RADIUS_METERS);
        s.homeUndergroundFloorCount = Cursors.getInt(c, Settings.COL_HOME_UNDERGROUND_FLOOR_COUNT);
        s.homeGroundFloorCount = Cursors.getInt(c, Settings.COL_HOME_GROUND_FLOOR_COUNT);
        s.btDisconnectStabilizeSeconds = Cursors.getInt(c, Settings.COL_BT_DISCONNECT_STABILIZE_SECONDS);
        s.wifiConnectStabilizeSeconds = Cursors.getInt(c, Settings.COL_WIFI_CONNECT_STABILIZE_SECONDS);
        s.wifiDisconnectStabilizeSeconds = Cursors.getInt(c, Settings.COL_WIFI_DISCONNECT_STABILIZE_SECONDS);
        s.gpsEnterStabilizeSeconds = Cursors.getInt(c, Settings.COL_GPS_ENTER_STABILIZE_SECONDS);
        s.gpsExitStabilizeSeconds = Cursors.getInt(c, Settings.COL_GPS_EXIT_STABILIZE_SECONDS);
        s.isOnboardingCompleted = Cursors.getBool(c, Settings.COL_IS_ONBOARDING_COMPLETED);
        s.createdAt = Cursors.getString(c, Settings.COL_CREATED_AT);
        s.updatedAt = Cursors.getString(c, Settings.COL_UPDATED_AT);
        return s;
    }
}
