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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarMetric;
import dezz.status.widget.databinding.OverlayStatusWidgetBinding;
import dezz.status.widget.update.Updater;

public class WidgetService extends Service implements WidgetHost {
    private static final int WIDGET_MODE_FLOATING = 0;
    private static final int WIDGET_MODE_STATUS_BAR = 1;

    private static final int OVERLAY_FADE_DURATION_MS = 500;
    private static final int BRICK_TRANSITION_DURATION_MS = 450;
    private static final int CONTENT_CHANGE_DURATION_MS = 250;
    private static final int BRICK_ALPHA_DURATION_MS = 300;

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long DATETIME_UPDATE_INTERVAL_MS = 60_000L;
    private static final long FOREGROUND_APP_CHECK_INTERVAL_MS = 1000L;
    private static final long FOREGROUND_APP_LOOKBACK_MS = 60_000L;
    private static final String GNSSSHARE_CLIENT_PACKAGE = "dezz.gnssshare.client";
    private static final long FIRST_UPDATE_CHECK_DELAY_MS = TimeUnit.MINUTES.toMillis(2);

    private static WidgetService instance;

    private Preferences prefs;

    private final EnumMap<BrickType, RenderBrick> renderBricks = new EnumMap<>(BrickType.class);

    private GnssProvider gnssProvider;
    @Nullable
    private WifiPopupDialog wifiPopupDialog;

    @Nullable
    private UpdatePrompt updatePrompt;
    private final Runnable checkUpdatesIfDue = () -> Updater.get(this).checkIfDue();

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    private OverlayStatusWidgetBinding binding;

    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable carAvailabilityListener = () -> {
        if (binding != null) applyPreferences();
    };
    private GradientDrawable background = null;
    private int bgColor = -1;
    private int bgCornerRadius = -1;

    private int touchSlop;

    private UsageStatsManager usageStatsManager = null;
    private Set<String> hiddenInPackages;
    private String lastForegroundPackage;
    private boolean overlayHiddenByApp = false;

    private int pendingBufferedTransitions = 0;

    private final Runnable shrinkBufferSafetyClose = this::endBufferedTransition;

    @Nullable
    private android.animation.LayoutTransition contentLayoutTransition;

    private Context themedContext;
    private int appliedThemePref = -1;

    public interface OverlayStateListener {
        void onOverlayStateChanged(int x, int y, int width, int height);
    }

    @Nullable private OverlayStateListener overlayStateListener;

    private final Runnable updateDateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateDateTime();
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(this, delay);
        }
    };

    private final Runnable foregroundAppCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkForegroundApp();
            mainHandler.postDelayed(this, FOREGROUND_APP_CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        prefs = new Preferences(this);
        gnssProvider = new GnssProvider(this, mainHandler);
        renderBricks.put(BrickType.TIME, new TimeRenderBrick(this));
        renderBricks.put(BrickType.DATE, new DateRenderBrick(this));
        renderBricks.put(BrickType.MEDIA, new MediaRenderBrick(this));
        renderBricks.put(BrickType.WIFI, new WifiRenderBrick(this));
        renderBricks.put(BrickType.GPS, new GpsRenderBrick(this));
        renderBricks.put(BrickType.BLUETOOTH, new BluetoothRenderBrick(this));
        renderBricks.put(BrickType.INDOOR_TEMP, new TempRenderBrick(this, BrickType.INDOOR_TEMP,
                prefs.indoorTemp, R.id.indoorTempBrick,
                R.drawable.ic_reading_cabin_temp,
                R.drawable.ic_reading_filled_cabin_temp,
                R.drawable.ic_reading_bars_cabin_temp));
        renderBricks.put(BrickType.OUTDOOR_TEMP, new TempRenderBrick(this, BrickType.OUTDOOR_TEMP,
                prefs.outdoorTemp, R.id.outdoorTempBrick,
                R.drawable.ic_reading_outside_temp,
                R.drawable.ic_reading_filled_outside_temp,
                R.drawable.ic_reading_bars_outside_temp));
        renderBricks.put(BrickType.GNSS_INFO, new GnssInfoRenderBrick(this));
        renderBricks.put(BrickType.FUEL_LEVEL, new FuelLevelRenderBrick(this));
        renderBricks.put(BrickType.FUEL_RANGE, new FuelRangeRenderBrick(this));
        renderBricks.put(BrickType.BATTERY_VOLTAGE, new BatteryVoltageRenderBrick(this));

        // Belgee X50 / Geely Coolray (SX11) bricks
        renderBricks.put(BrickType.SPEED, new X50MetricRenderBrick(this, BrickType.SPEED,
                prefs.carSpeed, R.id.speedBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.SPEED_KMH));
        renderBricks.put(BrickType.ODOMETER, new X50MetricRenderBrick(this, BrickType.ODOMETER,
                prefs.carOdometer, R.id.odometerBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.ODOMETER_KM));
        renderBricks.put(BrickType.GEAR_STATE, new X50MetricRenderBrick(this, BrickType.GEAR_STATE,
                prefs.carGearState, R.id.gearStateBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.GEAR_STATE));
        renderBricks.put(BrickType.ENGINE_TEMP, new X50MetricRenderBrick(this, BrickType.ENGINE_TEMP,
                prefs.carEngineTemp, R.id.engineTempBrick,
                R.drawable.ic_reading_cabin_temp, R.drawable.ic_reading_filled_cabin_temp, R.drawable.ic_reading_bars_cabin_temp,
                CarMetric.ENGINE_TEMP_C));
        renderBricks.put(BrickType.DRIVE_MODE, new X50MetricRenderBrick(this, BrickType.DRIVE_MODE,
                prefs.carDriveMode, R.id.driveModeBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.DRIVE_MODE));
        renderBricks.put(BrickType.ENGINE_RUNNING, new X50MetricRenderBrick(this, BrickType.ENGINE_RUNNING,
                prefs.carEngineRunning, R.id.engineRunningBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.ENGINE_RUNNING));
        renderBricks.put(BrickType.TYRE_PRESSURE_FL, new X50MetricRenderBrick(this, BrickType.TYRE_PRESSURE_FL,
                prefs.carTyrePressureFl, R.id.tyrePressureFlBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.TYRE_PRESSURE_FL_KPA));
        renderBricks.put(BrickType.TYRE_PRESSURE_FR, new X50MetricRenderBrick(this, BrickType.TYRE_PRESSURE_FR,
                prefs.carTyrePressureFr, R.id.tyrePressureFrBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.TYRE_PRESSURE_FR_KPA));
        renderBricks.put(BrickType.TYRE_PRESSURE_RL, new X50MetricRenderBrick(this, BrickType.TYRE_PRESSURE_RL,
                prefs.carTyrePressureRl, R.id.tyrePressureRlBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.TYRE_PRESSURE_RL_KPA));
        renderBricks.put(BrickType.TYRE_PRESSURE_RR, new X50MetricRenderBrick(this, BrickType.TYRE_PRESSURE_RR,
                prefs.carTyrePressureRr, R.id.tyrePressureRrBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.TYRE_PRESSURE_RR_KPA));
        renderBricks.put(BrickType.TYRE_TEMP_FL, new X50MetricRenderBrick(this, BrickType.TYRE_TEMP_FL,
                prefs.carTyreTempFl, R.id.tyreTempFlBrick,
                R.drawable.ic_reading_outside_temp, R.drawable.ic_reading_filled_outside_temp, R.drawable.ic_reading_bars_outside_temp,
                CarMetric.TYRE_TEMP_FL_C));
        renderBricks.put(BrickType.TYRE_TEMP_FR, new X50MetricRenderBrick(this, BrickType.TYRE_TEMP_FR,
                prefs.carTyreTempFr, R.id.tyreTempFrBrick,
                R.drawable.ic_reading_outside_temp, R.drawable.ic_reading_filled_outside_temp, R.drawable.ic_reading_bars_outside_temp,
                CarMetric.TYRE_TEMP_FR_C));
        renderBricks.put(BrickType.TYRE_TEMP_RL, new X50MetricRenderBrick(this, BrickType.TYRE_TEMP_RL,
                prefs.carTyreTempRl, R.id.tyreTempRlBrick,
                R.drawable.ic_reading_outside_temp, R.drawable.ic_reading_filled_outside_temp, R.drawable.ic_reading_bars_outside_temp,
                CarMetric.TYRE_TEMP_RL_C));
        renderBricks.put(BrickType.TYRE_TEMP_RR, new X50MetricRenderBrick(this, BrickType.TYRE_TEMP_RR,
                prefs.carTyreTempRr, R.id.tyreTempRrBrick,
                R.drawable.ic_reading_outside_temp, R.drawable.ic_reading_filled_outside_temp, R.drawable.ic_reading_bars_outside_temp,
                CarMetric.TYRE_TEMP_RR_C));
        renderBricks.put(BrickType.MAINTENANCE_KM, new X50MetricRenderBrick(this, BrickType.MAINTENANCE_KM,
                prefs.carMaintenanceKm, R.id.maintenanceKmBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.MAINTENANCE_KM));
        renderBricks.put(BrickType.MAINTENANCE_DAYS, new X50MetricRenderBrick(this, BrickType.MAINTENANCE_DAYS,
                prefs.carMaintenanceDays, R.id.maintenanceDaysBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.MAINTENANCE_DAYS));
        renderBricks.put(BrickType.TRIP_DIST, new X50MetricRenderBrick(this, BrickType.TRIP_DIST,
                prefs.carTripDist, R.id.tripDistBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.TRIP_DIST_KM));
        renderBricks.put(BrickType.TRIP_TIME, new X50MetricRenderBrick(this, BrickType.TRIP_TIME,
                prefs.carTripTime, R.id.tripTimeBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.TRIP_TIME_MIN));
        renderBricks.put(BrickType.TRIP_AVG_SPEED, new X50MetricRenderBrick(this, BrickType.TRIP_AVG_SPEED,
                prefs.carTripAvgSpeed, R.id.tripAvgSpeedBrick,
                R.drawable.ic_reading_range, R.drawable.ic_reading_filled_range, R.drawable.ic_reading_bars_range,
                CarMetric.TRIP_AVG_SPEED_KMH));
        renderBricks.put(BrickType.TRIP_AVG_CONS, new X50MetricRenderBrick(this, BrickType.TRIP_AVG_CONS,
                prefs.carTripAvgCons, R.id.tripAvgConsBrick,
                R.drawable.ic_reading_fuel, R.drawable.ic_reading_filled_fuel, R.drawable.ic_reading_bars_fuel,
                CarMetric.TRIP_AVG_CONS_L100));

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        if (!Permissions.allPermissionsGranted(this)) {
            prefs.widgetEnabled.set(false);
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show();
            startMainActivity();
            stopSelf();
            return;
        }

        instance = this;

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        windowManager = getSystemService(WindowManager.class);

        CarIntegrations.get(this).addAvailabilityListener(carAvailabilityListener);

        createOverlayView();

        updatePrompt = new UpdatePrompt(this, gnssProvider);
        updatePrompt.start();
        mainHandler.postDelayed(checkUpdatesIfDue, FIRST_UPDATE_CHECK_DELAY_MS);
    }

    private void createOverlayView() {
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
        for (RenderBrick brick : renderBricks.values()) {
            brick.bind(binding);
        }
        binding.getRoot().setAlpha(0f);
        binding.getRoot().setVisibility(View.VISIBLE);
        binding.overlayContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            updateBackground();
            if (params == null) return;
            int oldWidth = oldRight - oldLeft;
            int newWidth = right - left;
            boolean nonStatusBar = prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR;
            if (nonStatusBar
                    && prefs.widgetAlignRight.get() && oldWidth > 0 && newWidth > 0 && newWidth != oldWidth) {
                params.x += oldWidth - newWidth;
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                }
                prefs.overlayX.set(params.x);
            }
            notifyOverlayState();
        });

        binding.overlayContainer.setMeasureUnconstrainedWidth(
                prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR);

        binding.overlayContainer.setSizeChangeHint((oldW, newW, oldH, newH) -> {
            if (params == null) return;
            if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) return;
            if (newW >= oldW) return;
            if (pendingBufferedTransitions > 0) return;
            beginBufferedTransition(true);
            mainHandler.removeCallbacks(shrinkBufferSafetyClose);
            mainHandler.postDelayed(shrinkBufferSafetyClose,
                    BRICK_TRANSITION_DURATION_MS + 200);
        });

        contentLayoutTransition = new android.animation.LayoutTransition();
        android.animation.LayoutTransition lt = contentLayoutTransition;
        lt.disableTransitionType(android.animation.LayoutTransition.APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_DISAPPEARING);
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.setDuration(android.animation.LayoutTransition.CHANGING, CONTENT_CHANGE_DURATION_MS);
        lt.setInterpolator(android.animation.LayoutTransition.CHANGING,
                new android.view.animation.AccelerateDecelerateInterpolator());
        lt.addTransitionListener(new android.animation.LayoutTransition.TransitionListener() {
            @Override
            public void startTransition(android.animation.LayoutTransition transition,
                                        android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                beginBufferedTransition(true);
            }

            @Override
            public void endTransition(android.animation.LayoutTransition transition,
                                      android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                endBufferedTransition();
            }
        });
        binding.overlayContainer.setLayoutTransition(lt);

        setupDragListener();

        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        params = new WindowManager.LayoutParams(
                statusBar
                        ? WindowManager.LayoutParams.MATCH_PARENT
                        : WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                ,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = statusBar ? 0 : prefs.overlayX.get();
        params.y = statusBar ? 0 : prefs.overlayY.get();
        params.windowAnimations = 0;

        try {
            windowManager.addView(binding.getRoot(), params);
        } catch (Exception e) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        applyPreferences();

        binding.getRoot().animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_DURATION_MS)
                .start();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        for (RenderBrick brick : renderBricks.values()) {
            brick.onConfigurationChanged();
        }
        themedContext = null;
        appliedThemePref = -1;

        if (binding != null) {
            windowManager.removeView(binding.getRoot());
            createOverlayView();
        }
    }

    @SuppressLint("MissingPermission")
    public void applyPreferences() {
        hiddenInPackages = prefs.hideInPackages.get();
        rebuildEffectiveHideLists();
        updateForegroundAppTracking();
        updateThemedContext();

        updateBackground();
        updateDateTime();

        List<BrickType> bricks = BrickType.parseOrder(prefs.brickOrder.get());
        Set<BrickType> bricksSet = EnumSet.noneOf(BrickType.class);
        bricksSet.addAll(bricks);

        binding.overlayContainer.setLayoutTransition(
                prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR ? null : contentLayoutTransition);

        binding.overlayContainer.setMeasureUnconstrainedWidth(
                prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR);

        reorderBricks(bricks);

        for (RenderBrick brick : renderBricks.values()) {
            brick.applySettings();
        }

        applyBrickVisibility(bricksSet);
        applyOverlayPosition();

        for (RenderBrick brick : renderBricks.values()) {
            brick.refreshContent();
        }

        binding.overlayContainer.setPadding(
                prefs.paddingLeft.get(),
                prefs.paddingTop.get(),
                prefs.paddingRight.get(),
                prefs.paddingBottom.get());

        int verticalPadding = binding.overlayContainer.getPaddingTop()
                + binding.overlayContainer.getPaddingBottom();
        binding.overlayContainer.setMinimumHeight(
                computeMinWidgetHeight(bricksSet) + verticalPadding);

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        if (anyBrickNeedsClockTick(bricksSet)) {
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(updateDateTimeRunnable, delay);
        }

        for (RenderBrick brick : renderBricks.values()) {
            brick.syncSource(bricksSet.contains(brick.type));
        }
    }

    private static void setTextIfChanged(android.widget.TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private void reorderBricks(List<BrickType> bricks) {
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            reorderForStatusBar(bricks);
        } else {
            reorderForFloating(bricks);
        }
    }

    private void reorderForFloating(List<BrickType> bricks) {
        LinearLayout root = binding.overlayContainer;
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();
        binding.startGroup.setVisibility(View.GONE);
        binding.centerGroup.setVisibility(View.GONE);
        binding.endGroup.setVisibility(View.GONE);
        binding.startCenterSpacer.setVisibility(View.GONE);
        binding.centerEndSpacer.setVisibility(View.GONE);

        List<View> expected = new ArrayList<>();
        expected.add(binding.startGroup);
        expected.add(binding.startCenterSpacer);
        expected.add(binding.centerGroup);
        expected.add(binding.centerEndSpacer);
        expected.add(binding.endGroup);
        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v != null) expected.add(v);
        }
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) expected.add(v);
            }
        }
        applyChildOrder(root, expected);
    }

    private void reorderForStatusBar(List<BrickType> bricks) {
        LinearLayout root = binding.overlayContainer;
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();

        List<View> rootChildren = new ArrayList<>();
        rootChildren.add(binding.startGroup);
        rootChildren.add(binding.startCenterSpacer);
        rootChildren.add(binding.centerGroup);
        rootChildren.add(binding.centerEndSpacer);
        rootChildren.add(binding.endGroup);
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) rootChildren.add(v);
            }
        }
        applyChildOrder(root, rootChildren);

        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v == null) continue;
            int alignment = clampAlignment(prefs.statusAlignmentFor(type).get());
            LinearLayout target = (alignment == 1) ? binding.centerGroup
                    : (alignment == 2) ? binding.endGroup
                    : binding.startGroup;
            target.addView(v);
        }

        binding.startGroup.setVisibility(View.VISIBLE);
        binding.centerGroup.setVisibility(View.VISIBLE);
        binding.endGroup.setVisibility(View.VISIBLE);
        binding.startCenterSpacer.setVisibility(View.VISIBLE);
        binding.centerEndSpacer.setVisibility(View.VISIBLE);
    }

    private static void applyChildOrder(ViewGroup parent, List<View> expected) {
        boolean inOrder = parent.getChildCount() == expected.size();
        if (inOrder) {
            for (int i = 0; i < expected.size(); i++) {
                if (parent.getChildAt(i) != expected.get(i)) {
                    inOrder = false;
                    break;
                }
            }
        }
        if (inOrder) return;
        parent.removeAllViews();
        for (View v : expected) {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
            parent.addView(v);
        }
    }

    private static int clampAlignment(int v) {
        return v < 0 ? 0 : (v > 2 ? 2 : v);
    }

    @Nullable
    private View viewForBrick(BrickType type) {
        RenderBrick brick = renderBricks.get(type);
        return brick != null ? brick.view() : null;
    }

    private int textOutlineColor(int alpha) {
        return (ContextCompat.getColor(themedContext, R.color.text_outline) & 0x00FFFFFF) | (alpha << 24);
    }

    private void updateThemedContext() {
        int pref = prefs.widgetTheme.get();
        if (themedContext != null && pref == appliedThemePref) return;
        if (pref == 0) {
            themedContext = this;
        } else {
            int uiMode;
            if (pref == 1) {
                uiMode = Configuration.UI_MODE_NIGHT_NO;
            } else if (pref == 2) {
                uiMode = Configuration.UI_MODE_NIGHT_YES;
            } else {
                int systemNight = getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                uiMode = (systemNight == Configuration.UI_MODE_NIGHT_YES)
                        ? Configuration.UI_MODE_NIGHT_NO
                        : Configuration.UI_MODE_NIGHT_YES;
            }
            Configuration cfg = new Configuration(getResources().getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | uiMode;
            themedContext = createConfigurationContext(cfg);
        }
        appliedThemePref = pref;
    }

    private final EnumMap<BrickType, Set<String>> effectiveHideLists = new EnumMap<>(BrickType.class);

    private void rebuildEffectiveHideLists() {
        effectiveHideLists.clear();
        for (BrickType type : BrickType.values()) {
            BrickType source = prefs.effectiveHideSourceFor(type);
            effectiveHideLists.put(type, prefs.hideListFor(source).get());
        }
    }

    private boolean isBrickHiddenByApp(@NonNull BrickType type) {
        if (lastForegroundPackage == null) return false;
        Set<String> list = effectiveHideLists.get(type);
        return list != null && list.contains(lastForegroundPackage);
    }

    private boolean anyBrickHasHideList() {
        for (Set<String> s : effectiveHideLists.values()) {
            if (s != null && !s.isEmpty()) return true;
        }
        return false;
    }

    private boolean anyBrickNeedsClockTick(Set<BrickType> order) {
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.needsClockTick() && order.contains(brick.type)) return true;
        }
        return false;
    }

    private void applyBrickVisibility(Set<BrickType> bricksSet) {
        if (binding == null) return;
        List<BrickTarget> targets = new ArrayList<>(renderBricks.size());
        for (RenderBrick brick : renderBricks.values()) {
            targets.add(resolveTarget(brick, bricksSet));
        }

        java.util.List<BrickTarget> visibilityFlips = new java.util.ArrayList<>();
        java.util.List<BrickTarget> alphaOnly = new java.util.ArrayList<>();
        boolean expanding = false;
        for (BrickTarget t : targets) {
            if (t.flips()) {
                visibilityFlips.add(t);
                if (t.visibility == View.VISIBLE) expanding = true;
            } else if (t.visibility == View.VISIBLE) {
                alphaOnly.add(t);
            }
        }
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.activeInLayout(bricksSet) && !isBrickHiddenByApp(brick.type)) {
                brick.onWillRender();
            }
        }

        if (!visibilityFlips.isEmpty()) {
            beginVisibilityTransition(binding.overlayContainer, expanding);
        }

        for (BrickTarget t : targets) {
            applyBrickTarget(t, visibilityFlips.contains(t));
        }
    }

    private static final class BrickTarget {
        final View view;
        final int visibility;
        final float visibleAlpha;
        BrickTarget(View view, int visibility, float visibleAlpha) {
            this.view = view;
            this.visibility = visibility;
            this.visibleAlpha = visibleAlpha;
        }

        boolean flips() {
            return view.getVisibility() != visibility;
        }
    }

    private BrickTarget resolveTarget(RenderBrick brick, Set<BrickType> order) {
        return resolveTarget(brick.type, brick.activeInLayout(order), brick.view(),
                Math.round(brick.contentAlpha() * 255f));
    }

    private BrickTarget resolveTarget(BrickType type, boolean activeInLayout, View view,
                                      int contentAlphaPref) {
        float baseAlpha = contentAlphaPref / 255f;
        if (!activeInLayout) {
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        if (isBrickHiddenByApp(type)) {
            if (prefs.hideKeepsSpaceFor(type).get()) {
                return new BrickTarget(view, View.VISIBLE, 0f);
            }
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        return new BrickTarget(view, View.VISIBLE, baseAlpha);
    }

    private void applyBrickTarget(BrickTarget target, boolean flip) {
        target.view.animate().cancel();
        if (target.visibility == View.GONE) {
            target.view.setVisibility(View.GONE);
            return;
        }
        target.view.setVisibility(View.VISIBLE);
        if (flip) {
            target.view.setAlpha(target.visibleAlpha);
        } else {
            target.view.animate()
                    .alpha(target.visibleAlpha)
                    .setDuration(BRICK_ALPHA_DURATION_MS)
                    .start();
        }
    }

    @Override
    public boolean syncBrickVisibility(@NonNull RenderBrick brick) {
        BrickTarget target = resolveTarget(brick, currentBrickSet());
        if (target.flips()) {
            applyBrickTarget(target, true);
        }
        return target.visibility == View.VISIBLE;
    }

    private void beginVisibilityTransition(ViewGroup sceneRoot, boolean expanding) {
        if (binding == null) return;
        beginBufferedTransition(expanding);

        if (contentLayoutTransition != null) {
            contentLayoutTransition.disableTransitionType(
                    android.animation.LayoutTransition.CHANGING);
        }

        android.transition.TransitionSet tx = new android.transition.TransitionSet();
        android.transition.ChangeBounds changeBounds = new android.transition.ChangeBounds();
        android.transition.Fade fade = new android.transition.Fade();
        tx.addTransition(changeBounds);
        tx.addTransition(fade);
        tx.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        tx.setDuration(BRICK_TRANSITION_DURATION_MS);
        tx.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        for (RenderBrick brick : renderBricks.values()) {
            View root = brick.view();
            if (!(root instanceof android.view.ViewGroup)) continue;
            changeBounds.excludeChildren(root, true);
            fade.excludeChildren(root, true);
            tx.excludeChildren(root, true);
        }
        final boolean[] closed = {false};
        Runnable closeOnce = () -> {
            if (closed[0]) return;
            closed[0] = true;
            if (contentLayoutTransition != null) {
                contentLayoutTransition.enableTransitionType(
                        android.animation.LayoutTransition.CHANGING);
            }
            endBufferedTransition();
        };
        tx.addListener(new android.transition.Transition.TransitionListener() {
            @Override public void onTransitionStart(android.transition.Transition t) {}
            @Override public void onTransitionEnd(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionCancel(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionPause(android.transition.Transition t) {}
            @Override public void onTransitionResume(android.transition.Transition t) {}
        });
        android.transition.TransitionManager.beginDelayedTransition(sceneRoot, tx);
        mainHandler.postDelayed(closeOnce, BRICK_TRANSITION_DURATION_MS + 500);
    }

    private void beginBufferedTransition(boolean expanding) {
        if (binding == null) return;
        if (pendingBufferedTransitions++ == 0) {
            if (params != null && prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                int oldWidth = params.width;
                if (expanding) {
                    params.width = getResources().getDisplayMetrics().widthPixels;
                } else {
                    int currentWidth = binding.getRoot().getWidth();
                    if (currentWidth > 0) params.width = currentWidth;
                }
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                    params.width = oldWidth;
                }
            }
        }
    }

    private void endBufferedTransition() {
        if (pendingBufferedTransitions <= 0) return;
        if (--pendingBufferedTransitions == 0) {
            mainHandler.removeCallbacks(shrinkBufferSafetyClose);
            restoreWindowToWrapContent();
        }
    }

    private void restoreWindowToWrapContent() {
        if (params == null || binding == null) return;
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {}
    }

    private Set<BrickType> currentBrickSet() {
        Set<BrickType> set = EnumSet.noneOf(BrickType.class);
        set.addAll(BrickType.parseOrder(prefs.brickOrder.get()));
        return set;
    }

    private int computeMinWidgetHeight(Set<BrickType> bricks) {
        int h = 0;
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.countsTowardFloor(bricks)) {
                h = Math.max(h, brick.minHeight());
            }
        }
        return h;
    }

    public void setOverlayStateListener(@Nullable OverlayStateListener listener) {
        this.overlayStateListener = listener;
        if (listener != null) {
            notifyOverlayState();
        }
    }

    private void notifyOverlayState() {
        if (overlayStateListener == null || params == null || binding == null) return;
        overlayStateListener.onOverlayStateChanged(
                params.x, params.y,
                binding.getRoot().getWidth(),
                binding.getRoot().getHeight());
    }

    private void applyOverlayPosition() {
        if (params == null || binding == null || windowManager == null) return;
        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        int newWidth = statusBar
                ? WindowManager.LayoutParams.MATCH_PARENT
                : WindowManager.LayoutParams.WRAP_CONTENT;
        if (pendingBufferedTransitions > 0 && !statusBar) {
            newWidth = params.width;
        }
        int newX = statusBar ? 0 : prefs.overlayX.get();
        int newY = statusBar ? 0 : prefs.overlayY.get();
        if (params.x == newX && params.y == newY && params.width == newWidth) return;
        params.x = newX;
        params.y = newY;
        params.width = newWidth;
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.isEmpty();
    }

    private void updateForegroundAppTracking() {
        boolean needTracking = !hiddenInPackages.isEmpty() || anyBrickHasHideList();
        boolean accessibilityActive = WidgetAccessibilityService.getInstance() != null;
        boolean usageGranted = Permissions.isUsageAccessGranted(this);
        boolean shouldPoll = needTracking && !accessibilityActive && usageGranted;
        if (needTracking && (accessibilityActive || usageGranted)) {
            if (usageGranted && usageStatsManager == null) {
                usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            }
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            if (shouldPoll) {
                mainHandler.post(foregroundAppCheckRunnable);
            }
            if (accessibilityActive) {
                checkForegroundApp();
            }
        } else {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            lastForegroundPackage = null;
            applyOverlayVisibility(false);
        }
    }

    public void onForegroundDisplayMapUpdated() {
        mainHandler.post(this::checkForegroundApp);
    }

    public void onForegroundTrackingPathChanged() {
        mainHandler.post(this::updateForegroundAppTracking);
    }

    private void checkForegroundApp() {
        if (hiddenInPackages.isEmpty() && !anyBrickHasHideList()) return;

        WidgetAccessibilityService a11y = WidgetAccessibilityService.getInstance();
        String latestPackage;
        if (a11y != null) {
            int myDisplayId = currentOverlayDisplayId();
            latestPackage = a11y.getForegroundPackageOnDisplay(myDisplayId);
            if (latestPackage == null && usageStatsManager != null
                    && Permissions.isUsageAccessGranted(this)) {
                latestPackage = latestPackageFromUsageStats();
            }
        } else {
            if (usageStatsManager == null) return;
            if (!Permissions.isUsageAccessGranted(this)) {
                updateForegroundAppTracking();
                return;
            }
            latestPackage = latestPackageFromUsageStats();
        }
        if (latestPackage == null) return;

        boolean changed = !latestPackage.equals(lastForegroundPackage);
        lastForegroundPackage = latestPackage;
        applyOverlayVisibility(hiddenInPackages.contains(latestPackage));
        if (changed && binding != null) {
            applyBrickVisibility(currentBrickSet());
        }
    }

    private int currentOverlayDisplayId() {
        if (binding == null) return android.view.Display.DEFAULT_DISPLAY;
        android.view.Display display = binding.getRoot().getDisplay();
        return display != null ? display.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
    }

    @Nullable
    private String latestPackageFromUsageStats() {
        if (usageStatsManager == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(now - FOREGROUND_APP_LOOKBACK_MS, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String latest = lastForegroundPackage;
        long latestTimestamp = 0;
        while (events.getNextEvent(event)) {
            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED)) {
                if (event.getTimeStamp() >= latestTimestamp) {
                    latestTimestamp = event.getTimeStamp();
                    latest = event.getPackageName();
                }
            }
        }
        return latest;
    }

    private void applyOverlayVisibility(boolean hide) {
        if (overlayHiddenByApp == hide) {
            return;
        }
        overlayHiddenByApp = hide;
        if (binding == null) return;
        View root = binding.getRoot();
        root.animate().cancel();
        if (hide) {
            root.animate()
                    .alpha(0f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .withEndAction(() -> {
                        if (overlayHiddenByApp) root.setVisibility(View.GONE);
                    })
                    .start();
        } else {
            root.setVisibility(View.VISIBLE);
            root.animate()
                    .alpha(1f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .start();
        }
    }

    private void updateBackground() {
        if (binding == null) {
            return;
        }
        if (themedContext == null) {
            updateThemedContext();
        }
        int width = binding.overlayContainer.getWidth();
        int height = binding.overlayContainer.getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        int maxRadius = Math.min(width, height) / 2;
        int backgroundCornerRadius = (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR)
                ? 0
                : maxRadius * prefs.backgroundCornerRadius.get() / 100;
        int backgroundColor = ContextCompat.getColor(themedContext, R.color.widget_background) & 0x00FFFFFF | (prefs.backgroundAlpha.get() << 24);
        binding.overlayContainer.setBackground(getBackground(backgroundColor, backgroundCornerRadius));
    }

    private Drawable getBackground(int color, int cornerRadius) {
        if (this.background == null || color != this.bgColor || cornerRadius != this.bgCornerRadius) {
            this.background = new GradientDrawable();
            this.background.setColor(color);
            this.background.setCornerRadius(cornerRadius);
            this.bgColor = color;
            this.bgCornerRadius = cornerRadius;
        }

        return this.background;
    }

    private void updateDateTime() {
        if (binding == null) return;
        Set<BrickType> order = currentBrickSet();
        java.util.Date now = new java.util.Date();
        for (RenderBrick brick : renderBricks.values()) {
            if (brick.needsClockTick() && order.contains(brick.type)) {
                brick.onClockTick(now);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDragListener() {
        binding.getRoot().setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) binding.getRoot().getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
                        return true;
                    }
                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(binding.getRoot(), params);
                    notifyOverlayState();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                        savePosition();
                    }

                    if (Math.abs(event.getRawX() - initialTouchX) < touchSlop && Math.abs(event.getRawY() - initialTouchY) < touchSlop) {
                        int rawX = (int) event.getRawX();
                        int rawY = (int) event.getRawY();

                        if (isViewHit(binding.wifiStatusIcon, rawX, rawY)) {
                            if (wifiPopupDialog == null) {
                                wifiPopupDialog = new WifiPopupDialog(this);
                            }
                            if (wifiPopupDialog.isShowing()) {
                                wifiPopupDialog.dismiss();
                            } else {
                                wifiPopupDialog.show();
                            }
                            return true;
                        }
                        if (isViewHit(binding.gnssStatusIcon, rawX, rawY)) {
                            Intent intent = getPackageManager().getLaunchIntentForPackage(GNSSSHARE_CLIENT_PACKAGE);
                            if (intent == null) {
                                intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStartActivity(intent);
                            return true;
                        }

                        startMainActivity();
                    }
                    return true;
            }
            return false;
        });
    }

    private static boolean isViewHit(@NonNull View view, int rawX, int rawY) {
        if (view.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        int pad = 12;
        return rawX >= (loc[0] - pad) && rawX <= (loc[0] + view.getWidth() + pad)
                && rawY >= (loc[1] - pad) && rawY <= (loc[1] + view.getHeight() + pad);
    }



    private void startMainActivity() {
        Intent startIntent = new Intent(WidgetService.this, MainActivity.class);
        startIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStartActivity(startIntent);
    }

    private void safeStartActivity(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed for " + intent.getAction(), t);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_title), NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_content)).setSmallIcon(R.drawable.ic_status_gps_good).setContentIntent(pendingIntent).setOngoing(true).build();
    }

    private void savePosition() {
        if (params != null) {
            prefs.overlayX.set(params.x);
            prefs.overlayY.set(params.y);
        }
    }

    @NonNull
    @Override
    public Context context() {
        return this;
    }

    @NonNull
    @Override
    public Context themed() {
        return themedContext != null ? themedContext : this;
    }

    @NonNull
    @Override
    public Preferences prefs() {
        return prefs;
    }

    @NonNull
    @Override
    public GnssProvider gnss() {
        return gnssProvider;
    }

    @NonNull
    @Override
    public Handler handler() {
        return mainHandler;
    }

    @Override
    public void onDestroy() {
        instance = null;

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        mainHandler.removeCallbacks(foregroundAppCheckRunnable);
        mainHandler.removeCallbacks(shrinkBufferSafetyClose);
        mainHandler.removeCallbacks(checkUpdatesIfDue);

        if (updatePrompt != null) updatePrompt.stop();

        for (RenderBrick brick : renderBricks.values()) {
            brick.onDestroy();
        }
        if (wifiPopupDialog != null) {
            wifiPopupDialog.dismiss();
            wifiPopupDialog = null;
        }
        gnssProvider.destroy();

        if (binding != null && windowManager != null) {
            windowManager.removeView(binding.getRoot());
        }

        CarIntegrations.get(this).removeAvailabilityListener(carAvailabilityListener);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static WidgetService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    private static Rect getBounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }
}
