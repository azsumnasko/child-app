package com.childhelper.app.child.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.childhelper.app.child.R
import com.childhelper.app.child.ui.theme.ChildColors
import com.childhelper.core.common.model.PairingState
import com.childhelper.core.p2p.QrCodeGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildPairingScreen(
    navController: NavController,
    viewModel: ChildPairingViewModel = hiltViewModel()
) {
    val code by viewModel.pairingCode.collectAsState()
    val state by viewModel.pairingState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val sessionId by viewModel.sessionId.collectAsState()
    val qrData by viewModel.qrData.collectAsState()
    val isP2p by viewModel.isP2pMode.collectAsState()

    val backContentDesc = stringResource(R.string.pairing_back_content_desc)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.pairing_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancelPairing()
                            navController.popBackStack()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = backContentDesc
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state) {
                PairingState.IDLE -> {
                    IdleContent(
                        onStartPairing = { viewModel.startPairing() },
                        onStartP2pPairing = { viewModel.startP2pPairing() }
                    )
                }

                PairingState.GENERATING -> {
                    GeneratingContent()
                }

                PairingState.WAITING -> {
                    if (isP2p) {
                        P2pWaitingContent(qrData = qrData, sessionId = sessionId, onCancel = {
                            viewModel.cancelPairing(); navController.popBackStack()
                        })
                    } else {
                        WaitingContentWithQr(
                            code = code, sessionId = sessionId, qrData = qrData, onCancel = {
                                viewModel.cancelPairing(); navController.popBackStack()
                            }
                        )
                    }
                }

                PairingState.PAIRED -> {
                    PairedContent(
                        onDone = {
                            viewModel.resetState()
                            navController.popBackStack()
                        }
                    )
                }

                PairingState.ERROR -> {
                    ErrorContent(
                        message = errorMessage ?: stringResource(R.string.pairing_error_default),
                        onRetry = { viewModel.startPairing() },
                        onCancel = {
                            viewModel.resetState()
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onStartPairing: () -> Unit, onStartP2pPairing: () -> Unit) {
    val genDesc = stringResource(R.string.pairing_generate_button)
    val p2pDesc = stringResource(R.string.pairing_nearby_p2p_content_desc)
    val p2pLabel = stringResource(R.string.pairing_nearby_p2p_button)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.pairing_connect_to_parent),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.pairing_generate_description),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onStartPairing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = genDesc },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ChildColors.Primary
            )
        ) {
            Text(
                text = stringResource(R.string.pairing_generate_button),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onStartP2pPairing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = p2pDesc },
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = p2pLabel,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun WaitingContentWithQr(
    code: String, sessionId: String, qrData: String, onCancel: () -> Unit
) {
    val qrBitmap = remember(qrData) { QrCodeGenerator.generate(qrData, 400) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.pairing_your_code), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ChildColors.PrimaryLight.copy(alpha = 0.2f))) {
            Text(text = code, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                fontSize = 48.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = ChildColors.PrimaryDark, letterSpacing = 12.sp)
        }
        Spacer(modifier = Modifier.height(20.dp))
        if (qrBitmap != null) {
            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = stringResource(R.string.pairing_qr_image_desc),
                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp))
                    .border(2.dp, ChildColors.Primary, RoundedCornerShape(16.dp)))
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.pairing_scan_instruction), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.pairing_session_label, sessionId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
            Text(stringResource(R.string.pairing_cancel), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GeneratingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = ChildColors.Primary,
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.pairing_generating),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WaitingContent(
    code: String,
    sessionId: String,
    onCancel: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.pairing_your_code),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Pairing code: ${code.toList().joinToString(" ")}"
                },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = ChildColors.PrimaryLight.copy(alpha = 0.2f)
            )
        ) {
            Text(
                text = code,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = ChildColors.PrimaryDark,
                letterSpacing = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.pairing_show_code_instruction),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Session ID: $sessionId",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = "Cancel pairing" },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text(
                text = stringResource(R.string.pairing_cancel),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PairedContent(onDone: () -> Unit) {
    val doneDesc = stringResource(R.string.pairing_done_content_desc)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.pairing_connected_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = ChildColors.Secondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.pairing_connected_message),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = doneDesc },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ChildColors.Secondary
            )
        ) {
            Text(
                text = stringResource(R.string.pairing_done),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val retryDesc = stringResource(R.string.pairing_retry_content_desc)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.pairing_error_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = retryDesc },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ChildColors.Primary
            )
        ) {
            Text(
                text = stringResource(R.string.pairing_try_again),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text(
                text = stringResource(R.string.pairing_cancel),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun P2pWaitingContent(
    qrData: String,
    sessionId: String,
    onCancel: () -> Unit
) {
    val qrErrorDesc = stringResource(R.string.pairing_qr_error_content)
    val qrBitmap = remember(qrData) {
        QrCodeGenerator.generate(qrData, 512)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.pairing_p2p_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.pairing_p2p_instruction),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.pairing_qr_image_desc),
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(2.dp, ChildColors.Primary, RoundedCornerShape(16.dp))
            )
        } else {
            Card(
                modifier = Modifier
                    .size(280.dp)
                    .semantics { contentDescription = qrErrorDesc },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.LightGray)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.pairing_qr_error_label), color = Color.Gray)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.pairing_device_label, sessionId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text(stringResource(R.string.pairing_cancel), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
