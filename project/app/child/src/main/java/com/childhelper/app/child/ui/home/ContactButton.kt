package com.childhelper.app.child.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childhelper.app.child.R
import com.childhelper.core.common.model.Contact
import com.childhelper.core.common.model.ContactRole

/**
 * A large, child-friendly contact button with a circular profile photo.
 * Minimum 72dp touch target for easy tapping by children.
 *
 * @param contact The contact to display (name, photo, role)
 * @param onClick Callback when the button is pressed
 * @param modifier Compose modifier
 * @param buttonColor The background color of the button
 */
@Composable
fun ContactButton(
    contact: Contact,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonColor: Color
) {
    val context = LocalContext.current
    val roleLabel = when (contact.role) {
        ContactRole.MOTHER -> stringResource(R.string.contact_mom_label)
        ContactRole.FATHER -> stringResource(R.string.contact_dad_label)
        ContactRole.GUARDIAN -> contact.name
    }

    val contentDesc = stringResource(R.string.contact_call_description, roleLabel)

    Button(
        onClick = onClick,
        modifier = modifier
            .height(120.dp)
            .fillMaxWidth()
            .semantics { contentDescription = contentDesc },
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Circular profile photo or fallback icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                if (contact.photoUri != null) {
                    // In a real app, use Coil to load the photo
                    // For now, show a role-based icon
                    val painter: Painter = when (contact.role) {
                        ContactRole.MOTHER -> painterResource(id = R.drawable.ic_contact_mom)
                        ContactRole.FATHER -> painterResource(id = R.drawable.ic_contact_dad)
                        ContactRole.GUARDIAN -> painterResource(id = R.drawable.ic_contact_guardian)
                    }
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val iconRes = when (contact.role) {
                        ContactRole.MOTHER -> R.drawable.ic_contact_mom
                        ContactRole.FATHER -> R.drawable.ic_contact_dad
                        ContactRole.GUARDIAN -> R.drawable.ic_contact_guardian
                    }
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = roleLabel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * Compact version of the contact button for smaller layouts.
 * Still maintains the 56dp minimum touch target.
 */
@Composable
fun CompactContactButton(
    contact: Contact,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonColor: Color
) {
    val roleLabel = when (contact.role) {
        ContactRole.MOTHER -> stringResource(R.string.contact_mom_label)
        ContactRole.FATHER -> stringResource(R.string.contact_dad_label)
        ContactRole.GUARDIAN -> contact.name
    }

    val contentDesc = stringResource(R.string.contact_call_description, roleLabel)

    Button(
        onClick = onClick,
        modifier = modifier
            .height(80.dp)
            .fillMaxWidth()
            .semantics { contentDescription = contentDesc },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                val iconRes = when (contact.role) {
                    ContactRole.MOTHER -> R.drawable.ic_contact_mom
                    ContactRole.FATHER -> R.drawable.ic_contact_dad
                    ContactRole.GUARDIAN -> R.drawable.ic_contact_guardian
                }
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = roleLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
