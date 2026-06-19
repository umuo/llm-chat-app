import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var apiKeyVisible = false
    @State private var modelQuery = ""
    @State private var showingResetConfirmation = false
    @State private var clearAPIKeyOnReset = false

    private var filteredModels: [String] {
        let models = store.availableModels.filter { model in
            switch store.selectedModelPickerTarget {
            case .chat:
                return !model.looksLikeEmbeddingModel && !model.looksLikeRerankModel
            case .embedding:
                return model.looksLikeEmbeddingModel && !model.looksLikeRerankModel
            case .rerank:
                return model.looksLikeRerankModel
            }
        }
        guard !modelQuery.isEmpty else { return models }
        return models.filter { $0.localizedCaseInsensitiveContains(modelQuery) }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Image(systemName: store.apiKey.isEmpty ? "exclamationmark.triangle.fill" : "checkmark.seal.fill")
                                .foregroundStyle(store.apiKey.isEmpty ? .orange : .green)
                            Text(store.apiKey.isEmpty ? "缺少 API 密钥" : "已保存 · \(store.settings.model)")
                                .font(.headline)
                        }
                        Text(statusDetail)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }

                Section("聊天补全端点") {
                    TextField("API 基础 URL", text: $store.settings.baseURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    if apiKeyVisible {
                        TextField(apiKeyPlaceholder, text: $store.apiKey)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } else {
                        SecureField(apiKeyPlaceholder, text: $store.apiKey)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }

                    Toggle("显示 API Key", isOn: $apiKeyVisible)
                }

                Section("模型") {
                    modelRow(title: "聊天模型", value: $store.settings.model, target: .chat)
                    modelRow(title: "Embedding 模型", value: $store.settings.embeddingModel, target: .embedding)
                    modelRow(title: "Rerank 模型", value: $store.settings.rerankModel, target: .rerank)

                    Picker("检索模式", selection: $store.settings.retrievalMode) {
                        ForEach(RetrievalMode.allCases) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                }

                Section("模型参数") {
                    valueSlider("Temperature", value: $store.settings.temperature, range: 0...2)
                    valueSlider("Top P", value: $store.settings.topP, range: 0...1)
                    Stepper("Top K: \(store.settings.topK)", value: $store.settings.topK, in: 1...200)
                    Stepper("Max Tokens: \(store.settings.maxTokens)", value: $store.settings.maxTokens, in: 256...32768, step: 256)
                    Stepper("Timeout: \(store.settings.timeoutSeconds)s", value: $store.settings.timeoutSeconds, in: 10...180, step: 5)
                }

                Section("上下文管理") {
                    Toggle("自动上下文压缩", isOn: $store.settings.contextCompressionEnabled)
                    Stepper("上下文窗口: \(store.settings.contextWindowTokens)", value: $store.settings.contextWindowTokens, in: 4096...262144, step: 4096)
                    Stepper("预留输出: \(store.settings.contextReserveTokens)", value: $store.settings.contextReserveTokens, in: 1024...32768, step: 1024)
                    Stepper("保留最近: \(store.settings.contextKeepRecentTokens)", value: $store.settings.contextKeepRecentTokens, in: 2048...65536, step: 1024)
                    if let summary = store.contextSummary {
                        Text("已压缩到：\(summary.updatedAt.formatted(date: .abbreviated, time: .shortened)) · 原上下文约 \(summary.tokensBefore) tokens")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Text("超过预算时，会摘要较早对话并保留最近消息原文；原始聊天记录不会删除。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("连接测试") {
                    Button {
                        store.testConnection(target: .chat)
                    } label: {
                        Label("测试连接并获取模型", systemImage: "antenna.radiowaves.left.and.right")
                    }

                    if !store.connectionStatus.isEmpty {
                        Text(store.connectionStatus)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    if !store.availableModels.isEmpty {
                        Picker("模型列表目标", selection: $store.selectedModelPickerTarget) {
                            ForEach(ModelPickerTarget.allCases) { target in
                                Text(target.rawValue).tag(target)
                            }
                        }

                        TextField("搜索模型", text: $modelQuery)

                        ForEach(filteredModels.prefix(30), id: \.self) { model in
                            Button {
                                store.selectModel(model, target: store.selectedModelPickerTarget)
                            } label: {
                                HStack {
                                    Text(model)
                                    Spacer()
                                    if model == selectedModel {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(.indigo)
                                    }
                                }
                            }
                        }
                    }
                }

                Section("数据管理") {
                    Toggle("重置时同时清除 API Key", isOn: $clearAPIKeyOnReset)
                    Button("重置本地数据", role: .destructive) {
                        showingResetConfirmation = true
                    }
                    Text("退出后台任务不会清除本地数据；这里会清空会话历史、记忆、工具配置和服务商设置，并恢复默认提示词。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("服务商")
            .confirmationDialog(
                "确认重置本地数据？",
                isPresented: $showingResetConfirmation,
                titleVisibility: .visible
            ) {
                Button("重置", role: .destructive) {
                    store.resetLocalData(clearAPIKey: clearAPIKeyOnReset)
                }
                Button("取消", role: .cancel) {}
            } message: {
                Text(clearAPIKeyOnReset ? "会同时清除 Keychain 中保存的 API Key。" : "API Key 会保留在 Keychain 中。")
            }
        }
    }

    private var apiKeyPlaceholder: String {
        store.apiKey.isEmpty ? "API 密钥" : "API 密钥（已保存，留空会清除）"
    }

    private var statusDetail: String {
        var parts = [store.settings.baseURL, "Chat: \(store.settings.model)"]
        if !store.settings.embeddingModel.isEmpty {
            parts.append("Embedding: \(store.settings.embeddingModel)")
        }
        if !store.settings.rerankModel.isEmpty {
            parts.append("Rerank: \(store.settings.rerankModel)")
        }
        parts.append(store.settings.retrievalMode.rawValue)
        return parts.joined(separator: " · ")
    }

    private var selectedModel: String {
        switch store.selectedModelPickerTarget {
        case .chat:
            return store.settings.model
        case .embedding:
            return store.settings.embeddingModel
        case .rerank:
            return store.settings.rerankModel
        }
    }

    private func modelRow(title: String, value: Binding<String>, target: ModelPickerTarget) -> some View {
        HStack {
            TextField(title, text: value)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button {
                store.selectedModelPickerTarget = target
                store.testConnection(target: target)
            } label: {
                Image(systemName: "arrow.down.circle")
            }
            .accessibilityLabel("获取\(title)")
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

private extension String {
    var looksLikeEmbeddingModel: Bool {
        let id = lowercased()
        return id.contains("embed") || id.contains("embedding") || id.contains("bge") || id.contains("e5-")
    }

    var looksLikeRerankModel: Bool {
        let id = lowercased()
        return id.contains("rerank") || id.contains("ranker") || id.contains("bge-reranker")
    }
}
