package com.lacknb.agentchat.feature.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lacknb.agentchat.core.memory.MemoryItem
import com.lacknb.agentchat.core.memory.MemoryRepository
import com.lacknb.agentchat.core.memory.MemorySensitivity
import com.lacknb.agentchat.core.memory.MemoryType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementRoute(
    repository: MemoryRepository,
    onBackToChat: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf<MemoryType?>(null) }
    var editorMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var previewMemory by remember { mutableStateOf<MemoryItem?>(null) }
    var pendingDelete by remember { mutableStateOf<MemoryItem?>(null) }
    var status by rememberSaveable { mutableStateOf("记忆会保存在本机 Room 数据库") }
    val memories by repository.observeSearch(query, selectedType).collectAsState(initial = emptyList())
    val allMemories by repository.memories.collectAsState(initial = emptyList())

    fun copyMemory(memory: MemoryItem) {
        clipboardManager.setText(AnnotatedString(memory.content))
        status = "已复制「${memory.title}」"
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "记忆库",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${allMemories.size} 条记忆 · Room 本机存储",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackToChat) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            editorMemory = null
                            showEditor = true
                        },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "新增记忆")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MemoryStatus(
                status = status,
                onCreate = {
                    editorMemory = null
                    showEditor = true
                },
                modifier = Modifier.padding(top = 10.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                label = { Text("搜索标题、内容或标签") },
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                item {
                    MemoryFilterChip(
                        label = "全部",
                        selected = selectedType == null,
                        onClick = { selectedType = null },
                    )
                }
                items(MemoryType.values(), key = { it.name }) { type ->
                    MemoryFilterChip(
                        label = type.displayName,
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                    )
                }
            }

            if (memories.isEmpty()) {
                EmptyMemoryState(
                    hasAnyMemory = allMemories.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(memories, key = { it.id }) { memory ->
                        MemoryRow(
                            memory = memory,
                            onPreview = { previewMemory = memory },
                            onCopy = { copyMemory(memory) },
                            onEdit = {
                                editorMemory = memory
                                showEditor = true
                            },
                            onDelete = { pendingDelete = memory },
                        )
                    }
                }
            }
        }
    }

    if (showEditor) {
        MemoryEditorDialog(
            memory = editorMemory,
            onDismiss = { showEditor = false },
            onSave = { title, content, type, tags, sensitivity ->
                scope.launch {
                    status = runCatching {
                        val current = editorMemory
                        if (current == null) {
                            repository.createMemory(
                                title = title,
                                content = content,
                                type = type,
                                tags = tags,
                                sensitivity = sensitivity,
                            )
                            "记忆已新增"
                        } else {
                            repository.updateMemory(
                                id = current.id,
                                title = title,
                                content = content,
                                type = type,
                                tags = tags,
                                sensitivity = sensitivity,
                            )
                            "记忆已更新"
                        }
                    }.getOrElse { error -> error.message ?: "保存失败" }
                    showEditor = false
                }
            },
        )
    }

    previewMemory?.let { memory ->
        MemoryPreviewDialog(
            memory = memory,
            onDismiss = { previewMemory = null },
            onCopy = { copyMemory(memory) },
            onEdit = {
                previewMemory = null
                editorMemory = memory
                showEditor = true
            },
        )
    }

    pendingDelete?.let { memory ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除记忆") },
            text = { Text("确定删除「${memory.title}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deleteMemory(memory.id)
                            status = "记忆已删除"
                            pendingDelete = null
                        }
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun MemoryStatus(
    status: String,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = status,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FilledTonalButton(onClick = onCreate, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("新增")
            }
        }
    }
}

@Composable
private fun MemoryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun MemoryRow(
    memory: MemoryItem,
    onPreview: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPreview)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = memory.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = memory.type.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = memory.sensitivity.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (memory.tags.isNotEmpty()) {
                Text(
                    text = memory.tags.joinToString(prefix = "#", separator = " #"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MemoryPreviewDialog(
    memory: MemoryItem,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = memory.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${memory.type.displayName} · ${memory.sensitivity.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = memory.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (memory.tags.isNotEmpty()) {
                        Text(
                            text = memory.tags.joinToString(prefix = "#", separator = " #"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("编辑")
                }
                Button(onClick = onCopy, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("复制")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun EmptyMemoryState(
    hasAnyMemory: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (hasAnyMemory) "没有匹配的记忆" else "暂无记忆",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryEditorDialog(
    memory: MemoryItem?,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        content: String,
        type: MemoryType,
        tags: List<String>,
        sensitivity: MemorySensitivity,
    ) -> Unit,
) {
    var title by rememberSaveable(memory?.id) { mutableStateOf(memory?.title.orEmpty()) }
    var content by rememberSaveable(memory?.id) { mutableStateOf(memory?.content.orEmpty()) }
    var tagsText by rememberSaveable(memory?.id) { mutableStateOf(memory?.tags.orEmpty().joinToString(", ")) }
    var type by rememberSaveable(memory?.id) { mutableStateOf(memory?.type ?: MemoryType.Note) }
    var sensitivity by rememberSaveable(memory?.id) { mutableStateOf(memory?.sensitivity ?: MemorySensitivity.Low) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (memory == null) "新增记忆" else "编辑记忆") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    minLines = 6,
                    shape = RoundedCornerShape(8.dp),
                )
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = { Text("标签，用逗号分隔") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
                Text(
                    text = "类型",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MemoryType.values(), key = { it.name }) { option ->
                        MemoryFilterChip(
                            label = option.displayName,
                            selected = type == option,
                            onClick = { type = option },
                        )
                    }
                }
                Text(
                    text = "敏感级别",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MemorySensitivity.values(), key = { it.name }) { option ->
                        MemoryFilterChip(
                            label = option.displayName,
                            selected = sensitivity == option,
                            onClick = { sensitivity = option },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        title,
                        content,
                        type,
                        tagsText.split(",", "，", "、").map { it.trim() }.filter { it.isNotBlank() },
                        sensitivity,
                    )
                },
                enabled = content.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
