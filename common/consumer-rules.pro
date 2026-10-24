# JNI looks up the exception constructor by its original name.
-keep class com.shilapi.xcertplay.transport.LinuxI2cNativeException {
    <init>(int, java.lang.String);
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The native Autunn settings and wheel integration currently invoke these members by name.
-keepclassmembers class com.shilapi.xcertplay.DiPlaySettingsActivity {
    java.lang.String page;
    java.lang.String setupError;
    void render();
    void openProjection();
    void connect(boolean);
    void reconnectForClusterMap();
    void onLocationReportingChanged(boolean);
    void confirmWirelessReset();
    void exportDiagnostics(android.net.Uri);
    void confirmDiagnosticUpload();
    void chooseReportDestination();
}
-keepclassmembers class com.shilapi.xcertplay.CarPlayHostActivity {
    android.view.View connectionPanel;
    android.view.View wifiRecoveryButton;
    android.view.View startupRetryButton;
    android.widget.TextView stageStatusView;
    java.lang.String latestStage;
    boolean touchOutsideContent;
    void showDiPlayHome(java.lang.String);
    void releaseTouch();
    void reconnectAfterLoss(java.lang.String, com.shilapi.xcertplay.network.WirelessStartupFailure, boolean);
}
-keep,allowoptimization class com.shilapi.xcertplay.AirPlayPersistence {
    public static ** INSTANCE;
    *** loadAutoStartOnBoot(...);
    *** saveAutoStartOnBoot(...);
    *** loadLocationReportingEnabled(...);
    *** loadDebugLogsEnabled(...);
    *** saveDebugLogsEnabled(...);
    *** saveWifiP2pPreferredChannel(...);
}
-keep,allowoptimization class com.shilapi.xcertplay.AppLocale {
    public static ** INSTANCE;
    *** preference(...);
    *** save(...);
}
-keep,allowoptimization class com.shilapi.xcertplay.CarPlayBackgroundSession {
    public static ** INSTANCE;
    *** hasSession(...);
    *** getActive(...);
    *** stop(kotlin.jvm.functions.Function0);
    *** snapshot(...);
}
-keepclassmembers class com.shilapi.xcertplay.CarPlayBackgroundSession$Snapshot {
    *** getController();
}
-keep,allowoptimization class com.shilapi.xcertplay.WheelKeyService {
    public static ** Companion;
}
-keepclassmembers class com.shilapi.xcertplay.WheelKeyService$Companion {
    *** connected();
    *** cancelLearning();
}
-keepclassmembers class com.shilapi.xcertplay.orchestration.CarPlayController {
    ** activeSession;
    ** appContext;
    ** touchExecutor;
    *** isClosed();
    *** activeAirPlaySessionToken();
    *** requestSiri();
    *** sendSiriButton(boolean, java.lang.Object);
    *** nativeScreenFocusChanged(int, java.lang.Object);
    *** selectProjectionScreen(boolean);
    *** sendMediaButton(int);
    *** sendTelephonyButton(int);
}
-keepclassmembers class com.shilapi.xcertplay.airplay.AirPlaySession {
    ** eventWriteLock;
    int eventCseq;
    *** sendCommand(java.util.Map);
    *** sendTelephony(int);
}
-keepnames enum com.shilapi.xcertplay.network.WirelessStartupFailure
