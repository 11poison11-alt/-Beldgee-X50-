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

/**
 * One quantity a vehicle can report, in a vendor-neutral unit.
 */
public enum CarMetric {

    /** Cabin air temperature, °C. */
    CABIN_TEMPERATURE_C,

    /** Outside air temperature, °C. */
    AMBIENT_TEMPERATURE_C,

    /** Fuel in the tank, percent of its capacity, 0..100. */
    FUEL_PERCENT,

    /** Distance the remaining fuel is predicted to cover, km. */
    FUEL_RANGE_KM,

    /** Low-voltage (12 V) system supply, volts — the starter battery, not a traction pack. */
    BATTERY_VOLTAGE_V,

    /**
     * Usable fuel-tank capacity, liters. A vehicle constant rather than a reading: it is
     * delivered once, when the integration learns it, and never changes afterwards.
     */
    FUEL_TANK_CAPACITY_L,

    // -------------------------------------------------------------------------
    // Belgee X50 / Geely Coolray (SX11) VHAL metrics
    // -------------------------------------------------------------------------

    /** Vehicle speed (291504647), km/h. */
    SPEED_KMH,

    /** Total odometer mileage (289407492), km. */
    ODOMETER_KM,

    /** Gearbox selector state (289408001): 1=N, 2=R, 4=P, 8=D, >=16=D1..D7. */
    GEAR_STATE,

    /** Engine coolant/oil temperature (557875223), °C. */
    ENGINE_TEMP_C,

    /** Drive mode ID (557850837): 570491137=ECO, 138=COMFORT, 139=SPORT, 201=ADAPTIVE. */
    DRIVE_MODE,

    /** Engine state (557875214): 1.0f = running (2102788), 0.0f = stopped (2102785). */
    ENGINE_RUNNING,

    /** Front-left tire pressure (557884502), kPa. */
    TYRE_PRESSURE_FL_KPA,

    /** Front-right tire pressure (557884503), kPa. */
    TYRE_PRESSURE_FR_KPA,

    /** Rear-left tire pressure (557884504), kPa. */
    TYRE_PRESSURE_RL_KPA,

    /** Rear-right tire pressure (557884505), kPa. */
    TYRE_PRESSURE_RR_KPA,

    /** Front-left tire temperature (557884512), °C. */
    TYRE_TEMP_FL_C,

    /** Front-right tire temperature (557884513), °C. */
    TYRE_TEMP_FR_C,

    /** Rear-left tire temperature (557884514), °C. */
    TYRE_TEMP_RL_C,

    /** Rear-right tire temperature (557884515), °C. */
    TYRE_TEMP_RR_C,

    /** Remaining distance until maintenance (557884772), km. */
    MAINTENANCE_KM,

    /** Remaining days until maintenance (557885043), days. */
    MAINTENANCE_DAYS,

    /** Cumulative trip distance (658548345, area=1), km. */
    TRIP_DIST_KM,

    /** Cumulative trip duration (658548346, area=1), minutes. */
    TRIP_TIME_MIN,

    /** Cumulative trip average speed (658547480, area=1), km/h. */
    TRIP_AVG_SPEED_KMH,

    /** Cumulative trip average fuel consumption (658548325, area=1), L/100km. */
    TRIP_AVG_CONS_L100
}
