package com.wonder.wherepark.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.wonder.wherepark.R;
import com.wonder.wherepark.data.model.Enums.EventType;
import com.wonder.wherepark.data.model.Enums.ParkingLevelType;
import com.wonder.wherepark.data.model.Enums.ParkingStatus;
import com.wonder.wherepark.data.model.Enums.SaveType;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.data.model.ParkingState;
import com.wonder.wherepark.data.repo.ParkingRepository;
import com.wonder.wherepark.data.repo.StateLogRepository;
import com.wonder.wherepark.data.repo.StateRepository;
import com.wonder.wherepark.notify.NotificationHelper;
import com.wonder.wherepark.photo.PhotoStore;
import com.wonder.wherepark.service.DetectionService;
import com.wonder.wherepark.ui.input.ParkingInputActivity;
import com.wonder.wherepark.util.MapLauncher;
import com.wonder.wherepark.util.ParkingFormat;
import com.wonder.wherepark.util.TimeUtil;
import com.wonder.wherepark.util.WindowInsetsUtil;

import java.util.Locale;

/** §13.9~13.12 주차 이력 상세. 정보 표시 + 수정/삭제/지도. */
public class ParkingDetailActivity extends AppCompatActivity {

    public static final String EXTRA_RECORD_ID = "record_id";

    private ParkingRepository parkingRepo;
    private StateRepository stateRepo;
    private StateLogRepository logRepo;
    private PhotoStore photoStore;

    private long recordId;
    @Nullable
    private ParkingRecord record;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parking_detail);
        WindowInsetsUtil.applySystemBars(this);

        Context ctx = this;
        parkingRepo = new ParkingRepository(ctx);
        stateRepo = new StateRepository(ctx);
        logRepo = new StateLogRepository(ctx);
        photoStore = new PhotoStore(ctx);

        recordId = getIntent().getLongExtra(EXTRA_RECORD_ID, ParkingRecord.NO_ID);

        findViewById(R.id.btn_edit).setOnClickListener(v -> {
            Intent i = new Intent(this, ParkingInputActivity.class);
            i.putExtra(ParkingInputActivity.EXTRA_RECORD_ID, recordId);
            startActivity(i);
        });
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onResume() {
        super.onResume();
        record = parkingRepo.getById(recordId);
        if (record == null) {
            finish(); // 삭제되었거나 없는 기록
            return;
        }
        bind(record);
    }

    private void bind(ParkingRecord r) {
        findViewById(R.id.txt_badge).setVisibility(r.isCurrent ? View.VISIBLE : View.GONE);

        bindRow(R.id.row_parked_at, getString(R.string.detail_parked_at),
                r.parkedAt != null ? r.parkedAt : getString(R.string.detail_none_value));
        bindRow(R.id.row_place, getString(R.string.detail_place),
                ParkingFormat.placeLabel(r.parkingPlaceType));
        bindRow(R.id.row_level, getString(R.string.detail_level),
                ParkingFormat.levelLabel(r.parkingLevelType));
        bindRow(R.id.row_floor, getString(R.string.detail_floor),
                (r.parkingLevelType != ParkingLevelType.ETC && r.floorLabel != null)
                        ? r.floorLabel : getString(R.string.detail_none_value));
        bindRow(R.id.row_memo, getString(R.string.detail_memo),
                (r.memo != null && !r.memo.isEmpty()) ? r.memo : getString(R.string.detail_none_value));
        bindRow(R.id.row_current, getString(R.string.detail_is_current),
                getString(r.isCurrent ? R.string.detail_yes : R.string.detail_no));
        bindRow(R.id.row_save_type, getString(R.string.detail_save_type),
                getString(r.saveType == SaveType.AUTO
                        ? R.string.detail_save_auto : R.string.detail_save_manual));
        bindRow(R.id.row_gps, getString(R.string.detail_gps),
                r.hasGps ? String.format(Locale.US, "%s (%.5f, %.5f)",
                        getString(R.string.detail_gps_saved), r.latitude, r.longitude)
                        : getString(R.string.detail_gps_none));

        showPhoto(r);

        View mapBtn = findViewById(R.id.btn_map);
        if (r.hasGps) {
            mapBtn.setVisibility(View.VISIBLE);
            mapBtn.setOnClickListener(v -> MapLauncher.open(this,
                    r.latitude, r.longitude, ParkingFormat.summary(r)));
        } else {
            mapBtn.setVisibility(View.GONE);
        }
    }

    private void showPhoto(ParkingRecord r) {
        ImageView img = findViewById(R.id.img_photo);
        if (!r.hasPhoto()) {
            img.setVisibility(View.GONE);
            return;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2;
        Bitmap bmp = BitmapFactory.decodeFile(r.photoPath, opts);
        if (bmp != null) {
            img.setImageBitmap(bmp);
            img.setVisibility(View.VISIBLE);
        } else {
            img.setVisibility(View.GONE);
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.detail_delete_confirm)
                .setPositiveButton(R.string.detail_delete, (d, w) -> doDelete())
                .setNegativeButton(R.string.settings_cancel, null)
                .show();
    }

    /** §13.12 삭제: 이력 삭제 + 사진 파일 삭제 + 현재 주차였다면 상태 초기화. */
    private void doDelete() {
        if (record == null) return;
        boolean wasCurrent = record.isCurrent;
        String photoPath = record.photoPath;

        parkingRepo.delete(record.id);
        photoStore.deleteByPath(photoPath); // §17.5 연결 사진 파일 삭제

        if (wasCurrent) {
            ParkingState st = stateRepo.get();
            st.currentParkingRecordId = ParkingState.NO_RECORD;
            st.parkingStatus = ParkingStatus.UNKNOWN;
            st.lastStateChangedAt = TimeUtil.now();
            stateRepo.update(st);
            logRepo.append(EventType.CURRENT_PARKING_CLEARED, null,
                    ParkingStatus.UNKNOWN.name(), false, null);
            NotificationHelper.cancelParkingNotifications(this); // §14.2 현재 주차 이력 삭제 시 제거
            DetectionService.refresh(this); // FGS 알림 갱신
        }
        finish();
    }

    private void bindRow(int rowId, String label, String value) {
        View row = findViewById(rowId);
        ((TextView) row.findViewById(R.id.detail_label)).setText(label);
        ((TextView) row.findViewById(R.id.detail_value)).setText(value);
    }
}
