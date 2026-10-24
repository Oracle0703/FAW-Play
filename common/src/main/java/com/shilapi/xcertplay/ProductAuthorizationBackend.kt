package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.licensing.DeviceActivation
import com.shilapi.xcertplay.licensing.LicenseDecision
import com.shilapi.xcertplay.licensing.ProductLicense
import com.shilapi.xcertplay.api.HttpRequestControl
import java.util.concurrent.atomic.AtomicBoolean

internal class ProductAuthorizationBackend(context: Context) : AutunnAuthorizationPanel.Backend {
    private val context = context.applicationContext
    override fun check() = ProductLicense.check(context)
    override fun configurationProblem() = ProductLicense.configurationProblem()
    override fun beginActivation() = ProductLicense.beginActivation(context)
    override fun beginActivation(cancelled: AtomicBoolean) =
        ProductLicense.beginActivation(context, HttpRequestControl(cancelled = cancelled))
    override fun awaitActivation(grant: DeviceActivation, cancelled: AtomicBoolean) =
        ProductLicense.awaitActivation(context, grant, cancelled)
    override fun cancelActivation(grant: DeviceActivation) = ProductLicense.cancelActivation(context, grant)
    override fun refresh() = ProductLicense.refresh(context)
    override fun refresh(cancelled: AtomicBoolean) =
        ProductLicense.refresh(context, HttpRequestControl(cancelled = cancelled))
    override fun load(result: (LicenseDecision) -> Unit) = ProductLicense.checkAsync(context, result)
}

