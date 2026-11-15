/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
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


package dezz.status.widget;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one subscription to everything positional: the platform's location fixes, the GNSS status
 * callback (with native satellite counting for Belgee X50 / Coolray head units), and the companion
 * gnss-share app's broadcast when present.
 */
final class GnssProvider {

    /** What a listener wants registered. Bit flags, OR-ed, declared through {@link #setNeeds}. */
    static final int NEED_NONE = 0;
    /** Platform location fixes: accuracy, speed, altitude, bearing, fix age. */
    static final int NEED_LOCATION = 1;
    /** The {@link GnssStatus} callback — engine transitions and native satellite counts. */
    static final int NEED_GNSS_STATUS = 2;
    /** The companion app's broadcast: its satellite count and the positioning-mode bits. */
    static final int NEED_SHARE = 4;

    interface Listener {
        /** A fix arrived. */
        default void onGnssLocation(@NonNull Location location) {
        }

        /** The GNSS engine started producing. */
        default void onGnssStarted() {
        }

        /** The GNSS engine stopped. */
        default void onGnssStopped() {
        }

        /** First fix of this session, with its time to first fix. */
        default void onGnssFirstFix(int ttffMillis) {
        }

        /** gnss-share or native GNSS satellite data arrived, or its freshness window closed. */
        default void onGnssShareStatus() {
        }

        /**
         * One second passed. The one ticker in the app for things that go stale on their own — a
         * fix that stops arriving, a fix age counting up — so neither brick runs its own.
         */
        default void onGnssTick() {
        }
    }

    private static final String TAG = "GnssProvider";
    private static final long TICK_INTERVAL_MS = 1000;

    /** Broadcast from the companion gnss-share app; see its README for the contract. */
    private static final String SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    /** Satellite count extra. {@code -1} means "no satellite data". */
    private static final String EXTRA_SATELLITES_COUNT = "count";
    private static final String EXTRA_USED_IN_FIX = "usedInFix";
    private static final String EXTRA_MODE = "mode";
    private static final int MODE_DR = 1;          // bit 0: position is dead-reckoned
    private static final int MODE_SPOOF = 2;       // bit 1: GPS spoofing detected
    private static final int MODE_ROAD_KNOWN = 4;  // bit 2: the road verdict below is live
    private static final int MODE_ON_ROAD = 8;     // bit 3: …and it says the position is on a road

    private static final long SHARE_TIMEOUT_MS = 30_000L;
    private static final long NATIVE_STATUS_TIMEOUT_MS = 15_000L;

    static final long FIX_STALE_MS = 10_000L;
    private static final float MOVING_SPEED_MPS = 1.4f;

    private final Context context;
    private final Handler handler;

    private final Map<Listener, Integer> listeners = new LinkedHashMap<>();

    @Nullable
    private LocationManager locationManager;
    private boolean locationRegistered;
    private boolean passiveRegistered;
    private boolean gnssStatusRegistered;
    private boolean shareRegistered;
    private boolean tickScheduled;

    // ── platform location ────────────────────────────────────────────────────
    @Nullable
    private Location lastLocation;
    /** {@link SystemClock#elapsedRealtime()} of the last fix; 0 = none since the process started. */
    private long lastLocationRealtime;

    // ── native GnssStatus satellites (for head units without gnss-share) ─────
    private int nativeSatellites = -1;
    private int nativeUsedInFix = -1;
    private long nativeStatusTimestamp;

    // ── gnss-share ───────────────────────────────────────────────────────────
    private boolean shareReceived;
    private int shareCount = -1;
    private int shareUsedInFix = -1;
    private int shareMode = 0;
    private long shareTimestamp;

    GnssProvider(@NonNull Context context, @NonNull Handler handler) {
        this.context = context;
        this.handler = handler;
    }

    void setNeeds(@NonNull Listener listener, int needs) {
        if (needs == NEED_NONE) {
            listeners.remove(listener);
        } else {
            listeners.put(listener, needs);
        }
        reconcile();
    }

    void destroy() {
        listeners.clear();
        reconcile();
    }

    private int combinedNeeds() {
        int needs = NEED_NONE;
        for (int n : listeners.values()) {
            needs |= n;
        }
        return needs;
    }

    private void dispatch(@NonNull Dispatch what) {
        List<Listener> snapshot = new ArrayList<>(listeners.keySet());
        for (Listener listener : snapshot) {
            what.deliver(listener);
        }
    }

    private interface Dispatch {
        void deliver(@NonNull Listener listener);
    }

    @SuppressLint("MissingPermission")
    private void reconcile() {
        int needs = combinedNeeds();

        boolean wantLocation = (needs & NEED_LOCATION) != 0;
        // Register GnssStatus if either NEED_GNSS_STATUS or NEED_SHARE is wanted so native
        // satellite counts are available even when gnss-share is not installed.
        boolean wantGnssStatus = (needs & (NEED_GNSS_STATUS | NEED_SHARE)) != 0;
        boolean wantShare = (needs & NEED_SHARE) != 0;

        if ((wantLocation || wantGnssStatus) && locationManager == null) {
            locationManager = context.getSystemService(LocationManager.class);
        }

        if (locationManager != null) {
            if (wantLocation != locationRegistered) {
                if (wantLocation) {
                    try {
                        if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                                    locationListener, Looper.getMainLooper());
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "requestLocationUpdates(GPS_PROVIDER) failed", t);
                    }
                    try {
                        if (locationManager.getAllProviders().contains(LocationManager.PASSIVE_PROVIDER)) {
                            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1000, 0,
                                    locationListener, Looper.getMainLooper());
                            passiveRegistered = true;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "requestLocationUpdates(PASSIVE_PROVIDER) failed", t);
                    }
                    seedLastKnownLocation();
                } else {
                    try {
                        locationManager.removeUpdates(locationListener);
                    } catch (Throwable ignored) {
                    }
                    passiveRegistered = false;
                    lastLocation = null;
                    lastLocationRealtime = 0;
                }
                locationRegistered = wantLocation;
            }
            if (wantGnssStatus != gnssStatusRegistered) {
                if (wantGnssStatus) {
                    try {
                        locationManager.registerGnssStatusCallback(gnssStatusCallback, handler);
                    } catch (Throwable t) {
                        Log.w(TAG, "registerGnssStatusCallback failed", t);
                    }
                } else {
                    try {
                        locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
                    } catch (Throwable ignored) {
                    }
                    nativeSatellites = -1;
                    nativeUsedInFix = -1;
                    nativeStatusTimestamp = 0;
                }
                gnssStatusRegistered = wantGnssStatus;
            }
        }

        if (wantShare != shareRegistered) {
            if (wantShare) {
                IntentFilter filter = new IntentFilter(SATELLITE_STATUS_ACTION);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(shareReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(shareReceiver, filter);
                }
            } else {
                try {
                    context.unregisterReceiver(shareReceiver);
                } catch (IllegalArgumentException ignored) {
                }
                handler.removeCallbacks(shareExpiryRunnable);
                clearShareData();
            }
            shareRegistered = wantShare;
        }

        boolean wantTick = wantLocation || wantShare;
        if (wantTick != tickScheduled) {
            if (wantTick) {
                handler.postDelayed(tickRunnable, TICK_INTERVAL_MS);
            } else {
                handler.removeCallbacks(tickRunnable);
            }
            tickScheduled = wantTick;
        }

        if (!wantLocation && !wantGnssStatus && locationManager != null
                && !locationRegistered && !gnssStatusRegistered) {
            locationManager = null;
        }
    }

    @SuppressLint("MissingPermission")
    private void seedLastKnownLocation() {
        if (locationManager == null) return;
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                if (!locationManager.getAllProviders().contains(provider)) continue;
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc != null && (best == null || loc.getTime() > best.getTime())) {
                    best = loc;
                }
            } catch (Throwable ignored) {
            }
        }
        if (best != null) {
            long ageMs = System.currentTimeMillis() - best.getTime();
            if (ageMs >= 0 && ageMs <= FIX_STALE_MS) {
                lastLocation = best;
                lastLocationRealtime = SystemClock.elapsedRealtime() - ageMs;
                Location initial = best;
                handler.post(() -> dispatch(listener -> listener.onGnssLocation(initial)));
            }
        }
    }

    @Nullable
    Location location() {
        return lastLocation;
    }

    @Nullable
    Location freshLocation() {
        long age = fixAgeMs();
        return age >= 0 && age <= FIX_STALE_MS ? lastLocation : null;
    }

    boolean moving() {
        Location fix = freshLocation();
        return fix != null && fix.getSpeed() >= MOVING_SPEED_MPS;
    }

    long fixAgeMs() {
        if (lastLocationRealtime == 0) return -1;
        return SystemClock.elapsedRealtime() - lastLocationRealtime;
    }

    private boolean isExternalShareFresh() {
        return shareReceived && SystemClock.uptimeMillis() - shareTimestamp < SHARE_TIMEOUT_MS;
    }

    private boolean isNativeStatusFresh() {
        return nativeSatellites >= 0
                && SystemClock.uptimeMillis() - nativeStatusTimestamp < NATIVE_STATUS_TIMEOUT_MS;
    }

    /**
     * Whether there is satellite/status data fresh enough to render — either from the external
     * gnss-share app or directly from the head unit's native GNSS receiver.
     */
    boolean shareHasData() {
        return isExternalShareFresh() || isNativeStatusFresh();
    }

    /** Satellite count from gnss-share if active, otherwise from native GnssStatus. */
    int shareSatellites() {
        if (isExternalShareFresh()) return shareCount;
        if (isNativeStatusFresh()) return nativeSatellites;
        return -1;
    }

    /** Satellites used in the fix from gnss-share if active, otherwise from native GnssStatus. */
    int shareUsedInFix() {
        if (isExternalShareFresh()) return shareUsedInFix;
        if (isNativeStatusFresh()) return nativeUsedInFix;
        return -1;
    }

    boolean deadReckoning() {
        return isExternalShareFresh() && (shareMode & MODE_DR) != 0;
    }

    boolean spoofDetected() {
        return isExternalShareFresh() && (shareMode & MODE_SPOOF) != 0;
    }

    boolean roadAnchored() {
        return isExternalShareFresh()
                && (shareMode & (MODE_ROAD_KNOWN | MODE_ON_ROAD)) == (MODE_ROAD_KNOWN | MODE_ON_ROAD);
    }

    private void clearShareData() {
        shareReceived = false;
        shareCount = -1;
        shareUsedInFix = -1;
        shareMode = 0;
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            dispatch(Listener::onGnssTick);
            handler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    private final Runnable shareExpiryRunnable = () -> {
        clearShareData();
        dispatch(Listener::onGnssShareStatus);
    };

    private final BroadcastReceiver shareReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            shareCount = intent.getIntExtra(EXTRA_SATELLITES_COUNT, -1);
            shareUsedInFix = intent.getIntExtra(EXTRA_USED_IN_FIX, -1);
            shareMode = intent.getIntExtra(EXTRA_MODE, 0);
            Log.d(TAG, "GNSS Share satellites count: " + shareCount
                    + ", used in fix: " + shareUsedInFix + ", mode: " + shareMode);
            shareReceived = true;
            shareTimestamp = SystemClock.uptimeMillis();
            handler.removeCallbacks(shareExpiryRunnable);
            handler.postDelayed(shareExpiryRunnable, SHARE_TIMEOUT_MS);
            dispatch(Listener::onGnssShareStatus);
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            Log.d(TAG, "GNSS is started");
            dispatch(Listener::onGnssStarted);
        }

        @Override
        public void onStopped() {
            Log.d(TAG, "GNSS is stopped");
            nativeSatellites = -1;
            nativeUsedInFix = -1;
            nativeStatusTimestamp = 0;
            dispatch(Listener::onGnssStopped);
            dispatch(Listener::onGnssShareStatus);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            Log.d(TAG, "GNSS has first fix");
            dispatch(listener -> listener.onGnssFirstFix(ttffMillis));
        }

        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int total = status.getSatelliteCount();
            int used = 0;
            for (int i = 0; i < total; i++) {
                if (status.usedInFix(i)) {
                    used++;
                }
            }
            nativeSatellites = total;
            nativeUsedInFix = used;
            nativeStatusTimestamp = SystemClock.uptimeMillis();
            if (!isExternalShareFresh()) {
                dispatch(Listener::onGnssShareStatus);
            }
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            Log.d(TAG, "Location changed: " + location);
            lastLocation = location;
            lastLocationRealtime = SystemClock.elapsedRealtime();
            dispatch(listener -> listener.onGnssLocation(location));
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, Bundle extras) {
            Log.d(TAG, "Provider " + provider + " status: " + status);
        }
    };
}
