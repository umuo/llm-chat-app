import Foundation

struct OpenAIClient {
    enum StreamEvent {
        case delta(String)
        case toolCallDelta(ToolCallDelta)
    }

    struct ToolCallDelta {
        var index: Int
        var id: String?
        var name: String?
        var arguments: String
    }

    struct StreamRequest {
        var settings: ProviderSettings
        var apiKey: String
        var messages: [ChatMessage]
        var mode: ChatMode
        var policy: AgentPolicyType
        var memories: [MemoryItem]
        var useWebSearch: Bool
        var rawMessages: [[String: Any]]? = nil
        var tools: [[String: Any]] = []
        var toolChoice: String? = nil
    }

    func streamChat(_ request: StreamRequest) -> AsyncThrowingStream<String, Error> {
        let eventStream = streamChatEvents(request)
        return AsyncThrowingStream { continuation in
            Task {
                do {
                    for try await event in eventStream {
                        if case let .delta(text) = event {
                            continuation.yield(text)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    func streamChatEvents(_ request: StreamRequest) -> AsyncThrowingStream<StreamEvent, Error> {
        AsyncThrowingStream { continuation in
            Task {
                do {
                    let url = try endpoint(baseURL: request.settings.baseURL, path: "chat/completions")
                    var urlRequest = URLRequest(url: url)
                    urlRequest.httpMethod = "POST"
                    urlRequest.timeoutInterval = TimeInterval(request.settings.timeoutSeconds)
                    urlRequest.setValue("Bearer \(request.apiKey)", forHTTPHeaderField: "Authorization")
                    urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    urlRequest.httpBody = try makeBody(request)

                    let (bytes, response) = try await URLSession.shared.bytes(for: urlRequest)
                    guard let httpResponse = response as? HTTPURLResponse,
                          (200...299).contains(httpResponse.statusCode) else {
                        let status = (response as? HTTPURLResponse)?.statusCode ?? -1
                        throw NSError(domain: "AgentChat", code: status, userInfo: [
                            NSLocalizedDescriptionKey: "HTTP \(status): 模型接口请求失败"
                        ])
                    }

                    for try await line in bytes.lines {
                        if Task.isCancelled { break }
                        guard line.hasPrefix("data:") else { continue }
                        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespacesAndNewlines)
                        if payload == "[DONE]" { break }
                        for event in parseEvents(payload) {
                            continuation.yield(event)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }

    func testConnection(settings: ProviderSettings, apiKey: String) async throws -> [String] {
        let url = try endpoint(baseURL: settings.baseURL, path: "models")
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = TimeInterval(settings.timeoutSeconds)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            let detail = String(data: data, encoding: .utf8)?.prefix(800) ?? ""
            throw NSError(domain: "AgentChat", code: status, userInfo: [
                NSLocalizedDescriptionKey: "HTTP \(status): \(detail)"
            ])
        }

        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let dataArray = object?["data"] as? [[String: Any]] ?? []
        let models = dataArray.compactMap { $0["id"] as? String }
        return models.sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    private func endpoint(baseURL: String, path: String) throws -> URL {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(trimmed)/\(path)") else {
            throw NSError(domain: "AgentChat", code: -1, userInfo: [
                NSLocalizedDescriptionKey: "Base URL 无效"
            ])
        }
        return url
    }

    private func makeBody(_ request: StreamRequest) throws -> Data {
        var messages = request.rawMessages ?? request.messages.map { message -> [String: Any] in
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

            guard !imageParts.isEmpty else {
                return ["role": message.role.rawValue, "content": message.content]
            }

            let content: [[String: Any]] = [["type": "text", "text": message.content]] + imageParts
            return ["role": message.role.rawValue, "content": content]
        }

        if request.mode == .agent {
            messages.insert([
                "role": "system",
                "content": "你是 AgentChat 智能体。你可以看到一组本机工具，并应尽量用现有工具完成适合工具处理的任务：保存、查询、更新、删除记忆，管理提示词，或执行工具中心提供的能力。用户明确要求记住、保存、查询历史偏好、管理提示词时，优先调用相应工具；如果问题只需要普通语言回答，则直接回答。不要暴露隐藏推理；只给出简洁的可见摘要和最终结果。"
            ], at: 0)
        }

        let hasSearchTool = request.tools.contains { tool in
            if let function = tool["function"] as? [String: Any],
               let name = function["name"] as? String {
                return name == "tavily_search" || name == "search_web" || name == "search"
            }
            return false
        }

        if request.mode == .agent && request.useWebSearch && hasSearchTool {
            messages.insert([
                "role": "system",
                "content": "用户开启了联网搜索。你必须首先调用网络搜索工具（如 tavily_search）来获取最新信息，然后再结合资料回答问题。"
            ], at: min(1, messages.count))
        }

        var body: [String: Any] = [
            "model": request.settings.model,
            "messages": messages,
            "stream": true,
            "temperature": request.settings.temperature,
            "top_p": request.settings.topP,
            "top_k": request.settings.topK,
            "max_tokens": request.settings.maxTokens
        ]
        if !request.tools.isEmpty {
            body["tools"] = request.tools
        }
        if let toolChoice = request.toolChoice {
            body["tool_choice"] = toolChoice
        }
        return try JSONSerialization.data(withJSONObject: body)
    }

    private func parseEvents(_ payload: String) -> [StreamEvent] {
        guard let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = object["choices"] as? [[String: Any]],
              let first = choices.first,
              let delta = first["delta"] as? [String: Any] else {
            return []
        }
        var events: [StreamEvent] = []
        if let content = delta["content"] as? String, !content.isEmpty {
            events.append(.delta(content))
        }
        if let toolCalls = delta["tool_calls"] as? [[String: Any]] {
            for (fallbackIndex, toolCall) in toolCalls.enumerated() {
                let function = toolCall["function"] as? [String: Any]
                let id = toolCall["id"] as? String
                let name = function?["name"] as? String
                let arguments = function?["arguments"] as? String ?? ""
                guard id != nil || name != nil || !arguments.isEmpty else { continue }
                events.append(.toolCallDelta(.init(
                    index: toolCall["index"] as? Int ?? fallbackIndex,
                    id: id,
                    name: name,
                    arguments: arguments
                )))
            }
        }
        return events
    }
}
