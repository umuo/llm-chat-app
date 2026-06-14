package com.lacknb.agentchat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lacknb.agentchat.core.network.mcp.McpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpPreviewDialog(
    mcpUrl: String,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableStateOf(0) }
    
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var tools by remember { mutableStateOf<List<McpClient.McpTool>>(emptyList()) }
    var resources by remember { mutableStateOf<List<McpClient.McpResource>>(emptyList()) }
    var prompts by remember { mutableStateOf<List<McpClient.McpPrompt>>(emptyList()) }

    LaunchedEffect(mcpUrl) {
        isLoading = true
        errorMessage = null
        try {
            val client = McpClient()
            client.connect(mcpUrl)
            
            // Fetch everything in parallel or sequentially
            tools = try { client.getTools() } catch (e: Exception) { emptyList() }
            resources = try { client.getResources() } catch (e: Exception) { emptyList() }
            prompts = try { client.getPrompts() } catch (e: Exception) { emptyList() }
            
            if (tools.isEmpty() && resources.isEmpty() && prompts.isEmpty()) {
                errorMessage = "连接成功，但服务器未返回任何可用的 Tools, Resources 或 Prompts。"
            }
        } catch (e: Exception) {
            errorMessage = "连接失败: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(0.8f) // Occupy 80% of screen height
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MCP Server 预览",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
            
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("正在连接服务器并获取能力列表...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
            } else {
                val tabs = listOf("Tools (${tools.size})", "Resources (${resources.size})", "Prompts (${prompts.size})")
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
                
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    when (selectedTab) {
                        0 -> ToolsList(tools)
                        1 -> ResourcesList(resources)
                        2 -> PromptsList(prompts)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsList(tools: List<McpClient.McpTool>) {
    if (tools.isEmpty()) {
        EmptyState("该服务器没有提供任何 Tools")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tools) { tool ->
            ItemCard(title = tool.name, description = tool.description) {
                Text("参数约束:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = tool.inputSchema.toString(2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ResourcesList(resources: List<McpClient.McpResource>) {
    if (resources.isEmpty()) {
        EmptyState("该服务器没有提供任何 Resources")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(resources) { res ->
            ItemCard(title = res.name, description = res.description) {
                Text("URI: ${res.uri}", style = MaterialTheme.typography.bodySmall)
                if (res.mimeType.isNotBlank()) {
                    Text("Type: ${res.mimeType}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PromptsList(prompts: List<McpClient.McpPrompt>) {
    if (prompts.isEmpty()) {
        EmptyState("该服务器没有提供任何 Prompts")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(prompts) { prompt ->
            ItemCard(title = prompt.name, description = prompt.description) {
                if (prompt.arguments.isNotEmpty()) {
                    Text("参数列表:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    prompt.arguments.forEach { arg ->
                        Text("- ${arg.name} (Required: ${arg.required}): ${arg.description}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("无参数", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ItemCard(title: String, description: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
