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
import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private static volatile UltraPresentationLease ultraPresentation;
    private static volatile int ultraOemFocus;
    private static final AtomicLong ULTRA_FOCUS_GENERATION = new AtomicLong();
    private static volatile Context context;
    private static volatile ClusterTurnGuidance guidance;
    private static volatile boolean routeActive;
    private static volatile boolean mapReady;
    private static volatile boolean mapRecoveryExpired;
    private static final Runnable MAP_OUTPUT_TIMEOUT = () -> {
        if (!mapReady && routeActive) {
            mapRecoveryExpired = true;
            com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("instrument", "streamRecoveryExpired=true");
            enqueue();
        }
    };
    private static volatile boolean mapInterfacesReady;
    private static volatile boolean cleanupPending;
    private static final AtomicBoolean queued = new AtomicBoolean();
    private static volatile boolean bound;
    private static volatile String moduleStatus = "hud=false instrument=false control=false";
    // Binder state and ownership are confined to WRITER.
    private static IBinder client, hud, instrument, control;
    private static volatile HongqiNavigationExitListener exitListener;
    private static long exitGeneration;
    private static boolean ownsHud, replayMap;
    private static volatile boolean ownsMap;
    private static volatile int mapLoadStep;
    private static boolean ownsInstrumentGuidance, replayInstrumentGuidance;
    private static boolean instrumentGuidanceSupported = true;
    private static HongqiInstrumentGuidance lastInstrumentGuidance;
    private static final Runnable REBIND = () -> bind();
    private static final Runnable RETRY_MODULES = () -> WRITER.execute(() -> {
        if (client == null) return;
        try { refreshModules(); }
        catch (RemoteException | RuntimeException failure) { disconnected(failure); }
    });
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

    /**
     * Borrows only the measured OEM map surface. It never disables a stock component or changes
     * the SOS warning layers. The caller's stationary policy decides when pixels are eligible.
     */
    public static UltraPresentationLease acquireUltraPresentation(Context app, Presentation presentation,
            Runnable onFactoryYield) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("main thread required");
        if (!outputsEnabled()) throw new IllegalStateException("HS5 output unavailable");
        if (!com.shilapi.xcertplay.licensing.ProductLicense.featureAllowed(app,
                com.shilapi.xcertplay.licensing.LicenseFeatures.INSTRUMENT_MAP))
            throw new IllegalStateException("Instrument output unavailable");
        Display selected = findDisplay(app);
        if (selected == null || selected.getDisplayId() != presentation.getDisplay().getDisplayId())
            throw new IllegalArgumentException("HS5 cluster display required");
        UltraPresentationLease old = ultraPresentation;
        if (old != null) old.closeNow();
        UltraPresentationLease lease = new UltraPresentationLease(presentation, onFactoryYield);
        ultraPresentation = lease;
        start(app);
        Window window = presentation.getWindow();
        if (window != null) {
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
        lease.classicReady = WINDOWS.remove(presentation);
        setVisible(presentation, false);
        updateWindows();
        enqueue();
        return lease;
    }

    /** OEM view 38 callbacks share camera / E-CALL priority with the existing CarPlay main view. */
    public static synchronized void onUltraOemViewFocus(int change) {
        final long generation = ULTRA_FOCUS_GENERATION.incrementAndGet();
        ultraOemFocus = change;
        MAIN.post(() -> {
            if (ULTRA_FOCUS_GENERATION.get() != generation) return;
            UltraPresentationLease lease = ultraPresentation;
            if (lease == null || lease.closed) return;
            if (change == 1) {
                lease.preempted = false;
            } else {
                lease.yieldNow(false);
            }
            updateWindows();
            enqueue();
        });
    }

    public static final class UltraPresentationLease implements Closeable {
        private final Presentation presentation;
        private final Runnable onFactoryYield;
        private Boolean classicReady;
        private volatile boolean requested, preempted, hardQuit, closed;
        private UltraPresentationLease(Presentation presentation, Runnable onFactoryYield) {
            this.presentation = presentation;
            this.onFactoryYield = onFactoryYield;
            this.preempted = ultraOemFocus != 1;
        }
        /** Availability means a live native map contract and exit observer, never display proof. */
        public boolean isFactoryAvailable() {
            return !closed && ultraPresentation == this && mapInterfacesReady && exitListener != null
                    && licensed(com.shilapi.xcertplay.licensing.LicenseFeatures.INSTRUMENT_MAP);
        }
        public boolean isPreempted() { return preempted || hardQuit || ultraOemFocus != 1; }
        public void setRequested(boolean value) {
            MAIN.post(() -> {
                if (closed || ultraPresentation != this) return;
                boolean next = value && !isPreempted();
                if (requested == next) return;
                requested = next;
                updateWindows();
                enqueue();
            });
        }
        private void yieldNow(boolean factoryQuit) {
            preempted = true;
            hardQuit |= factoryQuit;
            requested = false;
            setVisible(presentation, false);
            if (onFactoryYield != null) onFactoryYield.run();
        }
        private void closeNow() {
            if (closed) return;
            closed = true;
            requested = false;
            setVisible(presentation, false);
            if (ultraPresentation == this) ultraPresentation = null;
            if (classicReady != null && presentation.isShowing()) WINDOWS.put(presentation, classicReady);
            updateWindows();
            enqueue();
        }
        @Override public void close() {
            if (Looper.myLooper() == Looper.getMainLooper()) closeNow(); else MAIN.post(this::closeNow);
        }
    }

    public static boolean supported() {
        return com.shilapi.xcertplay.vehicle.Hs5Platform.current();
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
        return com.shilapi.xcertplay.shared.BuildConfig.AUTUNN_NAVIGATION_OUTPUTS_ENABLED && supported();
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
        AutunnNavigationOutputs.refreshClusterMapVisibility();
    }

    public static Display findDisplay(Context appContext) {
        // Discover the physical screen independently of the window permission preflight.
        // WindowManager still enforces TYPE_APPLICATION_OVERLAY when Presentation.show runs.
        if (!outputsEnabled()) return null;
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
        UltraPresentationLease ultra = ultraPresentation;
        if (ultra != null && ultra.presentation == presentation) {
            ultra.classicReady = ready;
            return;
        }
        WINDOWS.put(presentation, ready);
        updateWindows();
        enqueue();
    }

    /** Also called when Android removes the display, so a retained ready flag cannot outlive it. */
    public static void presentationStopped(Presentation presentation) {
        if (!outputsEnabled()) return;
        UltraPresentationLease ultra = ultraPresentation;
        if (ultra != null && ultra.presentation == presentation) ultra.closeNow();
        setVisible(presentation, false);
        WINDOWS.remove(presentation);
        if (!presentation.getDisplay().isValid()) {
            mapRecoveryExpired = true;
            MAIN.removeCallbacks(MAP_OUTPUT_TIMEOUT);
        }
        updateWindows();
        enqueue();
    }

    /** Uses the existing validated iAP2 route decoder; it never runs Binder I/O on that thread. */
    public static void onGuidance(ClusterTurnGuidance next) {
        onRouteGuidance(next != null, next);
    }

    /** Route ownership survives gaps in the independently delivered maneuver details. */
    public static void onRouteGuidance(boolean active, ClusterTurnGuidance next) {
        if (!outputsEnabled()) return;
        boolean routeStarted = !routeActive && active;
        if (routeActive != active) Log.i(TAG, "route active=" + active);
        routeActive = active;
        guidance = active ? next : null;
        if (routeStarted) AutunnNavigationOutputs.requestClusterContent();
        MAIN.post(() -> { updateWindows(); bind(); enqueue(); });
    }

    public static void clear() { onGuidance(null); }

    /** Guidance is cleared by the existing route/session lifecycle, including disconnect. */
    public static boolean isNavigating() { return outputsEnabled() && routeActive; }

    /** Non-sensitive state included in exported diagnostics; no vendor Binder calls are made here. */
    public static String diagnosticReport(Context app) {
        Display display = findDisplay(app);
        return "supported=" + supported()
                + "\noverlayPermissionPreflight=" + Settings.canDrawOverlays(app)
                + "\nselectedCluster=" + (display == null ? "none" : display.getDisplayId() + ":" + display.getName())
                + "\nserviceStarted=" + (context != null) + " serviceBound=" + bound
                + "\nmodules " + moduleStatus
                + "\nmapStreamReady=" + mapReady + " mapInterfacesReady=" + mapInterfacesReady
                + "\nrouteActive=" + routeActive + " guidanceActive=" + (guidance != null)
                + " hudEnabled=" + enabled() + " mapShown=" + mapShown(app);
    }

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
        UltraPresentationLease ultra = ultraPresentation;
        boolean ready = false;
        for (Map.Entry<Presentation, Boolean> entry : WINDOWS.entrySet()) {
            boolean active = Boolean.TRUE.equals(entry.getValue()) && entry.getKey().isShowing();
            ready |= active;
            setVisible(entry.getKey(), ultra == null && mapInterfacesReady && mapShown(context) && routeActive && active);
        }
        if (ultra != null) setVisible(ultra.presentation, mapInterfacesReady && ownsMap && mapLoadStep == 3 && ultra.requested
                && !ultra.isPreempted() && ultra.presentation.isShowing());
        if (ready || !routeActive) {
            MAIN.removeCallbacks(MAP_OUTPUT_TIMEOUT);
            mapRecoveryExpired = false;
        } else if (mapReady && !mapRecoveryExpired) {
            // A watchdog for failed pixel recovery, never a delay on healthy map entry.
            MAIN.removeCallbacks(MAP_OUTPUT_TIMEOUT);
            MAIN.postDelayed(MAP_OUTPUT_TIMEOUT, 10_000);
        }
        mapReady = ready;
        AutunnNavigationOutputs.refreshClusterMapVisibility();
    }

    private static void setVisible(Presentation presentation, boolean visible) {
        Window window = presentation.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams attrs = window.getAttributes();
        float alpha = visible ? 1f : 0f;
        if (attrs.alpha != alpha) { attrs.alpha = alpha; window.setAttributes(attrs); }
    }

    private static void bind() {
        if (context == null || bound || (!enabled() && !mapShown(context) && !routeActive && !cleanupPending)) return;
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
        @Override public void onServiceConnected(ComponentName name, IBinder connectedClient) {
            WRITER.execute(() -> {
                try {
                    if (!CLIENT.equals(connectedClient.getInterfaceDescriptor())) throw new SecurityException("client interface");
                    releaseMapExitListener();
                    client = connectedClient;
                    hud = null; instrument = null; control = null;
                    mapInterfacesReady = false;
                    refreshModules();
                } catch (RemoteException | RuntimeException failure) { disconnected(failure); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            WRITER.execute(() -> disconnected(null));
        }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
        @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
    };

    /** The service can bind before its individual modules finish starting. */
    private static void refreshModules() throws RemoteException {
        if (hud == null) hud = module(client, 23, HUD);
        if (instrument == null) {
            instrument = module(client, 8, INSTRUMENT);
            if (instrument != null) {
                instrumentGuidanceSupported = true;
                lastInstrumentGuidance = null;
                replayInstrumentGuidance = true;
            }
        }
        if (control == null) {
            control = module(client, 13, CONTROL);
            if (control != null) registerMapExitListener();
        }
        boolean ready = instrument != null && control != null;
        if (ready && !mapInterfacesReady) replayMap = true;
        mapInterfacesReady = ready;
        boolean modulesPending = hud == null || instrument == null || control == null;
        moduleStatus = "hud=" + (hud != null) + " instrument=" + (instrument != null) + " control=" + (control != null);
        Log.i(TAG, "navigation modules ready " + moduleStatus);
        MAIN.post(() -> {
            updateWindows();
            MAIN.removeCallbacks(RETRY_MODULES);
            if (modulesPending) MAIN.postDelayed(RETRY_MODULES, 3000);
        });
        apply();
    }

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
        com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("navigation", "rootServiceDisconnected=true");
        releaseMapExitListener();
        client = null;
        hud = null; instrument = null; control = null;
        moduleStatus = "hud=false instrument=false control=false";
        // Retain ownership across a service restart; the car may still show the last state.
        cleanupPending = ownsHud || ownsMap || ownsInstrumentGuidance;
        mapInterfacesReady = false;
        Log.w(TAG, "navigation service unavailable" + (failure == null ? "" : ": " + failure.getClass().getSimpleName()));
        MAIN.post(() -> {
            UltraPresentationLease ultra = ultraPresentation;
            if (ultra != null) {
                ultra.requested = false;
                setVisible(ultra.presentation, false);
                if (ultra.onFactoryYield != null) ultra.onFactoryYield.run();
            }
            MAIN.removeCallbacks(RETRY_MODULES);
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
                if (generation != exitGeneration || control != expected || !ownsMap || context == null) return;
                UltraPresentationLease ultra = ultraPresentation;
                if (ultra != null) {
                    MAIN.post(() -> {
                        if (ultraPresentation != ultra || ultra.closed) return;
                        ultra.yieldNow(true);
                        updateWindows(); enqueue();
                    });
                    return;
                }
                if (!mapShown(context)) return;
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
            apply();
        });
    }

    private interface OutputOperation { void run() throws RemoteException; }

    private static void apply() {
        final ClusterTurnGuidance next = guidance;
        applyOutput("instrument", () -> applyInstrument(next));
        applyOutput("hud", () -> applyHud(next));
        cleanupPending = ownsHud || ownsMap || ownsInstrumentGuidance;
    }

    private static void applyOutput(String output, OutputOperation operation) {
        try { operation.run(); }
        catch (RemoteException | RuntimeException failure) {
            com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("navigation",
                    "output=" + output + " failure=" + failure.getClass().getSimpleName()
                            + (failure instanceof HongqiNavigationTransport.Rejected
                            ? " result=" + ((HongqiNavigationTransport.Rejected) failure).result : ""));
            // A rejected business command is not evidence that a live Binder has died.
            if (failure instanceof HongqiNavigationTransport.Rejected) return;
            if (client != null && !client.isBinderAlive()) { disconnected(failure); return; }
            boolean changed = false;
            if (deadModule(hud, failure)) { hud = null; changed = true; }
            if (deadModule(instrument, failure)) { instrument = null; changed = true; }
            if (deadModule(control, failure)) {
                releaseMapExitListener(); control = null; changed = true;
            }
            if (changed) {
                moduleStatus = "hud=" + (hud != null) + " instrument=" + (instrument != null) + " control=" + (control != null);
                mapInterfacesReady = instrument != null && control != null;
                MAIN.post(() -> {
                    updateWindows();
                    MAIN.removeCallbacks(RETRY_MODULES);
                    MAIN.postDelayed(RETRY_MODULES, 3000);
                });
            }
        }
    }

    private static boolean deadModule(IBinder binder, Exception failure) {
        if (binder == null) return false;
        if (!binder.isBinderAlive()) return true;
        return failure instanceof HongqiNavigationTransport.CallFailure
                && ((HongqiNavigationTransport.CallFailure) failure).target == binder
                && failure.getCause() instanceof android.os.DeadObjectException;
    }

    private static void applyInstrument(ClusterTurnGuidance next) throws RemoteException {
        boolean showInstrumentRoute = routeActive && instrument != null
                && licensed(com.shilapi.xcertplay.licensing.LicenseFeatures.INSTRUMENT_MAP);
        boolean showInstrumentGuidance = next != null && showInstrumentRoute;
        if (!showInstrumentGuidance && ownsInstrumentGuidance && instrument != null) {
            sendInstrumentGuidance(null);
            if (!showInstrumentRoute) {
                call(instrument, INSTRUMENT, 5, 0);
                ownsInstrumentGuidance = false;
            }
        }
        UltraPresentationLease ultra = ultraPresentation;
        boolean showMap = ultra != null
                ? ultra.requested && !ultra.isPreempted() && !ultra.closed && mapInterfacesReady
                    && ultra.presentation.isShowing() && ultra.presentation.getDisplay().isValid()
                    && licensed(com.shilapi.xcertplay.licensing.LicenseFeatures.INSTRUMENT_MAP)
                : routeActive && mapShown(context) && mapInterfacesReady && (mapReady || ownsMap && !mapRecoveryExpired);
        if (!showMap && ownsMap && instrument != null && control != null) {
            fullMap(0);
            call(control, CONTROL, 69, 0, 0);
            ownsMap = false;
            mapLoadStep = 0;
            // The factory map exit may reset its navigation view; replay the live hints afterward.
            replayInstrumentGuidance = true;
            lastInstrumentGuidance = null;
            Log.i(TAG, "instrument map cleared");
        }
        if (showInstrumentRoute && (!ownsInstrumentGuidance || replayInstrumentGuidance)) {
            // Retain ownership if a later Binder write fails so route end can still clear it.
            ownsInstrumentGuidance = true;
            call(instrument, INSTRUMENT, 5, 1);
            replayInstrumentGuidance = false;
        }
        if (showMap && (!ownsMap || replayMap || mapLoadStep < 3)) {
            // Mark before the first write so a later failure still triggers cleanup on rebind.
            if (!ownsMap || replayMap) { mapLoadStep = 0; replayMap = false; }
            ownsMap = true;
            // Advance only after an acknowledged command. A rejected later command must
            // not reload the already accepted earlier steps on the next guidance update.
            if (mapLoadStep == 0) {
                if (ultra != null && (!ultra.requested || ultra.isPreempted() || ultra.closed)) return;
                fullMap(2); mapLoadStep = 1;
            }
            if (mapLoadStep == 1) {
                if (ultra != null && (!ultra.requested || ultra.isPreempted() || ultra.closed)) return;
                call(control, CONTROL, 69, 1, 0); mapLoadStep = 2;
            }
            if (mapLoadStep == 2) {
                if (ultra != null && (!ultra.requested || ultra.isPreempted() || ultra.closed)) return;
                fullMap(4); mapLoadStep = 3;
            }
            if (ultra != null) MAIN.post(HongqiNavigation::updateWindows);
            Log.i(TAG, "instrument map stream active");
        }
        if (showInstrumentGuidance) sendInstrumentGuidance(next);
    }

    private static void applyHud(ClusterTurnGuidance next) throws RemoteException {
        boolean showHud = next != null && enabled();
        if (!showHud && ownsHud && hud != null) {
            call(hud, HUD, 5, 0);
            call(hud, HUD, 70, 0, 0);
            call(hud, HUD, 6, 0);
            ownsHud = false;
            Log.i(TAG, "HUD guidance cleared");
        }
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
        com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("instrument", "mapLoadRequested=" + loadStatus);
        HongqiNavigationTransport.fullMap(instrument, INSTRUMENT, loadStatus);
        com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("instrument",
                "mapLoad=" + loadStatus + " route=" + routeActive + " stream=" + mapReady);
    }

    private static void call(IBinder binder, String token, int transaction, int... values) throws RemoteException {
        if (binder == control && transaction == 69)
            com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("instrument", "fullscreenRequested=" + values[0]);
        HongqiNavigationTransport.call(binder, token, transaction, values);
        if (binder == control && transaction == 69)
            com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("instrument", "fullscreen=" + values[0]);
    }

    private static void checkResult(int result) throws RemoteException {
        HongqiNavigationTransport.checkResult(result);
    }
}
