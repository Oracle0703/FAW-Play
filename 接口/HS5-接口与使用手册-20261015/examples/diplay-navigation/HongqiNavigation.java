// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.hud;

import android.app.Presentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Navigation-only adapter for the measured Android 9 HS5 Qisi interfaces. */
public final class HongqiNavigation {
    private static final String TAG = "DiPlay-HongqiNavi";
    private static final String CLIENT = "ltd.qisi.adapterproxy.IVehicleRemoteClienter";
    private static final String HUD = "ltd.qisi.adapterproxy.ICarHUDManager";
    private static final String INSTRUMENT = "ltd.qisi.adapterproxy.ICarInstrumentRelatedManager";
    private static final String CONTROL = "ltd.qisi.adapterproxy.ICarSystemControlManager";
    private static final int INSTRUMENT_GUIDANCE_TRANSACTION = 6;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "diplay-hongqi-navigation");
        thread.setDaemon(true);
        return thread;
    });
    // Only accessed on MAIN. Presentation windows must never capture instrument input.
    private static final Map<Presentation, Boolean> WINDOWS = new WeakHashMap<>();
    private static volatile Context context;
    private static volatile ClusterTurnGuidance guidance;
    private static volatile boolean mapReady;
    private static volatile boolean mapInterfacesReady;
    private static volatile boolean cleanupPending;
    private static final AtomicBoolean queued = new AtomicBoolean();
    private static boolean bound;
    // Binder state and ownership are confined to WRITER.
    private static IBinder hud, instrument, control;
    private static HongqiNavigationExitListener exitListener;
    private static long exitGeneration;
    private static boolean ownsHud, ownsMap, replayMap;
    private static boolean ownsInstrumentGuidance, replayInstrumentGuidance;
    private static boolean instrumentGuidanceSupported = true;
    private static HongqiInstrumentGuidance lastInstrumentGuidance;
    private static final Runnable REBIND = () -> bind();
    private static boolean lastHudLicense, lastMapLicense;
    private static final Runnable LICENSE_POLL = new Runnable() {
        @Override public void run() {
            if (context == null) return;
            boolean hudAllowed = licensed(com.shilapi.xcertplay.licensing.LicenseFeatures.HUD);
            boolean mapAllowed = licensed(com.shilapi.xcertplay.licensing.LicenseFeatures.INSTRUMENT_MAP);
            if (hudAllowed != lastHudLicense || mapAllowed != lastMapLicense) {
                lastHudLicense = hudAllowed; lastMapLicense = mapAllowed;
                updateWindows(); enqueue(); bind();
            }
            MAIN.postDelayed(this, 1000);
        }
    };
    private static final SharedPreferences.OnSharedPreferenceChangeListener SETTINGS = (prefs, key) -> {
        if ("navigation_enabled".equals(key) || "hongqi_cluster_visible".equals(key)) MAIN.post(() -> {
            updateWindows();
            enqueue();
            bind();
        });
    };

    private HongqiNavigation() {}

    public static boolean supported() {
        return Build.VERSION.SDK_INT == 28 && "HONG QI".equals(Build.MODEL)
                && "t1c_acrn".equals(Build.DEVICE);
    }

    public static boolean start(Context appContext) {
        if (!outputsEnabled()) return false;
        MAIN.post(() -> {
            if (context == null) {
                context = appContext.getApplicationContext();
                context.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
                        .registerOnSharedPreferenceChangeListener(SETTINGS);
                MAIN.post(LICENSE_POLL);
            }
            bind();
        });
        return true;
    }

    /** Preserve a saved off setting; only change the unset default on this measured platform. */
    public static boolean clusterDefault() { return outputsEnabled(); }

    private static boolean outputsEnabled() {
        return com.shilapi.xcertplay.shared.BuildConfig.FAW_NAVIGATION_OUTPUTS_ENABLED && supported();
    }

    /** Visibility is separate from negotiating the retained map stream, so flying back is immediate. */
    public static boolean mapShown(Context app) {
        if (app == null || !outputsEnabled() || !com.shilapi.xcertplay.licensing.ProductLicense.featureAllowed(app,
                com.shilapi.xcertplay.licensing.LicenseFeatures.INSTRUMENT_MAP)) return false;
        return app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
                .getBoolean("hongqi_cluster_visible", true);
    }

    public static void setMapShown(Context app, boolean shown) {
        if (!outputsEnabled()) return;
        app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
                .edit().putBoolean("hongqi_cluster_visible", shown).apply();
    }

    public static Display findDisplay(Context appContext) {
        if (!outputsEnabled() || !Settings.canDrawOverlays(appContext)) return null;
        DisplayManager manager = appContext.getSystemService(DisplayManager.class);
        if (manager == null) return null;
        for (Display display : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            Point size = new Point();
            display.getRealSize(size);
            if (display.isValid() && display.getDisplayId() != Display.DEFAULT_DISPLAY
                    && "Cluster-Display".equals(display.getName()) && size.x == 1920 && size.y == 720) {
                return display;
            }
        }
        return null;
    }

    /** Called during onCreate, before the existing Presentation attaches its window. */
    public static void preparePresentation(Presentation presentation) {
        if (!outputsEnabled()) return;
        start(presentation.getContext());
        Window window = presentation.getWindow();
        if (window == null) return;
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        WINDOWS.put(presentation, false);
        setVisible(presentation, false);
    }

    public static void streamActive(Presentation presentation, boolean ready) {
        if (!outputsEnabled()) return;
        WINDOWS.put(presentation, ready);
        updateWindows();
        enqueue();
    }

    /** Also called when Android removes the display, so a retained ready flag cannot outlive it. */
    public static void presentationStopped(Presentation presentation) {
        if (!outputsEnabled()) return;
        setVisible(presentation, false);
        WINDOWS.remove(presentation);
        updateWindows();
        enqueue();
    }

    /** Uses the existing validated iAP2 route decoder; it never runs Binder I/O on that thread. */
    public static void onGuidance(ClusterTurnGuidance next) {
        if (!outputsEnabled()) return;
        if ((guidance == null) != (next == null)) Log.i(TAG, "route active=" + (next != null));
        guidance = next;
        MAIN.post(() -> { updateWindows(); bind(); enqueue(); });
    }

    public static void clear() { onGuidance(null); }

    /** Guidance is cleared by the existing route/session lifecycle, including disconnect. */
    public static boolean isNavigating() { return outputsEnabled() && guidance != null; }

    private static boolean enabled() {
        Context app = context;
        return app != null && licensed(com.shilapi.xcertplay.licensing.LicenseFeatures.HUD)
                && app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
                .getBoolean("navigation_enabled", true);
    }
    private static boolean licensed(String feature) {
        return context != null && com.shilapi.xcertplay.licensing.ProductLicense.featureAllowed(context, feature);
    }

    private static void updateWindows() {
        boolean ready = false;
        for (Map.Entry<Presentation, Boolean> entry : WINDOWS.entrySet()) {
            boolean active = Boolean.TRUE.equals(entry.getValue()) && entry.getKey().isShowing();
            ready |= active;
            setVisible(entry.getKey(), mapInterfacesReady && mapShown(context) && guidance != null && active);
        }
        mapReady = ready;
    }

    private static void setVisible(Presentation presentation, boolean visible) {
        Window window = presentation.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams attrs = window.getAttributes();
        float alpha = visible ? 1f : 0f;
        if (attrs.alpha != alpha) { attrs.alpha = alpha; window.setAttributes(attrs); }
    }

    private static void bind() {
        if (context == null || bound || (!enabled() && !mapShown(context) && guidance == null && !cleanupPending)) return;
        MAIN.removeCallbacks(REBIND);
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
                "ltd.qisi.adapterservice", "ltd.qisi.adapterservice.QISIAdapterService"));
        try {
            bound = context.bindService(intent, CONNECTION, Context.BIND_AUTO_CREATE);
            if (!bound) { Log.w(TAG, "navigation service bind refused"); MAIN.postDelayed(REBIND, 3000); }
        } catch (RuntimeException failure) {
            Log.w(TAG, "navigation service bind failed: " + failure.getClass().getSimpleName());
            MAIN.postDelayed(REBIND, 3000);
        }
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder client) {
            WRITER.execute(() -> {
                try {
                    if (!CLIENT.equals(client.getInterfaceDescriptor())) throw new SecurityException("client interface");
                    hud = module(client, 23, HUD);
                    instrument = module(client, 8, INSTRUMENT);
                    control = module(client, 13, CONTROL);
                    registerMapExitListener();
                    mapInterfacesReady = instrument != null && control != null;
                    instrumentGuidanceSupported = true;
                    lastInstrumentGuidance = null;
                    replayMap = true;
                    replayInstrumentGuidance = true;
                    MAIN.post(() -> updateWindows());
                    Log.i(TAG, "navigation modules ready hud=" + (hud != null)
                            + " instrument=" + (instrument != null) + " control=" + (control != null));
                    apply();
                } catch (RemoteException | RuntimeException failure) { disconnected(failure); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            WRITER.execute(() -> disconnected(null));
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    private static IBinder module(IBinder client, int id, String expected) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLIENT); data.writeInt(id);
            if (!client.transact(1, data, reply, 0)) throw new RemoteException("queryBinder rejected");
            reply.readException();
            IBinder result = reply.readStrongBinder();
            if (result != null && !expected.equals(result.getInterfaceDescriptor()))
                throw new SecurityException("module interface");
            return result;
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void disconnected(Exception failure) {
        releaseMapExitListener();
        hud = null; instrument = null; control = null;
        // Retain ownership across a service restart; the car may still show the last state.
        cleanupPending = ownsHud || ownsMap || ownsInstrumentGuidance;
        mapInterfacesReady = false;
        Log.w(TAG, "navigation service unavailable" + (failure == null ? "" : ": " + failure.getClass().getSimpleName()));
        MAIN.post(() -> {
            updateWindows();
            if (bound) { try { context.unbindService(CONNECTION); } catch (RuntimeException ignored) {} }
            bound = false;
            MAIN.postDelayed(REBIND, 3000);
        });
    }

    private static void registerMapExitListener() {
        releaseMapExitListener();
        final IBinder expected = control;
        final long generation = exitGeneration;
        if (expected == null) return;
        try {
            exitListener = new HongqiNavigationExitListener(expected, () -> WRITER.execute(() -> {
                if (generation != exitGeneration || control != expected || !ownsMap || context == null || !mapShown(context)) return;
                setMapShown(context, false);
                Log.i(TAG, "factory instrument return: map hidden");
            }));
            Log.i(TAG, "factory instrument exit listener registered");
        } catch (RemoteException | RuntimeException failure) {
            // An unavailable optional observer must not stop HUD or map rendering.
            Log.w(TAG, "factory instrument exit listener unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static void releaseMapExitListener() {
        exitGeneration++;
        HongqiNavigationExitListener previous = exitListener;
        exitListener = null;
        if (previous == null) return;
        try { previous.close(); }
        catch (RemoteException | RuntimeException failure) {
            Log.w(TAG, "factory instrument exit listener release unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static void enqueue() {
        // At most one pending update while a vendor Binder is blocked; always read the latest state.
        if (!queued.compareAndSet(false, true)) return;
        WRITER.execute(() -> {
            queued.set(false);
            try { apply(); } catch (RemoteException | RuntimeException failure) { disconnected(failure); }
        });
    }

    private static void apply() throws RemoteException {
        ClusterTurnGuidance next = guidance;
        boolean showHud = next != null && enabled();
        if (!showHud && ownsHud && hud != null) {
            call(hud, HUD, 5, 0);
            call(hud, HUD, 70, 0, 0);
            call(hud, HUD, 6, 0);
            ownsHud = false;
            Log.i(TAG, "HUD guidance cleared");
        }
        boolean showInstrumentGuidance = next != null && instrument != null
                && licensed(com.shilapi.xcertplay.licensing.LicenseFeatures.INSTRUMENT_MAP);
        if (!showInstrumentGuidance && ownsInstrumentGuidance && instrument != null) {
            sendInstrumentGuidance(null);
            call(instrument, INSTRUMENT, 5, 0);
            ownsInstrumentGuidance = false;
        }
        boolean showMap = next != null && mapShown(context) && mapReady && mapInterfacesReady;
        if (!showMap && ownsMap && instrument != null && control != null) {
            fullMap(0);
            call(control, CONTROL, 69, 0, 0);
            ownsMap = false;
            // The factory map exit may reset its navigation view; replay the live hints afterward.
            replayInstrumentGuidance = true;
            lastInstrumentGuidance = null;
            Log.i(TAG, "instrument map cleared");
        }
        if (next == null) {
            cleanupPending = ownsHud || ownsMap || ownsInstrumentGuidance;
            return;
        }
        if (showInstrumentGuidance && (!ownsInstrumentGuidance || replayInstrumentGuidance)) {
            // Retain ownership if a later Binder write fails so route end can still clear it.
            ownsInstrumentGuidance = true;
            call(instrument, INSTRUMENT, 5, 1);
            replayInstrumentGuidance = false;
        }
        if (showMap && (!ownsMap || replayMap)) {
            // Mark before the first write so a later failure still triggers cleanup on rebind.
            ownsMap = true;
            fullMap(2);
            // Matches the stock setFullScreenNavigationView(1, false) sequence.
            call(control, CONTROL, 69, 1, 0);
            fullMap(4);
            replayMap = false;
            Log.i(TAG, "instrument map stream active");
        }
        if (showInstrumentGuidance) sendInstrumentGuidance(next);
        if (showHud && hud != null) {
            ownsHud = true;
            int icon = next.getIcon();
            if (icon < 0 || icon > 31) icon = 0;
            call(hud, HUD, 5, icon);
            int[] distance = nativeDistance(next.getDistanceMeters());
            call(hud, HUD, 70, distance[0], distance[1]);
            int exit = next.getRoundaboutExit();
            call(hud, HUD, 6, exit >= 0 && exit <= 16 ? exit : 0);
        }
        cleanupPending = ownsHud || ownsMap || ownsInstrumentGuidance;
    }

    /** Exact thresholds and integer units used by stock NaviDataManager: <15m clears distance. */
    static int[] nativeDistance(int meters) {
        if (meters < 15) return new int[] {0, 0};
        if (meters < 1000) return new int[] {1, meters};
        if (meters < 10000) return new int[] {2, meters / 100};
        return new int[] {3, meters / 1000};
    }

    private static void sendInstrumentGuidance(ClusterTurnGuidance next) {
        if (instrument == null || !instrumentGuidanceSupported) return;
        HongqiInstrumentGuidance info = HongqiInstrumentGuidance.from(next);
        if (info.equals(lastInstrumentGuidance)) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INSTRUMENT);
            data.writeInt(1); // QisiNaviInfo is present, including when clearing its fields.
            info.writeTo(data);
            if (!instrument.transact(INSTRUMENT_GUIDANCE_TRANSACTION, data, reply, 0))
                throw new RemoteException("instrument guidance rejected");
            reply.readException();
            checkResult(reply.readInt());
            lastInstrumentGuidance = info;
        } catch (RemoteException | RuntimeException failure) {
            // Optional ETE/TBT output must not tear down the working map and HUD channels.
            instrumentGuidanceSupported = false;
            Log.w(TAG, "instrument ETE/TBT unavailable: " + failure.getClass().getSimpleName());
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void fullMap(int loadStatus) throws RemoteException {
        if (instrument == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INSTRUMENT);
            data.writeInt(1); // QisiFullMapInfo is present.
            data.writeInt(0); // Navi_FullMapStatus, followed by Navi_FullMapLoadStatus.
            data.writeInt(loadStatus);
            if (!instrument.transact(10, data, reply, 0)) throw new RemoteException("fullMap rejected");
            reply.readException(); checkResult(reply.readInt());
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void call(IBinder binder, String token, int transaction, int... values) throws RemoteException {
        if (binder == null) return;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(token);
            for (int value : values) data.writeInt(value);
            if (!binder.transact(transaction, data, reply, 0)) throw new RemoteException("navigation transaction rejected");
            reply.readException(); checkResult(reply.readInt());
        } finally { data.recycle(); reply.recycle(); }
    }

    private static void checkResult(int result) throws RemoteException {
        if (result < 0) throw new RemoteException("navigation result " + result);
    }
}
