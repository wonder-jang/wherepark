package com.wonder.wherepark.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * §19.1 app_settings 단일 row 모델. 첫 설치 시 기본값으로 시딩된다.
 * BT/Wi-Fi/위치 관련 미설정 값은 null(미지정)일 수 있다.
 */
public class AppSettings {

    // 요구사항 §6.1, §8 기본값
    public static final int DEFAULT_HOME_RADIUS_METERS = 100;
    public static final int DEFAULT_FLOOR_COUNT = 1;
    public static final int DEFAULT_BT_DISCONNECT_STABILIZE_SECONDS = 10;
    public static final int DEFAULT_WIFI_CONNECT_STABILIZE_SECONDS = 10;
    public static final int DEFAULT_WIFI_DISCONNECT_STABILIZE_SECONDS = 10;

    public long id = com.wonder.wherepark.data.DbContract.Settings.ROW_ID;
    public String vehicleBtName;
    public String vehicleBtAddress;
    /** 집 Wi-Fi SSID 목록(여러 개 등록 가능). 비어 있으면 미설정. */
    public final List<String> homeWifiSsids = new ArrayList<>();
    public Double homeLatitude;
    public Double homeLongitude;
    public int homeRadiusMeters = DEFAULT_HOME_RADIUS_METERS;
    public int homeUndergroundFloorCount = DEFAULT_FLOOR_COUNT;
    public int homeGroundFloorCount = DEFAULT_FLOOR_COUNT;
    public int btDisconnectStabilizeSeconds = DEFAULT_BT_DISCONNECT_STABILIZE_SECONDS;
    public int wifiConnectStabilizeSeconds = DEFAULT_WIFI_CONNECT_STABILIZE_SECONDS;
    public int wifiDisconnectStabilizeSeconds = DEFAULT_WIFI_DISCONNECT_STABILIZE_SECONDS;
    /** 주차 시 사진 촬영 후 자동 분석 사용 여부. 기본 ON. */
    public boolean autoPhotoAnalysisEnabled = true;
    public boolean isOnboardingCompleted = false;
    public String createdAt;
    public String updatedAt;

    /** 차량 BT가 지정되었는지 여부. §9.3 미설정 안내 판단에 사용. */
    public boolean hasVehicleBt() {
        return vehicleBtAddress != null && !vehicleBtAddress.isEmpty();
    }

    /** 집 GPS 좌표가 지정되었는지 여부. */
    public boolean hasHomeLocation() {
        return homeLatitude != null && homeLongitude != null;
    }

    /** 집 Wi-Fi가 하나라도 등록되었는지 여부. */
    public boolean hasHomeWifi() {
        return !homeWifiSsids.isEmpty();
    }

    /** 주어진 SSID가 등록된 집 Wi-Fi 중 하나와 일치하는지 여부. */
    public boolean matchesHomeWifi(String ssid) {
        return ssid != null && homeWifiSsids.contains(ssid);
    }
}
