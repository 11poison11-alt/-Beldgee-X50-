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
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * Wi-Fi status icon adapted for Belgee X50 / Geely Coolray (Android 9 GKUI/E01).
 * Uses ConnectivityManager (including legacy NetworkInfo and active network bridge),
 * WifiManager state/broadcasts, and direct reachability probing without requiring GPS/Location.
 */
final class WifiRenderBrick extends IconRenderBrick {

    private static final String TAG = "WifiRenderBrick";
    private static final long PROBE_INTERVAL_MS = 15_000L;

    enum State {
        OFF, NO_INTERNET, LIMITED_INTERNET, INTERNET
    }

    private static final int[][] DESIGNS = {
            {   // classic
                    R.drawable.ic_status_wifi_off,
                    R.drawable.ic_status_wifi_no_internet,
                    R.drawable.ic_status_wifi_whitelist,
                    R.drawable.ic_status_wifi_internet
            },
            {   // solid
                    R.drawable.ic_status_filled_wifi_off,
                    R.drawable.ic_status_filled_wifi_no_internet,
                    R.drawable.ic_status_filled_wifi_whitelist,
                    R.drawable.ic_status_filled_wifi_internet
            },
            {   // bars
                    R.drawable.ic_status_bars_wifi_off,
                    R.drawable.ic_status_bars_wifi_no_internet,
                    R.drawable.ic_status_bars_wifi_whitelist,
                    R.drawable.ic_status_bars_wifi_internet
            }
    };

    private static final int[] STATE_COLORS = {
            R.color.status_off,
            R.color.status_error,
            R.color.status_warning,
            R.color.status_ok
    };

    private State state = State.OFF;
    @Nullable
    private ConnectivityManager connectivityManager;
    @Nullable
    private WifiManager wifiManager;
    @Nullable
    private ReachabilityChecker reachabilityChecker;
    private boolean pollingActive = false;
    private boolean receiverRegistered = false;

    WifiRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.WIFI, host.prefs().wifi);
    }

    @NonNull
    @Override
    protected OutlineImageView iconOf(@NonNull OverlayStatusWidgetBinding binding) {
        return binding.wifiStatusIcon;
    }

    @NonNull
    @Override
    protected int[][] designs() {
        return DESIGNS;
    }

    @NonNull
    @Override
    protected int[] stateColors() {
        return STATE_COLORS;
    }

    @Override
    protected int stateOrdinal() {
        return state.ordinal();
    }

    @Nullable
    @Override
    protected Drawable badgeDrawable(int stateIdx) {
        if (stateIdx != State.LIMITED_INTERNET.ordinal()) return null;
        Drawable flag = ContextCompat.getDrawable(host.context(), R.drawable.ic_badge_ru_flag);
        return flag != null ? flag.mutate() : null;
    }

    @Override
    void syncSource(boolean active) {
        if (active) {
            Context appCtx = host.context().getApplicationContext();
            if (wifiManager == null) {
                wifiManager = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
            }
            if (!receiverRegistered) {
                try {
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                    filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                    filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
                    appCtx.registerReceiver(wifiReceiver, filter);
                    receiverRegistered = true;
                } catch (Throwable t) {
                    Log.w(TAG, "registerReceiver failed", t);
                }
            }
            if (connectivityManager == null) {
                connectivityManager = host.context().getSystemService(ConnectivityManager.class);
                if (connectivityManager != null) {
                    try {
                        NetworkRequest request = new NetworkRequest.Builder()
                                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                .build();
                        connectivityManager.registerNetworkCallback(request, networkCallback, host.handler());
                    } catch (Throwable t) {
                        Log.w(TAG, "registerNetworkCallback failed", t);
                    }
                }
            }
            if (!pollingActive) {
                pollingActive = true;
                checkWifiStateAndProbe();
                host.handler().postDelayed(probeRunnable, PROBE_INTERVAL_MS);
            }
            refreshContent();
        } else {
            stopMonitoring();
        }
    }

    @Override
    void onDestroy() {
        stopMonitoring();
        if (reachabilityChecker != null) {
            reachabilityChecker.shutdown();
            reachabilityChecker = null;
        }
    }

    private void stopMonitoring() {
        pollingActive = false;
        host.handler().removeCallbacks(probeRunnable);
        if (receiverRegistered) {
            try {
                host.context().getApplicationContext().unregisterReceiver(wifiReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        if (connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {
            }
            connectivityManager = null;
        }
    }

    private void setState(State newState) {
        if (state == newState) return;
        state = newState;
        refreshContent();
    }

    @SuppressLint({"MissingPermission", "Deprecation"})
    private boolean isNetworkActive() {
        boolean wifiAdapterEnabled = false;
        if (wifiManager != null) {
            try {
                wifiAdapterEnabled = wifiManager.isWifiEnabled();
                if (wifiAdapterEnabled) {
                    WifiInfo info = wifiManager.getConnectionInfo();
                    if (info != null) {
                        if (info.getNetworkId() != -1 || info.getIpAddress() != 0) {
                            return true;
                        }
                        SupplicantState supplicant = info.getSupplicantState();
                        if (supplicant == SupplicantState.COMPLETED
                                || supplicant == SupplicantState.ASSOCIATED) {
                            return true;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "WifiManager check failed", t);
            }
        }

        if (connectivityManager != null) {
            try {
                Network activeNet = connectivityManager.getActiveNetwork();
                if (activeNet != null) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNet);
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                            || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))) {
                        return true;
                    }
                }
                for (Network net : connectivityManager.getAllNetworks()) {
                    NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
                    if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || (wifiAdapterEnabled && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)))) {
                        return true;
                    }
                }
                NetworkInfo activeInfo = connectivityManager.getActiveNetworkInfo();
                if (activeInfo != null && activeInfo.isConnected()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void checkWifiStateAndProbe() {
        if (!isNetworkActive()) {
            setState(State.OFF);
            return;
        }
        if (state == State.OFF) {
            setState(State.NO_INTERNET);
        }
        probeReachability();
    }

    private void probeReachability() {
        if (reachabilityChecker == null) {
            reachabilityChecker = new ReachabilityChecker(host.handler());
        }
        reachabilityChecker.check(reach -> {
            if (!isNetworkActive()) {
                setState(State.OFF);
                return;
            }
            switch (reach) {
                case FULL -> setState(State.INTERNET);
                case WHITELIST -> setState(State.LIMITED_INTERNET);
                case NONE -> setState(State.NO_INTERNET);
            }
        });
    }

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            host.handler().post(() -> checkWifiStateAndProbe());
        }
    };

    private final Runnable probeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollingActive) return;
            checkWifiStateAndProbe();
            host.handler().postDelayed(this, PROBE_INTERVAL_MS);
        }
    };

    @SuppressLint("MissingPermission")
    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    if (state == State.OFF) {
                        setState(State.NO_INTERNET);
                    }
                    host.handler().post(() -> probeReachability());
                }

                @Override
                public void onLost(@NonNull Network network) {
                    host.handler().post(() -> {
                        if (!isNetworkActive()) {
                            setState(State.OFF);
                        }
                    });
                }

                @Override
                public void onCapabilitiesChanged(@NonNull Network network,
                                                  @NonNull NetworkCapabilities capabilities) {
                    host.handler().post(() -> probeReachability());
                }
            };
}
