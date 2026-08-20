package com.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 定时任务闹钟接收器（⑥ 全自动版）：
 * AI 通过 android_schedule 设置定时后，MainActivity 用 AlarmManager 注册系统闹钟；
 * 到点时系统唤醒本接收器（即使 App 被杀也能触发）：
 *   1) 推送通知提醒（任务文本同时追加到执行日志，防丢失）
 *   2) 启动前台服务 EngineService（带任务 extra）→ 后台执行任务（ScheduleExecutor）
 *      —— 前台服务进程不会被系统回收，保证任务真正执行（不依赖 Activity/用户操作）
 */
public class AlarmReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "dsh_schedule";
    private static final int NOTIF_ID = 9001;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            String task = intent != null ? intent.getStringExtra("task") : null;
            String taskId = intent != null ? intent.getStringExtra("taskId") : null;
            if (task == null || task.isEmpty()) task = "定时任务时间到了";

            ScheduleExecutor.log(ctx, "闹钟触发 taskId=" + (taskId == null ? "?" : taskId) + " task=" + task);

            // 1) 推送通知（内容 = 任务文本；点击打开 App 可查看执行情况）
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "定时任务",
                            NotificationManager.IMPORTANCE_HIGH);
                    ch.setDescription("AI 设置的定时提醒");
                    nm.createNotificationChannel(ch);
                }
                Intent open = new Intent(ctx, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b;
                if (Build.VERSION.SDK_INT >= 26) {
                    b = new Notification.Builder(ctx, CHANNEL_ID);
                } else {
                    b = new Notification.Builder(ctx);
                }
                Notification n = b.setContentTitle("⏰ 定时任务")
                        .setContentText(task)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build();
                nm.notify(NOTIF_ID, n);
            }

            // 2) 启动前台服务执行任务（前台服务不会被杀，保证执行完成）
            try {
                Intent svc = new Intent(ctx, EngineService.class);
                svc.putExtra("scheduledTask", task);
                if (Build.VERSION.SDK_INT >= 26) {
                    ctx.startForegroundService(svc);
                } else {
                    ctx.startService(svc);
                }
            } catch (Throwable t) {
                ScheduleExecutor.log(ctx, "启动执行服务失败: " + t.getMessage());
            }
        } catch (Throwable t) {
            // 静默：闹钟触发失败不应崩溃
        }
    }
}
