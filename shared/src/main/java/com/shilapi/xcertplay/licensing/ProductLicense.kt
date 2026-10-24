package com.shilapi.xcertplay.licensing

import com.shilapi.xcertplay.api.ApiUrls
import com.shilapi.xcertplay.api.HttpRequestControl
import com.shilapi.xcertplay.api.HttpRequestCancelledException
import com.shilapi.xcertplay.api.readBytesLimited
import com.shilapi.xcertplay.updates.UpdateAuthorization
import com.shilapi.xcertplay.updates.UpdateAuthorizationException

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.shilapi.xcertplay.shared.BuildConfig
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class LicenseDecision(val allowed: Boolean, val message: String, val code: String = "", val expiresAtSeconds: Long? = null,
                           val features: Set<String> = emptySet(), val permanent: Boolean = false)
class ProductLicenseException(val decision: LicenseDecision) : IllegalStateException(decision.message)

object ProductLicense {
    private const val KEY_ALIAS = "faw_play_license_device_v1"
    private val stateLock = Any()
    private val operationLock = Any()
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "product-license").apply { isDaemon = true } }
    private val refreshing = AtomicBoolean(false)
    private data class CachedLicense(val token: String, val anchor: LicenseClockAnchor)
    // Local-owner only: equality proves that this exact ticket/anchor was committed in this
    // process. Never infer a shared monotonic epoch from an unknown persisted BOOT_COUNT.
    private var runtimeCommittedLicense: CachedLicense? = null
    private val memory = LicenseDecisionPublication()
    private val lastReportedDecision = AtomicReference<String?>()
    @Volatile private var localThread: Thread? = null
    private val localExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "product-license-local").apply { isDaemon = true; localThread = this }
    }
    private val localContext = AtomicReference<Context?>()
    private val localRefresh = LicenseSnapshotRefresh(localExecutor, SystemClock::elapsedRealtime) {
        localContext.get()?.let(::checkOnOwner)
    }
    private val nextAutomaticRefresh = AtomicLong(0L)
    private val connectivityStarted = AtomicBoolean(false)
    @Volatile private var internetAvailable = false
    @Volatile private var revokedToken: String? = null

    /** No disk, Keystore, network or contended persistence lock on output/key threads. */
    @JvmStatic fun featureAllowed(context: Context, feature: String): Boolean {
        if (feature !in LicenseFeatures.ALL) return false
        if (!isRequired()) return true
        val decision = currentDecision(context)
        val elapsed = SystemClock.elapsedRealtime()
        val due = nextAutomaticRefresh.get()
        if (elapsed >= due && nextAutomaticRefresh.compareAndSet(due, elapsed + 60_000L)) refreshAsync(context)
        return decision.allowed && feature in decision.features
    }

    private fun currentDecision(context: Context): LicenseDecision {
        requestLocalCheck(context.applicationContext)
        val state = memory.begin()
        val snapshot = state.snapshot
        val result = snapshot?.current(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            ?: LicenseDecisionSnapshot.pending()
        if (snapshot?.decision?.allowed == true && !result.allowed && result.code != "LICENSE_CHECK_PENDING") {
            memory.publish(state, LicenseDecisionSnapshot(result, SystemClock.elapsedRealtime()))
        }
        reportDecision(result)
        return result
    }

    private fun requestLocalCheck(context: Context) {
        localContext.compareAndSet(null, context.applicationContext)
        localRefresh.request()
    }

    private fun <T> onLocalOwner(action: () -> T): T {
        if (Thread.currentThread() === localThread) return action()
        return try { localExecutor.submit(Callable { action() }).get() }
        catch (error: ExecutionException) { throw error.cause ?: error }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
    }

    private fun publish(expected: LicenseDecisionPublication.Revision, decision: LicenseDecision, anchor: LicenseClockAnchor? = null): LicenseDecision {
        val immutable = decision.copy(features = Collections.unmodifiableSet(HashSet(decision.features)))
        val committed = LicenseDecisionSnapshot(immutable, anchor?.elapsedMillis ?: SystemClock.elapsedRealtime(), anchor)
        val result = if (memory.publish(expected, committed)) committed.current(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            else memory.begin().snapshot?.current(System.currentTimeMillis(), SystemClock.elapsedRealtime()) ?: LicenseDecisionSnapshot.pending()
        reportDecision(result)
        return result
    }

    private fun invalidateFeatures() { memory.invalidate() }

    private fun reportDecision(decision: LicenseDecision) {
        val state = "allowed=${decision.allowed} code=${decision.code} features=${decision.features.sorted().joinToString(",")}"
        if (lastReportedDecision.getAndSet(state) != state)
            com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("license", state)
    }

    private fun reportSlowCheck(operation: String, started: Long) {
        val duration = SystemClock.elapsedRealtime() - started
        if (duration >= 1000L)
            com.shilapi.xcertplay.diagnostics.ClientDiagnostics.record("license", "operation=$operation durationMs=$duration")
    }

    @JvmStatic fun isRequired(): Boolean = BuildConfig.AUTUNN_LICENSE_REQUIRED

    @JvmStatic fun configurationProblem(): String? {
        if (BuildConfig.AUTUNN_LICENSE_SERVER_URL.isBlank() || BuildConfig.AUTUNN_LICENSE_PUBLIC_KEY_SPKI.isBlank() ||
            BuildConfig.AUTUNN_LICENSE_OIDC_ISSUER.isBlank() || BuildConfig.AUTUNN_LICENSE_OIDC_CLIENT_ID.isBlank() ||
            !BuildConfig.AUTUNN_LICENSE_SIGNING_CERT_SHA256.matches(Regex("[0-9a-f]{64}"))) {
            return "此安装包尚未配置授权服务器，请联系提供方获取已配置的正式版本。"
        }
        return try {
            ApiUrls.validateConfiguredUrl(BuildConfig.AUTUNN_LICENSE_SERVER_URL)
            ApiUrls.validateConfiguredUrl(BuildConfig.AUTUNN_LICENSE_OIDC_ISSUER)
            null
        } catch (_: Exception) { "授权服务器配置无效，请联系提供方。" }
    }

    /** UI calls read a committed snapshot; worker callers can request a fresh local decision. */
    @JvmStatic fun check(context: Context): LicenseDecision {
        if (!isRequired()) return LicenseDecision(true, "开发版本：授权检查未启用。", "DEVELOPMENT_BUILD")
        return if (Looper.myLooper() == Looper.getMainLooper()) currentDecision(context)
            else onLocalOwner { checkOnOwner(context.applicationContext) }
    }

    /** Runs only on the local owner. HTTP never occupies this executor. */
    private fun checkOnOwner(context: Context): LicenseDecision {
        val started = SystemClock.elapsedRealtime()
        return try { verifyOnOwner(context) }
        finally { reportSlowCheck("local-check", started) }
    }

    private fun verifyOnOwner(context: Context): LicenseDecision {
        val expected = memory.begin()
        configurationProblem()?.let { return publish(expected, LicenseDecision(false, it, "NOT_CONFIGURED")) }
        return synchronized(stateLock) {
            try {
                LicensePackageSignature.verify(context)
                val cached = read(context) ?: return@synchronized publish(expected,
                    LicenseDecision(false, "请用手机扫码输入兑换码，激活这台车机。", "ACTIVATION_REQUIRED"))
                if (cached.token == revokedToken) return@synchronized publish(expected,
                    LicenseDecision(false, "此设备授权已被撤销。", "LICENSE_REVOKED"))
                val key = deviceKey()
                val claims = LicenseVerifier.verify(cached.token, BuildConfig.AUTUNN_LICENSE_PUBLIC_KEY_SPKI, context.packageName,
                    LicenseVerifier.sha256Hex(key.public.encoded))
                val wall = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val boot = bootCount(context)
                val nextAnchor = if (runtimeCommittedLicense == cached)
                    LicenseClock.advanceInProcess(cached.anchor, wall, elapsed, boot)
                else LicenseClock.advance(cached.anchor, wall, elapsed, boot)
                LicenseVerifier.validateTime(claims, nextAnchor.trustedSeconds)
                // Do not publish a new decision until the trusted boundary is committed.
                val committed = CachedLicense(cached.token, nextAnchor)
                write(context, committed)
                runtimeCommittedLicense = committed
                publish(expected, LicenseDecision(true, if (claims.permanent) "HUD、仪表地图和方向盘键控已永久授权；联网自动刷新验证票据。" else "HUD、仪表地图和方向盘键控已授权，联网后会自动续期。",
                    "LICENSE_VALID", LicenseVerifier.effectiveExpirySeconds(claims), claims.features, claims.permanent), nextAnchor)
            } catch (e: LicenseValidationException) {
                publish(expected, LicenseDecision(false, e.message ?: "授权验证失败。", e.code))
            } catch (_: Exception) {
                publish(expected, LicenseDecision(false, "无法读取设备授权，请联网重新激活。", "LICENSE_UNAVAILABLE"))
            }
        }
    }

    fun checkAsync(context: Context, result: (LicenseDecision) -> Unit) {
        val app = context.applicationContext
        localExecutor.execute { result(if (!isRequired()) check(app) else checkOnOwner(app)) }
    }

    @JvmStatic fun requireStart(context: Context) {
        val decision = check(context)
        if (!decision.allowed) throw ProductLicenseException(decision)
    }

    @JvmStatic fun refreshAsync(context: Context) {
        if (!isRequired()) return
        requestLocalCheck(context.applicationContext)
        if (configurationProblem() != null || !refreshing.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                observeConnectivity(app)
                // A CarPlay-only link may exist without any route to the authorization service.
                // Missing permission/service is treated as unknown, so explicit failures retain
                // the old network behavior rather than accidentally disabling renewal forever.
                if (hasInternet(app) != false) refresh(app)
            } finally { refreshing.set(false) }
        }
    }

    private fun hasInternet(context: Context): Boolean? = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) null else manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: SecurityException) { null }

    private fun observeConnectivity(context: Context) {
        if (!connectivityStarted.compareAndSet(false, true)) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        internetAvailable = hasInternet(context) == true
        try {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val restored = available && !internetAvailable
                    internetAvailable = available
                    if (restored) {
                        nextAutomaticRefresh.set(SystemClock.elapsedRealtime() + 60_000L)
                        refreshAsync(context)
                    }
                }
                override fun onLost(network: Network) { internetAvailable = false }
            })
        } catch (_: RuntimeException) { connectivityStarted.set(false) }
    }

    /** A failed connection keeps the existing offline ticket. Only an explicit denial clears it. */
    fun refresh(context: Context, control: HttpRequestControl = HttpRequestControl()): LicenseDecision = synchronized(operationLock) {
        configurationProblem()?.let { return@synchronized LicenseDecision(false, it, "NOT_CONFIGURED") }
        val cached = try { synchronized(stateLock) { read(context) } } catch (_: Exception) {
            return@synchronized LicenseDecision(false, "无法读取设备授权，请重新扫码激活。", "LICENSE_UNAVAILABLE")
        }
            ?: return@synchronized LicenseDecision(false, "请先扫码输入兑换码，激活。", "ACTIVATION_REQUIRED")
        try {
            control.ensureActive()
            LicensePackageSignature.verify(context)
            val key = deviceKey()
            val token = LicenseApi.renew(context.packageName, key.public.encoded, cached.token, control) { proof -> sign(key, proof) }
            accept(context, token, key)
        } catch (e: LicenseApiException) {
            if (e.status == 403 && e.code in setOf("LICENSE_REVOKED", "DEVICE_REVOKED", "NO_LICENSE", "LICENSE_EXPIRED")) {
                // Revoke admission immediately, including a local verification already in flight.
                revokedToken = cached.token
                invalidateFeatures()
                onLocalOwner {
                    synchronized(stateLock) {
                        if (read(context)?.token == cached.token) file(context).delete()
                    }
                    checkOnOwner(context)
                }
                LicenseDecision(false, e.message ?: "此设备授权已被撤销。", e.code)
            } else {
                val local = check(context)
                local.copy(message = if (local.allowed) "联网验证暂未完成，现有离线授权仍可使用。" else e.message ?: local.message)
            }
        } catch (e: LicenseValidationException) {
            LicenseDecision(false, e.message ?: "服务器返回的授权无效。", e.code)
        } catch (_: Exception) {
            val local = check(context)
            local.copy(message = if (local.allowed) "无法连接服务器，现有离线授权仍可使用。" else "无法连接服务器，请检查网络后重试。")
        }
    }

    fun beginActivation(context: Context, control: HttpRequestControl = HttpRequestControl()): DeviceActivation = synchronized(operationLock) {
        control.ensureActive()
        configurationProblem()?.let { throw LicenseApiException(0, "NOT_CONFIGURED", it) }
        LicensePackageSignature.verify(context)
        val key = deviceKey()
        LicenseApi.beginActivation(context.packageName, key.public.encoded, defaultDeviceName(), control) { sign(key, it) }
    }

    fun awaitActivation(context: Context, grant: DeviceActivation, cancelled: AtomicBoolean): LicenseDecision {
        val key = deviceKey()
        val token = LicenseApi.awaitActivation(grant, context.packageName, key.public.encoded, cancelled) { sign(key, it) }
        // Preserve a valid completed transaction even if the screen closes while collecting it.
        return synchronized(operationLock) { accept(context, token, key) }
    }

    fun cancelActivation(context: Context, grant: DeviceActivation) {
        val key = deviceKey()
        LicenseApi.cancelActivation(grant, context.packageName, key.public.encoded) { sign(key, it) }
    }

    /** Worker-thread only. Online update authorization never falls back to an offline decision. */
    fun prepareUpdate(context: Context, action: String, sha256: String = "",
                      control: HttpRequestControl = HttpRequestControl()): UpdateAuthorization = synchronized(operationLock) {
        control.ensureActive()
        configurationProblem()?.let { throw UpdateAuthorizationException("NOT_CONFIGURED", it) }
        try {
            LicensePackageSignature.verify(context)
            val cached = synchronized(stateLock) { read(context) }
                ?: throw UpdateAuthorizationException("ACTIVATION_REQUIRED", "请先扫码输入授权码激活这台车机，再更新软件。")
            val key = deviceKey()
            // An expired offline ticket can still identify an entitled device online. Preserve it.
            LicenseVerifier.verify(cached.token, BuildConfig.AUTUNN_LICENSE_PUBLIC_KEY_SPKI, context.packageName,
                LicenseVerifier.sha256Hex(key.public.encoded))
            LicenseApi.prepareUpdate(context.packageName, key.public.encoded, cached.token, action, sha256, control) { sign(key, it) }
        } catch (e: HttpRequestCancelledException) {
            throw e
        } catch (e: UpdateAuthorizationException) {
            throw e
        } catch (e: LicenseApiException) {
            val code = e.code.takeIf { it in setOf("NO_LICENSE", "ACTIVATION_REQUIRED", "INVALID_LICENSE",
                "LICENSE_EXPIRED", "DEVICE_REVOKED", "LICENSE_REVOKED") } ?: "UPDATE_AUTH_UNAVAILABLE"
            throw UpdateAuthorizationException(code, e.message ?: "更新授权验证未完成，请重试。")
        } catch (e: LicenseValidationException) {
            throw UpdateAuthorizationException(e.code, e.message ?: "请联网重新验证授权后更新。")
        } catch (_: Exception) {
            throw UpdateAuthorizationException("UPDATE_AUTH_UNAVAILABLE", "无法完成更新授权验证，请检查网络后重试。")
        }
    }

    /** Worker-thread only. Uses the same device key but never reads or changes the entitlement. */
    fun uploadDiagnostic(context: Context, metadata: DiagnosticMetadata, report: String,
                         control: HttpRequestControl = HttpRequestControl()): DiagnosticReceipt {
        control.ensureActive()
        configurationProblem()?.let { throw LicenseApiException(0, "NOT_CONFIGURED", it) }
        LicensePackageSignature.verify(context)
        val key = deviceKey()
        return LicenseApi.uploadDiagnostic(context.packageName, key.public.encoded, metadata, report, control) { sign(key, it) }
    }

    private fun accept(context: Context, issued: IssuedLicense, key: KeyPair): LicenseDecision = onLocalOwner {
        // A normal renewal is a replacement, not a revocation. Keep the committed ticket
        // readable through validation and persistence; its own expiry/clock checks still apply.
        // Explicit revocation can invalidate this revision and reject a late publication.
        val expected = memory.begin()
        synchronized(stateLock) {
        val claims = LicenseVerifier.verify(issued.token, BuildConfig.AUTUNN_LICENSE_PUBLIC_KEY_SPKI, context.packageName,
            LicenseVerifier.sha256Hex(key.public.encoded))
        // Only a newly verified server signature establishes a new clock anchor. The unsigned
        // serverTime response field can never rescue an expired or rolled-back cached ticket.
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount(context)
        val trustedNow = LicenseFreshness.acceptTime(claims, issued.expectedChallengeId)
        val committed = CachedLicense(issued.token, LicenseClockAnchor(trustedNow, wall, elapsed, boot))
        write(context, committed)
        runtimeCommittedLicense = committed
        revokedToken = null
        publish(expected, LicenseDecision(true, if (claims.permanent) "永久授权成功，HUD、仪表地图和方向盘键控已启用。" else "授权成功，HUD、仪表地图和方向盘键控已启用。", "LICENSE_VALID", LicenseVerifier.effectiveExpirySeconds(claims), claims.features, claims.permanent),
            LicenseClockAnchor(trustedNow, wall, elapsed, boot))
        }
    }

    private fun sign(key: KeyPair, proof: ByteArray): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key.private)
        signature.update(proof)
        return LicenseVerifier.encodeUrl(signature.sign())
    }

    private fun deviceKey(): KeyPair = synchronized(stateLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) {
            return@synchronized KeyPair(store.getCertificate(KEY_ALIAS).publicKey, store.getKey(KEY_ALIAS, null) as java.security.PrivateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        // Android selects hardware protection where available. StrongBox and biometrics are not
        // required because car units frequently lack them and background renewal must work.
        generator.initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build())
        generator.generateKeyPair()
    }

    private fun bootCount(context: Context): Int = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "product-license-v1.json"))

    private fun read(context: Context): CachedLicense? {
        val atomic = file(context)
        if (!atomic.baseFile.exists()) return null
        atomic.openRead().use { stream ->
            val bytes = stream.readBytesLimited(32768)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return CachedLicense(json.getString("license"), LicenseClockAnchor(json.getLong("trustedSeconds"), json.getLong("wallMillis"), json.getLong("elapsedMillis"), json.getInt("bootCount"), json.optInt("fractionalMillis", 0)))
        }
    }

    private fun write(context: Context, cached: CachedLicense) {
        val json = JSONObject().put("license", cached.token).put("trustedSeconds", cached.anchor.trustedSeconds)
            .put("wallMillis", cached.anchor.wallMillis).put("elapsedMillis", cached.anchor.elapsedMillis).put("bootCount", cached.anchor.bootCount)
            .put("fractionalMillis", cached.anchor.fractionalMillis)
        val atomic = file(context)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    fun defaultDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".take(80)
}
