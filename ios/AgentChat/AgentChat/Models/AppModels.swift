import Foundation

enum ChatMode: String, Codable, CaseIterable, Identifiable {
    case chat = "Chat"
    case agent = "Agent"

    var id: String { rawValue }
}

enum AgentPolicyType: String, Codable, CaseIterable, Identifiable {
    case react = "ReAct"
    case planning = "Planning"

    var id: String { rawValue }
}

enum MessageRole: String, Codable {
    case user
    case assistant
    case system
    case tool
}

enum MessageStatus: String, Codable {
    case streaming
    case complete
    case failed
}

enum RetrievalMode: String, Codable, CaseIterable, Identifiable {
    case keyword = "关键字检索"
    case vector = "向量检索"
    case hybrid = "混合检索"

    var id: String { rawValue }
}

struct ChatAttachment: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var name: String
    var mimeType: String
    var isImage: Bool
    var base64Data: String?
    var textContent: String?
}

struct ChatMessage: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var role: MessageRole
    var content: String
    var status: MessageStatus = .complete
    var createdAt: Date = Date()
    var attachments: [ChatAttachment] = []
    var toolCalls: [ChatToolCall] = []
}

struct ChatToolCall: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var index: Int = 0
    var externalID: String?
    var name: String
    var arguments: String
    var result: String?
    var contentOffset: Int? = nil
}

struct ProviderSettings: Codable, Equatable {
    var profileName: String = "OpenAI Compatible"
    var baseURL: String = "https://newapi.lacknb.edu.kg/v1"
    var model: String = "gpt-5.4-mini"
    var embeddingModel: String = ""
    var rerankModel: String = ""
    var retrievalMode: RetrievalMode = .keyword
    var temperature: Double = 0.7
    var topP: Double = 0.95
    var topK: Int = 40
    var maxTokens: Int = 32768
    var timeoutSeconds: Int = 60
    var tavilyAPIKey: String = ""
    var mcpServerURL: String = ""
    var contextCompressionEnabled: Bool = true
    var contextWindowTokens: Int = 131072
    var contextReserveTokens: Int = 4096
    var contextKeepRecentTokens: Int = 32768

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        profileName = try container.decodeIfPresent(String.self, forKey: .profileName) ?? "OpenAI Compatible"
        baseURL = try container.decodeIfPresent(String.self, forKey: .baseURL) ?? "https://newapi.lacknb.edu.kg/v1"
        model = try container.decodeIfPresent(String.self, forKey: .model) ?? "gpt-5.4-mini"
        embeddingModel = try container.decodeIfPresent(String.self, forKey: .embeddingModel) ?? ""
        rerankModel = try container.decodeIfPresent(String.self, forKey: .rerankModel) ?? ""
        retrievalMode = try container.decodeIfPresent(RetrievalMode.self, forKey: .retrievalMode) ?? .keyword
        temperature = try container.decodeIfPresent(Double.self, forKey: .temperature) ?? 0.7
        topP = try container.decodeIfPresent(Double.self, forKey: .topP) ?? 0.95
        topK = try container.decodeIfPresent(Int.self, forKey: .topK) ?? 40
        maxTokens = try container.decodeIfPresent(Int.self, forKey: .maxTokens) ?? 32768
        timeoutSeconds = try container.decodeIfPresent(Int.self, forKey: .timeoutSeconds) ?? 60
        tavilyAPIKey = try container.decodeIfPresent(String.self, forKey: .tavilyAPIKey) ?? ""
        mcpServerURL = try container.decodeIfPresent(String.self, forKey: .mcpServerURL) ?? ""
        contextCompressionEnabled = try container.decodeIfPresent(Bool.self, forKey: .contextCompressionEnabled) ?? true
        contextWindowTokens = try container.decodeIfPresent(Int.self, forKey: .contextWindowTokens) ?? 131072
        contextReserveTokens = try container.decodeIfPresent(Int.self, forKey: .contextReserveTokens) ?? 4096
        contextKeepRecentTokens = try container.decodeIfPresent(Int.self, forKey: .contextKeepRecentTokens) ?? 32768
    }
}

struct ContextSummary: Codable, Equatable {
    var summary: String
    var summarizedThroughMessageID: UUID?
    var tokensBefore: Int
    var updatedAt: Date = Date()
}

struct ConversationSummary: Identifiable, Codable, Equatable {
    var id: UUID
    var title: String
    var selectedMode: ChatMode
    var messageCount: Int
    var updatedAt: Date
}

struct ConversationSnapshot: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var title: String
    var selectedMode: ChatMode
    var messages: [ChatMessage]
    var agentEvents: [AgentEvent]
    var contextSummary: ContextSummary?
    var updatedAt: Date = Date()

    var summary: ConversationSummary {
        ConversationSummary(
            id: id,
            title: title,
            selectedMode: selectedMode,
            messageCount: messages.count,
            updatedAt: updatedAt
        )
    }
}

enum MemoryType: String, Codable, CaseIterable, Identifiable {
    case preference = "偏好"
    case fact = "事实"
    case project = "项目"
    case lesson = "经验"
    case summary = "摘要"

    var id: String { rawValue }
}

struct MemoryItem: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var type: MemoryType
    var content: String
    var source: String
    var isEnabled: Bool = true
    var confidence: Double = 0.82
    var updatedAt: Date = Date()
}

struct ManagedPrompt: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var title: String
    var category: String
    var content: String
    var updatedAt: Date = Date()
}

enum AgentEventKind: String, Codable {
    case plan = "计划"
    case action = "行动"
    case observation = "观察"
    case result = "结果"
}

struct AgentEvent: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var kind: AgentEventKind
    var summary: String
    var createdAt: Date = Date()
}

struct ToolConfiguration: Codable, Equatable {
    var webSearchEnabled: Bool = false
    var fileWorkspaceEnabled: Bool = false
    var memoryWriteReviewEnabled: Bool = true
}
