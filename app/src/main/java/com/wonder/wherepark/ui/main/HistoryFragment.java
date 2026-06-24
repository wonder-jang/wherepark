package com.wonder.wherepark.ui.main;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.wonder.wherepark.data.model.Enums.ParkingPlaceType;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.data.repo.ParkingRepository;
import com.wonder.wherepark.databinding.FragmentHistoryBinding;
import com.wonder.wherepark.ui.detail.ParkingDetailActivity;
import com.wonder.wherepark.ui.history.HistoryAdapter;
import com.wonder.wherepark.util.ParkingFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** §13 주차 이력 탭. 최신순 리스트 + 포함 검색 + 집/외부 필터칩. 항목 클릭 시 상세로 이동. */
public class HistoryFragment extends Fragment {

    private FragmentHistoryBinding binding;
    private ParkingRepository parkingRepo;
    private HistoryAdapter adapter;

    private String query = "";
    @Nullable
    private ParkingPlaceType placeFilter = null; // null = 전체

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHistoryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context ctx = requireContext();
        parkingRepo = new ParkingRepository(ctx);

        adapter = new HistoryAdapter(this::openDetail);
        binding.recycler.setLayoutManager(new LinearLayoutManager(ctx));
        binding.recycler.setAdapter(adapter);

        binding.inputSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        binding.chipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            int id = checkedIds.isEmpty() ? binding.chipAll.getId() : checkedIds.get(0);
            if (id == binding.chipHome.getId()) {
                placeFilter = ParkingPlaceType.HOME;
            } else if (id == binding.chipOutside.getId()) {
                placeFilter = ParkingPlaceType.OUTSIDE;
            } else {
                placeFilter = null; // 전체
            }
            applyFilter();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        applyFilter(); // 입력/수정/삭제 후 복귀 시 갱신
    }

    /** §13.6/13.7 검색어 + 필터를 함께 적용. 정렬은 repo에서 최신순 고정. */
    private void applyFilter() {
        if (binding == null) return;
        List<ParkingRecord> all = parkingRepo.getAll();
        List<ParkingRecord> filtered = new ArrayList<>();
        for (ParkingRecord r : all) {
            if (placeFilter != null && r.parkingPlaceType != placeFilter) {
                continue;
            }
            if (!query.isEmpty() && !searchable(r).contains(query)) {
                continue;
            }
            filtered.add(r);
        }
        adapter.submit(filtered);
        binding.txtEmpty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 검색 대상 텍스트(§13.6): 구분/유형/층/추가정보/일시. 소문자. */
    private String searchable(ParkingRecord r) {
        StringBuilder sb = new StringBuilder();
        sb.append(ParkingFormat.summary(r)).append(' ');
        if (r.parkedAt != null) sb.append(r.parkedAt);
        return sb.toString().toLowerCase(Locale.getDefault());
    }

    private void openDetail(ParkingRecord record) {
        Intent intent = new Intent(requireContext(), ParkingDetailActivity.class);
        intent.putExtra(ParkingDetailActivity.EXTRA_RECORD_ID, record.id);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
