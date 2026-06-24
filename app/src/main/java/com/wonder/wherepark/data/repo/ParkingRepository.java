package com.wonder.wherepark.data.repo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wonder.wherepark.data.DbContract.Records;
import com.wonder.wherepark.data.DbHelper;
import com.wonder.wherepark.data.model.Enums.ParkingLevelType;
import com.wonder.wherepark.data.model.Enums.ParkingPlaceType;
import com.wonder.wherepark.data.model.Enums.SaveType;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * parking_records 읽기/쓰기. §19.2 제약을 보장한다.
 * 핵심: is_current=1 레코드는 최대 1개. {@link #insertAsCurrent}/{@link #setCurrent}는
 * 한 트랜잭션 안에서 기존 current를 0으로 내린 뒤 새 레코드를 current로 만든다.
 */
public class ParkingRepository {

    private final DbHelper db;

    public ParkingRepository(@NonNull Context context) {
        this.db = DbHelper.getInstance(context);
    }

    /**
     * 새 레코드를 저장하면서 동시에 현재 주차로 지정한다.
     * 기존 current는 모두 0으로 내린다. 반환값은 새 레코드 id.
     */
    public long insertAsCurrent(@NonNull ParkingRecord r) {
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            clearCurrentFlag(d);
            r.isCurrent = true;
            long id = d.insert(Records.TABLE, null, toValues(r, true));
            d.setTransactionSuccessful();
            r.id = id;
            return id;
        } finally {
            d.endTransaction();
        }
    }

    /** 현재 주차로 지정하지 않고 이력만 추가한다. 반환값은 새 레코드 id. */
    public long insert(@NonNull ParkingRecord r) {
        long id = db.getWritableDatabase().insert(Records.TABLE, null, toValues(r, true));
        r.id = id;
        return id;
    }

    /** 기존 레코드 내용을 수정한다(현재 주차 플래그는 건드리지 않음). */
    public void update(@NonNull ParkingRecord r) {
        db.getWritableDatabase().update(Records.TABLE, toValues(r, false),
                Records.COL_ID + "=?", new String[]{String.valueOf(r.id)});
    }

    /** 지정한 레코드를 현재 주차로 만든다. 기존 current는 0으로 내린다. */
    public void setCurrent(long recordId) {
        SQLiteDatabase d = db.getWritableDatabase();
        d.beginTransaction();
        try {
            clearCurrentFlag(d);
            ContentValues v = new ContentValues();
            v.put(Records.COL_IS_CURRENT, 1);
            v.put(Records.COL_UPDATED_AT, TimeUtil.now());
            d.update(Records.TABLE, v, Records.COL_ID + "=?",
                    new String[]{String.valueOf(recordId)});
            d.setTransactionSuccessful();
        } finally {
            d.endTransaction();
        }
    }

    /** §9.7 현재 주차 정보 초기화 — 이력은 남기고 is_current만 0으로. */
    public void clearCurrent() {
        clearCurrentFlag(db.getWritableDatabase());
    }

    /** 현재 주차 레코드를 반환한다. 없으면 null. */
    @Nullable
    public ParkingRecord getCurrent() {
        SQLiteDatabase d = db.getReadableDatabase();
        try (Cursor c = d.query(Records.TABLE, null,
                Records.COL_IS_CURRENT + "=1", null, null, null,
                Records.COL_ID + " DESC", "1")) {
            return c.moveToFirst() ? map(c) : null;
        }
    }

    @Nullable
    public ParkingRecord getById(long id) {
        SQLiteDatabase d = db.getReadableDatabase();
        try (Cursor c = d.query(Records.TABLE, null,
                Records.COL_ID + "=?", new String[]{String.valueOf(id)},
                null, null, null)) {
            return c.moveToFirst() ? map(c) : null;
        }
    }

    /** 모든 이력을 최신순(parked_at, id DESC)으로 반환한다. */
    @NonNull
    public List<ParkingRecord> getAll() {
        List<ParkingRecord> out = new ArrayList<>();
        SQLiteDatabase d = db.getReadableDatabase();
        try (Cursor c = d.query(Records.TABLE, null, null, null, null, null,
                Records.COL_PARKED_AT + " DESC, " + Records.COL_ID + " DESC")) {
            while (c.moveToNext()) {
                out.add(map(c));
            }
        }
        return out;
    }

    /** 단일 이력을 삭제한다. (사진 파일 삭제는 호출 측 책임 — Phase 3에서 연결) */
    public void delete(long id) {
        db.getWritableDatabase().delete(Records.TABLE,
                Records.COL_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** §8.6.1 모든 주차 이력 삭제. */
    public void deleteAll() {
        db.getWritableDatabase().delete(Records.TABLE, null, null);
    }

    /** §8.6.2 모든 사진 파일 삭제 — DB의 photo_path 값을 비운다(파일 삭제는 호출 측). */
    public void clearAllPhotoPaths() {
        ContentValues v = new ContentValues();
        v.putNull(Records.COL_PHOTO_PATH);
        db.getWritableDatabase().update(Records.TABLE, v, null, null);
    }

    private void clearCurrentFlag(SQLiteDatabase d) {
        ContentValues v = new ContentValues();
        v.put(Records.COL_IS_CURRENT, 0);
        v.put(Records.COL_UPDATED_AT, TimeUtil.now());
        d.update(Records.TABLE, v, Records.COL_IS_CURRENT + "=1", null);
    }

    private static ContentValues toValues(ParkingRecord r, boolean isInsert) {
        String now = TimeUtil.now();
        ContentValues v = new ContentValues();
        v.put(Records.COL_PARKED_AT, r.parkedAt != null ? r.parkedAt : now);
        v.put(Records.COL_IS_CURRENT, r.isCurrent ? 1 : 0);
        v.put(Records.COL_SAVE_TYPE, r.saveType.name());
        v.put(Records.COL_PARKING_PLACE_TYPE, r.parkingPlaceType.name());
        v.put(Records.COL_PARKING_LEVEL_TYPE, r.parkingLevelType.name());
        v.put(Records.COL_FLOOR_LABEL, r.floorLabel);
        v.put(Records.COL_MEMO, r.memo);
        putNullableDouble(v, Records.COL_LATITUDE, r.latitude);
        putNullableDouble(v, Records.COL_LONGITUDE, r.longitude);
        v.put(Records.COL_HAS_GPS, r.hasGps ? 1 : 0);
        v.put(Records.COL_PHOTO_PATH, r.photoPath);
        v.put(Records.COL_UPDATED_AT, now);
        if (isInsert) {
            v.put(Records.COL_CREATED_AT, r.createdAt != null ? r.createdAt : now);
        }
        return v;
    }

    private static void putNullableDouble(ContentValues v, String col, Double value) {
        if (value == null) {
            v.putNull(col);
        } else {
            v.put(col, value);
        }
    }

    private static ParkingRecord map(Cursor c) {
        ParkingRecord r = new ParkingRecord();
        r.id = Cursors.getLong(c, Records.COL_ID);
        r.parkedAt = Cursors.getString(c, Records.COL_PARKED_AT);
        r.isCurrent = Cursors.getBool(c, Records.COL_IS_CURRENT);
        r.saveType = SaveType.fromDb(Cursors.getString(c, Records.COL_SAVE_TYPE));
        r.parkingPlaceType = ParkingPlaceType.fromDb(Cursors.getString(c, Records.COL_PARKING_PLACE_TYPE));
        r.parkingLevelType = ParkingLevelType.fromDb(Cursors.getString(c, Records.COL_PARKING_LEVEL_TYPE));
        r.floorLabel = Cursors.getString(c, Records.COL_FLOOR_LABEL);
        r.memo = Cursors.getString(c, Records.COL_MEMO);
        r.latitude = Cursors.getNullableDouble(c, Records.COL_LATITUDE);
        r.longitude = Cursors.getNullableDouble(c, Records.COL_LONGITUDE);
        r.hasGps = Cursors.getBool(c, Records.COL_HAS_GPS);
        r.photoPath = Cursors.getString(c, Records.COL_PHOTO_PATH);
        r.createdAt = Cursors.getString(c, Records.COL_CREATED_AT);
        r.updatedAt = Cursors.getString(c, Records.COL_UPDATED_AT);
        return r;
    }
}
