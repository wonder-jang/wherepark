package com.wonder.wherepark.ui.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.wonder.wherepark.R;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.util.ParkingFormat;

import java.util.ArrayList;
import java.util.List;

/** §13.4 주차 이력 리스트 어댑터. 요약/일시/현재 배지/사진 아이콘만 표시(사진 원본은 상세에서). */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

    public interface OnItemClick {
        void onClick(ParkingRecord record);
    }

    private final List<ParkingRecord> items = new ArrayList<>();
    private final OnItemClick listener;

    public HistoryAdapter(OnItemClick listener) {
        this.listener = listener;
    }

    public void submit(List<ParkingRecord> records) {
        items.clear();
        items.addAll(records);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ParkingRecord r = items.get(position);
        h.summary.setText(ParkingFormat.summary(r));
        h.date.setText(r.parkedAt != null ? r.parkedAt : "");
        h.badge.setVisibility(r.isCurrent ? View.VISIBLE : View.GONE);
        h.photoIcon.setVisibility(r.hasPhoto() ? View.VISIBLE : View.GONE);
        h.itemView.setOnClickListener(v -> listener.onClick(r));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView badge;
        final TextView summary;
        final TextView date;
        final TextView photoIcon;

        VH(@NonNull View itemView) {
            super(itemView);
            badge = itemView.findViewById(R.id.txt_badge);
            summary = itemView.findViewById(R.id.txt_summary);
            date = itemView.findViewById(R.id.txt_date);
            photoIcon = itemView.findViewById(R.id.txt_photo_icon);
        }
    }
}
