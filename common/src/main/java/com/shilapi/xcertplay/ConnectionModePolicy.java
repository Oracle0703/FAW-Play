// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay;

import android.content.Context;
import android.content.SharedPreferences;
import com.shilapi.xcertplay.network.WifiP2pChannels;
import java.util.Map;

/** This HS5 edition has one connection path: wireless CarPlay over Wi-Fi Direct. */
public final class ConnectionModePolicy {
    private static final String PREFERENCES_NAME = "xcertplay_airplay";
    private static final String KEY_WIRELESS_ENABLED = "wireless_enabled";
    private static final String KEY_HOTSPOT_MODE = "wireless_hotspot_mode";
    private static final String KEY_PREFERRED_CHANNEL = "wifi_p2p_preferred_channel";
    private static final String WIFI_DIRECT_MODE = "WIFI_P2P";

    private ConnectionModePolicy() {}

    public static void enforce(Context context) {
        AutunnSystemParameters.enforce(context);
        final SharedPreferences prefs =
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        // Legacy values may have different types; typed getters would throw before migration.
        final Map<String, ?> stored = prefs.getAll();
        if (Boolean.TRUE.equals(stored.get(KEY_WIRELESS_ENABLED))
                && WIFI_DIRECT_MODE.equals(stored.get(KEY_HOTSPOT_MODE))
                && Integer.valueOf(WifiP2pChannels.AUTO).equals(stored.get(KEY_PREFERRED_CHANNEL))) {
            return;
        }

        prefs.edit()
                .putBoolean(KEY_WIRELESS_ENABLED, true)
                .putString(KEY_HOTSPOT_MODE, WIFI_DIRECT_MODE)
                .putInt(KEY_PREFERRED_CHANNEL, WifiP2pChannels.AUTO)
                .apply();
    }
}
