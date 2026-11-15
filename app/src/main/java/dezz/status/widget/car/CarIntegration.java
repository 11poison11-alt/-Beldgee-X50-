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

import java.util.Set;

public interface CarIntegration {

    interface Listener {
        void onCarValue(@NonNull CarMetric metric, float value);
    }

    boolean isMetricSupported(@NonNull CarMetric metric);

    default boolean areMetricsSupported(@NonNull Set<CarMetric> metrics) {
        for (CarMetric m : metrics) {
            if (!isMetricSupported(m)) return false;
        }
        return true;
    }

    default void addAvailabilityListener(@NonNull Runnable listener) {
    }

    default void removeAvailabilityListener(@NonNull Runnable listener) {
    }

    void setNeeds(@NonNull Listener listener, @NonNull Set<CarMetric> needs);

    @Nullable
    default String getDiagnosticStatus(@NonNull CarMetric metric) {
        return null;
    }

    default void shutdown() {
    }
}
