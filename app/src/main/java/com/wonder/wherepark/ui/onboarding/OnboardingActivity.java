package com.wonder.wherepark.ui.onboarding;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.wonder.wherepark.data.model.AppSettings;
import com.wonder.wherepark.data.repo.SettingsRepository;
import com.wonder.wherepark.databinding.ActivityOnboardingBinding;
import com.wonder.wherepark.util.PermissionUtil;
import com.wonder.wherepark.util.WindowInsetsUtil;

/**
 * §7 첫 실행 화면. 앱 소개와 권한 안내를 표시하고, 전경 권한 → 백그라운드 위치 순으로 요청한다.
 * `시작하기`를 누르면 is_onboarding_completed=1로 저장하고 종료한다.
 * 권한을 거절해도 진입을 막지 않는다(§7.4).
 */
public class OnboardingActivity extends AppCompatActivity {

    private ActivityOnboardingBinding binding;
    private SettingsRepository settingsRepo;

    private ActivityResultLauncher<String[]> foregroundLauncher;
    private ActivityResultLauncher<String> backgroundLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOnboardingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsUtil.applySystemBars(this);
        settingsRepo = new SettingsRepository(this);

        foregroundLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> maybeRequestBackgroundLocation());

        backgroundLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { /* 거절해도 진행 가능 */ });

        binding.btnGrant.setOnClickListener(v -> requestForegroundPermissions());
        binding.btnStart.setOnClickListener(v -> completeOnboarding());
    }

    private void requestForegroundPermissions() {
        String[] perms = PermissionUtil.foregroundRequestList(this);
        if (perms.length == 0) {
            maybeRequestBackgroundLocation();
            return;
        }
        foregroundLauncher.launch(perms);
    }

    /** 전경 위치가 허용된 뒤에야 백그라운드 위치를 요청할 수 있다(Android 정책). */
    private void maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && PermissionUtil.location(this) == PermissionUtil.Status.GRANTED
                && PermissionUtil.backgroundLocation(this) != PermissionUtil.Status.GRANTED) {
            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
    }

    private void completeOnboarding() {
        AppSettings s = settingsRepo.get();
        s.isOnboardingCompleted = true;
        settingsRepo.update(s);
        finish();
    }
}
