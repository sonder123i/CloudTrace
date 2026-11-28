package com.zcshou.gogogo.run.guard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.zcshou.gogogo.run.engine.FreeRoamController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 「定位真实位置」采样器：优先取非模拟缓存帧；
 * 若当前注入正在污染 provider，则临时移除测试 Provider 采样一次再恢复。
 */
object RealLocationSampler {

    private val providers = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    suspend fun sample(context: Context): Location? {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        bestNonMock(lm)?.let { return it }

        val paused = FreeRoamController.isAttached()
        if (paused) FreeRoamController.pauseInjection()
        val fresh = try {
            requestNetworkFix(context, lm)?.takeUnless { isMock(it) }
        } finally {
            if (paused) FreeRoamController.resumeInjection()
        }
        return fresh ?: bestNonMock(lm)
    }

    @RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    private fun bestNonMock(lm: LocationManager): Location? = runCatching {
        providers.asSequence()
            .mapNotNull { lm.getLastKnownLocation(it) }
            .filter { !isMock(it) }
            .maxByOrNull { it.time }
    }.getOrNull()

    @RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    private fun bestAny(lm: LocationManager): Location? = runCatching {
        providers.asSequence()
            .mapNotNull { lm.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    }.getOrNull()

    @RequiresPermission(anyOf = [
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ])
    private suspend fun requestNetworkFix(
        context: Context,
        lm: LocationManager
    ): Location? = withTimeoutOrNull(4_500) {
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                runCatching {
                    lm.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        signal,
                        context.mainExecutor
                    ) { loc -> if (cont.isActive) cont.resume(loc) }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                runCatching {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER,
                        listener,
                        Looper.getMainLooper()
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    runCatching { lm.removeUpdates(listener) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
        else location.isFromMockProvider
}
