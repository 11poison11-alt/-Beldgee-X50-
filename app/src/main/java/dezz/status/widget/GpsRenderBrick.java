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

import android.content.Context;
import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import dezz.status.widget.databinding.OverlayStatusWidgetBinding;

/**
 * GNSS status icon adapted for Belgee X50 / Geely Coolray (Android 9).
 * Reads the shared {@link GnssProvider} — fixes (including those without accuracy metadata)
 * and satellite counts from either GNSS Share or the head unit's native GNSS receiver.
 */
final class GpsRenderBrick extends IconRenderBrick implements GnssProvider.Listener {

    private static final float GOOD_ACCURACY_METERS = 35.0f;

    enum State {
        OFF, BAD, GOOD
    }

    private static final int[][] DESIGNS = {
            { R.drawable.ic_status_gps_off, R.drawable.ic_status_gps_bad, R.drawable.ic_status_gps_good },
            { R.drawable.ic_status_filled_gps_off, R.drawable.ic_status_filled_gps_bad, R.drawable.ic_status_filled_gps_good },
            { R.drawable.ic_status_bars_gps_off, R.drawable.ic_status_bars_gps_bad, R.drawable.ic_status_bars_gps_good }
    };

    private static final int[] STATE_COLORS = {
            R.color.status_off,
            R.color.status_warning,
            R.color.status_ok
    };

    private final Preferences.GpsBrickPrefs gpsPrefs;

    private State state = State.OFF;
    private long lastLocationUpdateTime = 0;

    GpsRenderBrick(@NonNull WidgetHost host) {
        super(host, BrickType.GPS, host.prefs().gps);
        this.gpsPrefs = host.prefs().gps;
    }

    @NonNull
    @Override
    protected OutlineImageView iconOf(@NonNull OverlayStatusWidgetBinding binding) {
        return binding.gnssStatusIcon;
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
    protected IconRenderBrick.TextBadge textBadge(int stateIdx, int styleBg) {
        if (!gpsPrefs.showSatelliteBadge.get()) return null;
        if (!host.gnss().shareHasData()) return null;
        boolean deadReckoning = host.gnss().deadReckoning();
        boolean spoofDetected = host.gnss().spoofDetected();
        String text;
        if (deadReckoning) {
            text = host.context().getString(spoofDetected
                    ? R.string.gnss_dr_spoof_badge : R.string.gnss_dr_badge);
        } else if (spoofDetected) {
            text = host.context().getString(R.string.gnss_spoof_badge);
        } else if (host.gnss().shareSatellites() > 0) {
            text = String.valueOf(host.gnss().shareSatellites());
        } else {
            return null;
        }
        Context ctx = host.themed();
        int ring = host.gnss().roadAnchored()
                ? ContextCompat.getColor(ctx, R.color.status_ok)
                : 0;
        if (spoofDetected) {
            return new TextBadge(text, ContextCompat.getColor(ctx, R.color.status_error),
                    ContextCompat.getColor(ctx, R.color.status_badge_text), ring);
        }
        if (deadReckoning) {
            return new TextBadge(text, ContextCompat.getColor(ctx, R.color.status_warning),
                    ContextCompat.getColor(ctx, R.color.status_badge_text), ring);
        }
        return new TextBadge(text, styleBg, defaultBadgeForeground(), ring);
    }

    @Override
    void syncSource(boolean active) {
        int needs = GnssProvider.NEED_NONE;
        if (active) {
            needs = GnssProvider.NEED_LOCATION | GnssProvider.NEED_GNSS_STATUS;
            if (gpsPrefs.showSatelliteBadge.get()) {
                needs |= GnssProvider.NEED_SHARE;
            }
        }
        host.gnss().setNeeds(this, needs);
        if (active) {
            Location fresh = host.gnss().freshLocation();
            if (fresh != null) {
                onGnssLocation(fresh);
            } else if (host.gnss().shareSatellites() > 0 && state == State.OFF) {
                setState(State.BAD);
            }
            refreshContent();
        }
    }

    @Override
    void onDestroy() {
        host.gnss().setNeeds(this, GnssProvider.NEED_NONE);
    }

    private void setState(State newState) {
        if (state == newState) return;
        state = newState;
        refreshContent();
    }

    // ── GnssProvider.Listener ────────────────────────────────────────────────

    @Override
    public void onGnssLocation(@NonNull Location location) {
        lastLocationUpdateTime = System.currentTimeMillis();
        // On some Belgee X50 / Coolray firmwares the native GNSS driver reports valid fixes
        // with hasAccuracy() == false or accuracy == 0.0f. Treat missing/zero accuracy as GOOD.
        if (!location.hasAccuracy() || location.getAccuracy() <= GOOD_ACCURACY_METERS) {
            setState(State.GOOD);
        } else {
            setState(State.BAD);
        }
    }

    @Override
    public void onGnssStarted() {
        if (state == State.OFF) {
            setState(State.BAD);
        }
    }

    @Override
    public void onGnssStopped() {
        setState(State.OFF);
    }

    @Override
    public void onGnssFirstFix(int ttffMillis) {
        if (state == State.OFF) {
            setState(State.BAD);
        }
    }

    @Override
    public void onGnssTick() {
        long silentFor = System.currentTimeMillis() - lastLocationUpdateTime;
        if (silentFor > GnssProvider.FIX_STALE_MS) {
            if (host.gnss().shareSatellites() > 0) {
                setState(State.BAD);
            } else {
                setState(State.OFF);
            }
        } else if (silentFor > 5000) {
            setState(State.BAD);
        }
    }

    @Override
    public void onGnssShareStatus() {
        if (state == State.OFF && host.gnss().shareSatellites() > 0) {
            setState(State.BAD);
        }
        refreshContent();
    }
}
