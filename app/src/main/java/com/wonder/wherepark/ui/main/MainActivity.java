package com.wonder.wherepark.ui.main;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.wonder.wherepark.R;
import com.wonder.wherepark.data.repo.SettingsRepository;
import com.wonder.wherepark.databinding.ActivityMainBinding;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.service.DetectionService;
import com.wonder.wherepark.ui.onboarding.OnboardingActivity;
import com.wonder.wherepark.util.WindowInsetsUtil;

/**
 * 앱 메인 화면. 하단 탭 3개(주차 위치 / 주차 이력 / 설정)를 호스팅한다.
 * 최초 진입 탭은 주차 위치 탭이다.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private boolean onboardingLaunched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsUtil.applyWithBottomBar(this, binding.bottomNav);

        NotificationHelper.ensureChannels(this);

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_parking) {
                showFragment(new ParkingFragment());
                return true;
            } else if (id == R.id.nav_history) {
                showFragment(new HistoryFragment());
                return true;
            } else if (id == R.id.nav_settings) {
                showFragment(new SettingsFragment());
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            binding.bottomNav.setSelectedItemId(R.id.nav_parking);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 첫 실행이면 온보딩(§7)을 한 번만 띄운다. 완료 시 is_onboarding_completed=1 저장됨.
        if (!onboardingLaunched
                && !new SettingsRepository(this).get().isOnboardingCompleted) {
            onboardingLaunched = true;
            startActivity(new Intent(this, OnboardingActivity.class));
        }
        // 차량 BT가 설정돼 있으면 자동 감지 서비스를 보장한다(§15).
        DetectionService.start(this);
    }

    /** 다른 화면(예: 주차 위치 탭의 안내)에서 설정 탭으로 전환할 때 사용. */
    public void goToSettingsTab() {
        binding.bottomNav.setSelectedItemId(R.id.nav_settings);
    }

    private void showFragment(@NonNull Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
