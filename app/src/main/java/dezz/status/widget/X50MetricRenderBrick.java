package dezz.status.widget;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumMap;
import java.util.Locale;

import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarMetric;

final class X50MetricRenderBrick extends CarTextRenderBrick {

    private final CarMetric metric;

    X50MetricRenderBrick(@NonNull WidgetHost host,
                         @NonNull BrickType brickType,
                         @NonNull Preferences.CarTextBrickPrefs prefs,
                         int viewId,
                         @DrawableRes int iconOutlineRes,
                         @DrawableRes int iconFilledRes,
                         @DrawableRes int iconBarsRes,
                         @NonNull CarMetric metric) {
        super(host, brickType, prefs, viewId, iconOutlineRes, iconFilledRes, iconBarsRes);
        this.metric = metric;
    }

    @NonNull
    @Override
    protected ReadingState readingState(@NonNull EnumMap<CarMetric, Float> latest) {
        Float raw = latest.get(metric);
        if (raw == null || Float.isNaN(raw)) {
            return ReadingState.NO_DATA;
        }
        float val = raw;
        switch (metric) {
            case ENGINE_TEMP_C:
                if (val >= 115f) return ReadingState.CRITICAL;
                if (val >= 105f) return ReadingState.WARNING;
                return ReadingState.OK;

            case TYRE_PRESSURE_FL_KPA:
            case TYRE_PRESSURE_FR_KPA:
            case TYRE_PRESSURE_RL_KPA:
            case TYRE_PRESSURE_RR_KPA:
                if (val < 180f || val > 300f) return ReadingState.CRITICAL;
                if (val < 200f || val > 270f) return ReadingState.WARNING;
                return ReadingState.OK;

            case TYRE_TEMP_FL_C:
            case TYRE_TEMP_FR_C:
            case TYRE_TEMP_RL_C:
            case TYRE_TEMP_RR_C:
                if (val >= 85f) return ReadingState.CRITICAL;
                if (val >= 70f) return ReadingState.WARNING;
                return ReadingState.OK;

            case MAINTENANCE_KM:
                if (val <= 500f) return ReadingState.CRITICAL;
                if (val <= 1500f) return ReadingState.WARNING;
                return ReadingState.OK;

            case MAINTENANCE_DAYS:
                if (val <= 7f) return ReadingState.CRITICAL;
                if (val <= 30f) return ReadingState.WARNING;
                return ReadingState.OK;

            default:
                return ReadingState.OK;
        }
    }

    @NonNull
    @Override
    protected String placeholder() {
        String diag = CarIntegrations.get(host.context()).getDiagnosticStatus(metric);
        if (diag != null && !diag.isEmpty()) {
            return diag;
        }
        switch (metric) {
            case SPEED_KMH:
            case TRIP_AVG_SPEED_KMH:
                return "--\u00A0км/ч";
            case ODOMETER_KM:
            case MAINTENANCE_KM:
            case TRIP_DIST_KM:
                return "--\u00A0км";
            case ENGINE_TEMP_C:
            case TYRE_TEMP_FL_C:
            case TYRE_TEMP_FR_C:
            case TYRE_TEMP_RL_C:
            case TYRE_TEMP_RR_C:
                return "--\u00A0°C";
            case TYRE_PRESSURE_FL_KPA:
            case TYRE_PRESSURE_FR_KPA:
            case TYRE_PRESSURE_RL_KPA:
            case TYRE_PRESSURE_RR_KPA:
                return "--\u00A0кПа";
            case MAINTENANCE_DAYS:
                return "--\u00A0дн.";
            case TRIP_TIME_MIN:
                return "--\u00A0мин";
            case TRIP_AVG_CONS_L100:
                return "--\u00A0л/100";
            default:
                return "--";
        }
    }

    @Nullable
    @Override
    protected String format(@NonNull EnumMap<CarMetric, Float> latest) {
        Float raw = latest.get(metric);
        if (raw == null || Float.isNaN(raw)) {
            return null;
        }
        float val = raw;
        switch (metric) {
            case SPEED_KMH:
            case TRIP_AVG_SPEED_KMH:
                return Math.round(val) + "\u00A0км/ч";

            case ODOMETER_KM:
            case MAINTENANCE_KM:
                return Math.round(val) + "\u00A0км";

            case TRIP_DIST_KM:
                return String.format(Locale.US, "%.1f\u00A0км", val);

            case ENGINE_TEMP_C:
            case TYRE_TEMP_FL_C:
            case TYRE_TEMP_FR_C:
            case TYRE_TEMP_RL_C:
            case TYRE_TEMP_RR_C:
                return Math.round(val) + "\u00A0°C";

            case TYRE_PRESSURE_FL_KPA:
            case TYRE_PRESSURE_FR_KPA:
            case TYRE_PRESSURE_RL_KPA:
            case TYRE_PRESSURE_RR_KPA:
                return Math.round(val) + "\u00A0кПа";

            case MAINTENANCE_DAYS:
                return Math.round(val) + "\u00A0дн.";

            case TRIP_TIME_MIN:
                return Math.round(val) + "\u00A0мин";

            case TRIP_AVG_CONS_L100:
                return String.format(Locale.US, "%.1f\u00A0л/100", val);

            case GEAR_STATE:
                return formatGear(Math.round(val));

            case DRIVE_MODE:
                return formatDriveMode(Math.round(val));

            case ENGINE_RUNNING:
                return val >= 0.5f ? "ВКЛ" : "ВЫКЛ";

            default:
                return String.valueOf(Math.round(val));
        }
    }

    @NonNull
    private static String formatGear(int gear) {
        switch (gear) {
            case 1: return "N";
            case 2: return "R";
            case 4: return "P";
            case 8: return "D";
            default:
                if (gear >= 16 && gear <= 23) {
                    return "D" + (gear - 15);
                }
                return "P";
        }
    }

    @NonNull
    private static String formatDriveMode(int mode) {
        switch (mode) {
            case 570491137:
            case 137:
                return "ЭКО";
            case 570491138:
            case 138:
                return "КОМФОРТ";
            case 570491139:
            case 139:
                return "СПОРТ";
            case 570491201:
            case 201:
                return "АДАПТИВ";
            default:
                return "РЕЖ " + mode;
        }
    }
}