package com.childhelper.core.common.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Role classification for an emergency contact.
 */
@Serializable
enum class ContactRole {
    /** Biological or adoptive mother. */
    MOTHER,

    /** Biological or adoptive father. */
    FATHER,

    /** Other legal guardian or designated caregiver. */
    GUARDIAN
}

/**
 * Represents a trusted contact displayed on the child's home screen.
 *
 * Contacts are stored locally and never uploaded to any server.
 * The child can tap a contact to initiate an encrypted voice/video call.
 *
 * **Privacy:** Only name, role, and a local photo URI are stored. No cloud sync.
 *
 * @property id Unique identifier for this contact (UUID).
 * @property name Display name shown to the child.
 * @property role Relationship role determining icon and color.
 * @property photoUri Optional URI to a locally stored avatar image.
 * @property phoneNumber Optional phone number for PSTN fallback (not used for in-app calls).
 * @property isPrimary Whether this is the primary contact (shown most prominently).
 */
@Serializable
data class Contact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val role: ContactRole,
    val photoUri: String? = null,
    val phoneNumber: String? = null,
    val isPrimary: Boolean = false
)
