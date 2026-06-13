# LLM Chat Agent Android App 设计文档

版本：0.1  
日期：2026-06-13  
目标平台：Android，优先 Jetpack Compose + Material Design 3

## 1. 产品定位

这是一款面向个人用户和轻量专业用户的 LLM 对话 App。它既要能像常规 ChatGPT 客户端一样快速聊天，也要能在 Agent 模式下执行多步骤任务、展示计划、调用工具、积累记忆，并允许用户自由接入 OpenAI 兼容接口。

核心原则：

- 用户拥有模型配置：支持自定义 API Base URL、API Key、Model、默认参数。
- 用户理解 Agent 行为：Agent 的计划、行动、工具调用、记忆写入都应可见、可暂停、可回滚或需确认。
- App 优先安全和隐私：Key、聊天记录、记忆默认本地保存；敏感操作明确授权。
- Android 原生体验：遵循 Material Design 3，支持动态色、深色模式、平板和折叠屏适配。

## 2. 目标与非目标

### 2.1 目标

- 提供稳定的普通聊天模式：流式响应、上下文管理、会话列表、消息编辑/重试/复制/分享。
- 提供 Agent 模式：支持 ReAct Agent 和 Planning Agent 两类执行方式。
- 提供记忆系统：包含用户偏好、事实记忆、任务经验、会话摘要和短期工作记忆。
- 支持 OpenAI 兼容服务配置：API URL、Key、Model、组织/项目 ID 可选、温度、上下文窗口等。
- 支持多配置 Profile：例如 OpenAI、Azure OpenAI、OpenRouter、本地 vLLM/Ollama OpenAI-compatible endpoint。
- 给后续工具系统留接口：搜索、网页读取、文件、日历、剪贴板、Android 分享入口等。

### 2.2 非目标

- MVP 不做完整多端同步。
- MVP 不默认提供后台常驻自动执行。
- MVP 不提供无确认的高风险工具，例如发送邮件、付款、删除文件。
- MVP 不展示模型原始隐藏推理链，只展示计划、行动摘要、工具输入输出摘要和可审计事件。

## 3. 用户画像与场景

### 3.1 普通聊天用户

用户希望配置自己的 API Key，然后像使用常规聊天 App 一样提问、翻译、写作、总结。

关键体验：

- 首次打开可快速进入“配置模型”。
- 配置完成后立即进入聊天。
- 支持流式输出、停止生成、重新生成。
- 支持按会话切换模型配置。

### 3.2 Agent 用户

用户希望 App 能处理复杂任务，例如“帮我规划一个三天学习 Kotlin Compose 的计划，并每天提醒我复盘”，或“分析这段需求，拆成开发任务”。

关键体验：

- 用户能选择 Agent 类型：ReAct 或 Planning。
- Agent 先给出计划或下一步行动摘要。
- 工具调用和记忆写入需要透明展示。
- 用户可以暂停、继续、跳过步骤或改写目标。

### 3.3 隐私敏感用户

用户希望个人偏好、Key、记忆和对话不被云端同步。

关键体验：

- Key 使用 Android Keystore 加密。
- 记忆可按类型开关，支持查看、编辑、删除。
- 每次发送前可看到当前会附带哪些记忆。

## 4. 信息架构

手机端使用底部导航；平板、折叠屏和横屏使用 NavigationRail 或 PermanentNavigationDrawer。

主要入口：

- 聊天：会话列表 + 当前会话。
- Agent：Agent 任务、运行记录、模板。
- 记忆：记忆库浏览、搜索、编辑、开关。
- 设置：模型配置、安全、外观、数据管理。

推荐导航结构：

```text
AppShell
├── Chat
│   ├── ConversationList
│   └── ConversationDetail
├── Agent
│   ├── AgentHome
│   ├── AgentRunDetail
│   └── AgentTemplateEditor
├── Memory
│   ├── MemoryList
│   ├── MemoryDetail
│   └── MemoryReviewQueue
└── Settings
    ├── ProviderProfiles
    ├── ModelDefaults
    ├── SecurityPrivacy
    └── DataManagement
```

## 5. Android UI 设计

### 5.1 设计语言

- 使用 Material Design 3。
- 支持动态色，Android 12 以下使用自定义 light/dark color scheme。
- 整体视觉应偏工具型：密度适中、信息清楚、少装饰。
- 卡片圆角控制在 8dp 左右，避免层层套卡片。
- 触控区域不小于 48dp。
- 所有图标按钮提供 contentDescription。

### 5.2 Chat 页面

布局：

- 顶部：当前会话标题、模型名、模式切换入口、更多菜单。
- 主体：LazyColumn 消息流。
- 底部：输入框、附件/工具按钮、发送/停止按钮、模式状态。

消息气泡：

- 用户消息右侧，模型消息左侧。
- 模型消息支持 Markdown、代码块、复制、重新生成。
- 流式输出时显示轻量进度状态。
- 错误消息使用可恢复 UI：重试、切换模型、查看错误详情。

模式切换：

- 使用 SegmentedButton：`Chat`、`Agent`。
- 切到 Agent 后，输入框上方显示 Agent 类型 Chip：`ReAct` 或 `Planning`。
- 不把复杂设置直接塞进聊天页，点击 Chip 进入底部 Sheet 配置。

### 5.3 Agent 页面

Agent 不是单纯聊天皮肤，而是任务运行器。

AgentHome：

- 最近任务列表。
- 新建任务 FAB。
- Agent 类型筛选：全部、ReAct、Planning。
- 常用模板：研究、写作、计划、代码分析、学习计划。

AgentRunDetail：

- 顶部显示目标、状态、模型、成本估计。
- 中部为步骤时间线：Plan、Action、Observation、Result、Memory Write。
- 底部为用户干预区：继续、暂停、修改目标、批准工具、终止。

步骤时间线建议使用竖向列表而不是复杂图形，保证移动端可读性。

### 5.4 Memory 页面

MemoryList：

- 搜索框。
- 类型 Filter Chips：偏好、事实、项目、任务经验、会话摘要。
- 每条记忆显示内容摘要、来源会话、更新时间、置信度、启用状态。

MemoryDetail：

- 完整内容。
- 来源消息链接。
- 被哪些会话/Agent 使用过。
- 编辑、禁用、删除。

MemoryReviewQueue：

- Agent 想写入长期记忆时进入待审核队列。
- 用户可一键接受、修改后接受、拒绝。

### 5.5 Settings 页面

ProviderProfiles：

- Profile 名称。
- API Base URL。
- API Key。
- Model。
- 兼容模式：OpenAI Chat Completions / Responses API 风格 / 自定义。
- Test Connection 按钮。

安全细节：

- API Key 输入框默认隐藏。
- 支持粘贴后立即本地加密保存。
- 测试连接只发送最小请求。
- 错误详情要脱敏，不显示完整 Key。

## 6. Agent 设计

### 6.1 Agent 模式总览

Agent 层分为四个核心对象：

- Goal：用户目标。
- State：当前任务状态，包括历史步骤、上下文、工具结果、记忆检索结果。
- Policy：Agent 策略，例如 ReAct 或 Planning。
- Runtime：负责模型调用、工具调度、权限确认、事件记录。

推荐 Kotlin 抽象：

```kotlin
interface AgentPolicy {
    suspend fun next(state: AgentState): AgentDecision
}

sealed interface AgentDecision {
    data class AskUser(val question: String) : AgentDecision
    data class CallTool(val call: ToolCall) : AgentDecision
    data class Respond(val message: AssistantMessage) : AgentDecision
    data class UpdatePlan(val plan: AgentPlan) : AgentDecision
    data class WriteMemory(val candidate: MemoryCandidate) : AgentDecision
}
```

### 6.2 ReAct Agent

ReAct 适合短到中等长度任务，以“观察当前状态 -> 决定下一步行动 -> 执行工具 -> 观察结果”的循环完成。

执行循环：

```text
User Goal
→ Retrieve Memory
→ Build Prompt
→ Model decides next action
→ If tool needed: request permission if required
→ Execute Tool
→ Record Observation
→ Repeat until final answer or max steps
```

UI 展示：

- 不展示原始隐藏推理。
- 展示 `下一步`、`调用工具`、`观察结果摘要`、`最终回答`。
- 每个工具调用可展开查看输入和输出摘要。

安全边界：

- 设置最大步数，默认 8。
- 设置最大工具调用次数，默认 5。
- 工具调用分风险等级。
- 中高风险工具必须用户确认。

### 6.3 Planning Agent

Planning 适合长任务。它先产生计划，再按步骤执行，并在失败或新信息出现时重规划。

执行流程：

```text
User Goal
→ Retrieve Memory
→ Create Plan
→ User reviews optional
→ Execute Step 1..N
→ Reflect after each step
→ Replan if blocked
→ Final Summary
→ Memory Candidate Extraction
```

Plan 数据结构：

```kotlin
data class AgentPlan(
    val title: String,
    val steps: List<PlanStep>,
    val assumptions: List<String>,
    val risks: List<String>
)

data class PlanStep(
    val id: String,
    val title: String,
    val status: PlanStepStatus,
    val expectedOutput: String,
    val requiredTools: List<String>
)
```

UI 行为：

- 计划用 Checklist 呈现。
- 当前步骤高亮。
- 用户可以重排、删除、添加步骤。
- 当 Agent 重规划时，用 Diff 风格展示变化。

### 6.4 Hermes Agent 可借鉴点

这里不照搬某个具体实现，而吸收 Hermes Agent 这类个人 Agent 系统的几个架构启发：

- Profile 化：模型、工具、记忆、提示词、权限策略组成可切换的 Agent Profile。
- 记忆和工具是一等能力：不是聊天上下文的附属品，而是可单独配置、审计、禁用的模块。
- 运行轨迹持久化：每次 Agent 执行都记录 plan、action、observation、result，便于复盘和调试。
- 跨会话连续性：长期记忆和任务经验能帮助后续任务，但写入需要来源、时间和置信度。
- 权限闸门：长期运行、定时任务、记忆写入和外部副作用要有明确授权。

Android App 的取舍：

- 默认不做常驻自主 Agent，避免后台滥用电量和权限。
- 所有外部副作用工具都走用户确认。
- 记忆写入先进入审核队列，用户可开启“低风险自动写入偏好记忆”。

### 6.5 Agent Harness 设计

Agent Harness 是 Agent 的执行外壳。Policy 负责决定“下一步该做什么”，Harness 负责保证这件事以可控、可恢复、可审计的方式发生。

Harness 不应该放在 UI 层，也不应该散落在 ViewModel 里。它属于 domain/runtime 层，对上暴露 AgentRun 状态流，对下协调模型、工具、记忆、权限和持久化。

核心职责：

- Context Assembly：组装系统提示、用户目标、会话历史、计划、工具说明、检索到的记忆和预算。
- Model Adapter：屏蔽不同 OpenAI 兼容接口差异，统一流式响应、工具调用、错误和 token usage。
- Tool Router：注册工具、校验 schema、执行工具、截断和摘要化工具结果。
- Permission Gate：根据工具风险、记忆敏感度和外部副作用决定是否需要用户确认。
- State Store：把每一步 AgentEvent 持久化，支持恢复、复盘和失败诊断。
- Budget Manager：控制最大步数、最大 token、最大费用估计、超时和重试次数。
- Verifier：检查模型输出是否满足结构化协议、工具结果是否被正确消费、最终答案是否覆盖目标。
- Recovery：网络失败、App 进后台、进程被杀后可以从最近的 AgentEvent 继续或安全终止。

建议生命周期：

```text
Create Run
→ Load Profile
→ Retrieve Memory
→ Assemble Context
→ Ask Policy for Decision
→ Validate Decision
→ Permission Gate
→ Execute Model or Tool
→ Persist AgentEvent
→ Verify Progress
→ Continue / Pause / Finish / Fail
```

Harness 配置建议：

```kotlin
data class AgentHarnessConfig(
    val providerProfileId: String,
    val model: String,
    val policy: AgentPolicyType,
    val maxSteps: Int,
    val maxToolCalls: Int,
    val maxRunDurationSeconds: Int,
    val memoryScope: MemoryScope,
    val permissionMode: PermissionMode,
    val retryPolicy: RetryPolicy
)
```

运行接口建议：

```kotlin
interface AgentHarness {
    fun observeRun(runId: String): Flow<AgentRunState>
    suspend fun start(goal: String, config: AgentHarnessConfig): AgentRunId
    suspend fun submitUserApproval(runId: String, approval: UserApproval)
    suspend fun pause(runId: String)
    suspend fun resume(runId: String)
    suspend fun cancel(runId: String)
}
```

Harness 与 UI 的关系：

- UI 只订阅 `AgentRunState`，不直接拼 Prompt 或执行工具。
- AgentRunDetail 的时间线直接来自 AgentEvent。
- 暂停、继续、批准、拒绝都是显式 Intent。
- 如果 App 进后台，前台服务不是默认能力；MVP 先暂停长任务，后续由用户开启后台执行。

Harness 的关键设计取舍：

- 采用事件溯源风格记录 AgentEvent，便于恢复和调试。
- 每次模型决策必须结构化解析，解析失败进入 Recovery，而不是让 UI 猜。
- 工具结果进入下一轮上下文前先摘要和标注来源，减少 prompt injection 风险。
- Planning Agent 复用同一 Harness，只替换 AgentPolicy 和计划相关 Verifier。

## 7. 记忆系统设计

### 7.1 记忆类型

- Working Memory：当前会话或当前 Agent Run 内使用，任务结束后可丢弃。
- Conversation Summary：长会话摘要，用于压缩上下文。
- User Preference：用户偏好，例如语言、代码风格、常用模型。
- Semantic Fact：关于用户、项目、世界或任务的事实。
- Episodic Memory：过去任务记录，例如“上次为项目 X 制定过 Compose 架构”。
- Procedural Memory：可复用做法，例如“写周报时先列指标再写风险”。

### 7.2 写入流程

```text
Conversation / Agent Trace
→ Memory Extractor
→ Candidate
→ Deduplicate & Merge
→ Risk Classification
→ User Review or Auto Accept
→ Persist
→ Embed for Retrieval
```

候选记忆字段：

```kotlin
data class MemoryCandidate(
    val content: String,
    val type: MemoryType,
    val sourceMessageIds: List<String>,
    val confidence: Float,
    val sensitivity: Sensitivity,
    val reason: String
)
```

### 7.3 检索流程

```text
User Message / Goal
→ Query Rewrite
→ Vector Search + Keyword Search
→ Filter by Profile / Scope / Enabled
→ Rerank
→ Attach Top K Memory Summaries
```

默认策略：

- 普通聊天最多附带 5 条记忆。
- Agent 模式最多附带 12 条记忆。
- 高敏记忆默认不自动附带，除非用户明确允许。
- 每次发送前可在展开区查看“将使用的记忆”。

### 7.4 存储建议

- Room：会话、消息、Agent Run、工具事件、记忆元数据。
- 本地向量索引：MVP 可先用 SQLite FTS + 简单 embedding 表；后续替换为专用向量库。
- Android Keystore：加密 API Key。
- EncryptedSharedPreferences 或自定义 Keystore-backed storage：保存 Provider secret。
- DataStore：保存非敏感设置，例如主题、默认 Profile、Agent 默认步数。

## 8. 模型与 Provider 配置

### 8.1 Provider Profile

```kotlin
data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val encryptedApiKeyRef: String,
    val defaultModel: String,
    val apiStyle: ApiStyle,
    val headers: Map<String, String>,
    val timeoutSeconds: Int,
    val enabled: Boolean
)
```

ApiStyle：

- OpenAI Chat Completions compatible。
- OpenAI Responses compatible。
- Custom OpenAI-like endpoint。

### 8.2 Model Settings

- temperature。
- topP。
- maxOutputTokens。
- contextWindow。
- stream enabled。
- tool calling enabled。
- reasoning effort 可选，只有支持的模型展示。

### 8.3 配置体验

首次启动：

```text
Welcome
→ Add Provider
→ Test Connection
→ Choose Default Model
→ Start Chat
```

Provider 配置页需要：

- 清晰显示当前 Base URL 和 Model。
- 支持复制 Profile。
- 支持导入/导出不含 Key 的配置。
- 支持“仅本会话使用此模型”。

## 9. 数据模型草案

```text
Conversation
├── id
├── title
├── mode: chat | agent
├── providerProfileId
├── model
├── createdAt
└── updatedAt

Message
├── id
├── conversationId
├── role: user | assistant | system | tool
├── content
├── status
├── tokenUsage
├── createdAt
└── metadata

AgentRun
├── id
├── conversationId
├── goal
├── policy: react | planning
├── status
├── maxSteps
├── startedAt
└── completedAt

AgentEvent
├── id
├── runId
├── type: plan | action | observation | memory | user_approval | final
├── payloadJson
├── riskLevel
├── createdAt
└── parentEventId

Memory
├── id
├── type
├── content
├── summary
├── embedding
├── sourceIds
├── confidence
├── sensitivity
├── enabled
├── createdAt
└── updatedAt
```

## 10. 技术架构

### 10.1 分层

```text
app
├── ui
│   ├── chat
│   ├── agent
│   ├── memory
│   └── settings
├── domain
│   ├── chat
│   ├── agent
│   ├── memory
│   ├── provider
│   └── harness
├── data
│   ├── local
│   ├── remote
│   └── secure
└── core
    ├── design
    ├── navigation
    ├── network
    └── logging
```

### 10.2 推荐技术栈

- Jetpack Compose：UI。
- Material 3：组件和主题。
- Navigation Compose：导航。
- ViewModel + Kotlin Coroutines + Flow：状态管理。
- Room：结构化本地数据。
- DataStore：非敏感配置。
- Android Keystore + Jetpack Security Crypto：敏感信息。
- OkHttp：SSE 流式响应。
- Kotlin Serialization：API DTO 和 Agent event payload。
- WorkManager：后续可用于用户明确开启的后台任务。

### 10.3 UI 状态模式

推荐每个页面使用单向数据流：

```kotlin
data class ChatUiState(
    val conversations: List<ConversationItem>,
    val currentConversation: ConversationDetail?,
    val input: String,
    val isStreaming: Boolean,
    val selectedMode: ChatMode,
    val error: UiError?
)

sealed interface ChatIntent {
    data class SendMessage(val text: String) : ChatIntent
    data object StopGeneration : ChatIntent
    data class SelectConversation(val id: String) : ChatIntent
    data class SwitchMode(val mode: ChatMode) : ChatIntent
}
```

## 11. 工具系统设计

### 11.1 Tool Registry

工具以注册表形式提供给 Agent Runtime。

```kotlin
interface AgentTool {
    val name: String
    val description: String
    val riskLevel: RiskLevel
    suspend fun execute(input: ToolInput): ToolResult
}
```

### 11.2 MVP 工具

- memory_search：检索记忆。
- memory_write_candidate：提交待审核记忆。
- summarize_conversation：生成会话摘要。
- current_datetime：获取当前时间。
- calculator：简单计算。

### 11.3 后续工具

- webpage_read：读取用户提供 URL。
- file_read：读取用户明确选择的文件。
- calendar_create_event：创建日程，需要系统权限和用户确认。
- share_to_android：调用 Android share sheet。
- notification_reminder：创建提醒，需要用户确认。

风险等级：

- Low：纯本地、无副作用，例如计算。
- Medium：读取用户选择的数据，例如读取文件。
- High：产生外部副作用，例如创建日程、发送消息、联网读取敏感 URL。

## 12. 安全与隐私

### 12.1 Secret 管理

- API Key 不进入日志、不进入 crash report、不进入导出文件。
- UI 和错误消息只显示 Key 后 4 位。
- Key 使用 Android Keystore 派生密钥加密保存。
- 清除 Profile 时同步清除 secret。

### 12.2 Prompt Injection 防护

Agent 可能读取网页、文件或历史记忆。所有非用户直接输入都标注来源，并在 Prompt 中降权处理。

策略：

- 工具返回内容作为 untrusted observation。
- 工具内容不能直接修改系统设置、记忆、权限。
- 记忆写入必须经过 Memory Extractor 和策略检查。
- 高风险工具调用必须绑定到当前用户批准的具体 action。

### 12.3 记忆安全

- 每条记忆都有来源。
- 高敏记忆默认禁用自动注入。
- 支持按类型关闭记忆。
- 支持一键清除所有记忆。
- 支持导出前脱敏预览。

## 13. 错误处理

常见错误：

- API Key 无效。
- Base URL 不可达。
- 模型不存在。
- 流式响应中断。
- 上下文过长。
- Tool 执行失败。
- Agent 达到最大步数。

UI 要求：

- 错误原因用短句说明。
- 给出恢复动作：重试、编辑配置、切换模型、压缩上下文、继续执行。
- 技术详情放在可展开区域。

## 14. 可访问性与适配

- 支持 TalkBack，交互元素有语义描述。
- 字体遵循系统字号。
- 长文本不裁切，消息内容可滚动。
- 横屏和平板使用双栏布局：左侧会话/任务列表，右侧详情。
- 输入区避免被 IME 遮挡。
- 支持深色模式和动态色。

## 15. MVP 范围

第一阶段建议实现：

- Provider Profile：增删改、测试连接、Key 加密保存。
- Chat 模式：会话列表、流式聊天、重试、停止。
- Agent 模式：ReAct Agent，支持最多 8 步，工具仅限本地低风险工具。
- Memory：用户偏好、会话摘要、待审核写入、搜索和删除。
- Settings：主题、默认模型、数据清除。

第二阶段：

- Planning Agent。
- Plan 编辑和步骤时间线。
- 向量检索。
- 更多工具和权限确认。
- 导入/导出配置和数据。

第三阶段：

- 多 Profile Agent 模板。
- 可选云同步。
- 用户明确开启的 WorkManager 后台任务。
- 插件化工具 SDK。

## 16. 测试计划

单元测试：

- Provider 配置校验。
- API Key 脱敏。
- Harness 决策校验、预算停止、事件持久化。
- ReAct 最大步数和停止条件。
- Memory deduplicate 和 sensitivity 分类。
- Prompt 构建不泄漏 secret。

集成测试：

- Mock OpenAI endpoint 流式响应。
- Agent 工具调用事件记录。
- AgentRun 暂停后恢复执行。
- Room migration。
- Key 加密读写。

UI 测试：

- 首次配置流程。
- 普通聊天发送和停止。
- Agent Run 暂停、继续、终止。
- 记忆审核接受/拒绝。
- 深色模式和平板双栏布局。

安全测试：

- 工具输出中的 prompt injection 不能越权调用工具。
- 导出数据不包含 API Key。
- crash/log 不包含 secret。
- 高风险工具必须有用户确认事件。

## 17. 关键产品决策

- Agent 模式放在聊天页可快速切换，同时保留独立 Agent 页面做任务管理。
- Agent Harness 作为独立运行时层，UI 不直接管理 Prompt、工具执行和权限。
- 记忆默认透明可审计，不做黑盒长期记忆。
- MVP 先做低风险本地工具，把工具权限模型打牢后再接外部副作用工具。
- Planning Agent 晚于 ReAct Agent 实现，因为它需要更完整的计划 UI、重规划和用户编辑能力。
- Android 端默认本地优先，不把 Hermes Agent 式常驻自主执行作为默认能力。

## 18. 参考资料

- Android skill：Material Design 3、Jetpack Compose、Navigation Compose、动态色、适配和可访问性约束。
- Hermes Agent 相关公开讨论和论文线索：个人 Agent 系统常见的 Profile、Tools & Memory、长期运行、记忆隔离和权限闸门设计。参考：
  - Sleeper Channels and Provenance Gates: https://arxiv.org/abs/2605.13471
  - Channel Fracture: https://arxiv.org/abs/2606.04896
  - OpenJarvis: https://arxiv.org/abs/2605.17172
- ReAct 思路：Reason + Act 循环，但产品 UI 只展示可审计摘要，不展示隐藏推理链。参考：https://arxiv.org/abs/2210.03629
- Agent Harness 相关设计线索：harness 会影响上下文、工具、状态、权限、追踪和恢复，因此应作为模型之外的独立能力评估和实现。参考：
  - Harness-Bench: https://arxiv.org/abs/2605.27922
  - SafeHarness: https://arxiv.org/abs/2604.13630
  - HarnessFix: https://arxiv.org/abs/2606.06324

## 19. 后续开发拆分建议

如果进入开发阶段，建议按下面顺序拆任务：

1. 初始化 Android 工程：Compose、Material 3、Navigation、Room、DataStore、基础主题。
2. 实现 Provider Profile：配置页、Key 加密、连接测试、默认模型选择。
3. 实现 Chat MVP：会话列表、消息流、SSE 流式响应、停止/重试。
4. 实现本地数据层：Conversation、Message、ProviderProfile、基础 migration。
5. 实现 Agent Harness：AgentRun 状态流、AgentEvent 持久化、预算、暂停/恢复/取消。
6. 实现 ReAct Runtime：AgentPolicy、最大步数、低风险工具注册表、结构化决策解析。
7. 实现 Memory MVP：会话摘要、偏好记忆、审核队列、检索注入。
8. 实现 Agent UI：任务时间线、工具事件展开、暂停/继续/终止。
9. 实现 Planning Agent：计划生成、计划编辑、步骤执行、重规划。
10. 补齐安全测试：secret 脱敏、prompt injection 权限闸门、导出数据检查。
