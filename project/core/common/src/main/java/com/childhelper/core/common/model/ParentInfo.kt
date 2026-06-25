package com.childhelper.core.common.model

import kotlinx.serialization.Serializable

/**
 * Parent profile info stored on the server and consumed by the child app.
 *
 * The parent app configures these fields (display names + PSTN phone numbers)
 * so the child app's "Audio Mom" / "Audio Dad" buttons can place a real phone
 * call ([android.content.Intent.ACTION_CALL]) as a fallback to the in-app
 * WebRTC call.
 *
 * **Privacy:** Phone numbers are stored in the encrypted server DB and in each
 * app's encrypted SecurePreferences. They are never attached to media.
 *
 * @property momPhoneNumber Phone number for the "Mom" button (tel digits / E.164).
 * @property momDisplayName Display name shown for Mom on the child device.
 * @property dadPhoneNumber Phone number for the "Dad" button.
 * @property dadDisplayName Display name shown for Dad on the child device.
 */
@Serializable
data class ParentInfo(
    val momPhoneNumber: String? = null,
    val momDisplayName: String? = null,
    val dadPhoneNumber: String? = null,
    val dadDisplayName: String? = null
)
