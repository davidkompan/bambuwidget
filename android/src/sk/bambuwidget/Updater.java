package sk.bambuwidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Runs one update and keeps the chosen schedule (JobScheduler for 15 min and more, alarms below that). */
final class Updater {
    private static final int JOB_ID = 1;
    static final String ACTION_ALARM = "sk.bambuwidget.ALARM";

    /** Fetch, then refresh the widget and the status bar notification. Blocking; call off the UI thread. */
    static void run(Context c) {
        Store.setRefreshing(c, true);
        WidgetProvider.updateAll(c);
        try {
            Fetcher.refresh(c);
        } finally {
            Store.setRefreshing(c, false);
            WidgetProvider.updateAll(c);
            Notifier.update(c);
        }
    }

    static void runInBackground(final Context c, final Runnable done) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { Updater.run(c); } finally { if (done != null) done.run(); }
            }
        }).start();
    }

    /** Applies the current interval setting. */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        js.cancel(JOB_ID);
        am.cancel(alarmIntent(c));
        if (!Store.signedIn(c)) return;
        int min = Store.interval(c);
        if (min >= 15) {
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, RefreshJob.class))
                    .setPeriodic(min * 60000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } else if (min > 0) {
            scheduleAlarm(c);
        }
    }

    /** Next alarm for intervals below 15 minutes; each alarm schedules the following one. */
    static void scheduleAlarm(Context c) {
        int min = Store.interval(c);
        if (min <= 0 || min >= 15 || !Store.signedIn(c)) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + min * 60000L, alarmIntent(c));
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(c, 10, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
