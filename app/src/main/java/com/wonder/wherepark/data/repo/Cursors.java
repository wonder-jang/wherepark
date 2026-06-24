package com.wonder.wherepark.data.repo;

import android.database.Cursor;

/** Cursor에서 컬럼을 null-safe하게 읽는 헬퍼. */
final class Cursors {

    private Cursors() {
    }

    static String getString(Cursor c, String col) {
        int i = c.getColumnIndexOrThrow(col);
        return c.isNull(i) ? null : c.getString(i);
    }

    static int getInt(Cursor c, String col) {
        return c.getInt(c.getColumnIndexOrThrow(col));
    }

    static long getLong(Cursor c, String col) {
        return c.getLong(c.getColumnIndexOrThrow(col));
    }

    static boolean getBool(Cursor c, String col) {
        return c.getInt(c.getColumnIndexOrThrow(col)) != 0;
    }

    /** NULL이면 null Integer를 반환. */
    static Integer getNullableInt(Cursor c, String col) {
        int i = c.getColumnIndexOrThrow(col);
        return c.isNull(i) ? null : c.getInt(i);
    }

    /** NULL이면 null Long을 반환. */
    static Long getNullableLong(Cursor c, String col) {
        int i = c.getColumnIndexOrThrow(col);
        return c.isNull(i) ? null : c.getLong(i);
    }

    /** NULL이면 null Double을 반환. */
    static Double getNullableDouble(Cursor c, String col) {
        int i = c.getColumnIndexOrThrow(col);
        return c.isNull(i) ? null : c.getDouble(i);
    }
}
