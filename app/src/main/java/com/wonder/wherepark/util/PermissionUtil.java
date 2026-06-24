package com.wonder.wherepark.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 권한 상태 판정 유틸. §8.4 권한 상태 영역에서 사용한다.
 * Android 버전 차이를 흡수해 각 권한을 GRANTED/NEEDED/CHECK_NEEDED로 매핑한다.
 */
public final class PermissionUtil {

    /** §8.4 상태값: 허용됨 / 필요 / 확인 필요. */
    public enum Status {
        GRANTED, NEEDED, CHECK_NEEDED
    }

    private PermissionUtil() {
    }

    private static boolean granted(Context c, String perm) {
        return ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** 차량 BT(연결/페어링 목록). API 31+는 BLUETOOTH_CONNECT 런타임 권한 필요. */
    public static Status bluetooth(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return granted(c, Manifest.permission.BLUETOOTH_CONNECT) ? Status.GRANTED : Status.NEEDED;
        }
        return Status.GRANTED; // 레거시 BLUETOOTH는 일반 권한
    }

    /** 위치(집 근처 판단, GPS 저장). FINE 또는 COARSE 허용 시 사용 가능. */
    public static Status location(Context c) {
        boolean ok = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION);
        return ok ? Status.GRANTED : Status.NEEDED;
    }

    /** 백그라운드 위치. API 29+에서 별도 권한. 전경 위치가 먼저 허용되어야 요청 가능. */
    public static Status backgroundLocation(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    ? Status.GRANTED : Status.NEEDED;
        }
        return Status.GRANTED;
    }

    /** 알림. API 33+에서 런타임 권한. */
    public static Status notification(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return granted(c, Manifest.permission.POST_NOTIFICATIONS)
                    ? Status.GRANTED : Status.NEEDED;
        }
        return Status.GRANTED;
    }

    /**
     * Wi-Fi 상태 접근(SSID 판단). ACCESS_WIFI_STATE는 일반 권한이지만,
     * 실제 SSID 조회에는 위치 권한 + 위치 서비스가 필요하므로 위치 권한에 종속된다.
     */
    public static Status wifiAccess(Context c) {
        return location(c) == Status.GRANTED ? Status.GRANTED : Status.NEEDED;
    }

    /** 배터리 최적화 예외 여부. 예외 처리돼 있으면 GRANTED, 아니면 확인 필요. */
    public static Status batteryOptimization(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName())) {
            return Status.GRANTED;
        }
        return Status.CHECK_NEEDED;
    }

    /**
     * 온보딩/설정에서 한 번에 요청할 전경 런타임 권한 목록(아직 미허용분만).
     * 백그라운드 위치는 전경 위치 허용 이후 별도로 요청해야 하므로 제외한다.
     */
    public static String[] foregroundRequestList(Context c) {
        List<String> req = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && !granted(c, Manifest.permission.BLUETOOTH_CONNECT)) {
            req.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (location(c) != Status.GRANTED) {
            req.add(Manifest.permission.ACCESS_FINE_LOCATION);
            req.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !granted(c, Manifest.permission.POST_NOTIFICATIONS)) {
            req.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return req.toArray(new String[0]);
    }
}
