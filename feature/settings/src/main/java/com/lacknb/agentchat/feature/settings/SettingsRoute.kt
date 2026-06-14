package com.lacknb.agentchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.remember
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
import com.lacknb.agentchat.core.model.RetrievalMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    settings: ProviderSettings,
    onBackToChat: () -> Unit,
    onSaveProvider: (
        baseUrl: String,
        apiKey: String,
        model: String,
        embeddingModel: String,
        rerankModel: String,
        retrievalMode: RetrievalMode,
    ) -> Result<Unit>,
    onTestConnection: suspend () -> Result<Unit>,
    onFetchModels: suspend (baseUrl: String, apiKey: String) -> Result<List<String>>,
) {
    val scope = rememberCoroutineScope()
    var baseUrl by rememberSaveable(settings.baseUrl) { androidx.compose.runtime.mutableStateOf(settings.baseUrl) }
    var apiKey by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var model by rememberSaveable(settings.model) { androidx.compose.runtime.mutableStateOf(settings.model) }
    var embeddingModel by rememberSaveable(settings.embeddingModel) {
        androidx.compose.runtime.mutableStateOf(settings.embeddingModel)
    }
    var rerankModel by rememberSaveable(settings.rerankModel) {
        androidx.compose.runtime.mutableStateOf(settings.rerankModel)
    }
    var retrievalMode by rememberSaveable(settings.retrievalMode) {
        androidx.compose.runtime.mutableStateOf(settings.retrievalMode)
    }
    var isTesting by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isLoadingModels by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var isModelPickerOpen by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var modelPickerTarget by rememberSaveable { androidx.compose.runtime.mutableStateOf(ModelPickerTarget.Chat) }
    var modelQuery by rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var modelOptions by remember { androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }
    var status by rememberSaveable(
        settings.hasApiKey,
        settings.baseUrl,
        settings.model,
        settings.embeddingModel,
        settings.rerankModel,
        settings.retrievalMode,
    ) {
        androidx.compose.runtime.mutableStateOf(
            if (settings.hasApiKey) {
                "已保存 · ${settings.model}"
            } else {
                "缺少 API 密钥"
            },
        )
    }

    if (isModelPickerOpen) {
        ModelPickerDialog(
            models = modelOptions,
            target = modelPickerTarget,
            selectedModel = when (modelPickerTarget) {
                ModelPickerTarget.Chat -> model
                ModelPickerTarget.Embedding -> embeddingModel
                ModelPickerTarget.Rerank -> rerankModel
            },
            query = modelQuery,
            onQueryChange = { modelQuery = it },
            onSelect = { selectedModel ->
                when (modelPickerTarget) {
                    ModelPickerTarget.Chat -> model = selectedModel
                    ModelPickerTarget.Embedding -> embeddingModel = selectedModel
                    ModelPickerTarget.Rerank -> rerankModel = selectedModel
                }
                isModelPickerOpen = false
                status = "已选择模型 · $selectedModel"
            },
            onDismiss = { isModelPickerOpen = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "服务商",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "聊天补全端点",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onBackToChat) {
                        Text("完成")
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
                        label = { Text("API 基础 URL") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (settings.hasApiKey) "API 密钥 (${settings.maskedApiKey})" else "API 密钥") },
                        placeholder = {
                            Text(if (settings.hasApiKey) "留空以保留已保存的密钥" else "sk-...")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModelTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = "聊天模型",
                            modifier = Modifier.weight(1f),
                        )
                        FetchModelsButton(
                            isLoading = isLoadingModels,
                            onClick = {
                                modelPickerTarget = ModelPickerTarget.Chat
                                isLoadingModels = true
                                status = "正在获取模型..."
                                scope.launch {
                                    val fetchStatus = onFetchModels(baseUrl, apiKey)
                                    status = fetchStatus.openModelPicker(
                                        target = ModelPickerTarget.Chat,
                                        setOptions = { modelOptions = it },
                                        openPicker = { isModelPickerOpen = true },
                                        resetQuery = { modelQuery = "" },
                                    )
                                    isLoadingModels = false
                                }
                            },
                        )
                    }
                    ModelSelectorRow(
                        value = embeddingModel,
                        onValueChange = { embeddingModel = it },
                        label = "Embedding 模型",
                        isLoading = isLoadingModels && modelPickerTarget == ModelPickerTarget.Embedding,
                        onPick = {
                            modelPickerTarget = ModelPickerTarget.Embedding
                            isLoadingModels = true
                            status = "正在获取模型..."
                            scope.launch {
                                status = onFetchModels(baseUrl, apiKey).openModelPicker(
                                    target = ModelPickerTarget.Embedding,
                                    setOptions = { modelOptions = it },
                                    openPicker = { isModelPickerOpen = true },
                                    resetQuery = { modelQuery = "" },
                                )
                                isLoadingModels = false
                            }
                        },
                    )
                    ModelSelectorRow(
                        value = rerankModel,
                        onValueChange = { rerankModel = it },
                        label = "Rerank 模型",
                        isLoading = isLoadingModels && modelPickerTarget == ModelPickerTarget.Rerank,
                        onPick = {
                            modelPickerTarget = ModelPickerTarget.Rerank
                            isLoadingModels = true
                            status = "正在获取模型..."
                            scope.launch {
                                status = onFetchModels(baseUrl, apiKey).openModelPicker(
                                    target = ModelPickerTarget.Rerank,
                                    setOptions = { modelOptions = it },
                                    openPicker = { isModelPickerOpen = true },
                                    resetQuery = { modelQuery = "" },
                                )
                                isLoadingModels = false
                            }
                        },
                    )
                    RetrievalModeSelector(
                        selected = retrievalMode,
                        onSelect = { retrievalMode = it },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        status = onSaveProvider(
                            baseUrl,
                            apiKey,
                            model,
                            embeddingModel,
                            rerankModel,
                            retrievalMode,
                        )
                            .fold(
                                onSuccess = {
                                    apiKey = ""
                                    "服务商设置已保存"
                                },
                                onFailure = { error -> error.message ?: "无法保存服务商设置" },
                            )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("保存")
                }
                FilledTonalButton(
                    onClick = {
                        isTesting = true
                        status = "正在测试..."
                        scope.launch {
                            status = onTestConnection()
                                .fold(
                                    onSuccess = { "连接成功" },
                                    onFailure = { error -> error.message ?: "连接失败" },
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
                    Text(if (isTesting) "测试中" else "测试连接")
                }
            }
        }
    }
}

@Composable
private fun ModelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
    )
}

@Composable
private fun ModelSelectorRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isLoading: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = Modifier.weight(1f),
        )
        FetchModelsButton(
            isLoading = isLoading,
            onClick = onPick,
        )
    }
}

@Composable
private fun FetchModelsButton(
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !isLoading,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text("选择")
        }
    }
}

@Composable
private fun RetrievalModeSelector(
    selected: RetrievalMode,
    onSelect: (RetrievalMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "记忆检索模式",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RetrievalMode.values().forEach { mode ->
                FilledTonalButton(
                    onClick = { onSelect(mode) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (selected == mode) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                        },
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(
                        text = mode.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<String>,
    target: ModelPickerTarget,
    selectedModel: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val filteredModels = remember(models, query) {
        val normalizedQuery = query.trim()
        val targetModels = models.filter { it.matchesTarget(target) }
            .ifEmpty { models }
        if (normalizedQuery.isBlank()) {
            targetModels
        } else {
            targetModels.filter { it.contains(normalizedQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.dialogTitle) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索模型") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                Text(
                    text = "共 ${models.size} 个，匹配 ${filteredModels.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp),
                        ),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    LazyColumn {
                        items(filteredModels, key = { it }) { option ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = option,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (option == selectedModel) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (option == selectedModel) {
                                    Text(
                                        text = "当前模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

private enum class ModelPickerTarget(val dialogTitle: String) {
    Chat("选择聊天模型"),
    Embedding("选择 Embedding 模型"),
    Rerank("选择 Rerank 模型"),
}

private fun String.matchesTarget(target: ModelPickerTarget): Boolean {
    val id = lowercase()
    return when (target) {
        ModelPickerTarget.Chat -> !id.looksLikeEmbeddingModel() && !id.looksLikeRerankModel()
        ModelPickerTarget.Embedding -> id.looksLikeEmbeddingModel() && !id.looksLikeRerankModel()
        ModelPickerTarget.Rerank -> id.looksLikeRerankModel()
    }
}

private fun String.looksLikeEmbeddingModel(): Boolean {
    return contains("embed") ||
        contains("embedding") ||
        contains("bge-m3") ||
        contains("e5-") ||
        contains("gte-")
}

private fun String.looksLikeRerankModel(): Boolean {
    return contains("rerank") ||
        contains("reranker") ||
        contains("bge-reranker") ||
        contains("jina-reranker")
}

private fun Result<List<String>>.openModelPicker(
    target: ModelPickerTarget,
    setOptions: (List<String>) -> Unit,
    openPicker: () -> Unit,
    resetQuery: () -> Unit,
): String {
    return fold(
        onSuccess = { models ->
            setOptions(models)
            resetQuery()
            openPicker()
            val matchedCount = models.count { it.matchesTarget(target) }
            if (matchedCount > 0) {
                "已获取 ${models.size} 个模型，匹配 ${matchedCount} 个"
            } else {
                "已获取 ${models.size} 个模型"
            }
        },
        onFailure = { error -> error.message ?: "获取模型失败" },
    )
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
                    text = if (settings.hasApiKey) "服务商已就绪" else "服务商信息未完善",
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
                text = buildString {
                    append(settings.model)
                    if (settings.embeddingModel.isNotBlank()) {
                        append(" · Embedding: ")
                        append(settings.embeddingModel)
                    }
                    if (settings.rerankModel.isNotBlank()) {
                        append(" · Rerank: ")
                        append(settings.rerankModel)
                    }
                    append(" · ")
                    append(settings.retrievalMode.displayName)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
