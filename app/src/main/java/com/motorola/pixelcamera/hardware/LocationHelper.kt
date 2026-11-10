package com.motorola.pixelcamera.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.motorola.pixelcamera.ui.SettingsActivity

/**
 * High-accuracy, low-power GPS Location Helper for Motorola Edge 20 Fusion.
 *
 * Implements:
 * 1. Dual-provider location tracking (GPS + Network/Cell tower)
 * 2. Background fresh location caching for zero shutter lag
 * 3. Settings integration (SettingsActivity.KEY_SAVE_LOCATION)
 */
class LocationHelper(private val context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val prefs = context.getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)

    private var lastKnownLocation: Location? = null
    private var isListening = false

    fun startListening() {
        if (isListening || !isLocationEnabledInSettings() || !hasLocationPermission()) return

        try {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (provider in providers) {
                if (locationManager?.isProviderEnabled(provider) == true) {
                    locationManager.requestLocationUpdates(
                        provider,
                        5000L, // 5 seconds
                        5f,    // 5 meters
                        this,
                        Looper.getMainLooper()
                    )
                    // Seed initial location
                    val last = locationManager.getLastKnownLocation(provider)
                    if (isBetterLocation(last, lastKnownLocation)) {
                        lastKnownLocation = last
                    }
                }
            }
            isListening = true
            Log.d(TAG, "Location tracking started")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start location updates: ${e.message}")
        }
    }

    fun stopListening() {
        if (!isListening) return
        try {
            locationManager?.removeUpdates(this)
            isListening = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop location updates: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(): Location? {
        if (!isLocationEnabledInSettings() || !hasLocationPermission()) {
            return null
        }

        // Return cached location if fresh (< 2 minutes old)
        val cached = lastKnownLocation
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.time) < 120_000L) {
            return cached
        }

        // Query providers for latest fix
        try {
            var best: Location? = cached
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            for (provider in providers) {
                val loc = locationManager?.getLastKnownLocation(provider)
                if (isBetterLocation(loc, best)) {
                    best = loc
                }
            }
            lastKnownLocation = best
            return best
        } catch (e: Exception) {
            Log.w(TAG, "Error getting location fix: ${e.message}")
        }
        return cached
    }

    override fun onLocationChanged(location: Location) {
        if (isBetterLocation(location, lastKnownLocation)) {
            lastKnownLocation = location
            Log.d(TAG, "Updated GPS location: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m")
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun isLocationEnabledInSettings(): Boolean {
        return prefs.getBoolean(SettingsActivity.KEY_SAVE_LOCATION, true)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isBetterLocation(location: Location?, currentBest: Location?): Boolean {
        if (location == null) return false
        if (currentBest == null) return true

        val timeDelta = location.time - currentBest.time
        val isSignificantlyNewer = timeDelta > 60_000L
        val isSignificantlyOlder = timeDelta < -60_000L
        val isNewer = timeDelta > 0

        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        val accuracyDelta = (location.accuracy - currentBest.accuracy).toInt()
        val isLessAccurate = accuracyDelta > 0
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200

        val isFromSameProvider = location.provider == currentBest.provider

        if (isMoreAccurate) return true
        if (isNewer && !isLessAccurate) return true
        if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return true

        return false
    }

    companion object {
        private const val TAG = "LocationHelper"
    }
}
