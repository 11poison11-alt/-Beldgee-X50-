package dezz.status.widget.car;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.ecarx.xui.adaptapi.FunctionStatus;
import com.ecarx.xui.adaptapi.car.Car;
import com.ecarx.xui.adaptapi.car.ICar;
import com.ecarx.xui.adaptapi.car.base.ICarInfo;
import com.ecarx.xui.adaptapi.car.sensor.ISensor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class GeelyCarIntegration implements CarIntegration {

    static final String TAG = "GeelyCarIntegration";

    private static final long INIT_RETRY_DELAY_MS = 10_000L;
    private static final int MAX_INIT_ATTEMPTS_PER_PROCESS = 6;
    private static final long PROBE_DEADLINE_MS = 10_000L;

    private enum InitState { IDLE, RETRY_PENDING, INITIALIZING, READY, FAILED }

    private static final long PULL_INTERVAL_MS = 10_000L;
    private static final long FAST_PULL_INTERVAL_MS = 1_000L;
    private static final long PULL_INTERVAL_MAX_MS = 30_000L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<CarMetric, Subscription> subscriptions = new EnumMap<>(CarMetric.class);
    private final Map<Listener, Set<CarMetric>> needsByListener = new LinkedHashMap<>();

    private InitState initState = InitState.IDLE;
    private int initAttempts = 0;
    @Nullable
    private ISensor sensors;
    @Nullable
    private EcarxBatteryVoltage batteryVoltage;
    private boolean batteryVoltageUnavailable;
    @Nullable
    private VhalProperties vhal;
    private boolean vhalUnavailable;
    private final Map<CarMetric, String> vhalOutcomes = new EnumMap<>(CarMetric.class);
    private final Map<CarMetric, String> diagnosticCodes = new EnumMap<>(CarMetric.class);
    @Nullable
    private ICarInfo carInfo;
    private float tankLiters = Float.NaN;

    private final Map<CarMetric, Boolean> provenByData = new EnumMap<>(CarMetric.class);
    private final List<Runnable> availabilityListeners = new ArrayList<>(2);

    private static final class Subscription {
        @Nullable final ISensor.ISensorListener sensorListener;
        @Nullable final Runnable poller;
        final AtomicBoolean cancelled;

        Subscription(@Nullable ISensor.ISensorListener sensorListener, @Nullable Runnable poller,
                     @NonNull AtomicBoolean cancelled) {
            this.sensorListener = sensorListener;
            this.poller = poller;
            this.cancelled = cancelled;
        }
    }

    private static final class PushedFeed {
        volatile float value = Float.NaN;
        volatile long changedAt;
    }

    private final Map<CarMetric, Float> lastDispatched = new EnumMap<>(CarMetric.class);

    GeelyCarIntegration(@NonNull Context appContext) {
        this.appContext = appContext;
        vhal();
    }

    @Nullable
    @Override
    public String getDiagnosticStatus(@NonNull CarMetric metric) {
        return diagnosticCodes.get(metric);
    }

    private void ensureInitStarted() {
        if (initState != InitState.IDLE) return;
        startProbeAttempt();
    }

    private void startProbeAttempt() {
        initState = InitState.INITIALIZING;
        initAttempts++;

        final AtomicBoolean settled = new AtomicBoolean(false);
        final Runnable watchdog = () -> {
            if (!settled.compareAndSet(false, true)) return;
            Log.w(TAG, "eCarX probe timeout; continuing with VHAL");
            onInitFinished(false, null);
        };
        mainHandler.postDelayed(watchdog, PROBE_DEADLINE_MS);

        Thread initThread = new Thread(() -> {
            ProbeResult probe = null;
            try {
                probe = probeVendorSdk();
            } catch (Throwable t) {
                Log.w(TAG, "eCarX AdaptAPI init failed (non-fatal, VHAL remains active)", t);
            }
            ProbeResult finalProbe = probe;
            boolean ok = probe != null && probe.sensors != null;
            mainHandler.post(() -> {
                if (!settled.compareAndSet(false, true)) return;
                mainHandler.removeCallbacks(watchdog);
                onInitFinished(ok, finalProbe);
            });
        }, "geely-car-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    private static final class ProbeResult {
        @Nullable final ISensor sensors;
        @Nullable ICarInfo carInfo;
        final EnumMap<CarMetric, Boolean> proven = new EnumMap<>(CarMetric.class);
        float tankLiters = Float.NaN;

        ProbeResult(@Nullable ISensor sensors) {
            this.sensors = sensors;
        }
    }

    @WorkerThread
    @NonNull
    private ProbeResult probeVendorSdk() {
        ICar car = Car.create(appContext);
        ISensor s = car.getSensorManager();
        ProbeResult result = new ProbeResult(s);
        if (s == null) return result;
        for (CarMetric metric : CarMetric.values()) {
            EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
            if (spec == null
                    || spec.transport == EcarxMetricSpec.Transport.CAR_INFO
                    || spec.transport == EcarxMetricSpec.Transport.VHAL_ONLY) continue;
            if (!Float.isNaN(readLatest(metric, spec, s))) {
                result.proven.put(metric, Boolean.TRUE);
            }
        }
        try {
            result.carInfo = car.getCarInfoManager();
            float liters = readTankLiters(result.carInfo);
            if (!Float.isNaN(liters)) {
                result.tankLiters = liters;
                result.proven.put(CarMetric.FUEL_TANK_CAPACITY_L, Boolean.TRUE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "probe getCarInfoManager failed", t);
        }
        return result;
    }

    private void onInitFinished(boolean ok, @Nullable ProbeResult probe) {
        sensors = probe != null ? probe.sensors : null;
        if (probe != null) {
            provenByData.putAll(probe.proven);
            if (probe.carInfo != null) carInfo = probe.carInfo;
            if (!Float.isNaN(probe.tankLiters)) tankLiters = probe.tankLiters;
        }
        if (ok) {
            initState = InitState.READY;
            reconcile();
        } else if (initAttempts < MAX_INIT_ATTEMPTS_PER_PROCESS) {
            initState = InitState.RETRY_PENDING;
            mainHandler.postDelayed(() -> {
                if (initState == InitState.RETRY_PENDING) startProbeAttempt();
            }, INIT_RETRY_DELAY_MS);
        } else {
            initState = InitState.FAILED;
        }
        notifyAvailabilityChanged();
    }

    private float readLatest(@NonNull CarMetric metric, @NonNull EcarxMetricSpec spec,
                             @Nullable ISensor s) {
        switch (spec.transport) {
            case SENSOR:
                if (s == null || spec.decoder == null) return Float.NaN;
                for (int sensorType : spec.ids) {
                    try {
                        float value = spec.decoder.decode(s.getSensorLatestValue(sensorType));
                        if (!Float.isNaN(value)) return value;
                    } catch (Throwable ignored) {
                    }
                }
                return Float.NaN;
            case SIGNAL:
                return EcarxBatteryVoltage.readSignalOnce(s);
            case CAR_INFO:
                return tankLiters;
            default:
                return Float.NaN;
        }
    }
    private float readLatest(@NonNull CarMetric metric) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
        if (spec == null) {
            diagnosticCodes.put(metric, "NoSpec");
            return Float.NaN;
        }

        if (spec.transport == EcarxMetricSpec.Transport.VHAL_ONLY) {
            return readVhal(metric, spec);
        }

        if (initState == InitState.READY) {
            if (spec.transport == EcarxMetricSpec.Transport.CAR_INFO && Float.isNaN(tankLiters)) {
                try {
                    tankLiters = readTankLiters(carInfo);
                } catch (Throwable t) {
                    Log.w(TAG, "getCarInfoFloat failed for the fuel tank capacity", t);
                }
                Subscription waiting = subscriptions.get(metric);
                if (!Float.isNaN(tankLiters) && waiting != null) {
                    postLatest(metric, waiting.cancelled);
                }
            }
            if (spec.transport == EcarxMetricSpec.Transport.SIGNAL) {
                return readBatteryVoltage();
            }
            float value = readLatest(metric, spec, sensors);
            if (!Float.isNaN(value)) {
                diagnosticCodes.remove(metric);
                return value;
            }
        }

        if (spec.hasVhalProperty()) {
            return readVhal(metric, spec);
        }
        diagnosticCodes.put(metric, "Sdk:" + initState.name());
        return Float.NaN;
    }

    private float readVhal(@NonNull CarMetric metric, @NonNull EcarxMetricSpec spec) {
        VhalProperties properties = vhal();
        if (properties == null) {
            diagnosticCodes.put(metric, "VhalNull");
            return Float.NaN;
        }
        if (!spec.hasVhalProperty()) {
            diagnosticCodes.put(metric, "NoProp");
            return Float.NaN;
        }
        VhalProperties.Read read;
        float value = Float.NaN;
        String rawDebug = "";

        if (spec.vhalType == EcarxMetricSpec.VhalType.INT && spec.vhalIntDecoder != null) {
            read = properties.readInt(spec.vhalProperty, spec.vhalAreaId);
            rawDebug = "I:" + read.intValue;
            if (read.problem == null && read.intValue != Integer.MIN_VALUE) {
                value = spec.vhalIntDecoder.decode(read.intValue);
            }
        } else if (spec.vhalType == EcarxMetricSpec.VhalType.FLOAT && spec.vhalFloatDecoder != null) {
            read = properties.readFloat(spec.vhalProperty, spec.vhalAreaId);
            rawDebug = "F:" + read.floatValue;
            if (read.problem == null && !Float.isNaN(read.floatValue)) {
                value = spec.vhalFloatDecoder.decode(read.floatValue);
            }
        } else if (spec.vhalType == EcarxMetricSpec.VhalType.BYTES && spec.vhalBytesDecoder != null) {
            read = properties.read(spec.vhalProperty, spec.vhalAreaId);
            rawDebug = "B:" + (read.bytes == null ? "null" : read.bytes.length);
            if (read.problem == null) {
                value = spec.vhalBytesDecoder.decode(read.bytes);
            }
        } else {
            diagnosticCodes.put(metric, "NoDec");
            return Float.NaN;
        }

        if (read.problem != null) {
            String shortErr = read.problem
                    .replace("bind failed: ", "Bind:")
                    .replace("threw ", "Ex:")
                    .replace("Exception", "");
            diagnosticCodes.put(metric, shortErr);
        } else if (Float.isNaN(value)) {
            diagnosticCodes.put(metric, "NaN(" + rawDebug + ")");
        } else {
            diagnosticCodes.remove(metric);
        }

        String outcome = read.outcome(value);
        if (!outcome.equals(vhalOutcomes.put(metric, outcome))) {
            Log.i(TAG, metric + " via VHAL " + spec.vhalProperty + " (area=" + spec.vhalAreaId + "): "
                    + read.detail + (read.problem == null ? " -> " + value : ""));
        }
        return value;
    }

    @Nullable
    private VhalProperties vhal() {
        if (vhal == null && !vhalUnavailable) {
            try {
                vhal = new VhalProperties(appContext);
            } catch (Throwable t) {
                Log.w(TAG, "VHAL properties unavailable", t);
                vhalUnavailable = true;
            }
        }
        return vhal;
    }

    private float readBatteryVoltage() {
        if (batteryVoltageUnavailable) return Float.NaN;
        try {
            if (batteryVoltage == null) batteryVoltage = new EcarxBatteryVoltage(vhal());
            return batteryVoltage.read(sensors);
        } catch (Throwable t) {
            Log.w(TAG, "12 V reader unavailable", t);
            batteryVoltageUnavailable = true;
            return Float.NaN;
        }
    }

    private static float readTankLiters(@Nullable ICarInfo info) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(CarMetric.FUEL_TANK_CAPACITY_L);
        if (info == null || spec == null || spec.decoder == null || spec.ids.length == 0) return Float.NaN;
        return spec.decoder.decode(info.getCarInfoFloat(spec.ids[0]));
    }

    @Override
    public boolean isMetricSupported(@NonNull CarMetric metric) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
        if (spec == null) return false;
        ensureInitStarted();
        return true;
    }

    @Override
    public void addAvailabilityListener(@NonNull Runnable listener) {
        if (!availabilityListeners.contains(listener)) {
            availabilityListeners.add(listener);
        }
    }

    @Override
    public void removeAvailabilityListener(@NonNull Runnable listener) {
        availabilityListeners.remove(listener);
    }

    private void notifyAvailabilityChanged() {
        for (Runnable listener : new ArrayList<>(availabilityListeners)) {
            listener.run();
        }
    }

    private void noteMetricProven(@NonNull CarMetric metric) {
        if (Boolean.TRUE.equals(provenByData.put(metric, Boolean.TRUE))) return;
        Log.i(TAG, "Metric " + metric + " delivered data — treating as supported");
        notifyAvailabilityChanged();
    }

    @Override
    public void setNeeds(@NonNull Listener listener, @NonNull Set<CarMetric> needs) {
        Set<CarMetric> previous = needsByListener.get(listener);
        Set<CarMetric> added = EnumSet.noneOf(CarMetric.class);
        for (CarMetric metric : needs) {
            if (EcarxMetricSpec.of(metric) != null && (previous == null || !previous.contains(metric))) {
                added.add(metric);
            }
        }
        if (needs.isEmpty()) {
            needsByListener.remove(listener);
        } else {
            needsByListener.put(listener, EnumSet.copyOf(needs));
            ensureInitStarted();
        }
        Set<CarMetric> started = reconcile();
        for (CarMetric metric : added) {
            Subscription running = subscriptions.get(metric);
            if (running != null && !started.contains(metric)) {
                seed(metric, running.cancelled, listener);
            }
        }
    }

    @NonNull
    private Set<CarMetric> reconcile() {
        Set<CarMetric> started = EnumSet.noneOf(CarMetric.class);
        Set<CarMetric> wanted = EnumSet.noneOf(CarMetric.class);
        for (Set<CarMetric> needs : needsByListener.values()) {
            wanted.addAll(needs);
        }
        for (CarMetric metric : CarMetric.values()) {
            boolean running = subscriptions.containsKey(metric);
            if (wanted.contains(metric) && !running) {
                if (subscribeNow(metric)) started.add(metric);
            } else if (!wanted.contains(metric) && running) {
                unsubscribeNow(metric);
            }
        }
        return started;
    }

    private void dispatch(@NonNull CarMetric metric, float value) {
        lastDispatched.put(metric, value);
        for (Map.Entry<Listener, Set<CarMetric>> e : new ArrayList<>(needsByListener.entrySet())) {
            if (e.getValue().contains(metric)) {
                e.getKey().onCarValue(metric, value);
            }
        }
    }

    private void postLatest(@NonNull CarMetric metric, @NonNull AtomicBoolean cancelled) {
        float latest = readLatest(metric);
        mainHandler.post(() -> {
            if (cancelled.get()) return;
            if (!Float.isNaN(latest)) noteMetricProven(metric);
            dispatch(metric, latest);
        });
    }

    private void seed(@NonNull CarMetric metric, @NonNull AtomicBoolean cancelled,
                      @NonNull Listener listener) {
        float read = readLatest(metric);
        Float delivered = lastDispatched.get(metric);
        float latest = !Float.isNaN(read) ? read : delivered != null ? delivered : Float.NaN;
        mainHandler.post(() -> {
            if (cancelled.get()) return;
            Set<CarMetric> needs = needsByListener.get(listener);
            if (needs == null || !needs.contains(metric)) return;
            if (!Float.isNaN(latest)) noteMetricProven(metric);
            listener.onCarValue(metric, latest);
        });
    }

    private boolean subscribeNow(@NonNull CarMetric metric) {
        EcarxMetricSpec spec = EcarxMetricSpec.of(metric);
        if (spec == null) return false;
        switch (spec.transport) {
            case SENSOR:
                return subscribeSensor(metric, spec);
            case SIGNAL:
            case VHAL_ONLY:
                return subscribeSignal(metric);
            case CAR_INFO: {
                AtomicBoolean cancelled = new AtomicBoolean(false);
                subscriptions.put(metric, new Subscription(null, null, cancelled));
                if (!Float.isNaN(tankLiters)) {
                    postLatest(metric, cancelled);
                }
                return true;
            }
            default:
                return false;
        }
    }
    private boolean subscribeSensor(@NonNull CarMetric metric, @NonNull EcarxMetricSpec spec) {
        ISensor s = sensors;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        if (s == null || spec.decoder == null || spec.ids.length == 0) {
            subscriptions.put(metric,
                    new Subscription(null, startPuller(metric, cancelled), cancelled));
            return true;
        }

        final int pushedSource = spec.ids[0];
        final PushedFeed pushed = new PushedFeed();

        ISensor.ISensorListener sensorListener = new ISensor.ISensorListener() {
            @Override
            public void onSensorEventChanged(int changedType, int value) {
            }

            @Override
            public void onSensorSupportChanged(int changedType, FunctionStatus status) {
            }

            @Override
            public void onSensorValueChanged(int changedType, float raw) {
                if (changedType != pushedSource) return;
                float value = spec.decoder.decode(raw);
                if (Float.isNaN(value)) return;
                boolean first = Float.isNaN(pushed.value);
                if (Float.compare(value, pushed.value) == 0) return;
                pushed.value = value;
                pushed.changedAt = SystemClock.elapsedRealtime();
                mainHandler.post(() -> {
                    if (cancelled.get()) return;
                    diagnosticCodes.remove(metric);
                    noteMetricProven(metric);
                    if (first && lastDispatched.containsKey(metric)) return;
                    dispatch(metric, value);
                });
            }
        };

        try {
            s.registerListener(sensorListener, pushedSource);
        } catch (Throwable ignored) {
        }
        subscriptions.put(metric,
                new Subscription(sensorListener, startPuller(metric, cancelled), cancelled));
        return true;
    }

    private boolean subscribeSignal(@NonNull CarMetric metric) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        subscriptions.put(metric, new Subscription(null, startPuller(metric, cancelled), cancelled));
        return true;
    }

    private static long basePollInterval(@NonNull CarMetric metric) {
        return (metric == CarMetric.SPEED_KMH || metric == CarMetric.GEAR_STATE)
                ? FAST_PULL_INTERVAL_MS : PULL_INTERVAL_MS;
    }

    @NonNull
    private Runnable startPuller(@NonNull CarMetric metric, @NonNull AtomicBoolean cancelled) {
        final long baseInterval = basePollInterval(metric);
        Runnable puller = new Runnable() {
            private long interval = baseInterval;

            @Override
            public void run() {
                if (cancelled.get()) return;
                float value = readLatest(metric);
                if (Float.isNaN(value)) {
                    interval = Math.min(interval * 2, PULL_INTERVAL_MAX_MS);
                    dispatch(metric, Float.NaN);
                } else {
                    interval = baseInterval;
                    noteMetricProven(metric);
                    Float last = lastDispatched.get(metric);
                    if (last == null || Float.compare(last, value) != 0) dispatch(metric, value);
                }
                mainHandler.postDelayed(this, interval);
            }
        };
        mainHandler.post(puller);
        mainHandler.postDelayed(puller, 1500L);
        return puller;
    }

    private void unsubscribeNow(@NonNull CarMetric metric) {
        Subscription subscription = subscriptions.remove(metric);
        if (subscription == null) return;
        lastDispatched.remove(metric);
        subscription.cancelled.set(true);
        if (subscription.poller != null) {
            mainHandler.removeCallbacks(subscription.poller);
        }
        if (subscription.sensorListener != null) {
            try {
                if (sensors != null) sensors.unregisterListener(subscription.sensorListener);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void shutdown() {
        needsByListener.clear();
        for (CarMetric metric : CarMetric.values()) {
            unsubscribeNow(metric);
        }
        if (batteryVoltage != null) {
            try {
                batteryVoltage.release();
            } catch (Throwable ignored) {
            }
        }
        if (vhal != null) {
            try {
                vhal.release();
            } catch (Throwable ignored) {
            }
        }
        availabilityListeners.clear();
        mainHandler.removeCallbacksAndMessages(null);
    }
}