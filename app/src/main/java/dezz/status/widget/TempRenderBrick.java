package dezz.status.widget;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumMap;

import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarMetric;

final class TempRenderBrick extends CarTextRenderBrick {

    TempRenderBrick(@NonNull WidgetHost host, @NonNull BrickType type,
                    @NonNull Preferences.TempBrickPrefs prefs, @IdRes int viewId,
                    @NonNull int... iconDrawables) {
        super(host, type, prefs, viewId, iconDrawables);
    }

    @NonNull
    private String prefix() {
        if (type == BrickType.INDOOR_TEMP) return "Салон ";
        if (type == BrickType.OUTDOOR_TEMP) return "Улица ";
        return "";
    }

    @NonNull
    @Override
    protected String placeholder() {
        for (CarMetric metric : type.requiredCarMetrics()) {
            String diag = CarIntegrations.get(host.context()).getDiagnosticStatus(metric);
            if (diag != null && !diag.isEmpty()) {
                return prefix() + diag;
            }
        }
        return prefix() + "--°";
    }

    @Nullable
    @Override
    protected String format(@NonNull EnumMap<CarMetric, Float> latest) {
        for (CarMetric metric : type.requiredCarMetrics()) {
            Float celsius = latest.get(metric);
            if (celsius != null && !Float.isNaN(celsius)) {
                return prefix() + Math.round(celsius) + "°";
            }
        }
        return null;
    }
}