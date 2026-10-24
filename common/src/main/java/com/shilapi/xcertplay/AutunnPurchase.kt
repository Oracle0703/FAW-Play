package com.shilapi.xcertplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import com.shilapi.xcertplay.host.R

/** Public purchase link supplied by the app owner; no device credential is sent to the shop. */
internal object AutunnPurchase {
    const val URL = "https://wzyp.cn/item/lwbqa1"
    fun qr(activity: Activity) = ImageView(activity).apply {
        tag = "autunn_purchase_qr"
        setBackgroundColor(Color.WHITE)
        setImageResource(R.drawable.autunn_purchase_qr)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "用手机扫码购买授权码；购买后生成设备激活二维码并输入授权码。"
    }
    fun open(activity: Activity) {
        try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(activity, "此设备没有可用浏览器，请用手机扫描购买二维码。", Toast.LENGTH_LONG).show() }
    }
}
