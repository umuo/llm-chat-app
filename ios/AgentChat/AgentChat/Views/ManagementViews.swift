import SwiftUI

struct MemoryView: View {
    @EnvironmentObject private var store: AppStore
    @State private var searchText = ""
    @State private var selectedType: MemoryType?
    @State private var editor: MemoryEditorState?

    private var filtered: [MemoryItem] {
        store.memories.filter { item in
            let typeMatches = selectedType == nil || item.type == selectedType
            let searchMatches = searchText.isEmpty
                || item.content.localizedCaseInsensitiveContains(searchText)
                || item.source.localizedCaseInsensitiveContains(searchText)
                || item.type.rawValue.localizedCaseInsensitiveContains(searchText)
            return typeMatches && searchMatches
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("类型", selection: $selectedType) {
                        Text("全部").tag(MemoryType?.none)
                        ForEach(MemoryType.allCases) { type in
                            Text(type.rawValue).tag(MemoryType?.some(type))
                        }
                    }
                    .pickerStyle(.segmented)
                }

                if filtered.isEmpty {
                    ContentUnavailableView("暂无记忆", systemImage: "brain.head.profile", description: Text("点击右上角添加一条本地记忆。"))
                } else {
                    ForEach(filtered) { memory in
                        Button {
                            editor = .edit(memory)
                        } label: {
                            MemoryRow(memory: memory, isEnabled: binding(for: memory))
                        }
                        .buttonStyle(.plain)
                        .swipeActions {
                            Button(role: .destructive) {
                                store.deleteMemory(memory)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                            Button {
                                editor = .edit(memory)
                            } label: {
                                Label("编辑", systemImage: "pencil")
                            }
                            .tint(.indigo)
                        }
                    }
                }
            }
            .searchable(text: $searchText, prompt: "搜索内容、来源或类型")
            .navigationTitle("记忆")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        editor = .create
                    } label: {
                        Image(systemName: "plus.circle.fill")
                    }
                    .accessibilityLabel("新建记忆")
                }
            }
            .sheet(item: $editor) { state in
                MemoryEditorSheet(state: state)
            }
        }
    }

    private func binding(for memory: MemoryItem) -> Binding<Bool> {
        Binding {
            store.memories.first(where: { $0.id == memory.id })?.isEnabled ?? false
        } set: { value in
            guard let index = store.memories.firstIndex(where: { $0.id == memory.id }) else { return }
            store.memories[index].isEnabled = value
            store.memories[index].updatedAt = Date()
        }
    }
}

private struct MemoryRow: View {
    let memory: MemoryItem
    @Binding var isEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(memory.type.rawValue, systemImage: "tag.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.indigo)
                Spacer()
                Text(memory.updatedAt, style: .date)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(memory.content)
                .font(.body)
                .foregroundStyle(.primary)
                .lineLimit(4)

            HStack {
                Text(memory.source)
                    .lineLimit(1)
                Spacer()
                Toggle("启用", isOn: $isEnabled)
                    .labelsHidden()
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
    }
}

private enum MemoryEditorState: Identifiable {
    case create
    case edit(MemoryItem)

    var id: String {
        switch self {
        case .create:
            return "create"
        case let .edit(memory):
            return memory.id.uuidString
        }
    }
}

private struct MemoryEditorSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    let state: MemoryEditorState

    @State private var type: MemoryType
    @State private var content: String
    @State private var source: String
    @State private var isEnabled: Bool
    @State private var confidence: Double

    init(state: MemoryEditorState) {
        self.state = state
        switch state {
        case .create:
            _type = State(initialValue: .preference)
            _content = State(initialValue: "")
            _source = State(initialValue: "手动添加")
            _isEnabled = State(initialValue: true)
            _confidence = State(initialValue: 0.82)
        case let .edit(memory):
            _type = State(initialValue: memory.type)
            _content = State(initialValue: memory.content)
            _source = State(initialValue: memory.source)
            _isEnabled = State(initialValue: memory.isEnabled)
            _confidence = State(initialValue: memory.confidence)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("基础信息") {
                    Picker("类型", selection: $type) {
                        ForEach(MemoryType.allCases) { type in
                            Text(type.rawValue).tag(type)
                        }
                    }
                    TextField("来源", text: $source)
                    Toggle("启用", isOn: $isEnabled)
                }

                Section("内容") {
                    TextField("内容", text: $content, axis: .vertical)
                        .lineLimit(6...14)
                }

                Section("置信度") {
                    HStack {
                        Slider(value: $confidence, in: 0...1, step: 0.01)
                        Text(confidence, format: .number.precision(.fractionLength(2)))
                            .foregroundStyle(.secondary)
                            .frame(width: 44, alignment: .trailing)
                    }
                }

                if case let .edit(memory) = state {
                    Section {
                        Button("删除记忆", role: .destructive) {
                            store.deleteMemory(memory)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(isEditing ? "编辑记忆" : "新建记忆")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        save()
                        dismiss()
                    }
                    .disabled(content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private var isEditing: Bool {
        if case .edit = state { return true }
        return false
    }

    private func save() {
        let cleanedContent = content.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanedSource = source.trimmingCharacters(in: .whitespacesAndNewlines)
        switch state {
        case .create:
            var memory = MemoryItem(
                type: type,
                content: cleanedContent,
                source: cleanedSource.isEmpty ? "手动添加" : cleanedSource
            )
            memory.isEnabled = isEnabled
            memory.confidence = confidence
            store.memories.insert(memory, at: 0)
        case let .edit(memory):
            var updated = memory
            updated.type = type
            updated.content = cleanedContent
            updated.source = cleanedSource.isEmpty ? "手动添加" : cleanedSource
            updated.isEnabled = isEnabled
            updated.confidence = confidence
            store.updateMemory(updated)
        }
    }
}

struct PromptsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var searchText = ""
    @State private var selectedCategory: String?
    @State private var editor: PromptEditorState?

    private var categories: [String] {
        Array(Set(store.prompts.map(\.category))).filter { !$0.isEmpty }.sorted()
    }

    private var filtered: [ManagedPrompt] {
        store.prompts.filter { prompt in
            let categoryMatches = selectedCategory == nil || prompt.category == selectedCategory
            let searchMatches = searchText.isEmpty
                || prompt.title.localizedCaseInsensitiveContains(searchText)
                || prompt.category.localizedCaseInsensitiveContains(searchText)
                || prompt.content.localizedCaseInsensitiveContains(searchText)
            return categoryMatches && searchMatches
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if !categories.isEmpty {
                    Section {
                        Picker("分类", selection: $selectedCategory) {
                            Text("全部").tag(String?.none)
                            ForEach(categories, id: \.self) { category in
                                Text(category).tag(String?.some(category))
                            }
                        }
                        .pickerStyle(.segmented)
                    }
                }

                if filtered.isEmpty {
                    ContentUnavailableView("暂无提示词", systemImage: "text.badge.star", description: Text("点击右上角创建常用提示词。"))
                } else {
                    ForEach(filtered) { prompt in
                        Button {
                            editor = .edit(prompt)
                        } label: {
                            PromptRow(prompt: prompt)
                        }
                        .buttonStyle(.plain)
                        .swipeActions {
                            Button(role: .destructive) {
                                store.deletePrompt(prompt)
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                            Button {
                                editor = .edit(prompt)
                            } label: {
                                Label("编辑", systemImage: "pencil")
                            }
                            .tint(.indigo)
                        }
                    }
                }
            }
            .searchable(text: $searchText, prompt: "搜索标题、分类或内容")
            .navigationTitle("提示词")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        editor = .create
                    } label: {
                        Image(systemName: "plus.circle.fill")
                    }
                    .accessibilityLabel("新建提示词")
                }
            }
            .sheet(item: $editor) { state in
                PromptEditorSheet(state: state)
            }
        }
    }
}

private struct PromptRow: View {
    let prompt: ManagedPrompt

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(prompt.title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Spacer()
                Text(prompt.category.isEmpty ? "未分类" : prompt.category)
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.indigo.opacity(0.12), in: Capsule())
            }
            Text(prompt.content)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(5)
            Text(prompt.updatedAt, style: .date)
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 6)
    }
}

private enum PromptEditorState: Identifiable {
    case create
    case edit(ManagedPrompt)

    var id: String {
        switch self {
        case .create:
            return "create"
        case let .edit(prompt):
            return prompt.id.uuidString
        }
    }
}

private struct PromptEditorSheet: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    let state: PromptEditorState

    @State private var title: String
    @State private var category: String
    @State private var content: String

    init(state: PromptEditorState) {
        self.state = state
        switch state {
        case .create:
            _title = State(initialValue: "")
            _category = State(initialValue: "通用")
            _content = State(initialValue: "")
        case let .edit(prompt):
            _title = State(initialValue: prompt.title)
            _category = State(initialValue: prompt.category)
            _content = State(initialValue: prompt.content)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("基础信息") {
                    TextField("标题", text: $title)
                    TextField("分类", text: $category)
                }

                Section("内容") {
                    TextField("提示词内容", text: $content, axis: .vertical)
                        .lineLimit(8...18)
                }

                if case let .edit(prompt) = state {
                    Section {
                        Button("删除提示词", role: .destructive) {
                            store.deletePrompt(prompt)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(isEditing ? "编辑提示词" : "新建提示词")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        save()
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private var isEditing: Bool {
        if case .edit = state { return true }
        return false
    }

    private func save() {
        let cleanedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanedCategory = category.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanedContent = content.trimmingCharacters(in: .whitespacesAndNewlines)

        switch state {
        case .create:
            store.addPrompt(
                title: cleanedTitle,
                category: cleanedCategory.isEmpty ? "通用" : cleanedCategory,
                content: cleanedContent
            )
        case let .edit(prompt):
            var updated = prompt
            updated.title = cleanedTitle
            updated.category = cleanedCategory.isEmpty ? "通用" : cleanedCategory
            updated.content = cleanedContent
            store.updatePrompt(updated)
        }
    }
}

struct ToolCenterView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        NavigationStack {
            Form {
                Section("联网检索") {
                    Toggle("允许 Web Search", isOn: $store.toolConfiguration.webSearchEnabled)
                    SecureField("Tavily API Key", text: $store.settings.tavilyAPIKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                Section("MCP") {
                    TextField("MCP Server URL", text: $store.settings.mcpServerURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("保存 MCP Server URL 后，智能体会按工具中心配置预留外部工具调用能力。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("本地工具") {
                    Toggle("文件工作区", isOn: $store.toolConfiguration.fileWorkspaceEnabled)
                    Toggle("记忆写入需审核", isOn: $store.toolConfiguration.memoryWriteReviewEnabled)
                }
            }
            .navigationTitle("工具中心")
        }
    }
}
