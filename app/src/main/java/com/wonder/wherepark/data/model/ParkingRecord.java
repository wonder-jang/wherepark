package com.wonder.wherepark.data.model;

import com.wonder.wherepark.data.model.Enums.ParkingLevelType;
import com.wonder.wherepark.data.model.Enums.ParkingPlaceType;
import com.wonder.wherepark.data.model.Enums.SaveType;

/** §19.2 parking_records 모델. 한 건의 주차 이력. */
public class ParkingRecord {

    /** 미저장(신규) 레코드의 id. */
    public static final long NO_ID = -1L;

    public long id = NO_ID;
    public String parkedAt;
    public boolean isCurrent = false;
    public SaveType saveType = SaveType.MANUAL;
    public ParkingPlaceType parkingPlaceType = ParkingPlaceType.OUTSIDE;
    public ParkingLevelType parkingLevelType = ParkingLevelType.ETC;
    /** `B2F`, `3F` 등. 기타(ETC)는 null 가능. */
    public String floorLabel;
    public String memo;
    public Double latitude;
    public Double longitude;
    public boolean hasGps = false;
    public String photoPath;
    public String createdAt;
    public String updatedAt;

    public boolean isSaved() {
        return id != NO_ID;
    }

    public boolean hasPhoto() {
        return photoPath != null && !photoPath.isEmpty();
    }
}
