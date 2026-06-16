package org.stypox.dicio.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.shreyaspatil.permissionflow.compose.rememberMultiplePermissionState
import org.dicio.skill.skill.Permission
import org.stypox.dicio.R

val PERMISSION_READ_CONTACTS = Permission.NormalPermission(
    name = R.string.perm_read_contacts,
    id = Manifest.permission.READ_CONTACTS,
)
val PERMISSION_CALL_PHONE = Permission.NormalPermission(
    name = R.string.perm_call_phone,
    id = Manifest.permission.CALL_PHONE,
)

fun checkPermissions(context: Context, vararg permissions: String): Boolean {
    for (permission in permissions) {
        if (ActivityCompat.checkSelfPermission(context, permission)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
    }
    return true
}

fun areAllPermissionsGranted(vararg grantResults: Int): Boolean {
    for (grantResult in grantResults) {
        if (grantResult != PackageManager.PERMISSION_GRANTED) {
            return false
        }
    }
    return true
}

fun getNonGrantedSecurePermissions(
    context: Context,
    permissions: List<Permission.SecurePermission>,
): List<Permission> {
    return permissions.filter {
        Settings.Secure.getString(context.contentResolver, it.id)
            ?.contains(context.packageName) != true
    }
}

@Composable
fun getNonGrantedPermissions(permissions: List<Permission>): List<Permission> {
    val normalPermissions = permissions.filterIsInstance<Permission.NormalPermission>()
    val securePermissions = permissions.filterIsInstance<Permission.SecurePermission>()

    val normal = if (normalPermissions.isEmpty()) {
        listOf<Permission>()
    } else {
        val permissionsState by
            rememberMultiplePermissionState(*(normalPermissions.map { it.id }.toTypedArray()))
        normalPermissions
            .zip(permissionsState.permissions)
            .filter { !it.second.isGranted }
            .map { it.first }
    }

    val secure = if (securePermissions.isEmpty()) {
        listOf()
    } else {
        val context = LocalContext.current
        var secureNonGranted by remember {
            mutableStateOf(getNonGrantedSecurePermissions(context, securePermissions))
        }
        LifecycleResumeEffect(null) {
            secureNonGranted = getNonGrantedSecurePermissions(context, securePermissions)
            onPauseOrDispose {}
        }
        secureNonGranted
    }

    return normal + secure
}

fun commaJoinPermissions(context: Context, permissions: List<Permission>): String {
    return permissions.joinToString(", ") { context.getString(it.name) }
}

typealias PermissionLauncher = ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>

fun requestAnyPermission(
    launcher: PermissionLauncher,
    context: Context,
    permissions: List<Permission>,
) {
    val normalPermissions = permissions.filterIsInstance<Permission.NormalPermission>()
    val securePermissions = permissions.filterIsInstance<Permission.SecurePermission>()

    if (normalPermissions.isNotEmpty()) {
        launcher.launch(normalPermissions.map { it.id }.toTypedArray())
        return
    }

    val action = securePermissions.firstOrNull()?.settingsAction ?: return
    context.startActivity(Intent(action))
}
