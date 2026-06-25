package com.wonder.wherepark.data;

/**
 * SQLite 테이블/컬럼 이름 상수. 요구사항 §19(DB 설계)를 그대로 반영한다.
 * 4개 테이블: app_settings / parking_records / parking_state / state_logs.
 */
public final class DbContract {

    public static final String DB_NAME = "wherepark.db";
    public static final int DB_VERSION = 1;

    private DbContract() {
    }

    /** §19.1 app_settings — 단일 row(id=1) 구조. */
    public static final class Settings {
        public static final String TABLE = "app_settings";
        public static final long ROW_ID = 1L;

        public static final String COL_ID = "id";
        public static final String COL_VEHICLE_BT_NAME = "vehicle_bt_name";
        public static final String COL_VEHICLE_BT_ADDRESS = "vehicle_bt_address";
        public static final String COL_HOME_WIFI_SSID = "home_wifi_ssid";
        public static final String COL_HOME_LATITUDE = "home_latitude";
        public static final String COL_HOME_LONGITUDE = "home_longitude";
        public static final String COL_HOME_RADIUS_METERS = "home_radius_meters";
        public static final String COL_HOME_UNDERGROUND_FLOOR_COUNT = "home_underground_floor_count";
        public static final String COL_HOME_GROUND_FLOOR_COUNT = "home_ground_floor_count";
        public static final String COL_BT_DISCONNECT_STABILIZE_SECONDS = "bt_disconnect_stabilize_seconds";
        public static final String COL_WIFI_CONNECT_STABILIZE_SECONDS = "wifi_connect_stabilize_seconds";
        public static final String COL_WIFI_DISCONNECT_STABILIZE_SECONDS = "wifi_disconnect_stabilize_seconds";
        public static final String COL_IS_ONBOARDING_COMPLETED = "is_onboarding_completed";
        public static final String COL_CREATED_AT = "created_at";
        public static final String COL_UPDATED_AT = "updated_at";

        private Settings() {
        }
    }

    /** §19.2 parking_records — 주차 이력. */
    public static final class Records {
        public static final String TABLE = "parking_records";

        public static final String COL_ID = "id";
        public static final String COL_PARKED_AT = "parked_at";
        public static final String COL_IS_CURRENT = "is_current";
        public static final String COL_SAVE_TYPE = "save_type";
        public static final String COL_PARKING_PLACE_TYPE = "parking_place_type";
        public static final String COL_PARKING_LEVEL_TYPE = "parking_level_type";
        public static final String COL_FLOOR_LABEL = "floor_label";
        public static final String COL_MEMO = "memo";
        public static final String COL_LATITUDE = "latitude";
        public static final String COL_LONGITUDE = "longitude";
        public static final String COL_HAS_GPS = "has_gps";
        public static final String COL_PHOTO_PATH = "photo_path";
        public static final String COL_CREATED_AT = "created_at";
        public static final String COL_UPDATED_AT = "updated_at";

        private Records() {
        }
    }

    /** §19.3 parking_state — 현재 상태값. 단일 row(id=1) 구조. */
    public static final class State {
        public static final String TABLE = "parking_state";
        public static final long ROW_ID = 1L;

        public static final String COL_ID = "id";
        public static final String COL_PARKING_STATUS = "parking_status";
        public static final String COL_HOME_STATUS = "home_status";
        public static final String COL_LOCATION_STATUS = "location_status";
        public static final String COL_CURRENT_PARKING_RECORD_ID = "current_parking_record_id";
        public static final String COL_LAST_BT_STATUS = "last_bt_status";
        public static final String COL_LAST_WIFI_STATUS = "last_wifi_status";
        public static final String COL_LAST_GPS_STATUS = "last_gps_status";
        public static final String COL_LAST_STATE_CHANGED_AT = "last_state_changed_at";
        public static final String COL_UPDATED_AT = "updated_at";

        private State() {
        }
    }

    /** §19.4 state_logs — 디버깅용 상태 변경 로그. 최근 1,000개만 보관. */
    public static final class Logs {
        public static final String TABLE = "state_logs";
        public static final int MAX_ROWS = 1000;

        public static final String COL_ID = "id";
        public static final String COL_EVENT_TYPE = "event_type";
        public static final String COL_PREVIOUS_STATE = "previous_state";
        public static final String COL_NEW_STATE = "new_state";
        public static final String COL_EVENT_TIME = "event_time";
        public static final String COL_IS_STABILIZED = "is_stabilized";
        public static final String COL_STABILIZATION_SECONDS = "stabilization_seconds";
        public static final String COL_MEMO = "memo";

        private Logs() {
        }
    }
}
