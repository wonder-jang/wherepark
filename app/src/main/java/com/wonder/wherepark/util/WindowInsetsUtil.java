package com.wonder.wherepark.util;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * targetSdk 35+에서 강제되는 edge-to-edge 대응. 콘텐츠 뷰에 시스템 바(상태바/내비게이션바)
 * 인셋만큼 패딩을 주어 UI가 상태바/제스처바와 겹치지 않게 한다.
 */
public final class WindowInsetsUtil {

    private WindowInsetsUtil() {
    }

    /** 액티비티의 콘텐츠 루트에 시스템 바 인셋 패딩을 적용한다. onCreate(setContentView 이후)에서 호출. */
    public static void applySystemBars(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    /**
     * 하단 탭(BottomNavigationView)이 있는 화면용. 콘텐츠에는 상단/좌우만,
     * 하단 인셋은 탭에만 적용해 탭 아래 빈 공간(이중 패딩)이 생기지 않게 한다.
     */
    public static void applyWithBottomBar(Activity activity, View bottomBar) {
        View content = activity.findViewById(android.R.id.content);
        if (content != null) {
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, 0); // 하단은 탭에서 처리
                return insets;
            });
        }
        if (bottomBar != null) {
            final int pl = bottomBar.getPaddingLeft();
            final int pt = bottomBar.getPaddingTop();
            final int pr = bottomBar.getPaddingRight();
            ViewCompat.setOnApplyWindowInsetsListener(bottomBar, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(pl, pt, pr, bars.bottom);
                return insets;
            });
        }
    }
}
