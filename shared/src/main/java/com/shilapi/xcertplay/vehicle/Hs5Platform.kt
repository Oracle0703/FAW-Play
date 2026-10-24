package com.shilapi.xcertplay.vehicle

import android.os.Build

/** Platform identity only. Each capability still needs its own permission and runtime check. */
object Hs5Platform {
    @JvmStatic fun current(): Boolean = matches(Build.VERSION.SDK_INT, Build.MODEL, Build.DEVICE)
    @JvmStatic fun matches(sdk: Int, model: String?, device: String?): Boolean =
        sdk == 28 && model == "HONG QI" && device == "t1c_acrn"
}
