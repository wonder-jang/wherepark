package com.wonder.wherepark.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** §15.4 재부팅 후 자동 감지 서비스를 복구한다(차량 BT가 설정된 경우에만). */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        try {
            DetectionService.start(context);
        } catch (Exception ignored) {
            // 부팅 직후 백그라운드 FGS 시작 제한 등 실패 시 다음 앱 실행에서 시작된다.
        }
    }
}
