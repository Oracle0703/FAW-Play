// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import com.shilapi.xcertplay.transport.VehicleGear;
import com.shilapi.xcertplay.transport.VehicleSpeedReading;
import com.shilapi.xcertplay.transport.VehicleSpeedSample;
import com.shilapi.xcertplay.transport.VehicleSpeedSource;
import java.util.ArrayList;
import java.util.List;

/** Read-only Android 9 vehicle HAL speed, already in m/s; no CAN scaling guesses or ADB. */
public final class HongqiVehicleSpeed implements VehicleSpeedSource {
    public static final String PERMISSION = "android.car.permission.CAR_SPEED";
    private static final String TAG = "FAW-VehicleSpeed";
    private static final String CAR = "android.car.ICar";
    private static final String PROPERTY = "android.car.hardware.property.ICarProperty";
    private static final String QISI_CLIENT = "ltd.qisi.adapterproxy.IVehicleRemoteClienter";
    private static final String QISI_STATE = "ltd.qisi.adapterproxy.ICarVehicleStateManager";
    private static final int SPEED = 0x11600207, GEAR = 0x11400400;
    private static final long INTERVAL_MS = 100, MAX_QUEUE_AGE_MS = 1500;
    private static final int MAX_SAMPLES = 20;
    private final Context context;
    private Session session;

    public HongqiVehicleSpeed(Context context) { this.context = context.getApplicationContext(); }

    public static boolean available(Context context) {
        return HongqiNavigation.supported()
                && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public synchronized void start() {
        if (session != null || !available(context)) return;
        session = new Session();
        session.main.post(session::bind);
        session.worker.post(session::poll);
    }

    @Override public synchronized void stop() {
        if (session == null) return;
        session.close();
        session = null;
    }

    @Override public synchronized VehicleSpeedReading drain() {
        return session == null ? null : session.drain(SystemClock.elapsedRealtime());
    }

    /** Standard VehicleGear values, distinct from Qisi's vendor enum. */
    static VehicleGear gear(int value) {
        switch (value) {
            case 1: return VehicleGear.NEUTRAL;
            case 2: return VehicleGear.REVERSE;
            case 4: return VehicleGear.PARK;
            case 8: case 16: case 32: case 64: case 128: case 256: case 512:
                return VehicleGear.DRIVE;
            default: return null;
        }
    }

    static boolean validSpeed(double value) { return Double.isFinite(value) && Math.abs(value) <= 100; }

    static VehicleGear qisiGear(int value) {
        switch (value) {
            case 0: return VehicleGear.PARK;
            case 1: return VehicleGear.REVERSE;
            case 2: return VehicleGear.NEUTRAL;
            case 3: case 4: case 5: case 6: case 7: case 8: return VehicleGear.DRIVE;
            default: return null;
        }
    }

    private final class Session implements ServiceConnection {
        final Handler main = new Handler(Looper.getMainLooper());
        final HandlerThread thread = new HandlerThread("faw-vehicle-speed");
        final Handler worker;
        final List<VehicleSpeedSample> samples = new ArrayList<>();
        final Runnable retry = this::bind;
        volatile boolean closed;
        volatile IBinder properties;
        volatile IBinder qisiState;
        boolean bound;
        boolean gearBound;
        VehicleGear currentGear;
        long nextLog;
        int accepted, rejected;
        boolean forwarded;
        final ServiceConnection gearConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder client) {
                worker.post(() -> {
                    if (closed) return;
                    try {
                        if (!QISI_CLIENT.equals(client.getInterfaceDescriptor())) throw new SecurityException("gear client");
                        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                        IBinder result;
                        try {
                            data.writeInterfaceToken(QISI_CLIENT); data.writeInt(15);
                            if (!client.transact(1, data, reply, 0)) throw new RemoteException("gear module rejected");
                            reply.readException(); result = reply.readStrongBinder();
                        } finally { data.recycle(); reply.recycle(); }
                        if (result == null || !QISI_STATE.equals(result.getInterfaceDescriptor()))
                            throw new SecurityException("gear module interface");
                        if (!closed) { qisiState = result; Log.i(TAG, "Qisi read-only gear ready"); }
                    } catch (RemoteException | RuntimeException error) { gearUnavailable(); }
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                qisiState = null;
                synchronized (Session.this) { samples.clear(); currentGear = null; }
            }
            @Override public void onBindingDied(ComponentName name) { gearUnavailable(); }
            @Override public void onNullBinding(ComponentName name) { gearUnavailable(); }
        };

        Session() { thread.start(); worker = new Handler(thread.getLooper()); }

        void bind() {
            if (closed) return;
            main.removeCallbacks(retry);
            Intent intent = new Intent("android.car.ICar").setComponent(new ComponentName(
                    "com.android.car", "com.android.car.CarService"));
            try {
                if (!bound) bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
                if (!gearBound) gearBound = context.bindService(new Intent(Intent.ACTION_MAIN).setComponent(
                        new ComponentName("ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService")),
                        gearConnection, Context.BIND_AUTO_CREATE);
                if (!bound || !gearBound) retry("bind refused");
            } catch (RuntimeException error) { retry(error.getClass().getSimpleName()); }
        }

        void retry(String reason) {
            if (closed) return;
            Log.w(TAG, "vehicle data unavailable: " + reason);
            main.removeCallbacks(retry);
            main.postDelayed(retry, 3000);
        }

        @Override public void onServiceConnected(ComponentName name, IBinder car) {
            worker.post(() -> {
                if (closed) return;
                try {
                    if (!CAR.equals(car.getInterfaceDescriptor())) throw new SecurityException("car interface");
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    IBinder result;
                    try {
                        data.writeInterfaceToken(CAR); data.writeString("property");
                        if (!car.transact(2, data, reply, 0)) throw new RemoteException("car service rejected");
                        reply.readException(); result = reply.readStrongBinder();
                    } finally { data.recycle(); reply.recycle(); }
                    if (result == null || !PROPERTY.equals(result.getInterfaceDescriptor()))
                        throw new SecurityException("property interface");
                    if (!closed) { properties = result; Log.i(TAG, "vehicle HAL ready; speed=m/s sampleMs=100"); }
                } catch (RemoteException | RuntimeException error) { unavailable(error); }
            });
        }

        @Override public void onServiceDisconnected(ComponentName name) { clear(); }
        @Override public void onBindingDied(ComponentName name) { unavailable(null); }
        @Override public void onNullBinding(ComponentName name) { unavailable(null); }

        void unavailable(Exception error) {
            clear();
            main.post(() -> {
                if (closed) return;
                unbind(); retry(error == null ? "service disconnected" : error.getClass().getSimpleName());
            });
        }

        synchronized void clear() { properties = null; samples.clear(); currentGear = null; }

        void gearUnavailable() {
            qisiState = null;
            main.post(() -> {
                if (closed) return;
                if (gearBound) {
                    gearBound = false;
                    try { context.unbindService(gearConnection); } catch (RuntimeException ignored) {}
                }
                retry("gear unavailable");
            });
        }

        void unbind() {
            if (gearBound) {
                gearBound = false; qisiState = null;
                try { context.unbindService(gearConnection); } catch (RuntimeException ignored) {}
            }
            if (!bound) return;
            bound = false;
            try { context.unbindService(this); } catch (RuntimeException ignored) {}
        }

        VehicleGear readQisiGear() {
            IBinder binder = qisiState;
            if (binder == null) return null;
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(QISI_STATE);
                if (!binder.transact(6, data, reply, 0)) throw new RemoteException("gear read rejected");
                reply.readException(); return qisiGear(reply.readInt());
            } catch (RemoteException | RuntimeException error) { gearUnavailable(); return null; }
            finally { data.recycle(); reply.recycle(); }
        }

        /** Matches this car's CarPropertyValue Parcelable; accepts only primitive speed/gear. */
        Number read(IBinder binder, int property, String expectedType) throws RemoteException {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(PROPERTY); data.writeInt(property); data.writeInt(0);
                if (!binder.transact(4, data, reply, 0)) throw new RemoteException("property read rejected");
                reply.readException();
                if (reply.readInt() == 0) return null;
                int id = reply.readInt(), area = reply.readInt(), status = reply.readInt();
                reply.readLong(); // ON_CHANGE property timestamp may be old while speed stays constant.
                String type = reply.readString();
                if (id != property || area != 0 || status != 0 || !expectedType.equals(type)) return null;
                // Parcel primitive value tags: 7=Float, 1=Integer. Never instantiate returned classes.
                int tag = reply.readInt();
                if ("java.lang.Float".equals(type) && tag == 7) return reply.readFloat();
                if ("java.lang.Integer".equals(type) && tag == 1) return reply.readInt();
                return null;
            } finally { data.recycle(); reply.recycle(); }
        }

        void poll() {
            if (closed) return;
            IBinder binder = properties;
            if (binder != null) {
                try {
                    Number direction = read(binder, GEAR, "java.lang.Integer");
                    Number value = read(binder, SPEED, "java.lang.Float");
                    long now = SystemClock.elapsedRealtime();
                    VehicleGear gear = direction == null ? null : gear(direction.intValue());
                    if (gear == null) gear = readQisiGear();
                    synchronized (this) {
                        if (closed || binder != properties) return;
                        if (value == null || gear == null || !validSpeed(value.doubleValue())) {
                            samples.clear(); currentGear = null; rejected++;
                        } else {
                            if (gear != currentGear) samples.clear();
                            currentGear = gear;
                            samples.add(new VehicleSpeedSample(now, Math.abs(value.doubleValue())));
                            if (samples.size() > MAX_SAMPLES) samples.remove(0);
                            accepted++;
                        }
                    }
                    if (now >= nextLog) {
                        Log.i(TAG, "samples accepted=" + accepted + " rejected=" + rejected
                                + " gear=" + gear + " halGear=" + direction
                                + " speedMps=" + (value == null ? "unavailable" : value));
                        nextLog = now + 10000;
                    }
                } catch (RemoteException | RuntimeException error) { unavailable(error); }
            }
            if (!closed) worker.postDelayed(this::poll, INTERVAL_MS);
        }

        synchronized VehicleSpeedReading drain(long now) {
            if (closed || properties == null || currentGear == null) return null;
            samples.removeIf(s -> now - s.getElapsedMillis() > MAX_QUEUE_AGE_MS || s.getElapsedMillis() > now);
            if (samples.isEmpty()) return null;
            List<VehicleSpeedSample> result = new ArrayList<>(samples); samples.clear();
            if (!forwarded) {
                forwarded = true;
                Log.i(TAG, "CarPlay vehicle-speed batch samples=" + result.size());
            }
            return new VehicleSpeedReading(currentGear, result);
        }

        void close() {
            closed = true; clear();
            main.removeCallbacks(retry);
            worker.removeCallbacksAndMessages(null);
            main.post(this::unbind);
            thread.quitSafely();
        }
    }
}
