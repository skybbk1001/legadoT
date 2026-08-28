package io.legado.app.service

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.startService
import io.legado.app.utils.stopService
import io.legado.app.utils.toastOnUi
import io.legado.app.web.mcp.McpToolServer
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import splitties.init.appCtx
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App 内直出的 MCP server(Streamable HTTP,端点 /mcp)。
 * 与 WebService 相互独立:工具直调内部,不依赖 Web 服务。
 * 绑定 0.0.0.0 供局域网直连,所有 /mcp 请求需携带 Authorization: Bearer token。
 */
class McpService : BaseService() {

    companion object {
        private const val TOKEN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val TOKEN_LENGTH = 32

        var isRun = false
        var hostAddress = ""

        fun start(context: Context) {
            context.startService<McpService>()
        }

        fun stop(context: Context) {
            context.stopService<McpService>()
        }

        /** 读取 MCP token,不存在则生成并持久化。 */
        fun ensureToken(): String {
            var token = appCtx.getPrefString(PreferKey.mcpToken).orEmpty()
            if (token.isEmpty()) {
                token = generateToken()
                appCtx.putPrefString(PreferKey.mcpToken, token)
            }
            return token
        }

        /** 重新生成 token 并持久化,鉴权在每次请求时读取,立即生效。 */
        fun regenerateToken(): String = generateToken().also {
            appCtx.putPrefString(PreferKey.mcpToken, it)
        }

        private fun generateToken(): String {
            val random = SecureRandom()
            return buildString(TOKEN_LENGTH) {
                repeat(TOKEN_LENGTH) {
                    append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)])
                }
            }
        }
    }

    private var engine: EmbeddedServer<*, *>? = null
    private var notificationList = mutableListOf(appCtx.getString(R.string.service_starting))
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            val addressList = NetworkUtils.getLocalIPAddress()
            notificationList.clear()
            if (addressList.any()) {
                notificationList.addAll(addressList.map { address ->
                    "http://${address.hostAddress}:${getPort()}/mcp"
                })
                hostAddress = notificationList.first()
            } else {
                hostAddress = getString(R.string.network_connection_unavailable)
                notificationList.add(hostAddress)
            }
            startForegroundNotification()
            postEvent(EventBus.MCP_SERVICE, hostAddress)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> stopSelf()
            "copyHostAddress" -> sendToClip(hostAddress)
            "copyToken" -> {
                sendToClip(ensureToken())
                toastOnUi(R.string.copy_complete)
            }
            else -> upMcpServer()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        networkChangedListener.unRegister()
        isRun = false
        engine?.stop(500, 1000)
        engine = null
        postEvent(EventBus.MCP_SERVICE, "")
    }

    private fun upMcpServer() {
        engine?.stop(500, 1000)
        engine = null
        val addressList = NetworkUtils.getLocalIPAddress()
        if (addressList.any()) {
            val port = getPort()
            try {
                val tokenGenerated = AppConfig.mcpToken.isEmpty()
                ensureToken()
                if (tokenGenerated) {
                    toastOnUi(R.string.mcp_token_generated)
                }
                engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    // Bearer token 鉴权:手机绑定 0.0.0.0 供局域网直连,必须有最小防护
                    intercept(ApplicationCallPipeline.Call) {
                        if (!call.request.path().startsWith("/mcp")) return@intercept
                        val expected = "Bearer ${AppConfig.mcpToken}"
                        val provided = call.request.header(HttpHeaders.Authorization)
                        val authorized = AppConfig.mcpToken.isNotEmpty() && provided != null &&
                            MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
                        if (!authorized) {
                            call.respondText(
                                """{"jsonrpc":"2.0","error":{"code":-32001,"message":"MCP 鉴权失败:Authorization 头需为 Bearer <token>(设置-其它-MCP Token 查看/复制)"},"id":null}""",
                                status = HttpStatusCode.Unauthorized,
                                contentType = ContentType.Application.Json,
                            )
                            finish()
                        }
                    }
                    // SDK 默认的 DNS-rebinding 防护只放行 localhost,拦掉局域网直连;
                    // 信任模型与相邻端口的 WebService(无 Host 校验)一致,关闭之
                    mcpStreamableHttp(enableDnsRebindingProtection = false) {
                        McpToolServer.create()
                    }
                }.also { it.start(wait = false) }
                notificationList.clear()
                notificationList.addAll(addressList.map { address ->
                    "http://${address.hostAddress}:$port/mcp"
                })
                hostAddress = notificationList.first()
                isRun = true
                postEvent(EventBus.MCP_SERVICE, hostAddress)
                startForegroundNotification()
            } catch (e: Exception) {
                toastOnUi(e.localizedMessage ?: "MCP 服务启动失败")
                e.printOnDebug()
                stopSelf()
            }
        } else {
            toastOnUi("mcp service cant start, no ip address")
            stopSelf()
        }
    }

    private fun getPort(): Int {
        var port = getPrefInt(PreferKey.mcpPort, 1236)
        if (port > 65530 || port < 1024) {
            port = 1236
        }
        return port
    }

    override fun startForegroundNotification() {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdWeb)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setOngoing(true)
            .setContentTitle(getString(R.string.mcp_service))
            .setContentText(notificationList.joinToString("\n"))
            .setContentIntent(
                servicePendingIntent<McpService>("copyHostAddress")
            )
        builder.addAction(
            R.drawable.ic_copy,
            getString(R.string.mcp_token_copy_action),
            servicePendingIntent<McpService>("copyToken")
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<McpService>(IntentAction.stop)
        )
        val notification = builder.build()
        startForeground(NotificationId.McpService, notification)
    }
}
