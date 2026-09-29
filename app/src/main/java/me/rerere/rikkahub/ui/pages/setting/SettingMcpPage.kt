package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileDownload
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MessageBlocked
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val navController = LocalNavController.current
    var orderedMcpConfigs by remember(mcpConfigs) { mutableStateOf(mcpConfigs) }
    var showImportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_mcp_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            showImportDialog = true
                        }
                    ) {
                        Icon(HugeIcons.FileImport, null)
                    }
                    IconButton(
                        onClick = {
                            val duplicateNames = orderedMcpConfigs
                                .map { it.commonOptions.name }
                                .filter { it.isNotBlank() }
                                .groupingBy { it }
                                .eachCount()
                                .filterValues { it > 1 }
                                .keys
                            if (duplicateNames.isNotEmpty()) {
                                toaster.show(
                                    "MCP 服务名称重复，无法导出：${duplicateNames.joinToString()}",
                                    type = ToastType.Error,
                                )
                            } else {
                                val json = buildMcpServersJson(orderedMcpConfigs)
                                context.writeClipboardText(json)
                                toaster.show(
                                    context.getString(R.string.setting_mcp_page_export_copied),
                                    type = ToastType.Success,
                                )
                            }
                        }
                    ) {
                        Icon(HugeIcons.FileDownload, null)
                    }
                    IconButton(
                        onClick = {
                            // 名称为空的配置不会连接，先加进列表，具体配置到详情页里填
                            val config = McpServerConfig.StreamableHTTPServer()
                            vm.updateSettings { latest ->
                                latest.copy(mcpServers = latest.mcpServers + config)
                            }
                            navController.navigate(Screen.SettingMcpDetail(serverId = config.id.toString()))
                        }
                    ) {
                        Icon(HugeIcons.Add01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val fromIndex = from.index
            val toIndex = to.index
            if (fromIndex in orderedMcpConfigs.indices && toIndex in orderedMcpConfigs.indices) {
                orderedMcpConfigs = orderedMcpConfigs.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
            }
        }
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        val layoutDirection = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                state = lazyListState,
            ) {
                items(orderedMcpConfigs, key = { it.id }) { mcpConfig ->
                    ReorderableItem(
                        state = reorderableState,
                        key = mcpConfig.id,
                    ) { isDragging ->
                        McpServerItem(
                            item = mcpConfig,
                            onEdit = {
                                navController.navigate(Screen.SettingMcpDetail(serverId = mcpConfig.id.toString()))
                            },
                            onDelete = {
                                vm.updateSettings { latest ->
                                    latest.copy(
                                        mcpServers = orderedMcpConfigs.filter { it.id != mcpConfig.id }
                                    )
                                }
                            },
                            modifier = Modifier
                                .animateItem()
                                .then(
                                    longPressReorder(
                                        isDragging = isDragging,
                                        onDragStopped = {
                                            vm.updateSettings { it.copy(mcpServers = orderedMcpConfigs) }
                                        },
                                    )
                                ),
                        )
                    }
                }
            }

            if (orderedMcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            onImport = { newConfigs ->
                val existingIds = orderedMcpConfigs.map { it.commonOptions.name }.toSet()
                val toAdd = newConfigs.filter { it.commonOptions.name.isNotBlank() && it.commonOptions.name !in existingIds }
                vm.updateSettings { it.copy(mcpServers = orderedMcpConfigs + toAdd) }
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun McpServerItem(
    item: McpServerConfig,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val status by mcpManager.getStatus(item).collectAsStateWithLifecycle(McpStatus.Idle)
    val protocolVersion = if (status == McpStatus.Connected) mcpManager.getProtocolVersion(item) else null
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf<McpStatus.Error?>(null) }

    errorDetail?.let { error ->
        val context = LocalContext.current
        val fullText = error.detail ?: error.message
        AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { Text(item.commonOptions.name.ifBlank { "MCP" }) },
            text = {
                SelectionContainer {
                    Text(
                        text = fullText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.writeClipboardText(fullText)
                        errorDetail = null
                    }
                ) {
                    Text(stringResource(R.string.copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { errorDetail = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    Card(
        onClick = { onEdit(item) },
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val canReconnect = item.commonOptions.enable &&
                status != McpStatus.Connecting &&
                status !is McpStatus.Reconnecting &&
                status != McpStatus.Authorizing
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canReconnect) {
                        scope.launch { mcpManager.reconnect(item) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (status) {
                    McpStatus.Idle -> Icon(
                        if (item.commonOptions.skipStartupInitialization) {
                            HugeIcons.McpServer
                        } else {
                            HugeIcons.MessageBlocked
                        },
                        stringResource(R.string.setting_mcp_page_sync_server),
                    )
                    McpStatus.Connecting -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    McpStatus.Connected -> Icon(
                        HugeIcons.McpServer,
                        stringResource(R.string.setting_mcp_page_sync_server),
                    )
                    is McpStatus.Reconnecting -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    is McpStatus.Error -> Icon(
                        HugeIcons.AlertCircle,
                        stringResource(R.string.setting_mcp_page_sync_server),
                    )
                    McpStatus.NeedsAuthorization -> Icon(
                        HugeIcons.AlertCircle,
                        stringResource(R.string.setting_mcp_page_sync_server),
                    )
                    McpStatus.Authorizing -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item.commonOptions.name.ifBlank { "MCP" },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val dotColor =
                        if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .drawWithContent {
                                drawCircle(
                                    color = dotColor
                                )
                            }
                    )
                    if (protocolVersion != null) {
                        Text(
                            text = "MCP $protocolVersion",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tag(type = TagType.SUCCESS) {
                        when (item) {
                            is McpServerConfig.SseTransportServer -> Text("SSE (unsupported)")
                            is McpServerConfig.StreamableHTTPServer -> Text("Streamable HTTP")
                        }
                    }
                    if (item.commonOptions.skipStartupInitialization) {
                        Tag(type = TagType.INFO) {
                            Text(stringResource(R.string.setting_mcp_page_skip_startup_initialization))
                        }
                    }
                }
                if (status is McpStatus.Error) {
                    val error = status as McpStatus.Error
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { errorDetail = error },
                    )
                }
                if (status == McpStatus.NeedsAuthorization) {
                    val context = LocalContext.current
                    Text(
                        text = "需要 OAuth 授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { mcpManager.startAuthorization(item, context) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text("OAuth 授权")
                    }
                }
                if (status == McpStatus.Authorizing) {
                    Text(
                        text = "正在授权，请在浏览器中完成…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(
                        onClick = { mcpManager.cancelAuthorization(item) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text("取消授权")
                    }
                }
            }

            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            )
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, item.commonOptions.name))
    }
}

private fun parseMcpServersFromJson(json: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(json).jsonObject
    val mcpServers = root["mcpServers"]?.jsonObject ?: return emptyList()
    return mcpServers.entries.mapNotNull { (name, element) ->
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "streamable_http"
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val disableToolNamePrefix = obj["disableToolNamePrefix"]?.jsonPrimitive?.booleanOrNull ?: false
        val skipStartupInitialization =
            obj["skipStartupInitialization"]?.jsonPrimitive?.booleanOrNull ?: false
        val terminalOutputEnabled = obj["terminalOutputEnabled"]?.jsonPrimitive?.booleanOrNull ?: false
        val enable = obj["enable"]?.jsonPrimitive?.booleanOrNull ?: true
        val headers = obj["headers"]?.jsonObject?.entries?.map { (k, v) ->
            k to (v.jsonPrimitive.contentOrNull ?: "")
        } ?: emptyList()
        val tools = obj["toolOptions"]?.jsonArray?.mapNotNull toolOptions@{ toolElement ->
            val tool = toolElement.jsonObject
            val toolName = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@toolOptions null
            McpTool(
                name = toolName,
                enable = tool["enable"]?.jsonPrimitive?.booleanOrNull ?: true,
                needsApproval = tool["needsApproval"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } ?: emptyList()
        val commonOptions = McpCommonOptions(
            enable = enable,
            name = name,
            skipStartupInitialization = skipStartupInitialization,
            terminalOutputEnabled = terminalOutputEnabled,
            disableToolNamePrefix = disableToolNamePrefix,
            headers = headers,
            tools = tools,
        )
        when (type) {
            "sse" -> null
            else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
        }
    }
}

private fun buildMcpServersJson(configs: List<McpServerConfig>): String {
    val mcpServersObj = buildJsonObject {
        configs.forEach { config ->
            put(
                config.commonOptions.name.ifBlank { config.id.toString() },
                buildJsonObject {
                    put("type", when (config) {
                        is McpServerConfig.SseTransportServer -> "sse"
                        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
                    })
                    put("url", when (config) {
                        is McpServerConfig.SseTransportServer -> config.url
                        is McpServerConfig.StreamableHTTPServer -> config.url
                    })
                    if (!config.commonOptions.enable) {
                        put("enable", false)
                    }
                    if (config.commonOptions.skipStartupInitialization) {
                        put("skipStartupInitialization", true)
                    }
                    if (config.commonOptions.terminalOutputEnabled) {
                        put("terminalOutputEnabled", true)
                    }
                    if (config.commonOptions.disableToolNamePrefix) {
                        put("disableToolNamePrefix", true)
                    }
                    if (config.commonOptions.headers.isNotEmpty()) {
                        put(
                            "headers",
                            buildJsonObject {
                                config.commonOptions.headers.forEach { (key, value) ->
                                    put(key, value)
                                }
                            }
                        )
                    }
                    if (config.commonOptions.tools.isNotEmpty()) {
                        put(
                            "toolOptions",
                            buildJsonArray {
                                config.commonOptions.tools.forEach { tool ->
                                    add(
                                        buildJsonObject {
                                            put("name", tool.name)
                                            if (!tool.enable) put("enable", false)
                                            if (tool.needsApproval) put("needsApproval", true)
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
            )
        }
    }
    val root = buildJsonObject {
        put("mcpServers", mcpServersObj)
    }
    return JsonInstantPretty.encodeToString(JsonElement.serializer(), root)
}

@Composable
private fun McpImportModal(
    onDismiss: () -> Unit,
    onImport: (List<McpServerConfig>) -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val noValidConfigMsg = stringResource(R.string.setting_mcp_page_import_no_valid_config)
    val parseErrorMsg = stringResource(R.string.setting_mcp_page_import_parse_error)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.setting_mcp_page_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setting_mcp_page_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("{ \"mcpServers\": { ... } }") },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        try {
                            val configs = parseMcpServersFromJson(jsonText.trim())
                            if (configs.isEmpty()) {
                                errorMessage = noValidConfigMsg
                            } else {
                                onImport(configs)
                            }
                        } catch (e: Exception) {
                            errorMessage = parseErrorMsg.format(e.message ?: "")
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_mcp_page_import_confirm))
                }
            }
        }
    }
}
