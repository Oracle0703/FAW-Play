package com.shilapi.xcertplay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android 9 HS5 keys arrive through Qisi's vehicle service, before Android's input filter. */
public final class HongqiWheelControls {
    private static final String TAG = "DiPlay-HongqiKeys";
    private static final String SERVICE = "ltd.qisi.systemservice.QisiSystemService";
    private static final String DESCRIPTOR = "ltd.qisi.systemmanager.ISystemServiceInterface";
    private static final String LISTENER = "ltd.qisi.systemmanager.IHardKeyListener";
    private static final int PHONE_KEY = 5;
    private static final int MODE_KEY = 110;
    // Qisi classifies the whole press; these are not Android DOWN/UP actions.
    private static final int SHORT_PRESS = 0;
    private static final int LONG_PRESS = 1;
    private static final int LONG_PRESS_COMPLETE = 2;
    private static final int TELEPHONY_DROP = 3;
    private static final int[] KEYS = {88, 87, PHONE_KEY, 259, MODE_KEY};
    private static final int REGISTER = 12;
    private static final int UNREGISTER = 13;
    private static final String VIEW_LISTENER = "ltd.qisi.systemmanager.IViewFocusChangedListener";
    // OEM Carlink uses view 38; the factory arbiter keeps reverse camera and E-CALL priority.
    private static final int CARLINK_VIEW = 38;
    private static final int REQUEST_VIEW = 5;
    private static final int ABANDON_VIEW = 6;
    private static final int QUERY_VIEW = 10;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static HongqiWheelControls instance;
    private static volatile WeakReference<Context> foreground = new WeakReference<>(null);

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "faw-vehicle-binder");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final Context context;
    private volatile Object controller;
    private volatile SessionLogFile diagnosticLog;
    private volatile IBinder remote;
    private boolean bound;
    private volatile boolean registered;
    private boolean phoneLongPressHandled;
    private volatile boolean closed;
    private long retryAt;
    private long boundAt;
    private volatile boolean failed;
    private volatile boolean viewRequested;
    private long viewRetryAt;
    private volatile long viewGeneration;
    private volatile long intentGeneration;
    private long keysIntentGeneration = -1;
    private long viewIntentGeneration = -1;
    private volatile long bindingGeneration;
    private volatile long controllerGeneration;
    private ServiceConnection connection;
    private final Object callbacks = new Object();
    private BindingEvent bindingEvent;
    private long abandonedViewGeneration = -1;

    private static final class BindingEvent {
        final long generation;
        final IBinder binder;
        final boolean terminal;
        BindingEvent(long generation, IBinder binder, boolean terminal) {
            this.generation = generation;
            this.binder = binder;
            this.terminal = terminal;
        }
    }

    public static boolean supported(Context context) {
        return Build.VERSION.SDK_INT == 28 && "HONG QI".equals(Build.MODEL)
                && "t1c_acrn".equals(Build.DEVICE);
    }

    public static void attach(final Context context, final Object controller) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_WHEEL_CONTROL_ENABLED || !supported(context)) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (instance == null) instance = new HongqiWheelControls(context.getApplicationContext());
                instance.closed = false;
                instance.controller = controller;
                instance.controllerGeneration++;
                instance.intentGeneration++;
                instance.refresh();
            }
        });
    }

    static void attachDiagnosticLog(final Object owner, final SessionLogFile log) {
        MAIN.post(() -> { if (instance != null && instance.controller == owner) instance.diagnosticLog = log; });
    }

    public static void detach(final Object expected) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (instance == null || instance.controller != expected) return;
                instance.close();

            }
        });
    }

    public static void onResume(final Context activity) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_WHEEL_CONTROL_ENABLED || !supported(activity)) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                foreground = new WeakReference<>(activity);
                if (instance != null) { instance.intentGeneration++; instance.refresh(); }
            }
        });
    }

    public static void onPause(final Context activity) {
        if (!com.shilapi.xcertplay.shared.BuildConfig.FAW_WHEEL_CONTROL_ENABLED || !supported(activity)) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (foreground.get() != activity) return;
                foreground.clear();
                if (instance != null) { instance.intentGeneration++; instance.refresh(); }
            }
        });
    }

    static String status(Context context) {
        HongqiWheelControls current = instance;
        if (!SteeringWheelControls.enabled(context)) return "方向盘按键已关闭";
        if (current == null || !SteeringWheelControls.ready(current.controller))
            return "CarPlay 未连接，连接后自动接入方向盘按键。";
        if (current.registered) return "红旗原厂按键已接入 · CarPlay 已连接";
        return current.failed ? "原厂按键服务接入失败，正在重试。" : "正在接入红旗原厂按键服务…";
    }

    private HongqiWheelControls(Context context) { this.context = context; }

    private final Runnable poll = new Runnable() {
        @Override public void run() { refresh(); }
    };
    private final Runnable cleanup = new Runnable() {
        @Override public void run() { scheduleRemote(); }
    };

    private final Binder listener = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LISTENER);
                return true;
            }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(LISTENER);
            final int key = data.readInt();
            final int action = data.readInt();
            final Object owner = controller;
            MAIN.post(new Runnable() {
                @Override public void run() { if (controller == owner) receive(key, action); }
            });
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private final Binder viewListener = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(VIEW_LISTENER);
                return true;
            }
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(VIEW_LISTENER);
            final int view = data.readInt();
            final int change = data.readInt();
            final long generation = viewGeneration;
            if (change == -1 && view == CARLINK_VIEW) {
                synchronized (callbacks) { abandonedViewGeneration = generation; }
                scheduleRemote();
            }
            // Temporary loss stays with the arbiter; camera/E-CALL retains priority.
            Log.i(TAG, "CarPlay view focus change=" + change);
            if (reply != null) reply.writeNoException();
            return true;
        }
    };

    private ServiceConnection newConnection(final long generation) {
        return new ServiceConnection() {
            private void accept(IBinder binder, boolean terminal) {
                synchronized (callbacks) {
                    if (generation != bindingGeneration) return;
                    bindingEvent = new BindingEvent(generation, binder, terminal);
                }
                scheduleRemote();
            }
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { accept(binder, false); }
            @Override public void onServiceDisconnected(ComponentName name) { accept(null, false); }
            @Override public void onBindingDied(ComponentName name) { accept(null, true); }
            @Override public void onNullBinding(ComponentName name) { accept(null, true); }
        };
    }

    private void acceptCallbacks() {
        BindingEvent event;
        long abandoned;
        synchronized (callbacks) {
            event = bindingEvent;
            bindingEvent = null;
            abandoned = abandonedViewGeneration;
            abandonedViewGeneration = -1;
        }
        if (event != null && bound && event.generation == bindingGeneration) {
            if (event.binder == null) {
                forgetRemote(); // The dead service no longer owns registrations.
                failed = true;
                if (event.terminal) release();
                retryAt = android.os.SystemClock.uptimeMillis() + 5_000;
            } else {
                try {
                    if (!DESCRIPTOR.equals(event.binder.getInterfaceDescriptor()))
                        throw new RemoteException("Unexpected vehicle key service");
                    remote = event.binder;
                    failed = false;
                    Log.i(TAG, "factory key service connected");
                } catch (RemoteException | RuntimeException failure) {
                    failed = true;
                    release();
                    retryAt = android.os.SystemClock.uptimeMillis() + 5_000;
                }
            }
        }
        if (abandoned == viewGeneration && viewRequested) {
            viewRequested = false;
            viewGeneration++;
            viewRetryAt = android.os.SystemClock.uptimeMillis() + 2_000;
        }
    }

    private void forgetRemote() {
        remote = null;
        registered = false;
        phoneLongPressHandled = false;
        viewRequested = false;
        viewGeneration++;
        viewRetryAt = 0;
    }

    private void refresh() {
        MAIN.removeCallbacks(poll);
        scheduleRemote();
        if (!closed) MAIN.postDelayed(poll, 250);
    }

    private void scheduleRemote() {
        if (!refreshPending.compareAndSet(false, true)) return;
        WORKER.execute(() -> {
            long generation = intentGeneration;
            try { acceptCallbacks(); refreshRemote(); }
            finally {
                if (closed) release();
                refreshPending.set(false);
                boolean pendingCallbacks;
                synchronized (callbacks) {
                    pendingCallbacks = bindingEvent != null || abandonedViewGeneration >= 0;
                }
                if (generation != intentGeneration || pendingCallbacks) scheduleRemote();
            }
        });
    }

    private void refreshRemote() {
        if (closed) { release(); return; }
        final Object owner = controller;
        final long generation = intentGeneration;
        final long keysGeneration = controllerGeneration;
        if (viewRequested && viewIntentGeneration != generation) releaseView();
        if (registered && keysIntentGeneration != controllerGeneration) releaseKeys();
        // Never replace responsibility for a release that has not succeeded.
        if ((viewRequested && viewIntentGeneration != generation)
                || (registered && keysIntentGeneration != controllerGeneration)) return;
        boolean ready = SteeringWheelControls.ready(controller);
        boolean wantedKeys = SteeringWheelControls.enabled(context) && ready;
        boolean wantedView = foreground.get() != null && ready;
        boolean wanted = wantedKeys || wantedView;
        if (bound && remote == null && android.os.SystemClock.uptimeMillis() - boundAt >= 5_000) {
            release();
            failed = true;
            retryAt = android.os.SystemClock.uptimeMillis() + 5_000;
        }
        if (!wanted) {
            release();
        } else if (!bound && android.os.SystemClock.uptimeMillis() >= retryAt) {
            try {
                Intent intent = new Intent().setComponent(new ComponentName("ltd.qisi.systemservice", SERVICE));
                connection = newConnection(++bindingGeneration);
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                boundAt = android.os.SystemClock.uptimeMillis();
                if (!bound) {
                    failed = true;
                    retryAt = android.os.SystemClock.uptimeMillis() + 5_000;
                    Log.w(TAG, "factory key service bind refused");
                }
            } catch (RuntimeException failure) {
                failed = true;
                retryAt = android.os.SystemClock.uptimeMillis() + 5_000;
                Log.w(TAG, "factory key service bind failed: " + failure.getClass().getSimpleName());
            }
        }
        if (wanted && !wantedKeys) releaseKeys();
        if (wanted && !wantedView) releaseView();
        if (wantedKeys && remote != null && !registered) {
            try {
                transact(true);
                registered = true;
                keysIntentGeneration = keysGeneration;
                if (closed || generation != intentGeneration || controller != owner || !SteeringWheelControls.ready(controller)) {
                    release();
                    return;
                }
                failed = false;
                Log.i(TAG, "temporary keys registered: previous,next,phone,voice,mode");
            } catch (RemoteException | RuntimeException failure) {
                failed = true;
                release();
                retryAt = android.os.SystemClock.uptimeMillis() + 5_000;
                Log.w(TAG, "factory key registration failed: " + failure.getClass().getSimpleName());
            }
        }
        if (wantedView && remote != null && !viewRequested
                && android.os.SystemClock.uptimeMillis() >= viewRetryAt) {
            // Retry only after an availability query; factory safety screens may forbid view 38.
            viewRetryAt = android.os.SystemClock.uptimeMillis() + 2_000;
            try {
                if (viewOperation(QUERY_VIEW) == 1) {
                    viewGeneration++;
                    int result = viewOperation(REQUEST_VIEW);
                    viewRequested = result == 1 || result == 2;
                    viewIntentGeneration = generation;
                    if (closed || generation != intentGeneration || controller != owner || foreground.get() == null) {
                        releaseView();
                        if (closed || controller != owner) releaseKeys();
                    }
                    Log.i(TAG, "CarPlay view focus request=" + result);
                }
            } catch (RemoteException | RuntimeException failure) {
                Log.w(TAG, "CarPlay view focus unavailable: " + failure.getClass().getSimpleName());
            }
        }
    }

    private int viewOperation(int operation) throws RemoteException {
        final IBinder target = remote;
        if (target == null) throw new RemoteException("Vehicle service disconnected");
        final long started = System.nanoTime();
        final SessionLogFile log = diagnosticLog;
        final long generation = intentGeneration;
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (operation != ABANDON_VIEW) request.writeInt(CARLINK_VIEW);
            if (operation != QUERY_VIEW) request.writeStrongBinder(viewListener);
            if (!target.transact(operation, request, response, 0))
                throw new RemoteException("Vehicle view focus operation unavailable");
            response.readException();
            return response.readInt();
        } finally {
            request.recycle();
            response.recycle();
            String line = "Vehicle binder generation=" + generation + " atNs=" + System.nanoTime()
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000;
            Log.i(TAG, line);
            AsyncDiagnosticLog.INSTANCE.append(log, line, System.currentTimeMillis());
        }
    }

    private void releaseView() {
        if (viewRequested && remote != null) {
            try {
                viewOperation(ABANDON_VIEW);
                Log.i(TAG, "CarPlay view focus released");
            } catch (RemoteException | RuntimeException failure) {
                Log.w(TAG, "CarPlay view focus release failed: " + failure.getClass().getSimpleName());
                return; // Keep ownership recorded so the next poll retries release.
            }
        }
        viewRequested = false;
        viewGeneration++;
        viewRetryAt = 0;
    }

    private void transact(boolean register) throws RemoteException {
        final IBinder target = remote;
        if (target == null) throw new RemoteException("Vehicle service disconnected");
        final long started = System.nanoTime();
        final SessionLogFile log = diagnosticLog;
        final long generation = intentGeneration;
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeString(context.getPackageName());
            request.writeString(context.getPackageName());
            // permanent=false selects the OEM's exclusive, temporary listener stack.
            // Register and unregister have different parcel argument order in this Android 9 SDK.
            if (register) { request.writeIntArray(KEYS); request.writeInt(0); }
            else { request.writeInt(0); request.writeIntArray(KEYS); }
            request.writeStrongBinder(listener);
            if (!target.transact(register ? REGISTER : UNREGISTER, request, response, 0))
                throw new RemoteException("Vehicle key operation unavailable");
            response.readException();
        } finally {
            request.recycle();
            response.recycle();
            String line = "Vehicle binder generation=" + generation + " atNs=" + System.nanoTime()
                    + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000;
            Log.i(TAG, line);
            AsyncDiagnosticLog.INSTANCE.append(log, line, System.currentTimeMillis());
        }
    }

    static int role(int key, int action) {
        if (action != SHORT_PRESS) return -1;
        if (key == 88) return SteeringWheelControls.PREVIOUS;
        if (key == 87) return SteeringWheelControls.NEXT;
        if (key == PHONE_KEY) return SteeringWheelControls.PHONE;
        if (key == 259) return SteeringWheelControls.SIRI;
        return -1;
    }

    private void receive(int key, int action) {
        if (closed || !registered) return;
        if (!SteeringWheelControls.enabled(context) || !SteeringWheelControls.ready(controller)) {
            refresh();
            return;
        }
        if (key == MODE_KEY) {
            // The temporary listener prevents the OEM media service from switching sources.
            Log.i(TAG, "factory mode key action=" + action + " consumed=true");
            return;
        }
        if (key == PHONE_KEY) {
            if (action == LONG_PRESS_COMPLETE) {
                phoneLongPressHandled = false;
                return;
            }
            if (action == LONG_PRESS) {
                if (phoneLongPressHandled) return;
                phoneLongPressHandled = true;
                boolean sent = SteeringWheelControls.sendTelephony(controller, TELEPHONY_DROP);
                Log.i(TAG, "factory key=" + key + " action=" + action + " phone=end_or_reject sent=" + sent);
                return;
            }
            // Never accept a pending call from a duplicate short notification during a long press.
            if (phoneLongPressHandled) return;
        }
        int role = role(key, action);
        if (role < 0) return;
        boolean sent = SteeringWheelControls.send(controller, role);
        Log.i(TAG, "factory key=" + key + " action=" + action + " sent=" + sent);
    }

    private void releaseKeys() {
        if (registered && remote != null) {
            try {
                transact(false);
                Log.i(TAG, "temporary keys released to factory");
            } catch (RemoteException | RuntimeException failure) {
                Log.w(TAG, "factory key release failed: " + failure.getClass().getSimpleName());
                return;
            }
        }
        registered = false;
        phoneLongPressHandled = false;
    }

    private void release() {
        releaseView();
        releaseKeys();
        if ((viewRequested || registered) && remote != null) {
            if (closed) {
                MAIN.removeCallbacks(cleanup);
                MAIN.postDelayed(cleanup, 250);
            }
            return;
        }
        MAIN.removeCallbacks(cleanup);
        remote = null;
        viewRequested = false;
        viewGeneration++;
        if (bound) {
            try { context.unbindService(connection); }
            catch (RuntimeException failure) { Log.w(TAG, "factory key unbind failed: " + failure.getClass().getSimpleName()); }
        }
        bound = false;
    }

    private void close() {
        closed = true;
        controllerGeneration++;
        intentGeneration++;
        MAIN.removeCallbacks(poll);
        controller = null;
        scheduleRemote();
    }
}
