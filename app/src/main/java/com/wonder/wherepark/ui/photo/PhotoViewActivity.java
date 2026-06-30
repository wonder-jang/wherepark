package com.wonder.wherepark.ui.photo;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wonder.wherepark.R;

/**
 * 저장된 주차 사진을 잘림 없이 전체(fitCenter)로 보여주는 뷰어. 화면을 탭하면 닫힌다.
 * 사진은 앱이 보관하는 압축본(긴 변 1280px)을 그대로 보여준다.
 */
public class PhotoViewActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "path";

    /** 사진 경로로 뷰어를 연다(경로가 비어 있으면 아무것도 하지 않음). */
    public static void open(@Nullable Context context, @Nullable String path) {
        if (context == null || path == null || path.isEmpty()) {
            return;
        }
        context.startActivity(new Intent(context, PhotoViewActivity.class).putExtra(EXTRA_PATH, path));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_view);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        Bitmap bmp = path != null ? BitmapFactory.decodeFile(path) : null;
        if (bmp == null) {
            Toast.makeText(this, R.string.input_photo_fail, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ImageView img = findViewById(R.id.img_full);
        img.setImageBitmap(bmp);
        findViewById(R.id.root).setOnClickListener(v -> finish());
    }
}
