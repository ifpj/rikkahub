package me.rerere.rikkahub.data.ai.mcp

import android.util.Base64
import androidx.annotation.Keep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resumeWithException

@Keep
internal interface RmcpCallCallback {
    @Keep
    fun onComplete(resultJson: String?, error: String?)
}

@Keep
internal interface RmcpCloseCallback {
    @Keep
    fun onClosed()
}

/** JNI boundary for the pinned rmcp 3.5.0 client. All blocking calls run off the UI thread. */
@Keep
internal object RmcpNative {
    init {
        System.loadLibrary("rikkahub_rmcp")
    }

    external fun connect(
        url: String,
        name: String,
        headersJson: String,
        certsJson: String,
        onClosed: RmcpCloseCallback,
    ): Long
    external fun listTools(id: Long): String
    external fun protocolVersion(id: Long): String
    external fun beginCall(id: Long, name: String, argumentsJson: String, onComplete: RmcpCallCallback): Long
    external fun cancelCall(callId: Long)
    external fun close(id: Long)
}

internal data class RmcpTool(
    val name: String,
    val description: String?,
    val inputSchema: InputSchema,
)

internal class RmcpClient private constructor(
    private val id: Long,
    private val closed: CompletableDeferred<Unit>,
) : Closeable {
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
            val closed = CompletableDeferred<Unit>()
            val callback = object : RmcpCloseCallback {
                override fun onClosed() {
                    closed.complete(Unit)
                }
            }
            RmcpClient(
                RmcpNative.connect(
                    config.url, config.commonOptions.name, JsonArray(headers).toString(), trustedCertificates,
                    callback,
                ),
                closed,
            )
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
        suspendCancellableCoroutine { continuation ->
            if (!continuation.isActive) return@suspendCancellableCoroutine
            val callback = object : RmcpCallCallback {
                override fun onComplete(resultJson: String?, error: String?) {
                    val result = runCatching {
                        if (error != null) throw IllegalStateException(error)
                        Json.parseToJsonElement(requireNotNull(resultJson)).jsonObject
                    }
                    continuation.resumeWith(result)
                }
            }
            val callId = try {
                RmcpNative.beginCall(id, name, args.toString(), callback)
            } catch (error: Exception) {
                continuation.resumeWithException(error)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { RmcpNative.cancelCall(callId) }
        }
    }

    suspend fun awaitClosed() = closed.await()

    fun protocolVersion(): String = RmcpNative.protocolVersion(id)

    override fun close() {
        try {
            RmcpNative.close(id)
        } finally {
            closed.complete(Unit)
        }
    }
}
