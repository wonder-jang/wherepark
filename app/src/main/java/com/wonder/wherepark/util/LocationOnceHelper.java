package com.wonder.wherepark.util;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;

/**
 * 현재 위치를 한 번 가져오는 헬퍼. §8.2.2 집 위치 저장에 사용.
 * 우선 마지막 위치를 시도하고, 없으면 단발 업데이트를 요청해 타임아웃까지 기다린다.
 * 위치 권한(FINE/COARSE)은 호출 측에서 확인한 뒤 호출해야 한다.
 */
public final class LocationOnceHelper {

    public interface Callback {
        void onResult(@Nullable Location location);
    }

    private static final long TIMEOUT_MS = 12_000L;

    private LocationOnceHelper() {
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION})
    public static void requestOnce(@NonNull Context context, @NonNull Callback callback) {
        LocationManager lm = (LocationManager) context.getApplicationContext()
                .getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            callback.onResult(null);
            return;
        }

        Location best = lastKnown(lm);
        // 최근 2분 이내 위치면 충분히 신선하다고 보고 즉시 사용.
        if (best != null && System.currentTimeMillis() - best.getTime() < 120_000L) {
            callback.onResult(best);
            return;
        }

        String provider = chooseProvider(lm);
        if (provider == null) {
            callback.onResult(best); // 가능한 마지막 위치라도 반환
            return;
        }

        Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        final Location[] fallback = {best};

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (done[0]) return;
                done[0] = true;
                lm.removeUpdates(this);
                callback.onResult(location);
            }

            @Override
            public void onProviderDisabled(@NonNull String p) {
            }

            @Override
            public void onProviderEnabled(@NonNull String p) {
            }
        };

        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());

        handler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            lm.removeUpdates(listener);
            callback.onResult(fallback[0]);
        }, TIMEOUT_MS);
    }

    @RequiresPermission(anyOf = {
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION})
    @Nullable
    private static Location lastKnown(LocationManager lm) {
        Location gps = null;
        Location net = null;
        try {
            gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {
        }
        try {
            net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ignored) {
        }
        if (gps == null) return net;
        if (net == null) return gps;
        return gps.getTime() >= net.getTime() ? gps : net;
    }

    @Nullable
    private static String chooseProvider(LocationManager lm) {
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        }
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER;
        }
        return null;
    }
}
