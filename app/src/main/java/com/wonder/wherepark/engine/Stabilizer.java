package com.wonder.wherepark.engine;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * §6 상태 변경 안정화. 채널(BT/WIFI/GPS)별로 확정을 지연하고, 같은 채널에 새 이벤트가 오면
 * 이전 대기를 취소한다(반대 이벤트로 후보 취소 = §6.4-3). 지연이 0초면 즉시 확정한다.
 *
 * <p>모든 콜백은 메인 스레드에서 실행된다.
 */
public class Stabilizer {

    /** 안정화 채널. 같은 채널의 새 스케줄은 이전 것을 대체(취소)한다. */
    public static final String CH_BT = "BT";
    public static final String CH_WIFI = "WIFI";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> pending = new HashMap<>();

    /** 채널에 확정 작업을 예약한다. delaySeconds &lt;= 0이면 즉시 실행. */
    public void schedule(@NonNull String channel, int delaySeconds, @NonNull Runnable onConfirm) {
        cancel(channel);
        if (delaySeconds <= 0) {
            onConfirm.run();
            return;
        }
        Runnable wrapper = () -> {
            pending.remove(channel);
            onConfirm.run();
        };
        pending.put(channel, wrapper);
        handler.postDelayed(wrapper, delaySeconds * 1000L);
    }

    /** 채널의 대기 중 확정을 취소한다. */
    public void cancel(@NonNull String channel) {
        Runnable r = pending.remove(channel);
        if (r != null) {
            handler.removeCallbacks(r);
        }
    }

    /** 모든 대기 확정을 취소한다(서비스 종료 시). */
    public void cancelAll() {
        for (Runnable r : pending.values()) {
            handler.removeCallbacks(r);
        }
        pending.clear();
    }
}
