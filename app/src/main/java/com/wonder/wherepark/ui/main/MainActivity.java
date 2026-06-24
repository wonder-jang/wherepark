package com.wonder.wherepark.ui.main;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.wonder.wherepark.R;
import com.wonder.wherepark.databinding.ActivityMainBinding;

/**
 * 앱 메인 화면. 하단 탭 3개(주차 위치 / 주차 이력 / 설정)를 호스팅한다.
 * 최초 진입 탭은 주차 위치 탭이다.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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

    private void showFragment(@NonNull Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}
