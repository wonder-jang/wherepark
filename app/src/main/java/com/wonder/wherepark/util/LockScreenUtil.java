package com.wonder.wherepark.util;

import android.app.Activity;
import android.os.Build;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * 액티비티가 잠금화면 위에 표시되고 화면을 켜도록 설정한다.
 * 풀스크린 인텐트로 진입하는 촬영 화면(AutoCapture/CameraCapture)이 잠금 해제 없이 보이게 한다.
 */
public final class LockScreenUtil {

    private LockScreenUtil() {
    }

    public static void showWhenLocked(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
        } else {
            activity.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }
}
