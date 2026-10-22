package sk.bambuwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Short-interval updates (below 15 minutes). The alarm keeps the phone awake while onReceive / goAsync runs. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        final PendingResult pending = goAsync();
        Updater.scheduleAlarm(c);
        Updater.runInBackground(c, new Runnable() {
            @Override
            public void run() { pending.finish(); }
        });
    }
}
