package com.lacknb.agentchat.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lacknb.agentchat.core.harness.AgentEvent
import com.lacknb.agentchat.core.harness.AgentEventType
import com.lacknb.agentchat.core.model.ChatMessage
import com.lacknb.agentchat.core.model.ChatMode
import com.lacknb.agentchat.core.model.ChatToolCall
import com.lacknb.agentchat.core.model.MessageRole
import com.lacknb.agentchat.core.model.MessageStatus
import com.lacknb.agentchat.core.model.ProviderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import android.net.Uri
import android.content.Context
import com.lacknb.agentchat.core.model.ChatAttachment
import kotlinx.coroutines.Dispatchers

@Composable
fun ChatRoute(
    providerSettings: ProviderSettings,
    onOpenSettings: () -> Unit,
    onSendMessage: suspend (
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        onToolCallDelta: (ChatToolCall) -> Unit,
    ) -> Result<Unit>,
    onRunAgent: suspend (
        goal: String,
        history: List<ChatMessage>,
        onEvent: (AgentEvent) -> Unit,
        onDelta: (String) -> Unit,
        onToolCallDelta: (ChatToolCall) -> Unit,
    ) -> Result<Unit>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val historyManager = remember { ConversationHistoryManager(context) }
    var currentConversationId by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var showHistoryDialog by remember { androidx.compose.runtime.mutableStateOf(false) }
    var historySummaries by remember { androidx.compose.runtime.mutableStateOf(historyManager.getSummaries()) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val agentEventsByMessage = remember { mutableStateMapOf<String, List<AgentEvent>>() }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val selectedAttachments = remember { androidx.compose.runtime.mutableStateListOf<ChatAttachment>() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val processed = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        val fileName = getFileName(context, uri)
                        val mimeType = context.contentResolver.getType(uri) ?: ""
                        val isImage = mimeType.startsWith("image/") || fileName.lowercase().let {
                            it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") || it.endsWith(".gif")
                        }
                        if (isImage) {
                            val base64 = readUriAsBase64(context, uri)
                            if (base64 != null) {
                                ChatAttachment(
                                    name = fileName,
                                    mimeType = mimeType,
                                    isImage = true,
                                    base64Data = base64
                                )
                            } else null
                        } else {
                            val textContent = DocumentTextExtractor.extractTextFromFile(context, uri, fileName)
                            ChatAttachment(
                                name = fileName,
                                mimeType = mimeType,
                                isImage = false,
                                textContent = textContent
                            )
                        }
                    }
                }
                selectedAttachments.addAll(processed)
            }
        }
    }

    var input by rememberSaveableMutableState("")
    var selectedMode by rememberSaveableMutableState(ChatMode.Chat)
    var isSending by rememberSaveableMutableState(false)
    var currentJob by remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }

    var programmaticScrollCount by remember { androidx.compose.runtime.mutableStateOf(0) }
    val isProgrammaticScrolling by remember {
        androidx.compose.runtime.derivedStateOf { programmaticScrollCount > 0 }
    }
    var shouldAutoScroll by remember { androidx.compose.runtime.mutableStateOf(true) }

    LaunchedEffect(listState.isScrollInProgress, listState.canScrollForward, isProgrammaticScrolling) {
        if (!listState.canScrollForward) {
            shouldAutoScroll = true
        } else if (listState.isScrollInProgress && !isProgrammaticScrolling) {
            shouldAutoScroll = false
        }
    }

    var lastMessagesSize by remember { androidx.compose.runtime.mutableStateOf(0) }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content, messages.lastOrNull()?.toolCalls) {
        if (messages.isNotEmpty()) {
            val sizeChanged = messages.size != lastMessagesSize
            lastMessagesSize = messages.size
            if (sizeChanged || shouldAutoScroll) {
                try {
                    programmaticScrollCount++
                    listState.animateScrollToItem(messages.lastIndex)
                } catch (e: Exception) {
                    // Ignored
                } finally {
                    programmaticScrollCount--
                }
            }
        }
    }

    fun stopGeneration() {
        currentJob?.cancel()
        currentJob = null
        isSending = false
        val lastAssistantMessage = messages.lastOrNull { it.role == MessageRole.Assistant }
        if (lastAssistantMessage != null && lastAssistantMessage.status == MessageStatus.Streaming) {
            messages.updateMessage(lastAssistantMessage.id) { message ->
                message.copy(
                    content = message.content.ifBlank { "已停止生成。" },
                    status = MessageStatus.Complete,
                )
            }
        }
        val savedId = historyManager.saveConversation(
            currentConversationId,
            selectedMode,
            messages.toList(),
            agentEventsByMessage.toMap()
        )
        currentConversationId = savedId
    }

    fun resetConversation() {
        currentJob?.cancel()
        currentJob = null
        isSending = false
        messages.clear()
        agentEventsByMessage.clear()
        currentConversationId = null
    }

    fun sendMessage() {
        val trimmed = input.trim()
        if ((trimmed.isEmpty() && selectedAttachments.isEmpty()) || isSending) return

        val builder = java.lang.StringBuilder(trimmed)
        val docs = selectedAttachments.filter { !it.isImage }
        if (docs.isNotEmpty()) {
            builder.append("\n\n--- 附件文件 ---\n")
            docs.forEach { doc ->
                builder.append("文件名: ").append(doc.name).append("\n")
                builder.append("内容:\n").append(doc.textContent ?: "").append("\n")
                builder.append("-------------------------\n")
            }
        }
        val finalContent = builder.toString()
        val imageUrls = selectedAttachments.filter { it.isImage }.mapNotNull { it.base64Data }

        messages += ChatMessage(
            id = "user-${messages.size}",
            role = MessageRole.User,
            content = finalContent,
            imageUrls = imageUrls,
            attachments = selectedAttachments.toList(),
        )
        val assistantId = "assistant-${messages.size}"
        messages += ChatMessage(
            id = assistantId,
            role = MessageRole.Assistant,
            content = "",
            status = MessageStatus.Streaming,
        )
        input = ""
        selectedAttachments.clear()
        isSending = true

        val savedId = historyManager.saveConversation(
            currentConversationId,
            selectedMode,
            messages.toList(),
            agentEventsByMessage.toMap()
        )
        currentConversationId = savedId

        currentJob = scope.launch {
            val result = if (selectedMode == ChatMode.Chat) {
                onSendMessage(
                    messages.toList(),
                    { delta ->
                        messages.updateMessage(assistantId) { message ->
                            message.copy(content = message.content + delta)
                        }
                    },
                    { toolDelta ->
                        messages.updateMessage(assistantId) { message ->
                            message.copy(toolCalls = message.toolCalls.merge(toolDelta))
                        }
                    },
                )
            } else {
                onRunAgent(
                    finalContent,
                    messages.toList(),
                    { event ->
                        agentEventsByMessage[assistantId] = agentEventsByMessage[assistantId].orEmpty() + event
                    },
                    { delta ->
                        messages.updateMessage(assistantId) { message ->
                            message.copy(content = message.content + delta)
                        }
                    },
                    { toolDelta ->
                        messages.updateMessage(assistantId) { message ->
                            message.copy(toolCalls = message.toolCalls.merge(toolDelta))
                        }
                    },
                )
            }

            messages.updateMessage(assistantId) { message ->
                result.fold(
                    onSuccess = {
                        message.copy(
                            content = message.content.ifBlank {
                                if (message.toolCalls.isNotEmpty()) "已请求工具调用。" else "完成。"
                            },
                            status = MessageStatus.Complete,
                        )
                    },
                    onFailure = { error ->
                        message.copy(
                            content = message.content.ifBlank { error.message ?: "请求失败" },
                            status = MessageStatus.Failed,
                        )
                    },
                )
            }
            isSending = false
            currentJob = null

            val finalSavedId = historyManager.saveConversation(
                currentConversationId,
                selectedMode,
                messages.toList(),
                agentEventsByMessage.toMap()
            )
            currentConversationId = finalSavedId
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "A",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "AgentChat",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "智能 AI 助手",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                NavigationDrawerItem(
                    label = { Text("智能对话", fontWeight = FontWeight.Medium) },
                    selected = true,
                    onClick = {
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "内部知识库",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "规划中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "工具中心",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "规划中",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                NavigationDrawerItem(
                    label = { Text("服务商设置", fontWeight = FontWeight.Medium) },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                ConversationTopBar(
                    providerSettings = providerSettings,
                    selectedMode = selectedMode,
                    turnCount = messages.count { it.role == MessageRole.User },
                    onOpenSettings = onOpenSettings,
                    onResetConversation = ::resetConversation,
                    onOpenDrawer = {
                        scope.launch {
                            drawerState.open()
                        }
                    },
                    onOpenHistory = {
                        historySummaries = historyManager.getSummaries()
                        showHistoryDialog = true
                    }
                )
            },
            bottomBar = {
                ComposerBar(
                    input = input,
                    onInputChange = { input = it },
                    selectedMode = selectedMode,
                    onModeSelected = { selectedMode = it },
                    isSending = isSending,
                    onSend = ::sendMessage,
                    onStop = ::stopGeneration,
                    selectedAttachments = selectedAttachments,
                    onAttachFileClick = { filePickerLauncher.launch("*/*") },
                    onRemoveAttachment = { selectedAttachments.remove(it) },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .imePadding(),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(innerPadding),
            ) {
                if (messages.isEmpty()) {
                    EmptyConversation(
                        hasApiKey = providerSettings.hasApiKey,
                        selectedMode = selectedMode,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 28.dp),
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            agentEvents = agentEventsByMessage[message.id].orEmpty(),
                        )
                    }
                }
            }
        }
    }

    if (showHistoryDialog) {
        HistoryDialog(
            summaries = historySummaries,
            currentConversationId = currentConversationId,
            onClose = { showHistoryDialog = false },
            onSelect = { summary ->
                showHistoryDialog = false
                val detail = historyManager.loadConversation(summary.id)
                if (detail != null) {
                    messages.clear()
                    messages.addAll(detail.messages)
                    agentEventsByMessage.clear()
                    agentEventsByMessage.putAll(detail.agentEvents)
                    selectedMode = detail.selectedMode
                    currentConversationId = detail.id
                    shouldAutoScroll = true
                }
            },
            onDelete = { summary ->
                historyManager.deleteConversation(summary.id)
                historySummaries = historyManager.getSummaries()
                if (currentConversationId == summary.id) {
                    resetConversation()
                    currentConversationId = null
                }
            }
        )
    }
}

@Composable
private fun ConversationTopBar(
    providerSettings: ProviderSettings,
    selectedMode: ChatMode,
    turnCount: Int,
    onOpenSettings: () -> Unit,
    onResetConversation: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "菜单",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AgentChat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(active = providerSettings.hasApiKey)
                    Text(
                        text = "${if (selectedMode == ChatMode.Chat) "聊天" else "智能体"} · $turnCount 轮对话 · ${providerSettings.model}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onOpenHistory) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = "历史对话",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onResetConversation) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "新增会话",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable(onClick = onResetConversation)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "清除",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "服务商",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(
    hasApiKey: Boolean,
    selectedMode: ChatMode,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "A",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = "我能帮您做些什么？",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (hasApiKey) {
                if (selectedMode == ChatMode.Agent) "在需要时使用工具，然后持续运行直到任务完成。"
                else "问我任何问题。支持 Markdown 和代码块流式响应。"
            } else {
                "请在设置中添加您的服务商密钥以开始。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComposerBar(
    input: String,
    onInputChange: (String) -> Unit,
    selectedMode: ChatMode,
    onModeSelected: (ChatMode) -> Unit,
    isSending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    selectedAttachments: List<ChatAttachment>,
    onAttachFileClick: () -> Unit,
    onRemoveAttachment: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeSelector(
                selectedMode = selectedMode,
                onModeSelected = onModeSelected,
            )

            if (selectedAttachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    items(selectedAttachments) { attachment ->
                        AttachmentPreviewChip(
                            attachment = attachment,
                            onRemove = { onRemoveAttachment(attachment) }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(24.dp),
                        )
                        .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onAttachFileClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "添加附件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        minLines = 1,
                        maxLines = 6,
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (input.isEmpty()) {
                                    Text(
                                        text = "给 AgentChat 发送消息",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    SendButton(
                        isSending = isSending,
                        enabled = input.isNotBlank() || selectedAttachments.isNotEmpty(),
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun SendButton(
    isSending: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = enabled && !isSending
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                if (isSending) MaterialTheme.colorScheme.error
                else if (canSend) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(enabled = isSending || canSend, onClick = if (isSending) onStop else onSend),
        contentAlignment = Alignment.Center,
    ) {
        if (isSending) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.onError, shape = RoundedCornerShape(2.dp))
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "发送",
                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeSelector(
    selectedMode: ChatMode,
    onModeSelected: (ChatMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SegmentPill(
            label = "聊天",
            selected = selectedMode == ChatMode.Chat,
            onClick = { onModeSelected(ChatMode.Chat) },
        )
        SegmentPill(
            label = "智能体",
            selected = selectedMode == ChatMode.Agent,
            onClick = { onModeSelected(ChatMode.Agent) },
        )
    }
}

@Composable
private fun SegmentPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    agentEvents: List<AgentEvent>,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.User
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            AssistantMark(status = message.status)
            Spacer(modifier = Modifier.width(10.dp))
        }
        MessageContent(
            message = message,
            agentEvents = agentEvents,
            modifier = if (isUser) {
                Modifier.fillMaxWidth(0.82f)
            } else {
                Modifier
                    .weight(1f)
                    .widthIn(max = 720.dp)
            },
        )
    }
}

@Composable
private fun AssistantMark(
    status: MessageStatus,
    modifier: Modifier = Modifier,
) {
    val color = when (status) {
        MessageStatus.Failed -> MaterialTheme.colorScheme.errorContainer
        MessageStatus.Streaming -> MaterialTheme.colorScheme.primaryContainer
        MessageStatus.Complete -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "A",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MessageContent(
    message: ChatMessage,
    agentEvents: List<AgentEvent>,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.User
    val contentColor = when {
        message.status == MessageStatus.Failed -> MaterialTheme.colorScheme.onErrorContainer
        isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    if (isUser || message.status == MessageStatus.Failed) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(22.dp),
            color = if (message.status == MessageStatus.Failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val displayContent = message.content.substringBefore("\n\n--- 附件文件")
                if (displayContent.isNotBlank()) {
                    MarkdownContent(markdown = displayContent, color = contentColor)
                }
                if (message.attachments.isNotEmpty()) {
                    MessageAttachments(attachments = message.attachments)
                }
            }
        }
        return
    }

    Column(
        modifier = modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AgentChat",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            if (message.status == MessageStatus.Streaming) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "流式传输中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (agentEvents.isNotEmpty()) {
            agentEvents.forEach { event ->
                when (event.type) {
                    AgentEventType.Action -> {
                        if (event.summary.startsWith("模型请求工具：")) {
                            val toolName = event.summary.substringAfter("模型请求工具：")
                            ToolCallBlock(
                                toolCall = ChatToolCall(
                                    index = 0,
                                    name = toolName,
                                    arguments = event.payloadJson ?: ""
                                )
                            )
                        }
                    }
                    AgentEventType.Observation -> {
                        if (event.summary.startsWith("工具运行结束：")) {
                            val toolName = event.summary.substringAfter("工具运行结束：")
                            ToolObservationBlock(
                                toolName = toolName,
                                result = event.payloadJson ?: ""
                            )
                        }
                    }
                    else -> {}
                }
            }
        } else {
            message.toolCalls.forEach { toolCall ->
                ToolCallBlock(toolCall = toolCall)
            }
        }

        if (message.content.isNotBlank()) {
            MarkdownContent(
                markdown = message.content,
                color = contentColor,
            )
        } else if (message.status == MessageStatus.Streaming && agentEvents.none { it.type == AgentEventType.Observation && it.summary.startsWith("工具运行结束：") }) {
            ThinkingIndicator()
        }
    }
}

@Composable
private fun ToolObservationBlock(
    toolName: String,
    result: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "工具返回结果：$toolName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (result.isNotBlank()) {
            CodeBlock(code = result)
        } else {
            Text(
                text = "（无返回内容）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolCallBlock(
    toolCall: ChatToolCall,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = "工具调用",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = toolCall.name ?: "函数_${toolCall.index}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (toolCall.arguments.isNotBlank()) {
            CodeBlock(code = toolCall.arguments)
        }
    }
}

@Composable
private fun StatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(7.dp)
            .background(
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                shape = CircleShape,
            ),
    )
}

@Composable
private fun <T> rememberSaveableMutableState(initial: T) =
    rememberSaveable { androidx.compose.runtime.mutableStateOf(initial) }

private fun MutableList<ChatMessage>.updateMessage(
    id: String,
    transform: (ChatMessage) -> ChatMessage,
) {
    val index = indexOfFirst { it.id == id }
    if (index >= 0) {
        this[index] = transform(this[index])
    }
}

private fun List<ChatToolCall>.merge(delta: ChatToolCall): List<ChatToolCall> {
    val existingIndex = indexOfFirst { it.index == delta.index }
    if (existingIndex < 0) return this + delta

    return toMutableList().also { calls ->
        val existing = calls[existingIndex]
        calls[existingIndex] = existing.copy(
            id = delta.id ?: existing.id,
            name = delta.name ?: existing.name,
            arguments = existing.arguments + delta.arguments,
        )
    }
}

@Composable
private fun ThinkingIndicator(
    modifier: Modifier = Modifier,
) {
    val dotStates = listOf(
        remember { Animatable(0.2f) },
        remember { Animatable(0.2f) },
        remember { Animatable(0.2f) },
    )

    dotStates.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * 150L)
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0.2f at 0 with LinearOutSlowInEasing
                        1f at 300 with LinearOutSlowInEasing
                        0.2f at 600 with LinearOutSlowInEasing
                        0.2f at 900 with LinearOutSlowInEasing
                    },
                    repeatMode = RepeatMode.Restart,
                ),
            )
        }
    }

    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dotStates.forEach { animatable ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = animatable.value)),
            )
        }
    }
}

@Composable
private fun HistoryDialog(
    summaries: List<ConversationSummary>,
    currentConversationId: String?,
    onClose: () -> Unit,
    onSelect: (ConversationSummary) -> Unit,
    onDelete: (ConversationSummary) -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "历史对话",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (summaries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无历史对话记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(summaries, key = { it.id }) { summary ->
                            HistoryItem(
                                summary = summary,
                                isCurrent = summary.id == currentConversationId,
                                onSelect = { onSelect(summary) },
                                onDelete = { onDelete(summary) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(
    summary: ConversationSummary,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateString = remember(summary.timestamp) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(summary.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (summary.selectedMode == ChatMode.Chat) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (summary.selectedMode == ChatMode.Chat) Icons.Filled.Message
                else Icons.Filled.SmartToy,
                contentDescription = if (summary.selectedMode == ChatMode.Chat) "聊天" else "智能体",
                modifier = Modifier.size(18.dp),
                tint = if (summary.selectedMode == ChatMode.Chat) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = dateString,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun AttachmentPreviewChip(
    attachment: ChatAttachment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val base64Data = attachment.base64Data
            if (attachment.isImage && base64Data != null) {
                val imageBitmap = remember(base64Data) {
                    try {
                        val base64Str = base64Data.substringAfter("base64,")
                        val bytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (imageBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable { onRemove() }
            )
        }
    }
}

@Composable
private fun MessageAttachments(
    attachments: List<ChatAttachment>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            val base64Data = attachment.base64Data
            if (attachment.isImage && base64Data != null) {
                val imageBitmap = remember(base64Data) {
                    try {
                        val base64Str = base64Data.substringAfter("base64,")
                        val bytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (imageBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = imageBitmap,
                        contentDescription = attachment.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "文档附件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = it.getString(nameIndex)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "unknown"
}

private fun readUriAsBase64(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val bytes = inputStream.use { it.readBytes() }
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        "data:$mimeType;base64,$base64"
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
