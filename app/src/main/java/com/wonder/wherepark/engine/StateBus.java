package com.wonder.wherepark.engine;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 주차/집 상태 변경을 화면에 알리는 단순 인-프로세스 이벤트 버스.
 * 백그라운드 감지(DetectionService/StateEngine)로 상태가 바뀌면 화면이 떠 있어도 즉시 갱신되도록 한다.
 * 모든 콜백은 메인 스레드에서 호출된다.
 */
public final class StateBus {

    public interface Listener {
        void onStateChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private StateBus() {
    }

    public static void register(Listener l) {
        listeners.add(l);
    }

    public static void unregister(Listener l) {
        listeners.remove(l);
    }

    /** 상태 변경을 알린다. 호출 스레드와 무관하게 메인 스레드에서 콜백한다. */
    public static void publish() {
        main.post(() -> {
            for (Listener l : listeners) {
                l.onStateChanged();
            }
        });
    }
}
