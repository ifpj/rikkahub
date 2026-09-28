package me.rerere.rikkahub.data.ai.mcp

import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import java.io.Closeable
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.util.Base64

/** JNI boundary for the pinned rmcp 3.5.0 client. All blocking calls run off the UI thread. */
@Keep
internal object RmcpNative {
    init {
        System.loadLibrary("rikkahub_rmcp")
    }

    external fun connect(url: String, name: String, headersJson: String, certsJson: String): Long
    external fun listTools(id: Long): String
    external fun protocolVersion(id: Long): String
    external fun beginCall(id: Long, name: String, argumentsJson: String): Long
    external fun pollCall(callId: Long): String?
    external fun cancelCall(callId: Long)
    external fun isClosed(id: Long): Boolean
    external fun close(id: Long)
}

internal data class RmcpTool(
    val name: String,
    val description: String?,
    val inputSchema: InputSchema,
)

internal class RmcpClient private constructor(private val id: Long) : Closeable {
    companion object {
        private val trustedCertificates: String by lazy {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            val certificates = factory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .flatMap { it.acceptedIssuers.asList() }
                .map { kotlinx.serialization.json.JsonPrimitive(Base64.encodeToString(it.encoded, Base64.NO_WRAP)) }
            JsonArray(certificates).toString()
        }

        suspend fun connect(config: McpServerConfig): RmcpClient = withContext(Dispatchers.IO) {
            require(config is McpServerConfig.StreamableHTTPServer) {
                "Legacy standalone SSE transport is no longer supported; use Streamable HTTP"
            }
            val headers = config.resolvedHeaders().map { (name, value) ->
                JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(name), kotlinx.serialization.json.JsonPrimitive(value)))
            }
            RmcpClient(RmcpNative.connect(
                config.url, config.commonOptions.name, JsonArray(headers).toString(), trustedCertificates,
            ))
        }
    }

    suspend fun listTools(): List<RmcpTool> = withContext(Dispatchers.IO) {
        Json.parseToJsonElement(RmcpNative.listTools(id)).jsonArray.map { element ->
            val tool = element.jsonObject
            val schema = tool["inputSchema"]?.jsonObject ?: JsonObject(emptyMap())
            RmcpTool(
                name = tool.getValue("name").jsonPrimitive.content,
                description = tool["description"]?.jsonPrimitive?.contentOrNull,
                inputSchema = InputSchema.Obj(
                    properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap()),
                    required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                ),
            )
        }
    }

    suspend fun callTool(name: String, args: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val callId = RmcpNative.beginCall(id, name, args.toString())
        try {
            while (true) {
                val result = RmcpNative.pollCall(callId)
                if (result != null) return@withContext Json.parseToJsonElement(result).jsonObject
                delay(100)
            }
            error("Unreachable")
        } finally {
            RmcpNative.cancelCall(callId)
        }
    }

    fun isClosed(): Boolean = RmcpNative.isClosed(id)

    fun protocolVersion(): String = RmcpNative.protocolVersion(id)

    override fun close() = RmcpNative.close(id)
}
