package com.lacknb.agentchat.tool

import com.lacknb.agentchat.core.harness.RiskLevel
import com.lacknb.agentchat.core.network.ChatCompletionFunction
import com.lacknb.agentchat.core.network.ChatCompletionTool
import org.json.JSONObject
import java.io.File

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

class ReadFileTool(private val agentWorkspace: File) : AgentTool {
    override val name: String = "read_file"
    override val riskLevel: RiskLevel = RiskLevel.Low

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
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
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
        require(file.exists() && file.isFile) { "文件不存在：${args.getString("path")}" }
        return JSONObject()
            .put("ok", true)
            .put("path", args.getString("path"))
            .put("content", file.readText().take(12_000))
            .toString()
    }
}

class WriteFileTool(private val agentWorkspace: File) : AgentTool {
    override val name: String = "write_file"
    override val riskLevel: RiskLevel = RiskLevel.Medium

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
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
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val file = resolveAgentWorkspaceFile(agentWorkspace, args.getString("path"))
        file.parentFile?.mkdirs()
        file.writeText(args.getString("content"))
        return JSONObject()
            .put("ok", true)
            .put("path", args.getString("path"))
            .put("bytes", file.length())
            .toString()
    }
}

class EditFileTool(private val agentWorkspace: File) : AgentTool {
    override val name: String = "edit_file"
    override val riskLevel: RiskLevel = RiskLevel.Medium

    override val declaration: ChatCompletionTool = ChatCompletionTool(
        function = ChatCompletionFunction(
            name = name,
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
        )
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
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
        return JSONObject()
            .put("ok", true)
            .put("path", args.getString("path"))
            .put("changed", true)
            .toString()
    }
}
