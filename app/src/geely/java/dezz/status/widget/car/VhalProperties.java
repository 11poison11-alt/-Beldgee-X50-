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
        try {
            int val = manager.getIntProperty(propertyId, areaId);
            return new Read(null, val, Float.NaN, null, "int=" + val);
        } catch (Throwable t) {
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
        try {
            float val = manager.getFloatProperty(propertyId, areaId);
            return new Read(null, Integer.MIN_VALUE, val, null, "float=" + val);
        } catch (Throwable t) {
            return new Read(null, Integer.MIN_VALUE, Float.NaN, "threw " + t.getClass().getSimpleName(), "threw " + t);
        }
    }

    @MainThread
    void release() {
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