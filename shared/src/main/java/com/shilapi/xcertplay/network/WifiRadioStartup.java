// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.network;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

/** Bring up the Wi-Fi radio before Wi-Fi Direct; never controls tethering. */
public final class WifiRadioStartup {
    public static final int READY = 0, ENABLING = 1, USER_ACTION = 2, UNAVAILABLE = 3;
    private static final String TAG = "Autunn-WifiStartup";
    private WifiRadioStartup() {}

    /** setWifiEnabled accepts a request; the connection worker must still await readiness. */
    @SuppressWarnings("deprecation")
    public static int requestEnable(Context context) {
        try {
            WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
            if (wifi == null) return UNAVAILABLE;
            if (wifi.isWifiEnabled()) return READY;
            if (wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLING) return ENABLING;
            // Android 10+ requires the system Wi-Fi panel for ordinary applications.
            if (Build.VERSION.SDK_INT >= 29) return USER_ACTION;
            if (wifi.setWifiEnabled(true)) {
                Log.i(TAG, "Wi-Fi enable requested before Wi-Fi Direct");
                return ENABLING;
            }
            Log.w(TAG, "Wi-Fi enable request refused; system action required");
            return USER_ACTION;
        } catch (SecurityException denied) {
            Log.w(TAG, "Wi-Fi enable requires system authorization");
            return USER_ACTION;
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Wi-Fi radio unavailable: " + unavailable.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }
}
