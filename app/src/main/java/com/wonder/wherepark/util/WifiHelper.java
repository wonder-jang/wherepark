package com.wonder.wherepark.util;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.annotation.Nullable;

/** 현재 연결된 Wi-Fi SSID 조회. §8.2.1 집 Wi-Fi 저장에 사용. */
public final class WifiHelper {

    private WifiHelper() {
    }

    /**
     * 현재 연결된 Wi-Fi의 SSID를 반환한다. 가져올 수 없으면 null.
     * (Wi-Fi 미연결, 위치 권한/서비스 미충족 시 시스템이 &lt;unknown ssid&gt;를 준다 → null 처리)
     */
    @Nullable
    public static String getCurrentSsid(Context context) {
        WifiManager wm = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            return null;
        }
        WifiInfo info = wm.getConnectionInfo();
        if (info == null) {
            return null;
        }
        String ssid = info.getSSID();
        if (ssid == null) {
            return null;
        }
        // SSID는 보통 큰따옴표로 감싸져 온다.
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        if (ssid.isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid) || "0x".equals(ssid)) {
            return null;
        }
        return ssid;
    }
}
