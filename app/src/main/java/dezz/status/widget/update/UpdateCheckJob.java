/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget.update;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import android.util.Log;

/**
 * Reads the update channel in the background, every {@link UpdatePolicy#CHECK_INTERVAL_MS} while a network
 * is up, whether or not anything of the app runs — the widget works as a service the person rarely opens the
 * settings of. A persisted periodic job, so it outlives reboots; what a found release leads to (the card over
 * other apps, the settings section) is the {@link Updater}'s listeners'.
 */
public final class UpdateCheckJob extends JobService {

    /** The app's only job. */
    static final int JOB_ID = 0xD20;

    /** Arm the job unless it is armed already (called at every start of whatever runs the app). */
    public static void schedule(Context ctx) {
        // Update check is disabled
    }


    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(Updater.TAG, "Background check skipped.");
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;   // the period brings the next attempt
    }
}
