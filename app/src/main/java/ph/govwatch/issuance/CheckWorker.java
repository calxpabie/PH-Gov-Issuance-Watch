package ph.govwatch.issuance;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs in the background about every few hours, even when the app is closed. */
public class CheckWorker extends Worker {

    static final int INTERVAL_HOURS = 6;
    private static final String UNIQUE_NAME = "giw-check";
    private static final long MAX_RUN_MS = 8 * 60 * 1000L;

    public CheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CheckWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Store store = new Store(context);
        int total = 0;
        List<String> lines = new ArrayList<>();
        long deadline = System.currentTimeMillis() + MAX_RUN_MS;

        synchronized (Checker.LOCK) {
            for (Sources.Source s : Sources.ALL) {
                if (isStopped() || System.currentTimeMillis() > deadline) {
                    break;
                }
                JSONObject r = Checker.runHttp(store, s);
                int n = r.optInt("newCount", 0);
                if (n > 0) {
                    total += n;
                    lines.add(s.label + ": " + n + " new");
                }
            }
            store.markRunFinished();
        }
        if (total > 0) {
            Notifier.notifyNew(context, total, lines);
        }
        return Result.success();
    }
}
