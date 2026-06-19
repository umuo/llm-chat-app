import Foundation
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published var settings: ProviderSettings {
        didSet { save(settings, key: Keys.settings) }
    }
    @Published var apiKey: String {
        didSet { KeychainStore.save(apiKey, account: Keys.apiKey) }
    }
    @Published var messages: [ChatMessage] {
        didSet { save(messages, key: Keys.messages) }
    }
    @Published var conversations: [ConversationSnapshot] {
        didSet { save(conversations, key: Keys.conversations) }
    }
    @Published var currentConversationID: UUID?
    @Published var memories: [MemoryItem] {
        didSet { save(memories, key: Keys.memories) }
    }
    @Published var prompts: [ManagedPrompt] {
        didSet { save(prompts, key: Keys.prompts) }
    }
    @Published var toolConfiguration: ToolConfiguration {
        didSet { save(toolConfiguration, key: Keys.tools) }
    }
    @Published var chatMode: ChatMode = .chat
    @Published var agentPolicy: AgentPolicyType = .planning
    @Published var agentEvents: [AgentEvent] = []
    @Published var contextSummary: ContextSummary?
    @Published var isStreaming = false
    @Published var connectionStatus = ""
    @Published var availableModels: [String] = []
    @Published var selectedModelPickerTarget: ModelPickerTarget = .chat

    private let client = OpenAIClient()
    private var generationTask: Task<Void, Never>?

    init() {
        settings = Self.load(ProviderSettings.self, key: Keys.settings) ?? ProviderSettings()
        apiKey = KeychainStore.read(Keys.apiKey)
        messages = Self.cleanDefaultMessages(Self.load([ChatMessage].self, key: Keys.messages) ?? [])
        conversations = Self.load([ConversationSnapshot].self, key: Keys.conversations) ?? []
        currentConversationID = nil
        memories = Self.cleanDefaultMemories(Self.load([MemoryItem].self, key: Keys.memories) ?? [])
        prompts = Self.load([ManagedPrompt].self, key: Keys.prompts) ?? Self.samplePrompts
        toolConfiguration = Self.load(ToolConfiguration.self, key: Keys.tools) ?? ToolConfiguration()
        contextSummary = nil
        save(messages, key: Keys.messages)
        save(memories, key: Keys.memories)
        if ProcessInfo.processInfo.arguments.contains("-AgentChatToolPanelPreview") {
            loadToolPanelPreviewData()
        }
    }

    func send(_ text: String, attachments: [ChatAttachment], useWebSearch: Bool) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (!trimmed.isEmpty || !attachments.isEmpty), !isStreaming else { return }
        guard !apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            messages.append(ChatMessage(role: .assistant, content: "请先在设置中配置 API Key。", status: .failed))
            return
        }

        let attachmentText = attachments
            .filter { !$0.isImage }
            .compactMap { attachment -> String? in
                guard let textContent = attachment.textContent, !textContent.isEmpty else { return nil }
                return "\n\n--- 附件文件：\(attachment.name) ---\n\(textContent)"
            }
            .joined()
        let userMessage = ChatMessage(
            role: .user,
            content: trimmed + attachmentText,
            attachments: attachments
        )
        let assistantID = UUID()
        messages.append(userMessage)
        messages.append(ChatMessage(id: assistantID, role: .assistant, content: "", status: .streaming))
        persistCurrentConversation()

        if chatMode == .agent {
            runAgent(
                assistantID: assistantID,
                requestMessages: messages.filter {
                    $0.id != assistantID && $0.status != .failed && !Self.isDefaultWelcomeMessage($0)
                },
                useWebSearch: useWebSearch
            )
            return
        }
        agentEvents = []

        isStreaming = true
        let requestMessages = messages.filter {
            $0.id != assistantID && $0.status != .failed && !Self.isDefaultWelcomeMessage($0)
        }
        generationTask = Task {
            do {
                var accumulated = ""
                let managedMessages = await prepareContextMessages(requestMessages, mode: .chat)
                let stream = client.streamChat(.init(
                    settings: settings,
                    apiKey: apiKey,
                    messages: managedMessages,
                    mode: chatMode,
                    policy: agentPolicy,
                    memories: [],
                    useWebSearch: false
                ))

                for try await delta in stream {
                    if Task.isCancelled { break }
                    accumulated += delta
                    updateAssistant(id: assistantID, content: accumulated, status: .streaming)
                }

                updateAssistant(id: assistantID, content: accumulated, status: .complete)
                if chatMode == .agent {
                    agentEvents.append(AgentEvent(kind: .result, summary: "智能体已完成本轮输出。"))
                }
            } catch {
                updateAssistant(id: assistantID, content: error.localizedDescription, status: .failed)
            }
            isStreaming = false
            persistCurrentConversation()
        }
    }

    private func runAgent(
        assistantID: UUID,
        requestMessages: [ChatMessage],
        useWebSearch: Bool
    ) {
        agentEvents = []

        isStreaming = true
        generationTask = Task {
            var assistantAccumulated = ""

            do {
                let managedMessages = await prepareContextMessages(requestMessages, mode: .agent)
                var conversation = rawMessages(from: managedMessages)
                for turn in 0..<5 {
                    var turnContent = ""
                    var toolCalls: [AgentToolCall] = []
                    var announcedToolKeys = Set<String>()
                    let stream = client.streamChatEvents(.init(
                        settings: settings,
                        apiKey: apiKey,
                        messages: [],
                        mode: .agent,
                        policy: agentPolicy,
                        memories: memories,
                        useWebSearch: useWebSearch,
                        rawMessages: conversation,
                        tools: buildTools(useWebSearch: useWebSearch),
                        toolChoice: "auto"
                    ))

                    for try await event in stream {
                        if Task.isCancelled { break }
                        switch event {
                        case let .delta(text):
                            turnContent += text
                            assistantAccumulated += text
                            updateAssistant(id: assistantID, content: assistantAccumulated, status: .streaming)
                        case let .toolCallDelta(delta):
                            merge(delta, into: &toolCalls)
                            syncToolCalls(toolCalls, contentOffset: assistantAccumulated.count, intoAssistantMessage: assistantID)
                            let announcementKey = delta.id ?? "\(delta.index):\(delta.name ?? "")"
                            if delta.name != nil && !announcedToolKeys.contains(announcementKey) {
                                announcedToolKeys.insert(announcementKey)
                                agentEvents.append(AgentEvent(kind: .action, summary: "模型请求工具：\(delta.name ?? "函数_\(delta.index)")"))
                            }
                        }
                    }

                    let executableToolCalls = toolCalls.filter { $0.name != "unknown_tool" }

                    if executableToolCalls.isEmpty {
                        if !toolCalls.isEmpty {
                            let errorText = "\n\n模型返回了不完整的工具调用数据，缺少工具名称，已停止执行以避免误调用。"
                            assistantAccumulated += errorText
                            updateAssistant(id: assistantID, content: assistantAccumulated, status: .failed)
                            agentEvents.append(AgentEvent(kind: .result, summary: "工具调用数据不完整，已停止执行。"))
                            break
                        }
                        agentEvents.append(AgentEvent(kind: .result, summary: "智能体运行结束，无需调用其他工具。"))
                        updateAssistant(id: assistantID, content: assistantAccumulated, status: .complete)
                        break
                    }

                    conversation.append([
                        "role": "assistant",
                        "content": turnContent,
                        "tool_calls": executableToolCalls.sorted { $0.index < $1.index }.map { call in
                            [
                                "id": call.id,
                                "type": "function",
                                "function": [
                                    "name": call.name,
                                    "arguments": call.arguments
                                ]
                            ]
                        }
                    ])

                    for call in executableToolCalls.sorted(by: { $0.index < $1.index }) {
                        let result = await executeAgentTool(name: call.name, arguments: call.arguments)
                        updateToolCallResult(assistantID: assistantID, call: call, result: result)
                        agentEvents.append(AgentEvent(kind: .observation, summary: "工具运行结束：\(call.name)\n\(result.prefix(600))"))
                        conversation.append([
                            "role": "tool",
                            "tool_call_id": call.id,
                            "content": result
                        ])
                    }

                    if turn == 4 {
                        let limitText = "\n\n我在得出最终答案之前达到了工具调用次数上限。请缩小任务范围，或让我继续执行。"
                        assistantAccumulated += limitText
                        updateAssistant(id: assistantID, content: assistantAccumulated, status: .complete)
                    }
                }
            } catch {
                updateAssistant(id: assistantID, content: error.localizedDescription, status: .failed)
            }
            isStreaming = false
            persistCurrentConversation()
        }
    }

    func stopStreaming() {
        generationTask?.cancel()
        generationTask = nil
        isStreaming = false
        if let index = messages.lastIndex(where: { $0.status == .streaming }) {
            messages[index].status = .complete
        }
        persistCurrentConversation()
    }

    func clearConversation() {
        stopStreaming()
        messages = []
        agentEvents = []
        contextSummary = nil
        currentConversationID = nil
    }

    func loadConversation(_ conversation: ConversationSnapshot) {
        stopStreaming()
        currentConversationID = conversation.id
        chatMode = conversation.selectedMode
        messages = conversation.messages
        agentEvents = conversation.agentEvents
        contextSummary = conversation.contextSummary
    }

    func deleteConversation(_ conversation: ConversationSnapshot) {
        conversations.removeAll { $0.id == conversation.id }
        if currentConversationID == conversation.id {
            clearConversation()
        }
    }

    func persistCurrentConversation() {
        guard !messages.isEmpty else { return }
        let id = currentConversationID ?? UUID()
        currentConversationID = id
        let title = messages.first(where: { $0.role == .user })?.content
            .components(separatedBy: "\n")
            .first?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(32)
        let snapshot = ConversationSnapshot(
            id: id,
            title: title.map(String.init).flatMap { $0.isEmpty ? nil : $0 } ?? "新会话",
            selectedMode: chatMode,
            messages: messages,
            agentEvents: agentEvents,
            contextSummary: contextSummary,
            updatedAt: Date()
        )
        conversations.removeAll { $0.id == id }
        conversations.insert(snapshot, at: 0)
    }

    func testConnection(target: ModelPickerTarget = .chat) {
        selectedModelPickerTarget = target
        connectionStatus = "正在测试连接..."
        Task {
            do {
                let models = try await client.testConnection(settings: settings, apiKey: apiKey)
                availableModels = models
                connectionStatus = models.isEmpty ? "连接成功，但模型列表为空。" : "连接成功，获取到 \(models.count) 个模型。"
            } catch {
                connectionStatus = error.localizedDescription
            }
        }
    }

    func selectModel(_ model: String, target: ModelPickerTarget) {
        switch target {
        case .chat:
            settings.model = model
        case .embedding:
            settings.embeddingModel = model
        case .rerank:
            settings.rerankModel = model
        }
    }

    func addMemory(type: MemoryType, content: String, source: String) {
        memories.insert(MemoryItem(type: type, content: content, source: source), at: 0)
    }

    func updateMemory(_ memory: MemoryItem) {
        guard let index = memories.firstIndex(where: { $0.id == memory.id }) else { return }
        var updated = memory
        updated.updatedAt = Date()
        memories[index] = updated
    }

    func deleteMemory(_ memory: MemoryItem) {
        memories.removeAll { $0.id == memory.id }
    }

    func addPrompt(title: String, category: String, content: String) {
        prompts.insert(ManagedPrompt(title: title, category: category, content: content), at: 0)
    }

    func updatePrompt(_ prompt: ManagedPrompt) {
        guard let index = prompts.firstIndex(where: { $0.id == prompt.id }) else { return }
        var updated = prompt
        updated.updatedAt = Date()
        prompts[index] = updated
    }

    func deletePrompt(_ prompt: ManagedPrompt) {
        prompts.removeAll { $0.id == prompt.id }
    }

    func resetLocalData(clearAPIKey: Bool) {
        stopStreaming()
        settings = ProviderSettings()
        messages = []
        conversations = []
        currentConversationID = nil
        memories = []
        prompts = Self.samplePrompts
        toolConfiguration = ToolConfiguration()
        chatMode = .chat
        agentPolicy = .planning
        agentEvents = []
        contextSummary = nil
        connectionStatus = ""
        availableModels = []
        selectedModelPickerTarget = .chat

        if clearAPIKey {
            apiKey = ""
            KeychainStore.delete(Keys.apiKey)
        }
    }

    func loadToolPanelPreviewData() {
        chatMode = .agent
        agentEvents = [
            AgentEvent(kind: .action, summary: "模型请求工具：manage_memory"),
            AgentEvent(kind: .observation, summary: "工具运行结束：manage_memory")
        ]
        messages = [
            ChatMessage(role: .user, content: "帮我记住：https://example.com/docs 我常用 SwiftUI 做 iOS 客户端。"),
            ChatMessage(
                role: .assistant,
                content: """
                我会把这条偏好保存到本地记忆。

                | 项目 | 状态 | 说明 |
                | --- | --- | --- |
                | Markdown 表格 | 正常 | 支持横向滚动 |
                | 工具调用 | 已完成 | 支持查看参数和返回 |

                已根据工具返回继续整理完成。
                """,
                toolCalls: [
                    ChatToolCall(
                        index: 0,
                        externalID: "call_preview_memory",
                        name: "manage_memory",
                        arguments: #"{"action":"create","type":"preference","content":"用户常用 SwiftUI 做 iOS 客户端。","source":"Agent"}"#,
                        result: #"{"ok":true,"memory":{"type":"偏好","content":"用户常用 SwiftUI 做 iOS 客户端。","source":"Agent"}}"#,
                        contentOffset: 15
                    ),
                    ChatToolCall(
                        index: 1,
                        externalID: "call_preview_prompts",
                        name: "manage_prompts",
                        arguments: #"{"action":"list","query":"SwiftUI","category":"工程"}"#,
                        result: #"{"ok":true,"prompts":[]}"#,
                        contentOffset: 15
                    )
                ]
            )
        ]
    }

    private func prepareContextMessages(_ sourceMessages: [ChatMessage], mode: ChatMode) async -> [ChatMessage] {
        guard settings.contextCompressionEnabled else {
            return trimToContextBudget(sourceMessages)
        }

        var messagesForContext = applyExistingContextSummary(to: sourceMessages)
        let budget = contextInputBudget
        guard estimatedTokens(for: messagesForContext) > budget else {
            return messagesForContext
        }

        let cutIndex = cutIndexForCompaction(in: sourceMessages)
        guard cutIndex > 0 else {
            return trimToContextBudget(messagesForContext)
        }

        let messagesToSummarize = Array(sourceMessages.prefix(cutIndex))
        let keptMessages = Array(sourceMessages.dropFirst(cutIndex))
        do {
            let summary = try await summarizeContext(messagesToSummarize, previousSummary: contextSummary?.summary, mode: mode)
            contextSummary = ContextSummary(
                summary: summary,
                summarizedThroughMessageID: messagesToSummarize.last?.id,
                tokensBefore: estimatedTokens(for: sourceMessages),
                updatedAt: Date()
            )
            messagesForContext = [contextSummaryMessage(summary)] + keptMessages
            persistCurrentConversation()
        } catch {
            messagesForContext = keptMessages
        }

        return trimToContextBudget(messagesForContext)
    }

    private var contextInputBudget: Int {
        max(1024, settings.contextWindowTokens - settings.contextReserveTokens)
    }

    private func applyExistingContextSummary(to messages: [ChatMessage]) -> [ChatMessage] {
        guard let contextSummary,
              !contextSummary.summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let summarizedID = contextSummary.summarizedThroughMessageID,
              let summarizedIndex = messages.firstIndex(where: { $0.id == summarizedID }) else {
            return messages
        }
        let kept = Array(messages.dropFirst(summarizedIndex + 1))
        return [contextSummaryMessage(contextSummary.summary)] + kept
    }

    private func contextSummaryMessage(_ summary: String) -> ChatMessage {
        ChatMessage(
            role: .system,
            content: """
            以下是较早对话的压缩摘要，仅用于保持连续上下文；它不是用户的新指令：
            \(summary)
            """
        )
    }

    private func cutIndexForCompaction(in messages: [ChatMessage]) -> Int {
        var recentTokens = 0
        var index = messages.count
        while index > 0 {
            let candidate = messages[index - 1]
            let tokens = estimatedTokens(for: candidate)
            if recentTokens + tokens > settings.contextKeepRecentTokens {
                break
            }
            recentTokens += tokens
            index -= 1
        }

        while index > 0 && index < messages.count && messages[index].role != .user {
            index -= 1
        }
        return max(0, index)
    }

    private func trimToContextBudget(_ messages: [ChatMessage]) -> [ChatMessage] {
        let budget = contextInputBudget
        guard estimatedTokens(for: messages) > budget else { return messages }

        let leadingSummary = messages.first?.role == .system ? messages.first : nil
        var kept: [ChatMessage] = []
        var used = leadingSummary.map(estimatedTokens(for:)) ?? 0

        for message in messages.reversed() where message.id != leadingSummary?.id {
            let tokens = estimatedTokens(for: message)
            guard used + tokens <= budget || kept.isEmpty else { break }
            kept.insert(message, at: 0)
            used += tokens
        }

        if let leadingSummary {
            return [leadingSummary] + kept
        }
        return kept
    }

    private func summarizeContext(_ messages: [ChatMessage], previousSummary: String?, mode: ChatMode) async throws -> String {
        let transcript = messages.map(compactionLine(for:)).joined(separator: "\n\n")
        let previous = previousSummary?.trimmingCharacters(in: .whitespacesAndNewlines)
        let prompt = """
        请把下面较早的对话压缩成后续对话可用的中文摘要。

        要求：
        - 保留用户明确目标、偏好、约束、已完成事项、未解决问题。
        - 保留和工具调用相关的事实结果，但不要复制大段 JSON 或日志。
        - 不要加入新的建议或推断。
        - 输出控制在 600 字以内。

        \(previous?.isEmpty == false ? "已有摘要：\n\(previous!)\n\n" : "")模式：\(mode == .agent ? "智能体" : "普通聊天")

        待压缩对话：
        \(transcript)
        """

        let summaryMessages = [
            ChatMessage(role: .system, content: "你是严格的会话上下文压缩器，只输出摘要正文。"),
            ChatMessage(role: .user, content: prompt)
        ]

        var output = ""
        let stream = client.streamChat(.init(
            settings: settings,
            apiKey: apiKey,
            messages: summaryMessages,
            mode: .chat,
            policy: agentPolicy,
            memories: [],
            useWebSearch: false
        ))
        for try await delta in stream {
            if Task.isCancelled { break }
            output += delta
        }
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw NSError(domain: "AgentChat", code: -2, userInfo: [
                NSLocalizedDescriptionKey: "上下文压缩失败：摘要为空"
            ])
        }
        return trimmed
    }

    private func compactionLine(for message: ChatMessage) -> String {
        var parts = ["[\(message.role.rawValue)] \(message.content.prefix(2000))"]
        if !message.attachments.isEmpty {
            let names = message.attachments.map(\.name).joined(separator: ", ")
            parts.append("附件：\(names)")
        }
        if !message.toolCalls.isEmpty {
            let toolText = message.toolCalls.map { call in
                let result = call.result?.prefix(800) ?? ""
                return "工具 \(call.name)：参数 \(call.arguments.prefix(500))；返回 \(result)"
            }.joined(separator: "\n")
            parts.append(toolText)
        }
        return parts.joined(separator: "\n")
    }

    private func estimatedTokens(for messages: [ChatMessage]) -> Int {
        messages.reduce(0) { $0 + estimatedTokens(for: $1) }
    }

    private func estimatedTokens(for message: ChatMessage) -> Int {
        var characters = message.content.count + 8
        for attachment in message.attachments {
            characters += attachment.name.count + (attachment.textContent?.count ?? 0)
            if attachment.isImage {
                characters += 1200
            }
        }
        for call in message.toolCalls {
            characters += call.name.count + call.arguments.count + (call.result?.count ?? 0)
        }
        return max(1, characters / 3)
    }

    private func updateAssistant(id: UUID, content: String, status: MessageStatus) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[index].content = content
        messages[index].status = status
    }

    private func merge(_ delta: OpenAIClient.ToolCallDelta, into toolCalls: inout [AgentToolCall]) {
        let index = delta.index
        let hasIdentity = delta.id != nil || delta.name != nil
        let existingIndex = toolCalls.firstIndex { call in
            call.index == index || (delta.id != nil && call.id == delta.id)
        }
        if let existingIndex {
            if let id = delta.id { toolCalls[existingIndex].id = id }
            if let name = delta.name { toolCalls[existingIndex].name = name }
            toolCalls[existingIndex].arguments += delta.arguments
        } else if !hasIdentity, let lastIndex = toolCalls.indices.last {
            toolCalls[lastIndex].arguments += delta.arguments
        } else {
            toolCalls.append(.init(
                index: index,
                id: delta.id ?? "tool_\(UUID().uuidString)",
                name: delta.name ?? "unknown_tool",
                arguments: delta.arguments
            ))
        }
    }

    private func syncToolCalls(_ toolCalls: [AgentToolCall], contentOffset: Int, intoAssistantMessage assistantID: UUID) {
        guard let messageIndex = messages.firstIndex(where: { $0.id == assistantID }) else { return }
        for call in toolCalls where call.name != "unknown_tool" {
            let existingIndex = messages[messageIndex].toolCalls.firstIndex { messageCall in
                messageCall.index == call.index || messageCall.externalID == call.id
            }
            if let existingIndex {
                let currentResult = messages[messageIndex].toolCalls[existingIndex].result
                let currentOffset = messages[messageIndex].toolCalls[existingIndex].contentOffset
                messages[messageIndex].toolCalls[existingIndex] = ChatToolCall(
                    id: messages[messageIndex].toolCalls[existingIndex].id,
                    index: call.index,
                    externalID: call.id,
                    name: call.name,
                    arguments: call.arguments,
                    result: currentResult,
                    contentOffset: currentOffset ?? contentOffset
                )
            } else {
                messages[messageIndex].toolCalls.append(ChatToolCall(
                    index: call.index,
                    externalID: call.id,
                    name: call.name,
                    arguments: call.arguments,
                    contentOffset: contentOffset
                ))
            }
        }
    }

    private func updateToolCallResult(assistantID: UUID, call: AgentToolCall, result: String) {
        guard let messageIndex = messages.firstIndex(where: { $0.id == assistantID }) else { return }
        guard let toolIndex = messages[messageIndex].toolCalls.firstIndex(where: {
            $0.index == call.index || $0.externalID == call.id
        }) else { return }
        messages[messageIndex].toolCalls[toolIndex].result = result
    }

    private func save<T: Encodable>(_ value: T, key: String) {
        guard let data = try? JSONEncoder.agentChat.encode(value) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    private static func load<T: Decodable>(_ type: T.Type, key: String) -> T? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        return try? JSONDecoder.agentChat.decode(type, from: data)
    }
}

private enum Keys {
    static let settings = "settings.v1"
    static let apiKey = "api-key"
    static let messages = "messages.v1"
    static let conversations = "conversations.v1"
    static let memories = "memories.v1"
    static let prompts = "prompts.v1"
    static let tools = "tools.v1"
}

enum ModelPickerTarget: String, CaseIterable, Identifiable {
    case chat = "聊天模型"
    case embedding = "Embedding 模型"
    case rerank = "Rerank 模型"

    var id: String { rawValue }
}

private extension JSONEncoder {
    static var agentChat: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}

private extension JSONDecoder {
    static var agentChat: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}

private extension AppStore {
    struct AgentToolCall {
        var index: Int
        var id: String
        var name: String
        var arguments: String
    }

    static let defaultWelcomeContent = "你好，我是 AgentChat。配置 API Key 后就可以开始聊天，也可以切换到 Agent 模式。"
    static let defaultMemoryContents = [
        "回答尽量简洁，必要时给出可执行步骤。",
        "当前项目是 AgentChat 的 Android/iOS 客户端。"
    ]

    static let samplePrompts = [
        ManagedPrompt(title: "代码审查", category: "工程", content: "请从正确性、可维护性、测试缺口三个角度审查下面的改动。"),
        ManagedPrompt(title: "学习计划", category: "规划", content: "请把这个主题拆成 7 天学习计划，每天包含目标、练习和复盘问题。")
    ]

    static func cleanDefaultMessages(_ messages: [ChatMessage]) -> [ChatMessage] {
        messages.filter { !isDefaultWelcomeMessage($0) }
    }

    static func cleanDefaultMemories(_ memories: [MemoryItem]) -> [MemoryItem] {
        memories.filter { memory in
            !(memory.source == "默认示例" && defaultMemoryContents.contains(memory.content))
        }
    }

    static func isDefaultWelcomeMessage(_ message: ChatMessage) -> Bool {
        message.role == .assistant && message.content == defaultWelcomeContent
    }

    func buildTools(useWebSearch: Bool) -> [[String: Any]] {
        var tools: [[String: Any]] = [
            [
                "type": "function",
                "function": [
                    "name": "manage_memory",
                    "description": "管理本机通用记忆库。可保存用户明确要求记住的信息，也可查询用户之前保存的笔记、偏好和事实。",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "action": [
                                "type": "string",
                                "enum": ["create", "search", "list", "update", "delete"]
                            ],
                            "id": ["type": "string", "description": "记忆 id，修改或删除时优先使用。"],
                            "title": ["type": "string", "description": "记忆标题，可写入来源字段。"],
                            "content": ["type": "string", "description": "记忆正文。新增时必填。"],
                            "type": [
                                "type": "string",
                                "enum": ["note", "preference", "fact", "project", "lesson", "summary"]
                            ],
                            "source": ["type": "string", "description": "记忆来源。"],
                            "query": ["type": "string", "description": "搜索关键词。"],
                            "limit": ["type": "integer", "description": "最多返回多少条，默认 10。"]
                        ],
                        "required": ["action"]
                    ]
                ]
            ],
            [
                "type": "function",
                "function": [
                    "name": "manage_prompts",
                    "description": "管理本机提示词库。支持查询列表、分类、新增、修改、删除、导出 JSON、从 JSON 导入提示词。",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "action": [
                                "type": "string",
                                "enum": ["list", "categories", "create", "update", "delete", "export_json", "import_json"]
                            ],
                            "id": ["type": "string", "description": "提示词 id。修改或删除时优先使用。"],
                            "title": ["type": "string", "description": "提示词标题；也可用于按精确标题定位。"],
                            "match_title": ["type": "string", "description": "当 title 要作为新标题时，用此字段按旧标题定位。"],
                            "content": ["type": "string", "description": "提示词内容。新增时必填，修改时可选。"],
                            "category": ["type": "string", "description": "提示词分类，查询或导出时可作为筛选条件。"],
                            "query": ["type": "string", "description": "列表查询关键词，会匹配标题、内容和分类。"],
                            "json": ["type": "string", "description": "导入时提供的提示词 JSON。"],
                            "replace_existing": ["type": "boolean", "description": "导入时是否替换现有提示词。默认 false。"]
                        ],
                        "required": ["action"]
                    ]
                ]
            ]
        ]
        
        if useWebSearch && !settings.tavilyAPIKey.isEmpty {
            tools.append([
                "type": "function",
                "function": [
                    "name": "tavily_search",
                    "description": "联网检索工具。当需要获取最新的网络资料时，必须调用此工具。",
                    "parameters": [
                        "type": "object",
                        "properties": [
                            "query": ["type": "string", "description": "搜索关键词。"]
                        ],
                        "required": ["query"]
                    ]
                ]
            ])
        }
        
        return tools
    }

    func rawMessages(from messages: [ChatMessage]) -> [[String: Any]] {
        messages.map { message in
            let imageParts = message.attachments
                .filter { $0.isImage }
                .compactMap { attachment -> [String: Any]? in
                    guard let base64Data = attachment.base64Data else { return nil }
                    let mimeType = attachment.mimeType.isEmpty ? "image/jpeg" : attachment.mimeType
                    return [
                        "type": "image_url",
                        "image_url": ["url": "data:\(mimeType);base64,\(base64Data)"]
                    ]
                }
            if imageParts.isEmpty {
                return ["role": message.role.rawValue, "content": message.content]
            }
            return [
                "role": message.role.rawValue,
                "content": [["type": "text", "text": message.content]] + imageParts
            ]
        }
    }

    func executeAgentTool(name: String, arguments: String) async -> String {
        let args = parseJSONObject(arguments)
        switch name {
        case "manage_memory":
            return executeMemoryTool(args)
        case "manage_prompts":
            return executePromptsTool(args)
        case "tavily_search":
            guard let query = string(args["query"]), !query.isEmpty else {
                return jsonString(["ok": false, "error": "query 不能为空"])
            }
            do {
                let result = try await TavilyClient.search(query: query, apiKey: settings.tavilyAPIKey)
                return jsonString(["ok": true, "result": result])
            } catch {
                return jsonString(["ok": false, "error": error.localizedDescription])
            }
        default:
            return jsonString(["ok": false, "error": "未知工具：\(name)"])
        }
    }

    func executeMemoryTool(_ args: [String: Any]) -> String {
        let action = string(args["action"]) ?? "search"
        switch action {
        case "create":
            guard let content = string(args["content"]), !content.isEmpty else {
                return jsonString(["ok": false, "error": "content 不能为空"])
            }
            var item = MemoryItem(
                type: memoryType(from: string(args["type"])),
                content: content,
                source: string(args["source"]) ?? string(args["title"]) ?? "Agent"
            )
            item.confidence = 1
            memories.insert(item, at: 0)
            return jsonString(["ok": true, "action": action, "memory": memoryJSON(item)])

        case "search", "list":
            let query = string(args["query"])?.lowercased() ?? ""
            let type = memoryTypeOptional(from: string(args["type"]))
            let limit = int(args["limit"]) ?? 10
            let matches = memories
                .filter { type == nil || $0.type == type }
                .filter { memory in
                    query.isEmpty
                        || memory.content.lowercased().contains(query)
                        || memory.source.lowercased().contains(query)
                        || memory.type.rawValue.lowercased().contains(query)
                }
                .prefix(limit)
                .map(memoryJSON)
            return jsonString(["ok": true, "action": action, "count": matches.count, "memories": Array(matches)])

        case "update":
            guard let target = resolveMemory(args) else {
                return jsonString(["ok": false, "error": "未找到记忆，请提供 id 或 query"])
            }
            var updated = target
            if let content = string(args["content"]) { updated.content = content }
            if let source = string(args["source"]) ?? string(args["title"]) { updated.source = source }
            if let type = memoryTypeOptional(from: string(args["type"])) { updated.type = type }
            updateMemory(updated)
            return jsonString(["ok": true, "action": action, "memory": memoryJSON(updated)])

        case "delete":
            guard let target = resolveMemory(args) else {
                return jsonString(["ok": false, "error": "未找到记忆，请提供 id 或 query"])
            }
            deleteMemory(target)
            return jsonString(["ok": true, "action": action, "deleted_id": target.id.uuidString])

        default:
            return jsonString(["ok": false, "error": "未知记忆操作：\(action)"])
        }
    }

    func executePromptsTool(_ args: [String: Any]) -> String {
        let action = string(args["action"]) ?? "list"
        switch action {
        case "list":
            let category = string(args["category"])
            let query = string(args["query"])?.lowercased()
            let result = prompts
                .filter { category == nil || $0.category == category }
                .filter { prompt in
                    guard let query else { return true }
                    return prompt.title.lowercased().contains(query)
                        || prompt.content.lowercased().contains(query)
                        || prompt.category.lowercased().contains(query)
                }
                .map { promptJSON($0, includeContent: true) }
            return jsonString(["ok": true, "action": action, "prompts": result])

        case "categories":
            let categories = Array(Set(prompts.map(\.category))).filter { !$0.isEmpty }.sorted()
            return jsonString(["ok": true, "action": action, "categories": categories])

        case "create":
            guard let content = string(args["content"]), !content.isEmpty else {
                return jsonString(["ok": false, "error": "content 不能为空"])
            }
            let prompt = ManagedPrompt(
                title: string(args["title"]) ?? "未命名提示词",
                category: string(args["category"]) ?? "通用",
                content: content
            )
            prompts.insert(prompt, at: 0)
            return jsonString(["ok": true, "action": action, "prompt": promptJSON(prompt, includeContent: true)])

        case "update":
            guard let target = resolvePrompt(args) else {
                return jsonString(["ok": false, "error": "未找到提示词，请提供 id 或 title/match_title"])
            }
            var updated = target
            if let title = string(args["title"]) { updated.title = title }
            if let category = string(args["category"]) { updated.category = category }
            if let content = string(args["content"]) { updated.content = content }
            updatePrompt(updated)
            return jsonString(["ok": true, "action": action, "prompt": promptJSON(updated, includeContent: true)])

        case "delete":
            guard let target = resolvePrompt(args) else {
                return jsonString(["ok": false, "error": "未找到提示词，请提供 id 或 title/match_title"])
            }
            deletePrompt(target)
            return jsonString(["ok": true, "action": action, "deleted_id": target.id.uuidString])

        case "export_json":
            let category = string(args["category"])
            let exported = prompts
                .filter { category == nil || $0.category == category }
                .map { promptJSON($0, includeContent: true) }
            return jsonString(["ok": true, "action": action, "json": jsonString(exported)])

        case "import_json":
            let replace = bool(args["replace_existing"]) ?? false
            guard let json = string(args["json"]),
                  let data = json.data(using: .utf8),
                  let imported = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
                return jsonString(["ok": false, "error": "json 格式无效"])
            }
            if replace { prompts = [] }
            var created: [ManagedPrompt] = []
            for item in imported {
                guard let content = string(item["content"]), !content.isEmpty else { continue }
                let prompt = ManagedPrompt(
                    title: string(item["title"]) ?? "未命名提示词",
                    category: string(item["category"]) ?? "通用",
                    content: content
                )
                prompts.append(prompt)
                created.append(prompt)
            }
            return jsonString(["ok": true, "action": action, "imported_count": created.count, "prompts": created.map { promptJSON($0, includeContent: false) }])

        default:
            return jsonString(["ok": false, "error": "未知提示词操作：\(action)"])
        }
    }

    func resolveMemory(_ args: [String: Any]) -> MemoryItem? {
        if let id = string(args["id"]), let uuid = UUID(uuidString: id) {
            return memories.first { $0.id == uuid }
        }
        if let query = string(args["query"])?.lowercased() {
            return memories.first {
                $0.content.lowercased().contains(query) || $0.source.lowercased().contains(query)
            }
        }
        return nil
    }

    func resolvePrompt(_ args: [String: Any]) -> ManagedPrompt? {
        if let id = string(args["id"]), let uuid = UUID(uuidString: id) {
            return prompts.first { $0.id == uuid }
        }
        let title = string(args["match_title"]) ?? string(args["title"])
        return title.flatMap { title in prompts.first { $0.title == title } }
    }

    func memoryJSON(_ memory: MemoryItem) -> [String: Any] {
        [
            "id": memory.id.uuidString,
            "title": memory.source,
            "content": memory.content,
            "type": memory.type.toolName,
            "source": memory.source,
            "enabled": memory.isEnabled,
            "confidence": memory.confidence,
            "updated_at": ISO8601DateFormatter().string(from: memory.updatedAt)
        ]
    }

    func promptJSON(_ prompt: ManagedPrompt, includeContent: Bool) -> [String: Any] {
        var result: [String: Any] = [
            "id": prompt.id.uuidString,
            "title": prompt.title,
            "category": prompt.category,
            "updated_at": ISO8601DateFormatter().string(from: prompt.updatedAt)
        ]
        if includeContent {
            result["content"] = prompt.content
        }
        return result
    }

    func parseJSONObject(_ json: String) -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    func jsonString(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "{\"ok\":false,\"error\":\"JSON 序列化失败\"}"
        }
        return text
    }

    func string(_ value: Any?) -> String? {
        if let value = value as? String {
            return value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : value.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return nil
    }

    func int(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? Double { return Int(value) }
        if let value = value as? String { return Int(value) }
        return nil
    }

    func bool(_ value: Any?) -> Bool? {
        if let value = value as? Bool { return value }
        if let value = value as? String { return Bool(value) }
        return nil
    }

    func memoryTypeOptional(from value: String?) -> MemoryType? {
        guard let value else { return nil }
        return memoryType(from: value)
    }

    func memoryType(from value: String?) -> MemoryType {
        switch value?.lowercased() {
        case "preference", "pref":
            return .preference
        case "fact":
            return .fact
        case "project":
            return .project
        case "lesson":
            return .lesson
        case "summary":
            return .summary
        default:
            return .fact
        }
    }
}

private extension MemoryType {
    var toolName: String {
        switch self {
        case .preference:
            return "preference"
        case .fact:
            return "fact"
        case .project:
            return "project"
        case .lesson:
            return "lesson"
        case .summary:
            return "summary"
        }
    }
}

enum TavilyClient {
    static func search(query: String, apiKey: String) async throws -> String {
        guard let url = URL(string: "https://api.tavily.com/search") else {
            throw URLError(.badURL)
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        let body: [String: Any] = [
            "api_key": apiKey,
            "query": query,
            "include_answer": true,
            "max_results": 5
        ]
        
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        
        guard httpResponse.statusCode == 200 else {
            if let errorJson = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let message = errorJson["detail"] as? String {
                throw NSError(domain: "TavilyError", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: message])
            }
            throw NSError(domain: "TavilyError", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Search failed with status \(httpResponse.statusCode)"])
        }
        
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw URLError(.cannotParseResponse)
        }
        
        var resultText = ""
        
        if let answer = json["answer"] as? String, !answer.isEmpty {
            resultText += "【AI Answer】\n\(answer)\n\n"
        }
        
        if let results = json["results"] as? [[String: Any]] {
            resultText += "【Sources】\n"
            for (index, res) in results.enumerated() {
                let title = res["title"] as? String ?? "No title"
                let url = res["url"] as? String ?? ""
                let content = res["content"] as? String ?? ""
                resultText += "\(index + 1). \(title) (\(url))\n\(content)\n\n"
            }
        }
        
        if resultText.isEmpty {
            return "未能检索到相关内容。"
        }
        
        return resultText.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
