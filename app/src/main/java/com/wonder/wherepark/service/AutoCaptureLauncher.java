package com.wonder.wherepark.service;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.data.model.ParkingState;
import com.wonder.wherepark.data.repo.StateRepository;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.ui.auto.AutoCaptureActivity;

/**
 * 주차 인식 시 자동 촬영 화면(AutoCaptureActivity)을 띄운다.
 * <ul>
 *   <li>화면 켜짐 + 잠금 해제 → 즉시 실행(오버레이 권한으로 백그라운드 액티비티 실행)</li>
 *   <li>그 외(잠금/화면 꺼짐, 또는 권한 없음) → 호출측이 입력요청 알림을 띄운다.
 *       그 알림은 풀스크린 인텐트라 잠금/꺼짐 상태에서 잠금화면 위로 촬영 화면을 띄운다.</li>
 * </ul>
 */
public final class AutoCaptureLauncher {

    /** 자동 실행 결과. */
    public enum Result {
        /** 지금 바로 촬영 화면을 띄웠다(알림 불필요). */
        LAUNCHED_NOW,
        /** 직접 실행하지 않았다 → 입력요청(풀스크린 인텐트) 알림으로 안내해야 한다. */
        NEEDS_NOTIFICATION
    }

    private AutoCaptureLauncher() {
    }

    /** 백그라운드에서 직접 액티비티를 띄울 수 있는지(= 오버레이 권한 보유). */
    public static boolean canAutoLaunch(@NonNull Context context) {
        return Settings.canDrawOverlays(context.getApplicationContext());
    }

    /** 주차 인식 시 호출. {@link Result} 참고. */
    public static Result onParkingDetected(@NonNull Context context) {
        Context app = context.getApplicationContext();
        // 화면 켜짐+잠금 해제 상태에서만 직접 실행(잠금/꺼짐은 풀스크린 인텐트 알림이 담당).
        if (canAutoLaunch(app) && isScreenOnAndUnlocked(app)) {
            launch(app);
            return Result.LAUNCHED_NOW;
        }
        return Result.NEEDS_NOTIFICATION;
    }

    private static boolean isScreenOnAndUnlocked(Context app) {
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        boolean locked = km != null && km.isKeyguardLocked();
        return interactive && !locked;
    }

    private static void launch(Context app) {
        NotificationHelper.cancelInputRequest(app); // 혹시 남은 입력요청 알림 정리
        // 여전히 주차+미입력 상태일 때만 띄운다(그 사이 입력/운행 전환됐을 수 있음).
        ParkingState st = new StateRepository(app).get();
        if (st.parkingStatus != ParkingStatus.PARKED
                || st.currentParkingRecordId != ParkingState.NO_RECORD) {
            return;
        }
        Intent intent = new Intent(app, AutoCaptureActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        app.startActivity(intent);
    }
}
