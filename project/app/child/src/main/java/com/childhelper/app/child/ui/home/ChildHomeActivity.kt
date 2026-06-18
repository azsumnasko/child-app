package com.childhelper.app.child.ui.home

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.childhelper.app.child.permission.PermissionManager
import com.childhelper.app.child.permission.PermissionResult
import com.childhelper.app.child.ui.bedtime.BedtimeModeScreen
import com.childhelper.app.child.ui.call.CallScreen
import com.childhelper.app.child.ui.pairing.ChildPairingScreen
import com.childhelper.app.child.ui.theme.ChildTheme
import com.childhelper.core.security.LocaleManager
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main activity for the child-facing app.
 * Hosts the Compose navigation graph and manages runtime permissions
 * through [PermissionManager] with rationale support and graceful denial handling.
 */
@AndroidEntryPoint
class ChildHomeActivity : ComponentActivity() {

    private lateinit var permissionManager: PermissionManager

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val result = permissionManager.handlePermissionResult(permissions)
        when (result) {
            is PermissionResult.AllGranted -> {
                Log.i(TAG, "All permissions granted: ${result.granted}")
                // All permissions granted, monitoring can start
            }
            is PermissionResult.SomeDenied -> {
                Log.w(TAG, "Some permissions denied: ${result.denied}")
                // Re-request denied permissions with rationale
                permissionManager.requestRequiredPermissions(
                    onRationale = { showRationaleDialog(it) },
                    onPermanentlyDenied = { startActivity(it) }
                )
            }
            is PermissionResult.PermanentlyDenied -> {
                Log.e(TAG, "Permissions permanently denied: ${result.permanentlyDenied}")
                // Show settings redirect for permanently denied permissions
                showSettingsRedirectDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionManager = PermissionManager(this, permissionLauncher)
        requestRequiredPermissions()

        setContent {
            ChildTheme {
                val navController = rememberNavController()
                ChildAppNavHost(navController = navController)
            }
        }
    }

    private fun requestRequiredPermissions() {
        permissionManager.requestRequiredPermissions(
            onRationale = { permissions -> showRationaleDialog(permissions) },
            onPermanentlyDenied = { intent ->
                startActivity(intent)
            }
        )

        // Also request POST_NOTIFICATIONS on API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!permissionManager.isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                )
            }
        }
    }

    /**
     * Show permission rationale to the user before requesting.
     *
     * @param permissions The list of permissions needing rationale
     */
    private fun showRationaleDialog(permissions: List<String>) {
        val message = buildRationaleMessage(permissions)

        android.app.AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Grant") { _, _ ->
                permissionManager.launchPermissionRequest(
                    permissions.toTypedArray()
                )
            }
            .setNegativeButton("Not Now") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show dialog redirecting to app settings when permissions are permanently denied.
     */
    private fun showSettingsRedirectDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "Some required permissions were permanently denied. " +
                        "Please open app settings to grant them manually."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(permissionManager.createSettingsIntent())
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Build a user-friendly rationale message for the given permissions.
     */
    private fun buildRationaleMessage(permissions: List<String>): String {
        val descriptions = permissions.map { permission ->
            when (permission) {
                Manifest.permission.CAMERA ->
                    "Camera is needed for motion detection to keep your child safe."
                Manifest.permission.RECORD_AUDIO ->
                    "Microphone is needed to detect crying sounds."
                Manifest.permission.ACCESS_FINE_LOCATION ->
                    "Location is optional and only used during SOS emergencies."
                else -> "Permission: $permission"
            }
        }
        return descriptions.joinToString("\n\n")
    }

    companion object {
        private const val TAG = "ChildHomeActivity"
    }
}

/**
 * Navigation host for the child app.
 * Defines all navigation routes between screens.
 */
@Composable
fun ChildAppNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        // Home screen
        composable("home") {
            ChildHomeScreen(navController = navController)
        }

        // SOS screen
        composable("sos") {
            com.childhelper.app.child.ui.sos.SosScreen(navController = navController)
        }

        // Call screen
        composable(
            route = "call/{contactId}?video={video}",
            arguments = listOf(
                navArgument("contactId") { type = NavType.StringType },
                navArgument("video") {
                    type = NavType.BoolType
                    defaultValue = true
                }
            )
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: ""
            val hasVideo = backStackEntry.arguments?.getBoolean("video") ?: true
            CallScreen(
                navController = navController,
                contactId = contactId,
                hasVideo = hasVideo
            )
        }

        // Bedtime mode screen
        composable("bedtime") {
            BedtimeModeScreen(navController = navController)
        }

        // Pairing screen
        composable("pairing") {
            ChildPairingScreen(navController = navController)
        }
    }
}
