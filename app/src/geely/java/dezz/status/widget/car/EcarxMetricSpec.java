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

package dezz.status.widget.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ecarx.xui.adaptapi.car.base.ICarInfo;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;
import com.ecarx.xui.adaptapi.car.sensor.IVirtualSensor;

/**
 * Metric source definitions tailored for Belgee X50 / Geely Coolray (SX11) head units.
 */
final class EcarxMetricSpec {

    enum Transport {
        SENSOR,
        SIGNAL,
        CAR_INFO,
        VHAL_ONLY
    }

    enum VhalType {
        NONE,
        BYTES,
        INT,
        FLOAT
    }

    interface FloatDecoder {
        float decode(float raw);
    }

    interface IntDecoder {
        float decode(int raw);
    }

    interface BytesDecoder {
        float decode(@Nullable byte[] bytes);
    }

    static final int NO_VHAL_PROPERTY = 0;

    @NonNull final Transport transport;
    @NonNull final int[] ids;
    final boolean supportApiMeaningful;
    @Nullable final FloatDecoder decoder;

    @NonNull final VhalType vhalType;
    final int vhalProperty;
    final int vhalAreaId;
    @Nullable final BytesDecoder vhalBytesDecoder;
    @Nullable final IntDecoder vhalIntDecoder;
    @Nullable final FloatDecoder vhalFloatDecoder;

    boolean hasVhalProperty() {
        return vhalProperty != NO_VHAL_PROPERTY && vhalType != VhalType.NONE;
    }

    private EcarxMetricSpec(@NonNull Transport transport, @NonNull int[] ids,
                            boolean supportApiMeaningful, @Nullable FloatDecoder decoder) {
        this.transport = transport;
        this.ids = ids;
        this.supportApiMeaningful = supportApiMeaningful;
        this.decoder = decoder;
        this.vhalType = VhalType.NONE;
        this.vhalProperty = NO_VHAL_PROPERTY;
        this.vhalAreaId = 0;
        this.vhalBytesDecoder = null;
        this.vhalIntDecoder = null;
        this.vhalFloatDecoder = null;
    }

    private EcarxMetricSpec(@NonNull Transport transport, @NonNull int[] ids,
                            boolean supportApiMeaningful, @Nullable FloatDecoder decoder,
                            @NonNull VhalType vhalType, int vhalProperty, int vhalAreaId,
                            @Nullable BytesDecoder bDec, @Nullable IntDecoder iDec,
                            @Nullable FloatDecoder fDec) {
        this.transport = transport;
        this.ids = ids;
        this.supportApiMeaningful = supportApiMeaningful;
        this.decoder = decoder;
        this.vhalType = vhalType;
        this.vhalProperty = vhalProperty;
        this.vhalAreaId = vhalAreaId;
        this.vhalBytesDecoder = bDec;
        this.vhalIntDecoder = iDec;
        this.vhalFloatDecoder = fDec;
    }

    private static EcarxMetricSpec vhalInt(int propId, int areaId, @NonNull IntDecoder decoder) {
        return new EcarxMetricSpec(Transport.VHAL_ONLY, new int[0], false, null,
                VhalType.INT, propId, areaId, null, decoder, null);
    }

    private static EcarxMetricSpec vhalFloat(int propId, int areaId, @NonNull FloatDecoder decoder) {
        return new EcarxMetricSpec(Transport.VHAL_ONLY, new int[0], false, null,
                VhalType.FLOAT, propId, areaId, null, null, decoder);
    }

    // --- Base metrics (with X50 VHAL primary/fallback sources, no duplicate CarMetric entries) ---

    private static final EcarxMetricSpec CABIN_TEMPERATURE = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{ISensor.SENSOR_TYPE_TEMPERATURE_INDOOR}, true, EcarxReadings::temperature);

    private static final EcarxMetricSpec AMBIENT_TEMPERATURE = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{ISensor.SENSOR_TYPE_TEMPERATURE_AMBIENT}, true, EcarxReadings::temperature);

    /** Fuel level %: tries AdaptAPI sensor first, falls back to X50 VHAL 557884777 (0x2140A569). */
    private static final EcarxMetricSpec FUEL_PERCENT = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{IVirtualSensor.TYPE_FUEL_PERCENTAGE}, false, EcarxReadings::fuelPercent,
            VhalType.INT, 557884777, 0,
            null, raw -> (raw >= 0 && raw <= 100) ? (float) raw : Float.NaN, null);

    /** Remaining fuel range km: tries AdaptAPI sensor first, falls back to X50 VHAL 289407752 (0x11400308). */
    private static final EcarxMetricSpec FUEL_RANGE = new EcarxMetricSpec(Transport.SENSOR,
            new int[]{ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE_FUEL, ISensor.SENSOR_TYPE_ENDURANCE_MILEAGE},
            false, EcarxReadings::rangeKm,
            VhalType.FLOAT, 289407752, 0,
            null, null, val -> (!Float.isNaN(val) && val >= 0f) ? val : Float.NaN);

    /** 12 V system voltage: reads directly from X50 VHAL 557875206 (0x21408006, raw / 10.0f). */
    private static final EcarxMetricSpec BATTERY_VOLTAGE = vhalInt(557875206, 0,
            raw -> raw > 0 ? (raw / 10.0f) : Float.NaN);

    private static final EcarxMetricSpec TANK_CAPACITY = new EcarxMetricSpec(Transport.CAR_INFO,
            new int[]{ICarInfo.FLT_INFO_FUEL_CAPACITY}, false, EcarxReadings::tankLiters);

    // --- Belgee X50 / Geely Coolray (SX11) VHAL metrics ---

    private static final EcarxMetricSpec SPEED_KMH = vhalFloat(291504647, 0,
            val -> (!Float.isNaN(val) && val >= 0f) ? val : Float.NaN);

    private static final EcarxMetricSpec ODOMETER_KM = vhalFloat(289407492, 0,
            val -> (!Float.isNaN(val) && val >= 0f) ? val : Float.NaN);

    private static final EcarxMetricSpec GEAR_STATE = vhalInt(289408001, 0,
            raw -> (float) raw);

    private static final EcarxMetricSpec ENGINE_TEMP_C = vhalInt(557875223, 0,
            raw -> (raw * 0.75f) - 48.0f);

    private static final EcarxMetricSpec DRIVE_MODE = vhalInt(557850837, 0,
            raw -> raw != 255 ? (float) raw : Float.NaN);

    private static final EcarxMetricSpec ENGINE_RUNNING = vhalInt(557875214, 0,
            raw -> raw == 2102788 ? 1.0f : (raw == 2102785 ? 0.0f : Float.NaN));

    private static final FloatDecoder TYRE_PRESS_DEC =
            val -> (!Float.isNaN(val) && val > 0f) ? val : Float.NaN;
    private static final IntDecoder TYRE_TEMP_DEC =
            raw -> (raw > -60 && raw < 300) ? Math.round((raw - 32.0f) / 1.8f) : Float.NaN;

    private static final EcarxMetricSpec TYRE_PRESSURE_FL = vhalFloat(557884502, 0, TYRE_PRESS_DEC);
    private static final EcarxMetricSpec TYRE_PRESSURE_FR = vhalFloat(557884503, 0, TYRE_PRESS_DEC);
    private static final EcarxMetricSpec TYRE_PRESSURE_RL = vhalFloat(557884504, 0, TYRE_PRESS_DEC);
    private static final EcarxMetricSpec TYRE_PRESSURE_RR = vhalFloat(557884505, 0, TYRE_PRESS_DEC);

    private static final EcarxMetricSpec TYRE_TEMP_FL = vhalInt(557884512, 0, TYRE_TEMP_DEC);
    private static final EcarxMetricSpec TYRE_TEMP_FR = vhalInt(557884513, 0, TYRE_TEMP_DEC);
    private static final EcarxMetricSpec TYRE_TEMP_RL = vhalInt(557884514, 0, TYRE_TEMP_DEC);
    private static final EcarxMetricSpec TYRE_TEMP_RR = vhalInt(557884515, 0, TYRE_TEMP_DEC);

    private static final EcarxMetricSpec MAINTENANCE_KM = vhalInt(557884772, 0,
            raw -> raw >= 0 ? (float) raw : Float.NaN);
    private static final EcarxMetricSpec MAINTENANCE_DAYS = vhalInt(557885043, 0,
            raw -> raw >= 0 ? (float) raw : Float.NaN);

    private static final EcarxMetricSpec TRIP_DIST_KM = vhalFloat(658548345, 1,
            val -> (!Float.isNaN(val) && val >= 0f) ? val : Float.NaN);
    private static final EcarxMetricSpec TRIP_TIME_MIN = vhalInt(658548346, 1,
            raw -> raw >= 0 ? (float) raw : Float.NaN);
    private static final EcarxMetricSpec TRIP_AVG_SPEED_KMH = vhalFloat(658547480, 1,
            val -> (!Float.isNaN(val) && val >= 0f) ? val : Float.NaN);
    private static final EcarxMetricSpec TRIP_AVG_CONS_L100 = vhalFloat(658548325, 1,
            val -> (!Float.isNaN(val) && val >= 0f) ? val : Float.NaN);

    @Nullable
    static EcarxMetricSpec of(@NonNull CarMetric metric) {
        switch (metric) {
            case CABIN_TEMPERATURE_C: return CABIN_TEMPERATURE;
            case AMBIENT_TEMPERATURE_C: return AMBIENT_TEMPERATURE;
            case FUEL_PERCENT: return FUEL_PERCENT;
            case FUEL_RANGE_KM: return FUEL_RANGE;
            case BATTERY_VOLTAGE_V: return BATTERY_VOLTAGE;
            case FUEL_TANK_CAPACITY_L: return TANK_CAPACITY;
            case SPEED_KMH: return SPEED_KMH;
            case ODOMETER_KM: return ODOMETER_KM;
            case GEAR_STATE: return GEAR_STATE;
            case ENGINE_TEMP_C: return ENGINE_TEMP_C;
            case DRIVE_MODE: return DRIVE_MODE;
            case ENGINE_RUNNING: return ENGINE_RUNNING;
            case TYRE_PRESSURE_FL_KPA: return TYRE_PRESSURE_FL;
            case TYRE_PRESSURE_FR_KPA: return TYRE_PRESSURE_FR;
            case TYRE_PRESSURE_RL_KPA: return TYRE_PRESSURE_RL;
            case TYRE_PRESSURE_RR_KPA: return TYRE_PRESSURE_RR;
            case TYRE_TEMP_FL_C: return TYRE_TEMP_FL;
            case TYRE_TEMP_FR_C: return TYRE_TEMP_FR;
            case TYRE_TEMP_RL_C: return TYRE_TEMP_RL;
            case TYRE_TEMP_RR_C: return TYRE_TEMP_RR;
            case MAINTENANCE_KM: return MAINTENANCE_KM;
            case MAINTENANCE_DAYS: return MAINTENANCE_DAYS;
            case TRIP_DIST_KM: return TRIP_DIST_KM;
            case TRIP_TIME_MIN: return TRIP_TIME_MIN;
            case TRIP_AVG_SPEED_KMH: return TRIP_AVG_SPEED_KMH;
            case TRIP_AVG_CONS_L100: return TRIP_AVG_CONS_L100;
            default: return null;
        }
    }
}
