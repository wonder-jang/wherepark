package com.wonder.wherepark.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** DB의 created_at/updated_at 등 일시 컬럼에 사용하는 시간 유틸. */
public final class TimeUtil {

    /** SQLite TEXT 컬럼에 저장하는 일시 포맷. 정렬 가능한 로컬 시간 표현. */
    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    private TimeUtil() {
    }

    /** 현재 시각을 {@link #PATTERN} 형식 문자열로 반환한다. */
    public static String now() {
        return format(new Date());
    }

    public static String format(Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat(PATTERN, Locale.US);
        return fmt.format(date);
    }
}
