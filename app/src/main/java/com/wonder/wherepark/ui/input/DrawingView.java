package com.wonder.wherepark.ui.input;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 하얀 배경에 검은 선으로 손가락 스케치를 그리는 커스텀 뷰. 주차 위치 약도 그리기에 사용.
 * 그린 결과는 {@link #getBitmap()}로 가져온다.
 */
public class DrawingView extends View {

    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private final Path path = new Path();
    private final Paint strokePaint;
    private float lastX, lastY;

    public DrawingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeWidth(dp(4f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }
        Bitmap created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(created);
        c.drawColor(Color.WHITE);
        if (bitmap != null) {
            // 크기 변경 시 기존 그림 유지
            c.drawBitmap(bitmap, 0, 0, null);
            bitmap.recycle();
        }
        bitmap = created;
        bitmapCanvas = c;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0, 0, null);
        }
        canvas.drawPath(path, strokePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                path.reset();
                path.moveTo(x, y);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                path.lineTo(x, y);
                if (bitmapCanvas != null) {
                    bitmapCanvas.drawPath(path, strokePaint);
                }
                path.reset();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 캔버스를 하얀 배경으로 초기화한다. */
    public void clear() {
        if (bitmapCanvas != null) {
            bitmapCanvas.drawColor(Color.WHITE);
        }
        path.reset();
        invalidate();
    }

    /** 그려진 결과 비트맵(하얀 배경 + 검은 선). 크기가 정해지기 전이면 null. */
    @Nullable
    public Bitmap getBitmap() {
        return bitmap;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
