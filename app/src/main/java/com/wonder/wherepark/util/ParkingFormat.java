package com.wonder.wherepark.util;

import androidx.annotation.NonNull;

import com.wonder.wherepark.data.model.Enums.ParkingLevelType;
import com.wonder.wherepark.data.model.Enums.ParkingPlaceType;
import com.wonder.wherepark.data.model.ParkingRecord;

/** 주차 기록을 사용자 표시 문자열로 변환한다 (§9.4). 예: `집 · 지하 B2F · 103동 엘리베이터 앞`. */
public final class ParkingFormat {

    private ParkingFormat() {
    }

    public static String placeLabel(ParkingPlaceType type) {
        return type == ParkingPlaceType.HOME ? "집" : "외부";
    }

    public static String levelLabel(ParkingLevelType type) {
        switch (type) {
            case UNDERGROUND: return "지하";
            case GROUND: return "지상";
            default: return "기타";
        }
    }

    /** 지하 n층 → `B{n}F`, 지상 n층 → `{n}F`. */
    public static String floorLabel(ParkingLevelType level, int floor) {
        if (level == ParkingLevelType.UNDERGROUND) {
            return "B" + floor + "F";
        }
        if (level == ParkingLevelType.GROUND) {
            return floor + "F";
        }
        return "";
    }

    /** `집 · 지하 B2F · 메모` 형태의 한 줄 요약. */
    @NonNull
    public static String summary(@NonNull ParkingRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(placeLabel(r.parkingPlaceType));
        sb.append(" · ").append(levelLabel(r.parkingLevelType));
        if (r.parkingLevelType != ParkingLevelType.ETC
                && r.floorLabel != null && !r.floorLabel.isEmpty()) {
            sb.append(' ').append(r.floorLabel);
        }
        if (r.memo != null && !r.memo.trim().isEmpty()) {
            sb.append(" · ").append(r.memo.trim());
        }
        return sb.toString();
    }
}
