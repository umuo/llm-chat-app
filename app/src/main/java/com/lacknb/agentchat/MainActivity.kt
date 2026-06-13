package com.lacknb.agentchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lacknb.agentchat.core.designsystem.AgentChatTheme
import com.lacknb.agentchat.core.harness.AgentEvent
import com.lacknb.agentchat.core.harness.AgentEventType
import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.model.ChatMessage
import com.lacknb.agentchat.core.model.ChatToolCall
import com.lacknb.agentchat.core.model.MessageRole
import com.lacknb.agentchat.core.model.MessageStatus
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionMessage
import com.lacknb.agentchat.core.network.ChatCompletionRequest
import com.lacknb.agentchat.core.network.ChatCompletionStreamEvent
import com.lacknb.agentchat.core.network.ChatCompletionTool
import com.lacknb.agentchat.core.network.ChatCompletionToolCall
import com.lacknb.agentchat.core.network.ChatCompletionToolCallFunction
import com.lacknb.agentchat.core.network.ChatCompletionsClient
import com.lacknb.agentchat.core.network.OpenAiChatCompletionsClient
import com.lacknb.agentchat.core.prompts.ManagedPrompt
import com.lacknb.agentchat.core.prompts.PromptRepository
import com.lacknb.agentchat.core.provider.ProviderRepository
import com.lacknb.agentchat.feature.chat.ChatRoute
import com.lacknb.agentchat.feature.prompts.PromptManagementRoute
import com.lacknb.agentchat.feature.settings.SettingsRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AgentChat)
        super.onCreate(savedInstanceState)
        val providerRepository = ProviderRepository(applicationContext)
        val promptRepository = PromptRepository(applicationContext)
        val chatClient = OpenAiChatCompletionsClient()
        val agentWorkspace = File(applicationContext.filesDir, "agent_workspace")

        setContent {
            AgentChatTheme {
                var showStartupSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(1_400)
                    showStartupSplash = false
                }

                if (showStartupSplash) {
                    StartupSplashImage()
                } else {
                    AgentChatApp(
                        providerRepository = providerRepository,
                        promptRepository = promptRepository,
                        chatClient = chatClient,
                        agentWorkspace = agentWorkspace,
                    )
                }
            }
        }
    }
}

@Composable
private fun StartupSplashImage() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFCFCF8)),
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_agentchat),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun AgentChatApp(
    providerRepository: ProviderRepository,
    promptRepository: PromptRepository,
    chatClient: ChatCompletionsClient,
    agentWorkspace: File,
) {
    val navController = rememberNavController()
    val providerSettings by providerRepository.settings.collectAsState()

    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.Chat.route,
    ) {
        composable(TopLevelDestination.Chat.route) {
            ChatRoute(
                providerSettings = providerSettings,
                onOpenSettings = { navController.navigate(TopLevelDestination.Settings.route) },
                onOpenPrompts = { navController.navigate(TopLevelDestination.Prompts.route) },
                onSendMessage = { messages, onDelta, onToolCallDelta ->
                    streamChatCompletion(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                        messages = messages,
                        onDelta = onDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                },
                onRunAgent = { goal, history, onEvent, onDelta, onToolCallDelta ->
                    runAgentChatCompletion(
                        providerRepository = providerRepository,
                        promptRepository = promptRepository,
                        chatClient = chatClient,
                        agentWorkspace = agentWorkspace,
                        goal = goal,
                        history = history,
                        onEvent = onEvent,
                        onDelta = onDelta,
                        onToolCallDelta = onToolCallDelta,
                    )
                },
            )
        }
        composable(TopLevelDestination.Settings.route) {
            SettingsRoute(
                settings = providerSettings,
                onBackToChat = { navController.popBackStack() },
                onSaveProvider = providerRepository::saveProvider,
                onTestConnection = {
                    testChatCompletionConnection(
                        providerRepository = providerRepository,
                        chatClient = chatClient,
                    )
                },
            )
        }
        composable(TopLevelDestination.Prompts.route) {
            PromptManagementRoute(
                repository = promptRepository,
                onBackToChat = { navController.popBackStack() },
            )
        }
    }
}

private suspend fun runAgentChatCompletion(
    providerRepository: ProviderRepository,
    promptRepository: PromptRepository,
    chatClient: ChatCompletionsClient,
    agentWorkspace: File,
    goal: String,
    history: List<ChatMessage>,
    onEvent: (AgentEvent) -> Unit,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit,
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API 密钥未配置")
    var eventIndex = 0
    val conversation = buildAgentMessages(goal, history).toMutableList()

    fun emitEvent(
        type: AgentEventType,
        summary: String,
        payloadJson: String? = null,
        riskLevel: RiskLevel? = null,
    ) {
        onEvent(
            AgentEvent(
                id = "agent-${System.currentTimeMillis()}-${eventIndex++}",
                type = type,
                summary = summary,
                payloadJson = payloadJson,
                riskLevel = riskLevel,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    emitEvent(
        type = AgentEventType.Plan,
        summary = "智能体模式：根据是否有工具调用执行操作，无工具调用时直接回复并结束。",
    )

    repeat(MaxAgentToolTurns) { turnIndex ->
        var emittedStreamingObservation = false
        val assistantContent = StringBuilder()
        val toolCalls = mutableListOf<ChatToolCall>()
        val announcedToolCallIndexes = mutableSetOf<Int>()

        emitEvent(
            type = AgentEventType.Action,
            summary = "正在使用 ${agentTools().size} 个可用工具调用 ${profile.defaultModel}。",
        )

        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = conversation,
            stream = true,
            tools = agentTools(),
            toolChoice = "auto",
        )

        chatClient.streamChatCompletion(
            profile = profile,
            apiKey = apiKey,
            request = request,
        ).collect { event ->
            when (event) {
                is ChatCompletionStreamEvent.Completed -> Unit
                is ChatCompletionStreamEvent.Delta -> {
                    if (!emittedStreamingObservation) {
                        emitEvent(
                            type = AgentEventType.Observation,
                            summary = "模型响应开始流式传输。",
                        )
                        emittedStreamingObservation = true
                    }
                    assistantContent.append(event.content)
                    onDelta(event.content)
                }
                is ChatCompletionStreamEvent.ToolCallDelta -> {
                    if (announcedToolCallIndexes.add(event.delta.index)) {
                        emitEvent(
                            type = AgentEventType.Action,
                            summary = "模型请求工具：${event.delta.functionName ?: "函数_${event.delta.index}"}",
                            payloadJson = event.delta.arguments.takeIf { it.isNotBlank() },
                            riskLevel = event.delta.functionName.toolRiskLevel(),
                        )
                    }
                    val toolDelta = ChatToolCall(
                        index = event.delta.index,
                        id = event.delta.id,
                        name = event.delta.functionName,
                        arguments = event.delta.arguments,
                    )
                    toolCalls.mergeInPlace(toolDelta)
                    onToolCallDelta(toolDelta.copy(index = turnIndex * 100 + toolDelta.index))
                }
                is ChatCompletionStreamEvent.Failed -> {
                    emitEvent(
                        type = AgentEventType.Error,
                        summary = event.message,
                    )
                    throw IllegalStateException(event.message, event.cause)
                }
            }
        }

        if (toolCalls.isEmpty()) {
            emitEvent(
                type = AgentEventType.Final,
                summary = "智能体运行结束，无需调用其他工具。",
            )
            return@runCatching
        }

        conversation += ChatCompletionMessage(
            role = "assistant",
            content = assistantContent.toString(),
            toolCalls = toolCalls.sortedBy { it.index }.map { call ->
                ChatCompletionToolCall(
                    id = call.id ?: "tool_${turnIndex}_${call.index}",
                    function = ChatCompletionToolCallFunction(
                        name = call.name ?: "unknown_tool",
                        arguments = call.arguments,
                    )
                )
            },
        )

        toolCalls.sortedBy { it.index }.forEach { call ->
            val toolName = call.name ?: "unknown_tool"
            val toolResult = executeAgentTool(
                name = toolName,
                argumentsJson = call.arguments,
                profile = profile,
                apiKey = apiKey,
                chatClient = chatClient,
                agentWorkspace = agentWorkspace,
                promptRepository = promptRepository,
            )

            emitEvent(
                type = AgentEventType.Observation,
                summary = "工具运行结束：$toolName",
                payloadJson = toolResult.take(1_200),
                riskLevel = toolName.toolRiskLevel(),
            )

            conversation += ChatCompletionMessage(
                role = "tool",
                toolCallId = call.id ?: "tool_${turnIndex}_${call.index}",
                content = toolResult,
            )
        }
    }

    emitEvent(
        type = AgentEventType.Error,
        summary = "为避免死循环，智能体在执行 $MaxAgentToolTurns 轮工具调用后停止。",
    )
    onDelta(
        "\n\n我在得出最终答案之前达到了工具调用次数上限。请尝试缩小任务范围，或让我继续执行。",
    )
}

private fun MutableList<ChatToolCall>.mergeInPlace(delta: ChatToolCall) {
    val existingIndex = indexOfFirst { it.index == delta.index }
    if (existingIndex < 0) {
        add(delta)
    } else {
        val existing = this[existingIndex]
        this[existingIndex] = existing.copy(
            id = delta.id ?: existing.id,
            name = delta.name ?: existing.name,
            arguments = existing.arguments + delta.arguments,
        )
    }
}

private suspend fun executeAgentTool(
    name: String,
    argumentsJson: String,
    profile: com.lacknb.agentchat.core.model.ProviderProfile,
    apiKey: String,
    chatClient: ChatCompletionsClient,
    agentWorkspace: File,
    promptRepository: PromptRepository,
): String {
    val args = parseToolArguments(argumentsJson)
    return runCatching {
        when (name) {
            "plan_agent" -> runPlanningSubAgent(
                objective = args.optString("objective").ifBlank { args.optString("goal") },
                context = args.optString("context"),
                profile = profile,
                apiKey = apiKey,
                chatClient = chatClient,
            )
            "read_file" -> {
                val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
                require(file.exists() && file.isFile) { "文件不存在：${args.getString("path")}" }
                JSONObject()
                    .put("ok", true)
                    .put("path", args.getString("path"))
                    .put("content", file.readText().take(MaxToolResultChars))
                    .toString()
            }
            "write_file" -> {
                val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
                file.parentFile?.mkdirs()
                file.writeText(args.getString("content"))
                JSONObject()
                    .put("ok", true)
                    .put("path", args.getString("path"))
                    .put("bytes", file.length())
                    .toString()
            }
            "edit_file" -> {
                val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
                require(file.exists() && file.isFile) { "文件不存在：${args.getString("path")}" }
                val oldText = args.getString("old_text")
                val newText = args.getString("new_text")
                val original = file.readText()
                require(oldText in original) { "在 ${args.getString("path")} 中未找到 old_text" }
                val updated = if (args.optBoolean("replace_all", false)) {
                    original.replace(oldText, newText)
                } else {
                    original.replaceFirst(oldText, newText)
                }
                file.writeText(updated)
                JSONObject()
                    .put("ok", true)
                    .put("path", args.getString("path"))
                    .put("changed", true)
                    .toString()
            }
            "record_memory_candidate" -> {
                JSONObject()
                    .put("ok", true)
                    .put("status", "candidate_recorded_for_review")
                    .put("content", args.optString("content"))
                    .put("reason", args.optString("reason"))
                    .put("sensitivity", args.optString("sensitivity"))
                    .toString()
            }
            "manage_prompts" -> executePromptTool(args, promptRepository)
            else -> {
                JSONObject()
                    .put("ok", false)
                    .put("error", "未知工具：$name")
                    .toString()
            }
        }
    }.getOrElse { error ->
        JSONObject()
            .put("ok", false)
            .put("error", error.message ?: "工具执行失败")
            .toString()
    }
}

private fun executePromptTool(args: JSONObject, promptRepository: PromptRepository): String {
    return when (val action = args.optString("action", "list")) {
        "list" -> {
            val category = args.optStringOrNull("category")
            val query = args.optStringOrNull("query")?.lowercase()
            val prompts = promptRepository.prompts.value
                .filter { prompt -> category == null || prompt.category == category }
                .filter { prompt ->
                    query == null ||
                        prompt.title.lowercase().contains(query) ||
                        prompt.content.lowercase().contains(query) ||
                        prompt.category.lowercase().contains(query)
                }
            JSONObject()
                .put("ok", true)
                .put("action", action)
                .put("prompts", JSONArray().apply { prompts.forEach { put(it.toToolJson(includeContent = true)) } })
                .toString()
        }
        "categories" -> JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("categories", JSONArray().apply { promptRepository.categories().forEach { put(it) } })
            .toString()
        "create" -> {
            val prompt = promptRepository.createPrompt(
                title = args.optString("title"),
                content = args.getString("content"),
                category = args.optString("category"),
            )
            JSONObject()
                .put("ok", true)
                .put("action", action)
                .put("prompt", prompt.toToolJson(includeContent = true))
                .toString()
        }
        "update" -> {
            val target = resolvePromptTarget(promptRepository, args)
            val prompt = promptRepository.updatePrompt(
                id = target.id,
                title = args.optStringOrNull("title"),
                content = args.optStringOrNull("content"),
                category = args.optStringOrNull("category"),
            )
            JSONObject()
                .put("ok", true)
                .put("action", action)
                .put("prompt", prompt.toToolJson(includeContent = true))
                .toString()
        }
        "delete" -> {
            val target = resolvePromptTarget(promptRepository, args)
            val deleted = promptRepository.deletePrompt(target.id)
            JSONObject()
                .put("ok", deleted)
                .put("action", action)
                .put("deleted_id", target.id)
                .toString()
        }
        "export_json" -> JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("json", promptRepository.exportToJson(args.optStringOrNull("category")))
            .toString()
        "import_json" -> {
            val imported = promptRepository.importFromJson(
                json = args.getString("json"),
                replaceExisting = args.optBoolean("replace_existing", false),
            )
            JSONObject()
                .put("ok", true)
                .put("action", action)
                .put("imported_count", imported.size)
                .put("prompts", JSONArray().apply { imported.forEach { put(it.toToolJson(includeContent = false)) } })
                .toString()
        }
        else -> JSONObject()
            .put("ok", false)
            .put("error", "未知提示词操作：$action")
            .toString()
    }
}

private fun resolvePromptTarget(
    promptRepository: PromptRepository,
    args: JSONObject,
): ManagedPrompt {
    args.optStringOrNull("id")?.let { id ->
        promptRepository.findById(id)?.let { return it }
        error("提示词不存在：$id")
    }
    val title = args.optStringOrNull("title")
        ?: args.optStringOrNull("match_title")
        ?: error("需要提供 id 或 title/match_title")
    val matches = promptRepository.prompts.value.filter { it.title == title }
    require(matches.isNotEmpty()) { "未找到标题为「$title」的提示词" }
    require(matches.size == 1) { "标题「$title」匹配到多条提示词，请改用 id" }
    return matches.first()
}

private fun ManagedPrompt.toToolJson(includeContent: Boolean): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("category", category)
        .put("updated_at_millis", updatedAtMillis)
        .apply {
            if (includeContent) put("content", content)
        }
}

private fun JSONObject.optStringOrNull(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name).trim().takeIf { it.isNotBlank() } else null
}

private suspend fun runPlanningSubAgent(
    objective: String,
    context: String,
    profile: com.lacknb.agentchat.core.model.ProviderProfile,
    apiKey: String,
    chatClient: ChatCompletionsClient,
): String {
    require(objective.isNotBlank()) { "目标（objective）是必需的" }
    val plan = StringBuilder()
    val request = ChatCompletionRequest(
        model = profile.defaultModel,
        messages = listOf(
            ChatCompletionMessage(
                role = "system",
                content = "你是一个规划子智能体。在有用的情况下，生成一个紧凑、可操作的 Markdown 格式的计划，并包含假设和风险。",
            ),
            ChatCompletionMessage(
                role = "user",
                content = buildString {
                    appendLine("目标：$objective")
                    if (context.isNotBlank()) appendLine("上下文：$context")
                },
            ),
        ),
        stream = true,
    )

    withTimeout(30_000) {
        chatClient.streamChatCompletion(
            profile = profile,
            apiKey = apiKey,
            request = request,
        ).collect { event ->
            when (event) {
                is ChatCompletionStreamEvent.Completed -> Unit
                is ChatCompletionStreamEvent.Delta -> plan.append(event.content)
                is ChatCompletionStreamEvent.ToolCallDelta -> Unit
                is ChatCompletionStreamEvent.Failed -> throw IllegalStateException(event.message, event.cause)
            }
        }
    }

    return JSONObject()
        .put("ok", true)
        .put("plan", plan.toString().take(MaxToolResultChars))
        .toString()
}

private fun parseToolArguments(argumentsJson: String): JSONObject {
    return runCatching {
        JSONObject(argumentsJson.ifBlank { "{}" })
    }.getOrDefault(JSONObject())
}

private fun resolveAgentWorkspaceFile(workspace: File, path: String): File {
    val normalized = path.trim().replace('\\', '/')
    require(normalized.isNotBlank()) { "path is required" }
    require(!normalized.startsWith("/") && normalized.split('/').none { it == ".." }) {
        "仅允许使用智能体工作空间内的相对路径"
    }
    workspace.mkdirs()
    val root = workspace.canonicalFile
    val file = File(root, normalized).canonicalFile
    require(file.path == root.path || file.path.startsWith(root.path + File.separator)) {
        "路径超出了智能体工作空间"
    }
    return file
}

private fun String?.toolRiskLevel(): RiskLevel {
    return when (this) {
        "write_file", "edit_file" -> RiskLevel.Medium
        "read_file", "plan_agent", "record_memory_candidate" -> RiskLevel.Low
        else -> RiskLevel.Low
    }
}

private const val MaxAgentToolTurns = 5
private const val MaxToolResultChars = 12_000

private suspend fun streamChatCompletion(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
    messages: List<ChatMessage>,
    onDelta: (String) -> Unit,
    onToolCallDelta: (ChatToolCall) -> Unit = {},
): Result<Unit> = runCatching {
    val profile = providerRepository.currentProfile()
    val apiKey = providerRepository.currentApiKey()
        ?: error("API 密钥未配置")

    val request = ChatCompletionRequest(
        model = profile.defaultModel,
        messages = messages
            .filter { message ->
                message.status != MessageStatus.Streaming &&
                    (message.content.isNotBlank() || message.imageUrls.isNotEmpty()) &&
                    message.role in setOf(MessageRole.User, MessageRole.Assistant, MessageRole.System)
            }
            .map { message ->
                ChatCompletionMessage(
                    role = message.role.toChatCompletionRole(),
                    content = message.content,
                    imageUrls = message.imageUrls,
                )
            },
        stream = true,
    )

    chatClient.streamChatCompletion(
        profile = profile,
        apiKey = apiKey,
        request = request,
    ).collect { event ->
        when (event) {
            is ChatCompletionStreamEvent.Completed -> Unit
            is ChatCompletionStreamEvent.Delta -> onDelta(event.content)
            is ChatCompletionStreamEvent.Failed -> throw IllegalStateException(event.message, event.cause)
            is ChatCompletionStreamEvent.ToolCallDelta -> {
                onToolCallDelta(
                    ChatToolCall(
                        index = event.delta.index,
                        id = event.delta.id,
                        name = event.delta.functionName,
                        arguments = event.delta.arguments,
                    ),
                )
            }
        }
    }
}

private fun buildAgentMessages(
    goal: String,
    history: List<ChatMessage>,
): List<ChatCompletionMessage> {
    val systemPrompt = """
        你是运行在智能体（Agent）模式下的 AgentChat。
        你可以通过调用可用的工具来协助用户完成任务。
        如果用户要求新增、查询、修改、删除、分类、导入或导出提示词，请使用 manage_prompts 工具操作本机提示词库。
        如果需要使用工具，请直接进行工具调用。
        如果不需要使用工具，或者在获得工具返回的结果后，请直接给出最终的回答。
        请务必使用中文进行回复。
    """.trimIndent()
    val recentHistory = history
        .filter { message ->
            message.status == MessageStatus.Complete &&
                message.id != "welcome" &&
                (message.content.isNotBlank() || message.imageUrls.isNotEmpty()) &&
                message.role in setOf(MessageRole.User, MessageRole.Assistant)
        }
        .takeLast(8)
        .map { message ->
            ChatCompletionMessage(
                role = message.role.toChatCompletionRole(),
                content = message.content,
                imageUrls = message.imageUrls,
            )
        }

    val goalMessage = ChatCompletionMessage(role = "user", content = goal)
    val conversation = if (recentHistory.lastOrNull() == goalMessage) {
        recentHistory
    } else {
        recentHistory + goalMessage
    }

    return listOf(ChatCompletionMessage(role = "system", content = systemPrompt)) + conversation
}

private fun agentTools(): List<ChatCompletionTool> {
    return listOf(
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "plan_agent",
                description = "请求规划子智能体为复杂任务创建一个紧凑的计划。作为工具使用，而不是用户可见的模式。",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "objective": {"type": "string"},
                        "context": {"type": "string"}
                      },
                      "required": ["objective"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "read_file",
                description = "从应用私有的智能体工作空间中读取一个 UTF-8 文本文件。",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"}
                      },
                      "required": ["path"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "write_file",
                description = "在应用私有的智能体工作空间内写入一个 UTF-8 文本文件，必要时创建父目录。",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"},
                        "content": {"type": "string"}
                      },
                      "required": ["path", "content"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "edit_file",
                description = "替换应用私有智能体工作空间内 UTF-8 文件中的文本。",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "path": {"type": "string"},
                        "old_text": {"type": "string"},
                        "new_text": {"type": "string"},
                        "replace_all": {"type": "boolean"}
                      },
                      "required": ["path", "old_text", "new_text"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "record_memory_candidate",
                description = "提议一条记忆项，在保存前应展示给用户以进行审批。",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "content": {"type": "string"},
                        "reason": {"type": "string"},
                        "sensitivity": {"type": "string", "enum": ["low", "medium", "high"]}
                      },
                      "required": ["content", "reason", "sensitivity"]
                    }
                """.trimIndent(),
            ),
        ),
        ChatCompletionTool(
            function = ChatCompletionFunction(
                name = "manage_prompts",
                description = "管理本机提示词库。支持查询列表、分类、新增、修改、删除、导出 JSON、从 JSON 导入提示词。",
                parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "action": {
                          "type": "string",
                          "enum": ["list", "categories", "create", "update", "delete", "export_json", "import_json"]
                        },
                        "id": {"type": "string", "description": "提示词 id。修改或删除时优先使用。"},
                        "title": {"type": "string", "description": "提示词标题；也可用于按精确标题定位。"},
                        "match_title": {"type": "string", "description": "当 title 要作为新标题时，用此字段按旧标题定位。"},
                        "content": {"type": "string", "description": "提示词内容。新增时必填，修改时可选。"},
                        "category": {"type": "string", "description": "提示词分类，查询或导出时可作为筛选条件。"},
                        "query": {"type": "string", "description": "列表查询关键词，会匹配标题、内容和分类。"},
                        "json": {"type": "string", "description": "导入时提供的提示词 JSON。"},
                        "replace_existing": {"type": "boolean", "description": "导入时是否替换现有提示词。默认 false。"}
                      },
                      "required": ["action"]
                    }
                """.trimIndent(),
            ),
        ),
    )
}

private suspend fun testChatCompletionConnection(
    providerRepository: ProviderRepository,
    chatClient: ChatCompletionsClient,
): Result<Unit> = runCatching {
    withTimeout(30_000) {
        val profile = providerRepository.currentProfile()
        val apiKey = providerRepository.currentApiKey()
            ?: error("API 密钥未配置")
        val request = ChatCompletionRequest(
            model = profile.defaultModel,
            messages = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = "请回复 ok。",
                ),
            ),
            stream = true,
            maxTokens = 4,
        )

        chatClient.streamChatCompletion(
            profile = profile,
            apiKey = apiKey,
            request = request,
        ).collect { event ->
            when (event) {
                is ChatCompletionStreamEvent.Failed -> {
                    throw IllegalStateException(event.message, event.cause)
                }
                is ChatCompletionStreamEvent.Completed,
                is ChatCompletionStreamEvent.Delta,
                is ChatCompletionStreamEvent.ToolCallDelta -> Unit
            }
        }
    }
}

private fun MessageRole.toChatCompletionRole(): String {
    return when (this) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.System -> "system"
        MessageRole.Tool -> "tool"
    }
}

private enum class TopLevelDestination(val route: String) {
    Chat(route = "chat"),
    Prompts(route = "prompts"),
    Settings(route = "settings"),
}
