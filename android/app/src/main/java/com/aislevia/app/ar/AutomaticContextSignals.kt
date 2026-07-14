package com.aislevia.app.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import kotlin.math.max

data class LocalizationPrior(
    val familiarEnvironment: Boolean = false,
    val preferredKeyframeIds: Set<Int> = emptySet()
)

private data class ContextSnapshot(
    val location: Location?,
    val wifiHashes: Set<String>
)

/**
 * Collects coarse signals without ever asking a shopper to map or scan the room.
 *
 * GPS/network location and nearby Wi-Fi are only priors: visual geometry must still prove the
 * metric room pose. Identifiers are hashed and retained only on this phone.
 */
class AutomaticContextSignals(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun refresh() {
        if (!hasFineLocation()) return
        runCatching { wifiManager.startScan() }
    }

    fun currentPrior(): LocalizationPrior {
        val snapshot = snapshot()
        val savedWifi = preferences.getStringSet(KEY_WIFI, emptySet()).orEmpty()
        val wifiOverlap = if (snapshot.wifiHashes.isEmpty() || savedWifi.isEmpty()) {
            0f
        } else {
            snapshot.wifiHashes.intersect(savedWifi).size.toFloat() /
                snapshot.wifiHashes.union(savedWifi).size.toFloat().coerceAtLeast(1f)
        }

        val savedLatitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull()
        val savedLongitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull()
        val location = snapshot.location
        val locationMatches = if (location != null && savedLatitude != null && savedLongitude != null) {
            val metres = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, savedLatitude, savedLongitude, metres)
            metres[0] <= max(120f, location.accuracy * 2f)
        } else {
            false
        }

        val familiar = wifiOverlap >= 0.24f || locationMatches
        val keyframes = if (familiar) {
            preferences.getString(KEY_KEYFRAMES, "")
                .orEmpty()
                .split(',')
                .mapNotNull { it.toIntOrNull() }
                .toSet()
        } else {
            emptySet()
        }
        return LocalizationPrior(familiar, keyframes)
    }

    fun rememberSuccessfulLock(keyframeId: Int?) {
        if (keyframeId == null) return
        val snapshot = snapshot()
        val recent = buildList {
            add(keyframeId)
            addAll(
                preferences.getString(KEY_KEYFRAMES, "")
                    .orEmpty()
                    .split(',')
                    .mapNotNull { it.toIntOrNull() }
                    .filter { it != keyframeId }
            )
        }.take(12)
        preferences.edit().apply {
            putString(KEY_KEYFRAMES, recent.joinToString(","))
            if (snapshot.wifiHashes.isNotEmpty()) putStringSet(KEY_WIFI, snapshot.wifiHashes.take(12).toSet())
            snapshot.location?.let { location ->
                putString(KEY_LATITUDE, location.latitude.toString())
                putString(KEY_LONGITUDE, location.longitude.toString())
            }
        }.apply()
    }

    @SuppressLint("MissingPermission")
    private fun snapshot(): ContextSnapshot {
        if (!hasFineLocation()) return ContextSnapshot(null, emptySet())
        val location = runCatching {
            locationManager.getProviders(true)
                .mapNotNull { provider -> locationManager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()
        val wifi = runCatching {
            wifiManager.scanResults
                .sortedByDescending { it.level }
                .take(16)
                .mapNotNull { result -> result.BSSID?.takeIf { it.isNotBlank() }?.let(::hashIdentifier) }
                .toSet()
        }.getOrDefault(emptySet())
        return ContextSnapshot(location, wifi)
    }

    private fun hasFineLocation(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun hashIdentifier(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val PREFERENCES = "automatic-localization-context"
        private const val KEY_WIFI = "wifi"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_KEYFRAMES = "keyframes"

        fun runtimePermissions(): Array<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }
}
