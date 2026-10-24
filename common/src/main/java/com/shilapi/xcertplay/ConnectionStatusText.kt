package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Full-screen CarPlay host. It renders decoded video through a [VideoSurfaceView], forwards touch to
 * the active AirPlay session. [CarPlayBackgroundSession] owns connection startup and recovery.
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
internal object ConnectionStatusText {
    fun friendly(context: Context, message: String): String = when {
        message == context.getString(R.string.carplay_vehicle_waiting) -> message
        message == context.getString(R.string.carplay_waiting_for_previous_connection) -> message
        message == context.getString(R.string.carplay_manually_disconnected) -> message
        message == context.getString(R.string.carplay_vehicle_ignition_off) -> message
        message == context.getString(R.string.wireless_startup_retries_exhausted) -> message
        message == context.getString(R.string.carplay_waiting_for_bluetooth) -> message
        message.startsWith("设备授权：") -> message
        message.startsWith("CarPlay准备失败") || message.startsWith("上一连接尚未释放") || message == "正在保存连接状态" -> message
        message == context.getString(R.string.waiting_for_mfi_coprocessor) ||
            message == context.getString(R.string.requesting_mfi_usb_permission) -> message
        message.contains("Turn on Wi-Fi", true) -> context.getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        message.contains("Allow precise Location", true) -> context.getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        message.contains("Allow Nearby devices", true) -> context.getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        message.contains("frequencyMHz=unknown", true) -> "无法读取车机 Wi-Fi Direct 信道，请点“重新连接”；仍然失败时请保存诊断报告。"
        message.contains("createGroup failed", true) -> context.getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> context.getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        message.contains("socket", true) || message.contains("RFCOMM", true) -> context.getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        message.contains("unsupported", true) || message.contains("not supported", true) -> context.getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        message.contains("denied", true) || message.contains("permission", true) -> context.getString(R.string.allow_the_connection_permission_to_continue)
        message.contains("Failed", true) || message.contains("error", true) -> message
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> context.getString(R.string.connect_your_iphone_with_a_usb_cable)
        message.contains("paired", true) -> context.getString(R.string.looking_for_your_paired_iphone)
        message.contains("Bluetooth", true) -> context.getString(R.string.connecting_to_your_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> context.getString(R.string.reconnecting_to_your_iphone)
        message.contains("active", true) || message.contains("running", true) -> context.getString(R.string.opening_carplay)
        else -> context.getString(R.string.getting_carplay_ready)
    }

    fun describe(context: Context, status: CarPlayStatus, wirelessHotspotMode: WirelessHotspotMode, wirelessEnabled: Boolean): String = with(status) { when (this) {
        CarPlayStatus.DiscoveringMfi -> context.getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> context.getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> context.getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> context.getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> context.getString(if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                context.getString(R.string.existing_wifi_ready, ssid, band, channel, address)
            } else context.getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) context.getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> context.getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.WaitingForBluetooth -> context.getString(R.string.carplay_waiting_for_bluetooth)
        CarPlayStatus.ConnectingBluetooth -> context.getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> context.getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive -> context.getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> context.getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> context.getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> context.getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> context.getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> context.getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> context.getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> context.getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> context.getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) context.getString(R.string.starting_airplay_service) else context.getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> context.getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> context.getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.AuthorizationRequired -> "设备授权：$message"
        is CarPlayStatus.Failed -> when (startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> context.getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> context.getString(R.string.first_tcp_timeout)
            else -> context.getString(R.string.status_failed, message)
        }
    } }
}
