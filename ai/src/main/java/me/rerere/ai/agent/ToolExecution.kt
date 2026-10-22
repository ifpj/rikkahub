package me.rerere.ai.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

fun validateRequiredToolArguments(tool: Tool, args: JsonElement) {
    val required = (tool.parameters() as? InputSchema.Obj)?.required.orEmpty()
    if (required.isEmpty()) return
    val values = args as? JsonObject ?: throw IllegalArgumentException("tool arguments must be an object")
    required.firstOrNull { it !in values || values[it] == JsonNull }?.let {
        throw IllegalArgumentException("missing required param: $it")
    }
}

fun toolExecutionErrorResult(error: Throwable): UIMessagePart.Text {
    val type = error.javaClass.simpleName.ifBlank { "Exception" }
    val detail = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty().take(300)
    return UIMessagePart.Text(buildJsonObject {
        put("isError", true)
        put("content", if (detail.isEmpty()) type else "$type: $detail")
    }.toString())
}

fun List<UIMessage>.withToolDisplayMetadata(metadataByToolName: Map<String, JsonObject>): List<UIMessage> {
    if (metadataByToolName.isEmpty()) return this
    val current = lastOrNull() ?: return this
    val parts = current.parts.map { part ->
        if (part !is UIMessagePart.Tool) return@map part
        val metadata = metadataByToolName[part.toolName] ?: return@map part
        val merged = JsonObject(part.metadata.orEmpty() + metadata)
        if (merged == part.metadata) part else part.copy(metadata = merged)
    }
    return if (parts == current.parts) this else dropLast(1) + current.copy(parts = parts)
}
