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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import dezz.status.widget.car.CarMetric;

/**
 * The set of building blocks the user can place into the widget. Order is preserved as a
 * comma-separated string in {@link Preferences#brickOrder}; missing types are hidden.
 */
public enum BrickType {
    // New constants must be APPENDED — the ordinal doubles as the RecyclerView stable id AND
    // its view type, and the name is persisted in brickOrder / hideSource prefs. Existing
    // constants may gain constructor arguments, but must never be reordered or renamed.
    TIME(R.string.brick_title_time),
    DATE(R.string.brick_title_date),
    MEDIA(R.string.brick_title_media),
    WIFI(R.string.brick_title_wifi),
    GPS(R.string.brick_title_gps),
    BLUETOOTH(R.string.brick_title_bluetooth),
    INDOOR_TEMP(R.string.brick_title_indoor_temp),
    OUTDOOR_TEMP(R.string.brick_title_outdoor_temp),
    GNSS_INFO(R.string.brick_title_gnss_info),
    FUEL_LEVEL(R.string.brick_title_fuel_level),
    FUEL_RANGE(R.string.brick_title_fuel_range),
    BATTERY_VOLTAGE(R.string.brick_title_battery_voltage),

    // Belgee X50 / Geely Coolray (SX11) VHAL bricks
    SPEED(R.string.brick_title_speed),
    ODOMETER(R.string.brick_title_odometer),
    GEAR_STATE(R.string.brick_title_gear),
    ENGINE_TEMP(R.string.brick_title_engine_temp),
    DRIVE_MODE(R.string.brick_title_drive_mode),
    ENGINE_RUNNING(R.string.brick_title_engine_running),
    TYRE_PRESSURE_FL(R.string.brick_title_tyre_pressure_fl),
    TYRE_PRESSURE_FR(R.string.brick_title_tyre_pressure_fr),
    TYRE_PRESSURE_RL(R.string.brick_title_tyre_pressure_rl),
    TYRE_PRESSURE_RR(R.string.brick_title_tyre_pressure_rr),
    TYRE_TEMP_FL(R.string.brick_title_tyre_temp_fl),
    TYRE_TEMP_FR(R.string.brick_title_tyre_temp_fr),
    TYRE_TEMP_RL(R.string.brick_title_tyre_temp_rl),
    TYRE_TEMP_RR(R.string.brick_title_tyre_temp_rr),
    MAINTENANCE_KM(R.string.brick_title_maintenance_km),
    MAINTENANCE_DAYS(R.string.brick_title_maintenance_days),
    TRIP_DIST(R.string.brick_title_trip_dist),
    TRIP_TIME(R.string.brick_title_trip_time),
    TRIP_AVG_SPEED(R.string.brick_title_trip_avg_speed),
    TRIP_AVG_CONS(R.string.brick_title_trip_avg_cons);

    private static final BrickType[] VALUES = values();

    /** Inverse of {@link #ordinal()} without re-cloning the constant array on every call. */
    public static BrickType byOrdinal(int ordinal) {
        return VALUES[ordinal];
    }

    @StringRes
    private final int titleRes;

    BrickType(@StringRes int titleRes) {
        this.titleRes = titleRes;
    }

    /** Human-readable brick name — the one source used by settings, chips and hints. */
    @StringRes
    public int titleRes() {
        return titleRes;
    }

    /**
     * The car metrics this brick cannot render without. Car-specific bricks are fed by the
     * flavor's {@link dezz.status.widget.car.CarIntegration} and are only offered in settings when
     * the vehicle supports every metric listed here. Metrics a brick merely makes use of when
     * present (a tank capacity for a reading in liters) do not belong in this set.
     */
    @NonNull
    public Set<CarMetric> requiredCarMetrics() {
        switch (this) {
            case INDOOR_TEMP:
                return EnumSet.of(CarMetric.CABIN_TEMPERATURE_C);
            case OUTDOOR_TEMP:
                return EnumSet.of(CarMetric.AMBIENT_TEMPERATURE_C);
            case FUEL_LEVEL:
                return EnumSet.of(CarMetric.FUEL_PERCENT);
            case FUEL_RANGE:
                return EnumSet.of(CarMetric.FUEL_RANGE_KM);
            case BATTERY_VOLTAGE:
                return EnumSet.of(CarMetric.BATTERY_VOLTAGE_V);
            case SPEED:
                return EnumSet.of(CarMetric.SPEED_KMH);
            case ODOMETER:
                return EnumSet.of(CarMetric.ODOMETER_KM);
            case GEAR_STATE:
                return EnumSet.of(CarMetric.GEAR_STATE);
            case ENGINE_TEMP:
                return EnumSet.of(CarMetric.ENGINE_TEMP_C);
            case DRIVE_MODE:
                return EnumSet.of(CarMetric.DRIVE_MODE);
            case ENGINE_RUNNING:
                return EnumSet.of(CarMetric.ENGINE_RUNNING);
            case TYRE_PRESSURE_FL:
                return EnumSet.of(CarMetric.TYRE_PRESSURE_FL_KPA);
            case TYRE_PRESSURE_FR:
                return EnumSet.of(CarMetric.TYRE_PRESSURE_FR_KPA);
            case TYRE_PRESSURE_RL:
                return EnumSet.of(CarMetric.TYRE_PRESSURE_RL_KPA);
            case TYRE_PRESSURE_RR:
                return EnumSet.of(CarMetric.TYRE_PRESSURE_RR_KPA);
            case TYRE_TEMP_FL:
                return EnumSet.of(CarMetric.TYRE_TEMP_FL_C);
            case TYRE_TEMP_FR:
                return EnumSet.of(CarMetric.TYRE_TEMP_FR_C);
            case TYRE_TEMP_RL:
                return EnumSet.of(CarMetric.TYRE_TEMP_RL_C);
            case TYRE_TEMP_RR:
                return EnumSet.of(CarMetric.TYRE_TEMP_RR_C);
            case MAINTENANCE_KM:
                return EnumSet.of(CarMetric.MAINTENANCE_KM);
            case MAINTENANCE_DAYS:
                return EnumSet.of(CarMetric.MAINTENANCE_DAYS);
            case TRIP_DIST:
                return EnumSet.of(CarMetric.TRIP_DIST_KM);
            case TRIP_TIME:
                return EnumSet.of(CarMetric.TRIP_TIME_MIN);
            case TRIP_AVG_SPEED:
                return EnumSet.of(CarMetric.TRIP_AVG_SPEED_KMH);
            case TRIP_AVG_CONS:
                return EnumSet.of(CarMetric.TRIP_AVG_CONS_L100);
            default:
                return EnumSet.noneOf(CarMetric.class);
        }
    }

    /** Whether this brick is fed by the car at all. */
    public boolean isCarSpecific() {
        return !requiredCarMetrics().isEmpty();
    }

    @Nullable
    public static BrickType fromName(String name) {
        try {
            return BrickType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static List<BrickType> parseOrder(String csv) {
        List<BrickType> result = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return result;
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            BrickType type = fromName(trimmed);
            if (type != null && !result.contains(type)) {
                result.add(type);
            }
        }
        return result;
    }

    public static String serializeOrder(List<BrickType> bricks) {
        StringBuilder sb = new StringBuilder();
        for (BrickType b : bricks) {
            if (sb.length() > 0) sb.append(',');
            sb.append(b.name());
        }
        return sb.toString();
    }
}
