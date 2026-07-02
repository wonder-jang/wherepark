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
    /** 분석된 배경/글자 색(ARGB int). 없으면 null. 사용자에겐 스와치로만 노출. */
    public Integer bgColorRgb;
    public Integer textColorRgb;
    public String createdAt;
    public String updatedAt;

    public boolean isSaved() {
        return id != NO_ID;
    }

    public boolean hasPhoto() {
        return photoPath != null && !photoPath.isEmpty();
    }

    /**
     * 사용자 상세 입력 없이 주차 시점 GPS만 담아 자동 생성된 자리표시 레코드인지 판단한다.
     * 층·메모·사진이 모두 비어 있으면 자리표시로 본다(입력 검증 §12.7상 실제 저장 레코드는
     * 층 또는 메모 또는 사진 중 하나는 반드시 있으므로 오탐되지 않는다).
     */
    public boolean isGpsPlaceholder() {
        boolean noFloor = floorLabel == null || floorLabel.isEmpty();
        boolean noMemo = memo == null || memo.trim().isEmpty();
        return noFloor && noMemo && !hasPhoto();
    }
}
