package com.wonder.wherepark.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 색 견본(스와치) 비트맵 생성과, 구버전 메모에 섞여 있을 수 있는 색 HEX 문자열 제거를 담당한다.
 * (분석된 색은 이제 별도 DB 컬럼에 저장되고 사용자에겐 스와치로만 노출된다.)
 */
public final class ColorMemo {

    // 구버전 메모에 들어가던 `· 배경 #RRGGBB`, `· 글자 #RRGGBB` 토큰 제거용.
    private static final Pattern LEGACY_HEX =
            Pattern.compile("\\s*·?\\s*(배경|글자)\\s*#[0-9A-Fa-f]{6}");

    private ColorMemo() {
    }

    /** 구버전 메모에 섞인 색 HEX 토큰을 제거한다(사용자 노출 방지). 빈 결과는 null. */
    @Nullable
    public static String stripHex(@Nullable String memo) {
        if (memo == null) {
            return null;
        }
        Matcher m = LEGACY_HEX.matcher(memo);
        String s = m.replaceAll("").trim();
        // 앞뒤에 남은 가운뎃점 정리
        s = s.replaceAll("^·\\s*", "").replaceAll("\\s*·$", "").trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 배경/글자 색을 표지판처럼 나타내는 정사각 스와치 비트맵을 만든다(배경 채움 + 가운데 글자색 사각형).
     * 둘 다 없으면 null.
     */
    @Nullable
    public static Bitmap swatch(@Nullable Integer bg, @Nullable Integer textColor) {
        if (bg == null && textColor == null) {
            return null;
        }
        int size = 144;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(bg != null ? bg : 0xFFEEEEEE);
        c.drawRoundRect(new RectF(0, 0, size, size), 24, 24, p);
        if (textColor != null) {
            p.setColor(textColor);
            float pad = size * 0.28f;
            c.drawRoundRect(new RectF(pad, pad, size - pad, size - pad), 12, 12, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4);
        p.setColor(0xFF888888);
        c.drawRoundRect(new RectF(2, 2, size - 2, size - 2), 24, 24, p);
        return bmp;
    }
}
