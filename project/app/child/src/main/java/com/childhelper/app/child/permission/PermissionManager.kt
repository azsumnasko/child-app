package com.childhelper.app.child.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

/**
 * Centralized permission manager for the child app.
 *
 * Handles runtime permission requests for:
 * - CAMERA: Motion detection via CameraX ImageAnalysis
 * - RECORD_AUDIO: Cry detection via AudioRecord
 * - ACCESS_FINE_LOCATION: SOS location (optional, best-effort)
 * - POST_NOTIFICATIONS: Alert notifications (API 33+)
 *
 * Features:
 * - Permission rationale shown before first request and after denial
 * - Graceful handling of permanent denial (redirects to app settings)
 * - Tracks permission state to avoid repeated requests
 */
class PermissionManager(
    private val activity: Activity,
    private val permissionLauncher: ActivityResultLauncher<Array<String>>
) {

    /**
     * Check if the given permission is granted.
     */
    fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all required monitoring permissions are granted.
     */
    fun hasRequiredPermissions(): Boolean {
        return isGranted(Manifest.permission.CAMERA) &&
                isGranted(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Check if location permission is granted.
     */
    fun hasLocationPermission(): Boolean {
        return isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /**
     * Request all required permissions with rationale.
     *
     * @param onRationale Callback to show a rationale dialog/message to the user.
     *        Called with the list of permissions that need rationale.
     * @param onLaunch Called right before launching the permission request.
     * @param onDenied Called when permissions are denied (not permanently).
     * @param onPermanentlyDenied Called when at least one permission is permanently denied.
     *        Provides a settings intent for redirect.
     */
    fun requestRequiredPermissions(
        onRationale: (List<String>) -> Unit = {},
        onLaunch: () -> Unit = {},
        onDenied: (List<String>) -> Unit = {},
        onPermanentlyDenied: (Intent) -> Unit = {}
    ) {
        val permissionsToRequest = mutableListOf<String>()

        if (!isGranted(Manifest.permission.CAMERA)) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (!isGranted(Manifest.permission.RECORD_AUDIO)) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isEmpty()) {
            return // All granted
        }

        // Check which permissions need rationale
        val rationalePermissions = permissionsToRequest.filter {
            activity.shouldShowRequestPermissionRationale(it)
        }

        if (rationalePermissions.isNotEmpty()) {
            onRationale(rationalePermissions)
            return // Wait for user to acknowledge rationale before launching
        }

        // Check for permanently denied permissions
        val permanentlyDenied = permissionsToRequest.filter {
            !activity.shouldShowRequestPermissionRationale(it) &&
                    !isGranted(it) &&
                    hasRequestedBefore(it)
        }

        if (permanentlyDenied.isNotEmpty()) {
            onPermanentlyDenied(createSettingsIntent())
            return
        }

        onLaunch()
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    /**
     * Launch the permission request after rationale has been shown.
     */
    fun launchPermissionRequest(permissions: Array<String>) {
        permissionLauncher.launch(permissions)
    }

    /**
     * Handle the result from the permission launcher.
     *
     * @param permissions Map of permission to granted state
     * @return [PermissionResult] summarizing the outcome
     */
    fun handlePermissionResult(
        permissions: Map<String, Boolean>
    ): PermissionResult {
        val denied = permissions.filter { !it.value }.keys.toList()
        val granted = permissions.filter { it.value }.keys.toList()

        // Mark all requested permissions as having been requested
        permissions.keys.forEach { markRequested(it) }

        return when {
            denied.isEmpty() -> PermissionResult.AllGranted(granted)
            else -> {
                val permanentlyDenied = denied.filter {
                    !activity.shouldShowRequestPermissionRationale(it)
                }
                if (permanentlyDenied.isNotEmpty()) {
                    PermissionResult.PermanentlyDenied(denied, permanentlyDenied)
                } else {
                    PermissionResult.SomeDenied(denied, granted)
                }
            }
        }
    }

    /**
     * Create an Intent to open the app's system settings page.
     */
    fun createSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", activity.packageName, null)
        }
    }

    // --- Internal ---

    private val prefs by lazy {
        activity.getSharedPreferences(PERMISSION_PREFS, Context.MODE_PRIVATE)
    }

    private fun hasRequestedBefore(permission: String): Boolean {
        return prefs.getBoolean("requested_$permission", false)
    }

    private fun markRequested(permission: String) {
        prefs.edit().putBoolean("requested_$permission", true).apply()
    }

    companion object {
        private const val PERMISSION_PREFS = "permission_manager_prefs"

        /** Permissions required for core monitoring functionality */
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        /** Optional permissions that enhance functionality */
        val OPTIONAL_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}

/**
 * Result of a permission request.
 */
sealed class PermissionResult {
    /** All requested permissions were granted */
    data class AllGranted(val granted: List<String>) : PermissionResult()

    /** Some permissions were denied but can be requested again */
    data class SomeDenied(val denied: List<String>, val granted: List<String>) : PermissionResult()

    /** At least one permission was permanently denied */
    data class PermanentlyDenied(
        val denied: List<String>,
        val permanentlyDenied: List<String>
    ) : PermissionResult()
}
