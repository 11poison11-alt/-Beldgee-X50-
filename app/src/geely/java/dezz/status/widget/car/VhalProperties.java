package dezz.status.widget.car;

import android.car.Car;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class VhalProperties {

    private static final String TAG = GeelyCarIntegration.TAG;
    private static final int GLOBAL_AREA = 0;
    private static final long REBIND_COOLDOWN_MS = 10_000L;

    static final class Read {
        @Nullable final byte[] bytes;
        final int intValue;
        final float floatValue;
        @Nullable final String problem;
        @NonNull final String detail;

        private Read(@Nullable byte[] bytes, int intValue, float floatValue,
                     @Nullable String problem, @NonNull String detail) {
            this.bytes = bytes;
            this.intValue = intValue;
            this.floatValue = floatValue;
            this.problem = problem;
            this.detail = detail;
        }

        @NonNull
        String outcome(float value) {
            return problem != null ? problem : EcarxReadings.verdict(value, bytes == null && intValue == Integer.MIN_VALUE && Float.isNaN(floatValue));
        }
    }

    private final Context appContext;
    @Nullable private ConnectionAttempt current;
    private long nextAttemptAllowedAt;
    @NonNull private volatile String carServiceState = "not bound";

    private final Set<Integer> subscribedIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Integer> liveInts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Float> liveFloats = new ConcurrentHashMap<>();

    private final CarPropertyManager.CarPropertyEventListener propertyListener =
            new CarPropertyManager.CarPropertyEventListener() {
                @Override
                public void onChangeEvent(CarPropertyValue value) {
                    if (value == null) return;
                    Object raw = value.getValue();
                    long key = cacheKey(value.getPropertyId(), value.getAreaId());
                    if (raw instanceof Integer) {
                        liveInts.put(key, (Integer) raw);
                    } else if (raw instanceof Float) {
                        liveFloats.put(key, (Float) raw);
                    }
                }

                @Override
                public void onErrorEvent(int propId, int zone) {
                }
            };

    private static long cacheKey(int propId, int areaId) {
        return (((long) propId) << 32) | (areaId & 0xFFFFFFFFL);
    }

    private final class ConnectionAttempt implements ServiceConnection {
        @Nullable Car car;
        @Nullable volatile CarPropertyManager manager;
        volatile boolean abandoned;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (abandoned || car == null) return;
            try {
                manager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
                if (manager != null) {
                    carServiceState = "connected";
                    subscribedIds.clear();
                    liveInts.clear();
                    liveFloats.clear();
                } else {
                    carServiceState = "mgr=null";
                }
                Log.i(TAG, "VHAL CarPropertyManager status: " + carServiceState);
            } catch (Throwable t) {
                String msg = t.getMessage();
                carServiceState = "cbErr:" + t.getClass().getSimpleName() + (msg != null ? "(" + msg + ")" : "");
                Log.w(TAG, "VHAL getCarManager failed in onServiceConnected", t);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            manager = null;
            subscribedIds.clear();
            carServiceState = "disconnected";
        }
    }

    VhalProperties(@NonNull Context appContext) {
        this.appContext = appContext.getApplicationContext();
        ensureBound();
    }

    @Nullable
    private CarPropertyManager ensureBound() {
        if (current != null && current.manager != null) {
            return current.manager;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextAttemptAllowedAt) {
            return null;
        }
        nextAttemptAllowedAt = now + REBIND_COOLDOWN_MS;

        if (current != null) {
            current.abandoned = true;
            if (current.car != null) {
                try {
                    current.car.disconnect();
                } catch (Throwable ignored) {
                }
            }
            current = null;
        }

        ConnectionAttempt attempt = new ConnectionAttempt();
        try {
            attempt.car = Car.createCar(appContext, attempt);
            if (attempt.car == null) {
                carServiceState = "createCar=null";
                return null;
            }
            attempt.car.connect();
            current = attempt;
            carServiceState = "connecting...";
        } catch (Throwable t) {
            String msg = t.getMessage();
            carServiceState = "connErr:" + t.getClass().getSimpleName() + (msg != null ? "(" + msg + ")" : "");
            Log.w(TAG, "VHAL Car.createCar failed", t);
        }
        return attempt.manager;
    }

    private void ensureSubscribed(@NonNull CarPropertyManager manager, int propertyId) {
        if (subscribedIds.add(propertyId)) {
            try {
                manager.registerListener(propertyListener, propertyId, 0f);
            } catch (Throwable t) {
                Log.w(TAG, "VHAL registerListener failed for " + propertyId, t);
            }
        }
    }

    @MainThread
    @NonNull
    Read read(int propertyId) {
        return read(propertyId, GLOBAL_AREA);
    }

    @MainThread
    @NonNull
    Read read(int propertyId, int areaId) {
        CarPropertyManager manager = ensureBound();
        if (manager == null) {
            return new Read(null, Integer.MIN_VALUE, Float.NaN, "bind failed: " + carServiceState, carServiceState);
        }
        try {
            CarPropertyValue<byte[]> value = manager.getProperty(byte[].class, propertyId, areaId);
            byte[] bytes = value != null ? value.getValue() : null;
            return new Read(bytes, Integer.MIN_VALUE, Float.NaN, null, "[" + hex(bytes) + "]");
        } catch (Throwable t) {
            return new Read(null, Integer.MIN_VALUE, Float.NaN, "threw " + t.getClass().getSimpleName(), "threw " + t);
        }
    }

    @MainThread
    @NonNull
    Read readInt(int propertyId, int areaId) {
        CarPropertyManager manager = ensureBound();
        if (manager == null) {
            return new Read(null, Integer.MIN_VALUE, Float.NaN, "bind failed: " + carServiceState, carServiceState);
        }
        ensureSubscribed(manager, propertyId);
        try {
            int val = manager.getIntProperty(propertyId, areaId);
            return new Read(null, val, Float.NaN, null, "int=" + val);
        } catch (Throwable t) {
            Integer pushed = liveInts.get(cacheKey(propertyId, areaId));
            if (pushed != null) {
                return new Read(null, pushed, Float.NaN, null, "int(live)=" + pushed);
            }
            return new Read(null, Integer.MIN_VALUE, Float.NaN, "threw " + t.getClass().getSimpleName(), "threw " + t);
        }
    }

    @MainThread
    @NonNull
    Read readFloat(int propertyId, int areaId) {
        CarPropertyManager manager = ensureBound();
        if (manager == null) {
            return new Read(null, Integer.MIN_VALUE, Float.NaN, "bind failed: " + carServiceState, carServiceState);
        }
        ensureSubscribed(manager, propertyId);
        try {
            float val = manager.getFloatProperty(propertyId, areaId);
            return new Read(null, Integer.MIN_VALUE, val, null, "float=" + val);
        } catch (Throwable t) {
            Float pushed = liveFloats.get(cacheKey(propertyId, areaId));
            if (pushed != null && !Float.isNaN(pushed)) {
                return new Read(null, Integer.MIN_VALUE, pushed, null, "float(live)=" + pushed);
            }
            return new Read(null, Integer.MIN_VALUE, Float.NaN, "threw " + t.getClass().getSimpleName(), "threw " + t);
        }
    }

    private final ConcurrentHashMap<Long, Number> initialValues = new ConcurrentHashMap<>();

    @MainThread
    @NonNull
    String findChangedProperties() {
        CarPropertyManager manager = ensureBound();
        if (manager == null) return "no_mgr";
        StringBuilder changed = new StringBuilder();
        try {
            java.util.List<android.car.hardware.CarPropertyConfig> configs = manager.getPropertyList();
            if (configs == null) return "list=null";
            for (android.car.hardware.CarPropertyConfig cfg : configs) {
                int id = cfg.getPropertyId();
                if (id == 291504647 || id == 289407492 || id == 557875223 ||
                        id == 557875206 || id == 557884777 || id == 289407752 ||
                        id == 658548345 || id == 658548346 || id == 658547480 || id == 658548325 ||
                        id == 557884281 || id == 557885093) {
                    continue;
                }
                ensureSubscribed(manager, id);
                int[] areas = cfg.getAreaIds();
                if (areas == null || areas.length == 0) areas = new int[]{0};
                Class<?> type = cfg.getPropertyType();
                for (int area : areas) {
                    try {
                        long key = cacheKey(id, area);
                        if (type == Integer.class || type == int.class) {
                            int val = manager.getIntProperty(id, area);
                            if (val != Integer.MIN_VALUE) {
                                Number first = initialValues.putIfAbsent(key, val);
                                if (first != null && Math.abs(val - first.intValue()) >= 1) {
                                    changed.append(id).append("@").append(area)
                                            .append(":").append(first.intValue())
                                            .append("->").append(val).append(" ");
                                }
                            }
                        } else if (type == Float.class || type == float.class) {
                            float val = manager.getFloatProperty(id, area);
                            if (!Float.isNaN(val)) {
                                Number first = initialValues.putIfAbsent(key, val);
                                if (first != null && Math.abs(val - first.floatValue()) >= 0.5f) {
                                    changed.append(id).append("@").append(area)
                                            .append(":").append(first.floatValue())
                                            .append("->").append(val).append(" ");
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
        String res = changed.toString().trim();
        return res.isEmpty() ? "ждём улицу..." : res;
    }

    @MainThread
    void release() {
        if (current != null) {
            current.abandoned = true;
            if (current.manager != null) {
                try {
                    current.manager.unregisterListener(propertyListener);
                } catch (Throwable ignored) {
                }
            }
            if (current.car != null) {
                try {
                    current.car.disconnect();
                } catch (Throwable ignored) {
                }
            }
            current = null;
        }
        subscribedIds.clear();
        liveInts.clear();
        liveFloats.clear();
        carServiceState = "released";
    }

    @NonNull
    private static String hex(@Nullable byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}