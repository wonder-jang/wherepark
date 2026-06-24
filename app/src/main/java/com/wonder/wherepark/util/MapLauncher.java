package com.wonder.wherepark.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * §16.2 지도에서 보기. 외부 지도 앱(geo: 인텐트)을 실행하고,
 * 처리할 앱이 없으면 브라우저 지도 URL로 fallback한다.
 */
public final class MapLauncher {

    private MapLauncher() {
    }

    public static void open(@NonNull Context context, double lat, double lng, @NonNull String label) {
        // Android 11+ 패키지 가시성 제한으로 resolveActivity가 null을 줄 수 있어,
        // 사전 체크 대신 startActivity를 직접 시도하고 예외로 fallback한다.
        // q에 라벨을 붙이면 일부 지도 앱이 라벨까지 검색어로 써서 검색이 실패하므로 좌표만 넣는다.
        Uri geo = Uri.parse(String.format(Locale.US,
                "geo:%f,%f?q=%f,%f", lat, lng, lat, lng));
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, geo));
            return;
        } catch (ActivityNotFoundException ignored) {
            // 지도 앱 없음 → 브라우저 지도 URL fallback
        }

        Uri web = Uri.parse(String.format(Locale.US,
                "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lng));
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, web));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "지도를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }
}
