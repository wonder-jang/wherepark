package com.wonder.wherepark.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.wonder.wherepark.R;
import com.wonder.wherepark.data.model.ParkingRecord;
import com.wonder.wherepark.ui.input.ParkingInputActivity;
import com.wonder.wherepark.ui.main.MainActivity;
import com.wonder.wherepark.util.ParkingFormat;

/**
 * §14 알림 3종 관리.
 * 1) 주차 위치 입력 요청(입력 완료 전까지 유지)
 * 2) 외부 주차 위치 상시(Ongoing, 운행/초기화/삭제 시 제거)
 * 3) 외출 시 주차 위치(일회성, 진동)
 */
public final class NotificationHelper {

    // v2: 진동을 켜기 위해 재생성한 채널(채널 설정은 생성 후 불변이라 ID를 올림).
    private static final String CH_INPUT = "input_request_v2";
    private static final String CH_INPUT_LEGACY = "input_request";
    // v2: 상시 알림이 앱 아이콘 뱃지를 남기지 않도록 setShowBadge(false)로 재생성한 채널.
    private static final String CH_ONGOING = "ongoing_parking_v2";
    private static final String CH_ONGOING_LEGACY = "ongoing_parking";
    // 더 이상 사용하지 않는 일회성 외출 알림 채널(외출 알림은 상시 FGS 알림 + 진동으로 대체됨).
    private static final String CH_AWAY_LEGACY = "away_reminder";

    private static final int ID_INPUT = 1001;
    private static final int ID_ONGOING = 1002;

    /** 포그라운드 서비스(감지 중/외부 주차 상시)용 알림 ID. */
    public static final int FGS_ID = 2001;

    private NotificationHelper() {
    }

    /**
     * §15 감지 포그라운드 서비스용 상시 알림. 외부 주차 중에는 이 알림이 §14.2의
     * 상시 알림 역할을 하며, FGS에 묶여 Android 14+에서도 스와이프로 지워지지 않는다.
     */
    public static Notification buildForeground(@NonNull Context context,
                                               @NonNull String title, @NonNull String text,
                                               @Nullable String photoPath) {
        ensureChannels(context);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(parkingTabPendingIntent(context));
        applyImageOrText(b, text, photoPath);
        return b.build();
    }

    /** 앱 시작 시 한 번 호출해 채널을 생성한다. */
    public static void ensureChannels(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel input = new NotificationChannel(CH_INPUT,
                context.getString(R.string.noti_channel_input), NotificationManager.IMPORTANCE_HIGH);
        input.enableVibration(true); // 주차 입력 요청은 진동과 함께 헤드업으로 알림
        input.setVibrationPattern(new long[]{0, 400, 200, 400});
        NotificationChannel ongoing = new NotificationChannel(CH_ONGOING,
                context.getString(R.string.noti_channel_ongoing), NotificationManager.IMPORTANCE_LOW);
        ongoing.setShowBadge(false); // 상시 알림은 앱 아이콘 뱃지를 남기지 않음
        nm.createNotificationChannel(input);
        nm.createNotificationChannel(ongoing);
        // 더 이상 쓰지 않는 구버전 채널 제거(기존 설치 업그레이드 대응).
        nm.deleteNotificationChannel(CH_INPUT_LEGACY);
        nm.deleteNotificationChannel(CH_ONGOING_LEGACY);
        nm.deleteNotificationChannel(CH_AWAY_LEGACY);
    }

    // ----- 1) 입력 요청 알림 (§14.1) -----

    public static void showInputRequest(@NonNull Context context) {
        ensureChannels(context);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_INPUT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.noti_input_title))
                .setContentText(context.getString(R.string.noti_input_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(inputPendingIntent(context));
        notify(context, ID_INPUT, b);
    }

    public static void cancelInputRequest(@NonNull Context context) {
        NotificationManagerCompat.from(context).cancel(ID_INPUT);
    }

    // ----- 2) 외부 주차 상시 알림 (§14.2) -----

    public static void showOngoingParking(@NonNull Context context, @NonNull ParkingRecord record) {
        ensureChannels(context);
        String text = context.getString(R.string.noti_ongoing_title)
                + ": " + ParkingFormat.summary(record);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CH_ONGOING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.noti_ongoing_title))
                .setContentText(ParkingFormat.summary(record))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(parkingTabPendingIntent(context));
        applyImageOrText(b, text, record.photoPath);
        notify(context, ID_ONGOING, b);
    }

    public static void cancelOngoingParking(@NonNull Context context) {
        NotificationManagerCompat.from(context).cancel(ID_ONGOING);
    }

    /** 현재 주차/입력 관련 알림을 모두 제거(운행 전환·초기화·삭제·데이터 초기화 시). */
    public static void cancelParkingNotifications(@NonNull Context context) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(ID_INPUT);
        nm.cancel(ID_ONGOING);
    }

    // ----- 내부 -----

    /** 사진/그림이 있으면 BigPictureStyle로 크게 표시하고, 없으면 BigTextStyle로 표시한다. */
    private static void applyImageOrText(NotificationCompat.Builder b, String text,
                                         @Nullable String photoPath) {
        Bitmap pic = loadBitmap(photoPath);
        if (pic != null) {
            b.setLargeIcon(pic);
            b.setStyle(new NotificationCompat.BigPictureStyle()
                    .bigPicture(pic)
                    .bigLargeIcon((Bitmap) null) // 펼쳤을 때 작은 썸네일 숨김
                    .setSummaryText(text));
        } else {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(text));
        }
    }

    @Nullable
    private static Bitmap loadBitmap(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 2; // 알림 표시에는 절반 해상도면 충분
        return BitmapFactory.decodeFile(path, opts);
    }

    private static void notify(Context context, int id, NotificationCompat.Builder b) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        if (!nm.areNotificationsEnabled()) {
            return; // 알림 권한 미허용 시 조용히 무시 (§7.4)
        }
        try {
            nm.notify(id, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS 미허용 등
        }
    }

    private static PendingIntent inputPendingIntent(Context context) {
        Intent intent = new Intent(context, ParkingInputActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 0, intent, pendingFlags());
    }

    private static PendingIntent parkingTabPendingIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, 1, intent, pendingFlags());
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }
}
