package com.example.zhizijing.nearby.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object NearbyPermissions {
    fun missingRuntimePermissions(context: Context): Array<String> =
        requiredRuntimePermissions()
            .filter { permission ->
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()

    fun hasRuntimePermissions(context: Context): Boolean =
        missingRuntimePermissions(context).isEmpty()

    fun requiredRuntimePermissions(): List<String> =
        requiredRuntimePermissionsForSdk(Build.VERSION.SDK_INT)

    fun requiredRuntimePermissionsForSdk(sdkInt: Int): List<String> {
        val permissions = mutableListOf<String>()
        if (sdkInt >= Build.VERSION_CODES.M) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (sdkInt in Build.VERSION_CODES.M..Build.VERSION_CODES.S) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (sdkInt >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_SCAN
        }
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return permissions
    }
}
