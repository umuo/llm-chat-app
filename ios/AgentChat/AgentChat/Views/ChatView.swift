import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct ChatView: View {
    @EnvironmentObject private var store: AppStore
    @State private var draft = ""
    @State private var useWebSearch = false
    @State private var attachments: [ChatAttachment] = []
    @State private var activeSheet: ActiveSheet?
    @State private var photoItem: PhotosPickerItem?
    @State private var showFileImporter = false
    @FocusState private var inputFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                modeBar
                messageList
                composer
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("AgentChat")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        activeSheet = .menu
                    } label: {
                        Image(systemName: "line.3.horizontal")
                    }
                    .accessibilityLabel("打开菜单")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 14) {
                        Button {
                            activeSheet = .history
                        } label: {
                            Image(systemName: "clock.arrow.circlepath")
                        }
                        .accessibilityLabel("会话历史")

                        Button {
                            store.clearConversation()
                        } label: {
                            Image(systemName: "plus.bubble")
                        }
                        .accessibilityLabel("新建会话")
                    }
                }
            }
            .sheet(item: $activeSheet) { sheet in
                switch sheet {
                case .menu:
                    NavigationMenuSheet(activeSheet: $activeSheet)
                        .presentationDetents([.medium, .large])
                case .history:
                    ConversationHistorySheet()
                case .params:
                    ParameterSheet()
                        .presentationDetents([.medium])
                case .settings:
                    SettingsView()
                case .memory:
                    MemoryView()
                case .prompts:
                    PromptsView()
                case .tools:
                    ToolCenterView()
                }
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.plainText, .text, .json, .pdf, .data],
                allowsMultipleSelection: true,
                onCompletion: importFiles
            )
            .onChange(of: photoItem) { _, item in
                guard let item else { return }
                Task { await importPhoto(item) }
            }
            .onChange(of: store.chatMode) { _, mode in
                if mode == .chat {
                    useWebSearch = false
                }
            }
        }
    }

    private var modeBar: some View {
        VStack(spacing: 10) {
            Picker("模式", selection: $store.chatMode) {
                Text("聊天").tag(ChatMode.chat)
                Text("智能体").tag(ChatMode.agent)
            }
            .pickerStyle(.segmented)

            HStack(spacing: 10) {
                statusChip(icon: "cpu", text: store.settings.model)
                if store.chatMode == .agent {
                    statusChip(icon: "doc.text.magnifyingglass", text: store.settings.retrievalMode.rawValue)
                }
                if store.chatMode == .agent && useWebSearch {
                    statusChip(icon: "globe.asia.australia.fill", text: "联网搜索")
                }
            }

            if store.chatMode == .agent {
                AgentTimeline(events: store.agentEvents)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.regularMaterial)
    }

    private func statusChip(icon: String, text: String) -> some View {
        Label(text, systemImage: icon)
            .font(.caption.weight(.medium))
            .lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color(.secondarySystemGroupedBackground), in: Capsule())
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    if store.messages.isEmpty {
                        EmptyConversationView(mode: store.chatMode)
                    }
                    ForEach(store.messages) { message in
                        MessageBubble(message: message)
                            .id(message.id)
                    }
                }
                .padding(16)
            }
            .onChange(of: store.messages) { _, _ in
                guard let last = store.messages.last else { return }
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }

    private var composer: some View {
        VStack(spacing: 10) {
            if !attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(attachments) { attachment in
                            AttachmentPill(attachment: attachment) {
                                attachments.removeAll { $0.id == attachment.id }
                            }
                        }
                    }
                    .padding(.horizontal, 2)
                }
            }

            HStack(spacing: 8) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Image(systemName: "photo")
                        .frame(width: 34, height: 34)
                }
                .accessibilityLabel("添加图片")

                Button {
                    showFileImporter = true
                } label: {
                    Image(systemName: "paperclip")
                        .frame(width: 34, height: 34)
                }
                .accessibilityLabel("添加文件")

                if store.chatMode == .agent {
                    Button {
                        useWebSearch.toggle()
                    } label: {
                        Image(systemName: useWebSearch ? "globe.asia.australia.fill" : "globe.asia.australia")
                            .frame(width: 34, height: 34)
                    }
                    .foregroundStyle(useWebSearch ? .indigo : .secondary)
                    .accessibilityLabel("联网搜索")
                }

                Button {
                    activeSheet = .params
                } label: {
                    Image(systemName: "slider.horizontal.3")
                        .frame(width: 34, height: 34)
                }
                .accessibilityLabel("模型参数")

                Spacer()
            }
            .font(.headline)

            HStack(alignment: .bottom, spacing: 10) {
                TextField("输入消息...", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .focused($inputFocused)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))

                Button {
                    if store.isStreaming {
                        store.stopStreaming()
                    } else {
                        let text = draft
                        let outgoingAttachments = attachments
                        draft = ""
                        attachments = []
                        inputFocused = false
                        store.send(text, attachments: outgoingAttachments, useWebSearch: store.chatMode == .agent && useWebSearch)
                    }
                } label: {
                    Image(systemName: store.isStreaming ? "stop.fill" : "arrow.up")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(store.isStreaming ? Color.red : Color.indigo, in: Circle())
                }
                .disabled(!store.isStreaming && draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && attachments.isEmpty)
                .opacity(!store.isStreaming && draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && attachments.isEmpty ? 0.45 : 1)
            }

            HStack(spacing: 12) {
                Label("\(store.settings.model) · \(store.settings.maxTokens) tokens", systemImage: "cpu")
                    .lineLimit(1)
                Spacer()
                if store.apiKey.isEmpty {
                    Label("未配置 Key", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(12)
        .background(.bar)
    }

    private func importFiles(_ result: Result<[URL], Error>) {
        guard case let .success(urls) = result else { return }
        for url in urls {
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }

            let data = try? Data(contentsOf: url)
            let text = data.flatMap { String(data: $0, encoding: .utf8) }
            attachments.append(ChatAttachment(
                name: url.lastPathComponent,
                mimeType: UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream",
                isImage: false,
                textContent: text ?? "文件已附加：\(url.lastPathComponent)"
            ))
        }
    }

    private func importPhoto(_ item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self) else { return }
        let attachment = ChatAttachment(
            name: "image-\(Date().timeIntervalSince1970).jpg",
            mimeType: "image/jpeg",
            isImage: true,
            base64Data: data.base64EncodedString()
        )
        await MainActor.run {
            attachments.append(attachment)
            photoItem = nil
        }
    }
}

private enum ActiveSheet: Identifiable {
    case menu
    case history
    case params
    case settings
    case memory
    case prompts
    case tools

    var id: String {
        switch self {
        case .menu: "menu"
        case .history: "history"
        case .params: "params"
        case .settings: "settings"
        case .memory: "memory"
        case .prompts: "prompts"
        case .tools: "tools"
        }
    }
}

private struct NavigationMenuSheet: View {
    @Binding var activeSheet: ActiveSheet?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("工作台") {
                    menuButton("会话历史", icon: "clock.arrow.circlepath", sheet: .history)
                    menuButton("提示词管理", icon: "text.badge.star", sheet: .prompts)
                    menuButton("记忆管理", icon: "brain.head.profile", sheet: .memory)
                    menuButton("工具中心", icon: "wrench.and.screwdriver", sheet: .tools)
                    menuButton("服务商设置", icon: "gearshape", sheet: .settings)
                }
            }
            .navigationTitle("AgentChat")
        }
    }

    private func menuButton(_ title: String, icon: String, sheet: ActiveSheet) -> some View {
        Button {
            dismiss()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                activeSheet = sheet
            }
        } label: {
            Label(title, systemImage: icon)
        }
    }
}

private struct MessageBubble: View {
    let message: ChatMessage

    private var isUser: Bool { message.role == .user }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 42) }

            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 6) {
                    Image(systemName: isUser ? "person.crop.circle.fill" : "sparkles")
                    Text(isUser ? "我" : "AgentChat")
                    if message.status == .streaming {
                        ProgressView()
                            .scaleEffect(0.7)
                    }
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(isUser ? .white.opacity(0.85) : .secondary)

                ForEach(renderItems) { item in
                    switch item {
                    case let .text(_, content):
                        MarkdownContent(markdown: content, inverted: isUser)
                    case let .tool(toolCall):
                        ToolCallCard(toolCall: toolCall, inverted: isUser)
                    }
                }

                if !message.attachments.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(message.attachments) { attachment in
                            Label(attachment.name, systemImage: attachment.isImage ? "photo" : "doc.text")
                                .font(.caption)
                                .lineLimit(1)
                        }
                    }
                    .foregroundStyle(isUser ? .white.opacity(0.78) : .secondary)
                }

                if message.status == .failed {
                    Label("发送失败", systemImage: "xmark.octagon.fill")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            .padding(14)
            .foregroundStyle(isUser ? .white : .primary)
            .background(isUser ? Color.indigo : Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isUser ? Color.clear : Color(.separator).opacity(0.2), lineWidth: 1)
            )

            if !isUser { Spacer(minLength: 42) }
        }
    }

    private var displayContent: String {
        let content = message.content.isEmpty ? "正在思考..." : message.content
        if message.content.isEmpty && !message.toolCalls.isEmpty {
            return "正在调用工具..."
        }
        return content.components(separatedBy: "\n\n--- 附件文件").first ?? content
    }

    private var renderItems: [MessageRenderItem] {
        let content = displayContent
        let toolCalls = message.toolCalls.sorted {
            let leftOffset = $0.contentOffset ?? content.count
            let rightOffset = $1.contentOffset ?? content.count
            if leftOffset == rightOffset {
                return $0.index < $1.index
            }
            return leftOffset < rightOffset
        }

        guard !toolCalls.isEmpty else {
            return [.text(id: "text-0", content: content)]
        }

        var items: [MessageRenderItem] = []
        var cursor = 0
        var textIndex = 0

        for toolCall in toolCalls {
            let offset = max(0, min(toolCall.contentOffset ?? content.count, content.count))
            if offset > cursor {
                let segment = content.substring(fromCharacterOffset: cursor, to: offset)
                if !segment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    items.append(.text(id: "text-\(textIndex)", content: segment))
                    textIndex += 1
                }
                cursor = offset
            }
            items.append(.tool(toolCall))
        }

        if cursor < content.count {
            let segment = content.substring(fromCharacterOffset: cursor, to: content.count)
            if !segment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                items.append(.text(id: "text-\(textIndex)", content: segment))
            }
        }

        return items.isEmpty ? [.text(id: "text-0", content: content)] : items
    }
}

private extension String {
    func substring(fromCharacterOffset start: Int, to end: Int) -> String {
        let lowerOffset = max(0, min(start, count))
        let upperOffset = max(lowerOffset, min(end, count))
        let lowerBound = index(startIndex, offsetBy: lowerOffset)
        let upperBound = index(startIndex, offsetBy: upperOffset)
        return String(self[lowerBound..<upperBound])
    }
}

private enum MessageRenderItem: Identifiable {
    case text(id: String, content: String)
    case tool(ChatToolCall)

    var id: String {
        switch self {
        case let .text(id, _):
            return id
        case let .tool(toolCall):
            return "tool-\(toolCall.id.uuidString)"
        }
    }
}

private struct ToolCallCard: View {
    let toolCall: ChatToolCall
    let inverted: Bool
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.18)) {
                    expanded.toggle()
                }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "hammer.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(iconColor)
                        .frame(width: 22, height: 22)
                        .background(iconBackground, in: Circle())

                    VStack(alignment: .leading, spacing: 2) {
                        Text(displayName)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(primaryText)
                            .lineLimit(1)
                        Text(summary)
                            .font(.caption2)
                            .foregroundStyle(secondaryText)
                            .lineLimit(1)
                    }

                    Spacer(minLength: 8)

                    Text(statusText)
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 4)
                        .background(statusBackground, in: Capsule())
                        .foregroundStyle(secondaryText)

                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(secondaryText)
                }
                .contentShape(Rectangle())
                .padding(10)
            }
            .buttonStyle(.plain)

            if expanded {
                Divider()
                    .overlay(borderColor)
                VStack(alignment: .leading, spacing: 8) {
                    codeSection(title: "参数", text: formattedArguments)
                    if let result = formattedResult {
                        codeSection(title: "返回", text: result)
                    }
                }
                .padding(10)
                .background(argumentBackground)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(cardBackground, in: RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(borderColor, lineWidth: 1)
        )
    }

    private var displayName: String {
        toolCall.name == "unknown_tool" ? "工具调用" : toolCall.name
    }

    private var summary: String {
        if toolCall.arguments.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "等待模型传入参数"
        }
        if let action = jsonObject["action"] as? String {
            return "\(toolCall.result == nil ? "请求" : "完成") · action: \(action)"
        }
        return toolCall.result == nil ? "等待工具返回结果" : "工具已返回结果"
    }

    private var formattedArguments: String {
        formattedJSON(toolCall.arguments) ?? "暂无参数"
    }

    private var formattedResult: String? {
        guard let result = toolCall.result?.trimmingCharacters(in: .whitespacesAndNewlines), !result.isEmpty else {
            return nil
        }
        return formattedJSON(result) ?? result
    }

    private func formattedJSON(_ value: String) -> String? {
        let candidate = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !candidate.isEmpty else { return nil }
        guard let data = candidate.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              JSONSerialization.isValidJSONObject(object),
              let pretty = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys]),
              let text = String(data: pretty, encoding: .utf8) else {
            return candidate
        }
        return text.replacingOccurrences(of: "\\/", with: "/")
    }

    private func codeSection(title: String, text: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(secondaryText)

            ScrollView(.horizontal, showsIndicators: true) {
                Text(text)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(primaryText)
                    .textSelection(.enabled)
                    .padding(8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(codeBackground, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(borderColor.opacity(0.7), lineWidth: 1)
            )
        }
    }

    private var jsonObject: [String: Any] {
        guard let data = toolCall.arguments.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    private var cardBackground: Color {
        inverted ? Color.white.opacity(0.12) : Color(.systemBackground)
    }

    private var argumentBackground: Color {
        inverted ? Color.black.opacity(0.18) : Color(.secondarySystemGroupedBackground)
    }

    private var codeBackground: Color {
        inverted ? Color.black.opacity(0.16) : Color(.systemBackground)
    }

    private var borderColor: Color {
        inverted ? Color.white.opacity(0.2) : Color(.separator).opacity(0.35)
    }

    private var iconColor: Color {
        inverted ? .indigo : .white
    }

    private var iconBackground: Color {
        inverted ? .white.opacity(0.9) : .indigo
    }

    private var statusText: String {
        toolCall.result == nil ? "调用中" : "已完成"
    }

    private var statusBackground: Color {
        toolCall.result == nil ? Color.orange.opacity(inverted ? 0.24 : 0.14) : Color.green.opacity(inverted ? 0.24 : 0.14)
    }

    private var primaryText: Color {
        inverted ? .white : .primary
    }

    private var secondaryText: Color {
        inverted ? .white.opacity(0.72) : .secondary
    }
}

private struct AgentTimeline: View {
    let events: [AgentEvent]
    @State private var expanded = false

    var body: some View {
        if !events.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) {
                        expanded.toggle()
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "list.bullet.rectangle")
                            .foregroundStyle(.indigo)
                            .frame(width: 18)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("行动观察")
                                .font(.caption.weight(.semibold))
                            Text(summaryText)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }

                        Spacer(minLength: 8)

                        Text("\(events.count)")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 4)
                            .background(Color(.tertiarySystemGroupedBackground), in: Capsule())

                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.bold))
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if expanded {
                    Divider()
                        .overlay(Color(.separator).opacity(0.35))

                    ForEach(events.suffix(6)) { event in
                        HStack(alignment: .top, spacing: 8) {
                            Image(systemName: icon(for: event.kind))
                                .foregroundStyle(.indigo)
                                .frame(width: 18)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(event.kind.rawValue)
                                    .font(.caption.weight(.semibold))
                                Text(event.summary)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(3)
                            }
                        }
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var summaryText: String {
        events.last?.summary.components(separatedBy: "\n").first ?? "暂无行动"
    }

    private func icon(for kind: AgentEventKind) -> String {
        switch kind {
        case .plan: "list.bullet.clipboard"
        case .action: "play.circle.fill"
        case .observation: "eye.fill"
        case .result: "checkmark.seal.fill"
        }
    }
}

private struct EmptyConversationView: View {
    let mode: ChatMode

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(mode == .chat ? "普通聊天" : "智能体模式", systemImage: mode == .chat ? "message.fill" : "sparkles")
                .font(.headline)
            Text(mode == .chat ? "配置服务商后，可以直接流式对话、附加文件或图片。" : "智能体会优先使用现有工具处理记忆、提示词等任务；不需要工具时会直接回答。")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }
}

private struct AttachmentPill: View {
    let attachment: ChatAttachment
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: attachment.isImage ? "photo" : "doc.text")
            Text(attachment.name)
                .lineLimit(1)
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill")
            }
        }
        .font(.caption)
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color(.secondarySystemGroupedBackground), in: Capsule())
    }
}

private struct ConversationHistorySheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var filter: ChatMode?

    private var conversations: [ConversationSnapshot] {
        guard let filter else { return store.conversations }
        return store.conversations.filter { $0.selectedMode == filter }
    }

    var body: some View {
        NavigationStack {
            List {
                Picker("模式", selection: $filter) {
                    Text("全部").tag(ChatMode?.none)
                    Text("聊天").tag(ChatMode?.some(.chat))
                    Text("智能体").tag(ChatMode?.some(.agent))
                }
                .pickerStyle(.segmented)

                ForEach(conversations) { conversation in
                    Button {
                        store.loadConversation(conversation)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(conversation.title)
                                    .font(.headline)
                                Spacer()
                                Text(conversation.selectedMode == .chat ? "聊天" : "智能体")
                                    .font(.caption.weight(.semibold))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(Color.indigo.opacity(0.12), in: Capsule())
                            }
                            Text("\(conversation.messages.count) 条消息 · \(conversation.updatedAt.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            store.deleteConversation(conversation)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                }
            }
            .navigationTitle("会话历史")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

private struct ParameterSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("采样参数") {
                    valueSlider("Temperature", value: $store.settings.temperature, range: 0...2)
                    valueSlider("Top P", value: $store.settings.topP, range: 0...1)
                    Stepper("Top K: \(store.settings.topK)", value: $store.settings.topK, in: 1...200)
                    Stepper("Max Tokens: \(store.settings.maxTokens)", value: $store.settings.maxTokens, in: 256...32768, step: 256)
                }
            }
            .navigationTitle("模型参数设置")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func valueSlider(_ title: String, value: Binding<Double>, range: ClosedRange<Double>) -> some View {
        VStack(alignment: .leading) {
            HStack {
                Text(title)
                Spacer()
                Text(value.wrappedValue, format: .number.precision(.fractionLength(2)))
                    .foregroundStyle(.secondary)
            }
            Slider(value: value, in: range, step: 0.05)
        }
    }
}
