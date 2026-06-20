package com.childhelper.app.parent.ui.pairing

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.childhelper.app.parent.R
import com.childhelper.app.parent.ui.theme.StatusOnline
import com.childhelper.core.common.model.PairingState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentPairingScreen(
    onNavigateBack: () -> Unit,
    viewModel: ParentPairingViewModel = hiltViewModel()
) {
    val enteredCode by viewModel.code.collectAsState()
    val state by viewModel.pairingState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val sessionId by viewModel.sessionId.collectAsState()
    val isP2p by viewModel.isP2pMode.collectAsState()
    val discovered by viewModel.discoveredDevices.collectAsState()
    val context = LocalContext.current

    val navigateBackDesc = stringResource(R.string.pairing_navigate_back_content)

    // QR scanner launcher
    val qrScannerLauncher = rememberLauncherForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents != null) {
            viewModel.onQrCodeScanned(result.contents)
        }
    }

    val scanOptions = ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt(stringResource(R.string.pairing_scan_prompt))
        setBeepEnabled(true)
        setOrientationLocked(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.pairing_pair_new_device),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.resetState()
                            onNavigateBack()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = navigateBackDesc
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
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            when (state) {
                PairingState.IDLE,
                PairingState.ERROR -> {
                    IdleContent(
                        enteredCode = enteredCode,
                        sessionId = sessionId,
                        errorMessage = errorMessage,
                        onCodeChange = { viewModel.onCodeChange(it) },
                        onSessionIdChange = { viewModel.setSessionId(it) },
                        onSubmit = { viewModel.submitCode() },
                        onScanQr = { qrScannerLauncher.launch(scanOptions) },
                        onNearbyPairing = { viewModel.startP2pDiscovery() },
                        state = state
                    )
                }

                PairingState.GENERATING -> {
                    GeneratingContent()
                }

                PairingState.WAITING -> {
                    WaitingContent()
                }

                PairingState.PAIRED -> {
                    PairedContent(
                        onDone = {
                            viewModel.resetState()
                            onNavigateBack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleContent(
    enteredCode: String,
    sessionId: String,
    errorMessage: String?,
    onCodeChange: (String) -> Unit,
    onSessionIdChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
    onNearbyPairing: () -> Unit,
    state: PairingState
) {
    val codeInputDesc = stringResource(R.string.pairing_code_input_content)
    val connectDesc = stringResource(R.string.pairing_connect_content_desc)
    val scanQrDesc = stringResource(R.string.pairing_scan_qr_content_desc)
    val p2pDiscoveryDesc = stringResource(R.string.pairing_nearby_p2p_content_desc)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.pairing_connect_to_child),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.pairing_enter_code_instruction),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = sessionId,
            onValueChange = { onSessionIdChange(it.take(36)) },
            label = { Text(stringResource(R.string.pairing_session_id_field_label)) },
            placeholder = { Text(stringResource(R.string.pairing_session_id_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next)
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = enteredCode,
            onValueChange = { newValue ->
                val cleaned = newValue.replace(" ", "").uppercase().take(6)
                onCodeChange(cleaned)
            },
            label = { Text(stringResource(R.string.pairing_code_label)) },
            placeholder = { Text(stringResource(R.string.pairing_code_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = codeInputDesc },
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Done
            )
        )

        if (errorMessage != null && state == PairingState.ERROR) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = connectDesc },
            shape = RoundedCornerShape(14.dp),
            enabled = enteredCode.length == 6
        ) {
            Text(
                text = stringResource(R.string.pairing_connect_button),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onScanQr,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = scanQrDesc },
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(stringResource(R.string.pairing_scan_qr_button), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onNearbyPairing,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics { contentDescription = p2pDiscoveryDesc },
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(stringResource(R.string.pairing_nearby_wifi_button), fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.pairing_connecting),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WaitingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.pairing_waiting),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PairedContent(onDone: () -> Unit) {
    val continueDesc = stringResource(R.string.pairing_continue_content_desc)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.pairing_paired_successfully),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = StatusOnline
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.pairing_paired_message),
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
                .semantics { contentDescription = continueDesc },
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = stringResource(R.string.pairing_continue_dashboard),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
