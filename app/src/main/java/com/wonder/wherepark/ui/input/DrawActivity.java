package com.wonder.wherepark.ui.input;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wonder.wherepark.R;
import com.wonder.wherepark.photo.PhotoStore;
import com.wonder.wherepark.util.WindowInsetsUtil;

/**
 * 주차 위치 약도 그리기 화면. 하얀 배경에 손가락으로 스케치하고 저장하면
 * 압축 이미지로 저장한 뒤 파일 경로를 결과로 돌려준다(사진과 동일한 슬롯에 사용).
 */
public class DrawActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT_PATH = "result_path";

    private DrawingView drawingView;
    private PhotoStore photoStore;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_draw);
        WindowInsetsUtil.applySystemBars(this);
        photoStore = new PhotoStore(this);

        drawingView = findViewById(R.id.drawing_view);
        findViewById(R.id.btn_clear).setOnClickListener(v -> drawingView.clear());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    private void save() {
        Bitmap bitmap = drawingView.getBitmap();
        if (bitmap == null) {
            Toast.makeText(this, R.string.draw_fail, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            String path = photoStore.saveBitmap(bitmap);
            runOnUiThread(() -> {
                if (path == null) {
                    Toast.makeText(this, R.string.draw_fail, Toast.LENGTH_SHORT).show();
                    return;
                }
                setResult(RESULT_OK, new android.content.Intent().putExtra(EXTRA_RESULT_PATH, path));
                finish();
            });
        }).start();
    }
}
