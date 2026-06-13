package com.lacknb.agentchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lacknb.agentchat.core.model.ProviderSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    settings: ProviderSettings,
    onBackToChat: () -> Unit,
    onSaveProvider: (baseUrl: String, apiKey: String, model: String) -> Result<Unit>,
    onTestConnection: suspend () -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    var baseUrl by rememberSaveable(settings.baseUrl) { androidx.compose.runtime.mutableStateOf(settings.baseUrl) }
    var apiKey by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var model by rememberSaveable(settings.model) { androidx.compose.runtime.mutableStateOf(settings.model) }
    var isTesting by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var status by rememberSaveable(settings.hasApiKey, settings.baseUrl, settings.model) {
        androidx.compose.runtime.mutableStateOf(
            if (settings.hasApiKey) {
                "Saved · ${settings.model}"
            } else {
                "Missing API key"
            },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Provider",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Chat Completions endpoint",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onBackToChat) {
                        Text("Done")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProviderStatusPanel(
                settings = settings,
                status = status,
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Base URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (settings.hasApiKey) "API Key (${settings.maskedApiKey})" else "API Key") },
                        placeholder = {
                            Text(if (settings.hasApiKey) "Leave blank to keep saved key" else "sk-...")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        status = onSaveProvider(baseUrl, apiKey, model)
                            .fold(
                                onSuccess = {
                                    apiKey = ""
                                    "Provider saved"
                                },
                                onFailure = { error -> error.message ?: "Unable to save provider" },
                            )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Save")
                }
                FilledTonalButton(
                    onClick = {
                        isTesting = true
                        status = "Testing..."
                        scope.launch {
                            status = onTestConnection()
                                .fold(
                                    onSuccess = { "Connection OK" },
                                    onFailure = { error -> error.message ?: "Connection failed" },
                                )
                            isTesting = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isTesting,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(if (isTesting) "Testing" else "Test")
                }
            }
        }
    }
}

@Composable
private fun ProviderStatusPanel(
    settings: ProviderSettings,
    status: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            ),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (settings.hasApiKey) 0.42f else 0.28f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (settings.hasApiKey) "Provider ready" else "Provider incomplete",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = settings.baseUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = settings.model,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
