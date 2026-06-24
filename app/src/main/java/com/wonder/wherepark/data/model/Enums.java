package com.wonder.wherepark.data.model;

/**
 * DB에 TEXT로 저장되는 닫힌 집합(enum) 값들. 요구사항 §4, §19의 값 정의를 반영한다.
 * 각 enum은 알 수 없는 값을 안전하게 기본값으로 되돌리는 {@code fromDb}를 제공한다.
 */
public final class Enums {

    private Enums() {
    }

    /** §4.1 주차 상태. */
    public enum ParkingStatus {
        UNKNOWN, DRIVING, PARKED;

        public static ParkingStatus fromDb(String value) {
            return parse(value, UNKNOWN);
        }

        private static ParkingStatus parse(String value, ParkingStatus def) {
            if (value == null) return def;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return def;
            }
        }
    }

    /** §4.2 집 상태. */
    public enum HomeStatus {
        UNKNOWN, HOME, AWAY;

        public static HomeStatus fromDb(String value) {
            if (value == null) return UNKNOWN;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    /** §4.3 위치 상태. */
    public enum LocationStatus {
        UNKNOWN, NEAR_HOME, OUTSIDE;

        public static LocationStatus fromDb(String value) {
            if (value == null) return UNKNOWN;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    /** §19.2 save_type. */
    public enum SaveType {
        AUTO, MANUAL;

        public static SaveType fromDb(String value) {
            if (value == null) return MANUAL;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return MANUAL;
            }
        }
    }

    /** §19.2 parking_place_type. */
    public enum ParkingPlaceType {
        HOME, OUTSIDE;

        public static ParkingPlaceType fromDb(String value) {
            if (value == null) return OUTSIDE;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return OUTSIDE;
            }
        }
    }

    /** §19.2 parking_level_type. */
    public enum ParkingLevelType {
        UNDERGROUND, GROUND, ETC;

        public static ParkingLevelType fromDb(String value) {
            if (value == null) return ETC;
            try {
                return valueOf(value);
            } catch (IllegalArgumentException e) {
                return ETC;
            }
        }
    }

    /** §19.4 state_logs event_type. 로그용이라 자유롭게 추가될 수 있다. */
    public static final class EventType {
        public static final String BT_CONNECTED = "BT_CONNECTED";
        public static final String BT_DISCONNECTED = "BT_DISCONNECTED";
        public static final String WIFI_CONNECTED = "WIFI_CONNECTED";
        public static final String WIFI_DISCONNECTED = "WIFI_DISCONNECTED";
        public static final String GPS_NEAR_HOME = "GPS_NEAR_HOME";
        public static final String GPS_OUTSIDE = "GPS_OUTSIDE";
        public static final String PARKING_DETECTED = "PARKING_DETECTED";
        public static final String DRIVING_DETECTED = "DRIVING_DETECTED";
        public static final String PARKING_INPUT_SAVED = "PARKING_INPUT_SAVED";
        public static final String PARKING_INPUT_SKIPPED = "PARKING_INPUT_SKIPPED";
        public static final String CURRENT_PARKING_CLEARED = "CURRENT_PARKING_CLEARED";

        private EventType() {
        }
    }
}
