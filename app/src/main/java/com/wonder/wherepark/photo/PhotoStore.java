package com.wonder.wherepark.photo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 주차 사진 파일 저장소 (§17). 원본은 보관하지 않고 앱용 압축본(긴 변 1280px, JPEG 80%)만
 * 앱 내부 저장소에 저장한다. SQLite에는 파일 경로만 저장한다.
 */
public final class PhotoStore {

    private static final String DIR_NAME = "parking_photos";
    private static final int MAX_EDGE_PX = 1280;   // §17.4 긴 변 최대 1280px
    private static final int JPEG_QUALITY = 80;    // §17.4 JPEG 품질 80%

    private final Context appContext;
    private final File dir;

    public PhotoStore(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.dir = new File(appContext.getFilesDir(), DIR_NAME);
    }

    @NonNull
    public File dir() {
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 카메라 촬영 결과를 임시로 받을 캐시 파일을 만든다. FileProvider로 공유. */
    @NonNull
    public File createTempCaptureFile() throws IOException {
        File cacheDir = new File(appContext.getCacheDir(), "capture");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        return File.createTempFile("cap_", ".jpg", cacheDir);
    }

    /**
     * content Uri(갤러리/카메라)에서 비트맵을 읽어 1280px/JPEG80으로 압축 저장한다.
     * 성공 시 저장된 파일의 절대 경로를 반환, 실패 시 null.
     */
    @Nullable
    public String saveCompressed(@NonNull Uri source) {
        try {
            Bitmap bitmap = decodeDownsampled(source);
            if (bitmap == null) {
                return null;
            }
            bitmap = applyExifRotation(source, bitmap);
            return writeJpeg(bitmap);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 비트맵(그리기 결과 등)을 1280px/JPEG80으로 압축 저장한다. 원본 비트맵은 재활용하지 않는다.
     * 성공 시 저장 경로, 실패 시 null.
     */
    @Nullable
    public String saveBitmap(@NonNull Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        Bitmap toWrite = src;
        boolean scaledCreated = false;
        int longEdge = Math.max(w, h);
        if (longEdge > MAX_EDGE_PX) {
            float ratio = (float) MAX_EDGE_PX / longEdge;
            toWrite = Bitmap.createScaledBitmap(src, Math.round(w * ratio), Math.round(h * ratio), true);
            scaledCreated = (toWrite != src);
        }
        File out = new File(dir(), "parking_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            toWrite.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            fos.flush();
        } catch (IOException e) {
            return null;
        } finally {
            if (scaledCreated) {
                toWrite.recycle();
            }
        }
        return out.getAbsolutePath();
    }

    /** 기존 사진 파일을 삭제한다(경로가 우리 폴더 안에 있을 때만). */
    public void deleteByPath(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        File f = new File(path);
        if (f.exists() && f.getParentFile() != null
                && f.getParentFile().getName().equals(DIR_NAME)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** §8.6.2 모든 사진 파일 삭제. 삭제한 파일 수를 반환한다. */
    public int deleteAll() {
        if (!dir.exists()) {
            return 0;
        }
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.delete()) {
                    count++;
                }
            }
        }
        return count;
    }

    // ----- 내부 -----

    @Nullable
    private Bitmap decodeDownsampled(@NonNull Uri source) throws IOException {
        // 1차: 크기만 읽어 inSampleSize 계산(OOM 방지)
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = appContext.getContentResolver().openInputStream(source)) {
            BitmapFactory.decodeStream(is, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX);
        Bitmap decoded;
        try (InputStream is = appContext.getContentResolver().openInputStream(source)) {
            decoded = BitmapFactory.decodeStream(is, null, opts);
        }
        if (decoded == null) {
            return null;
        }
        return scaleToMaxEdge(decoded, MAX_EDGE_PX);
    }

    private static int calcSampleSize(int w, int h, int maxEdge) {
        int longEdge = Math.max(w, h);
        int sample = 1;
        while (longEdge / sample > maxEdge * 2) {
            sample *= 2;
        }
        return sample;
    }

    private static Bitmap scaleToMaxEdge(Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) {
            return src;
        }
        float ratio = (float) maxEdge / longEdge;
        int nw = Math.round(w * ratio);
        int nh = Math.round(h * ratio);
        Bitmap scaled = Bitmap.createScaledBitmap(src, nw, nh, true);
        if (scaled != src) {
            src.recycle();
        }
        return scaled;
    }

    private Bitmap applyExifRotation(@NonNull Uri source, @NonNull Bitmap bitmap) {
        try (InputStream is = appContext.getContentResolver().openInputStream(source)) {
            if (is == null) {
                return bitmap;
            }
            ExifInterface exif = new ExifInterface(is);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            Matrix m = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    m.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    m.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    m.postRotate(270);
                    break;
                default:
                    return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), m, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (Exception e) {
            return bitmap;
        }
    }

    @Nullable
    private String writeJpeg(@NonNull Bitmap bitmap) {
        File out = new File(dir(), "parking_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            fos.flush();
        } catch (IOException e) {
            return null;
        } finally {
            bitmap.recycle();
        }
        return out.getAbsolutePath();
    }
}
