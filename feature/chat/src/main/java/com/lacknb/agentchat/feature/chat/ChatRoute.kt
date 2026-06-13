package com.lacknb.agentchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val agentEventsByMessage = remember { mutableStateMapOf<String, List<AgentEvent>>() }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by rememberSaveableMutableState("")
    var selectedMode by rememberSaveableMutableState(ChatMode.Chat)
    var isSending by rememberSaveableMutableState(false)

    LaunchedEffect(messages.size, messages.lastOrNull()?.content, messages.lastOrNull()?.toolCalls) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun sendMessage() {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || isSending) return

        messages += ChatMessage(
            id = "user-${messages.size}",
            role = MessageRole.User,
            content = trimmed,
        )
        val assistantId = "assistant-${messages.size}"
        messages += ChatMessage(
            id = assistantId,
            role = MessageRole.Assistant,
            content = "",
            status = MessageStatus.Streaming,
        )
        input = ""
        isSending = true

        scope.launch {
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
                    trimmed,
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
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ConversationTopBar(
                providerSettings = providerSettings,
                selectedMode = selectedMode,
                turnCount = messages.count { it.role == MessageRole.User },
                onOpenSettings = onOpenSettings,
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

@Composable
private fun ConversationTopBar(
    providerSettings: ProviderSettings,
    selectedMode: ChatMode,
    turnCount: Int,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                        .padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                        enabled = input.isNotBlank(),
                        onSend = onSend,
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
    modifier: Modifier = Modifier,
) {
    val canSend = enabled && !isSending
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(
                if (canSend) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(enabled = canSend, onClick = onSend),
        contentAlignment = Alignment.Center,
    ) {
        if (isSending) {
            Text(
                text = "...",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                MarkdownContent(markdown = message.content, color = contentColor)
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
                Text(
                    text = "流式传输中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (message.content.isNotBlank()) {
            MarkdownContent(
                markdown = message.content,
                color = contentColor,
            )
        }
        if (agentEvents.isNotEmpty()) {
            AgentTraceTimeline(events = agentEvents)
        }
        message.toolCalls.forEach { toolCall ->
            ToolCallBlock(toolCall = toolCall)
        }
    }
}

@Composable
private fun AgentTraceTimeline(
    events: List<AgentEvent>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "智能体运行轨迹",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            events.takeLast(5).forEach { event ->
                AgentEventRow(event = event)
            }
        }
    }
}

@Composable
private fun AgentEventRow(
    event: AgentEvent,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(7.dp)
                .background(
                    color = event.type.color(),
                    shape = CircleShape,
                ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = event.type.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = event.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun AgentEventType.color() = when (this) {
    AgentEventType.Plan -> MaterialTheme.colorScheme.primary
    AgentEventType.Action -> MaterialTheme.colorScheme.tertiary
    AgentEventType.Observation -> MaterialTheme.colorScheme.secondary
    AgentEventType.Memory -> MaterialTheme.colorScheme.secondary
    AgentEventType.UserApproval -> MaterialTheme.colorScheme.error
    AgentEventType.Final -> MaterialTheme.colorScheme.primary
    AgentEventType.Error -> MaterialTheme.colorScheme.error
}

private fun AgentEventType.label() = when (this) {
    AgentEventType.Plan -> "计划"
    AgentEventType.Action -> "行动"
    AgentEventType.Observation -> "观察"
    AgentEventType.Memory -> "记忆"
    AgentEventType.UserApproval -> "审批"
    AgentEventType.Final -> "最终结果"
    AgentEventType.Error -> "错误"
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
