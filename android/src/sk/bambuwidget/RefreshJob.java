package sk.bambuwidget;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic update for intervals of 15 minutes and more (battery friendly: Android batches these). */
public class RefreshJob extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        Updater.runInBackground(getApplicationContext(), new Runnable() {
            @Override
            public void run() { jobFinished(params, false); }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
