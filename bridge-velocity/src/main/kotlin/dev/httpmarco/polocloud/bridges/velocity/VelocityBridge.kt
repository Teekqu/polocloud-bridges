package dev.httpmarco.polocloud.bridges.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.player.KickedFromServerEvent
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import dev.httpmarco.polocloud.bridge.api.BridgeActorSupportInstance
import dev.httpmarco.polocloud.sdk.java.Polocloud
import dev.httpmarco.polocloud.shared.events.definitions.PlayerJoinEvent
import dev.httpmarco.polocloud.shared.events.definitions.PlayerLeaveEvent
import dev.httpmarco.polocloud.shared.player.PolocloudPlayer
import dev.httpmarco.polocloud.shared.service.Service
import org.bstats.velocity.Metrics
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull


class VelocityBridge @Inject constructor(
    val proxyServer: ProxyServer,
    val metricsFactory: Metrics.Factory
) : BridgeActorSupportInstance<RegisteredServer, ServerInfo>(VelocityPlayerActorBridge(proxyServer)) {

    private val maxPlayerCache = mutableMapOf<String, Int>()
    private lateinit var metrics: Metrics

    init {
        // remove all registered servers on startup
        proxyServer.allServers.forEach {
            proxyServer.unregisterServer(it.serverInfo)
        }
    }

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        this.processBind()

        proxyServer.scheduler.buildTask(this, Runnable {
            proxyServer.allServers.forEach { updatePing(it) }
        }).repeat(10, TimeUnit.SECONDS).schedule()

        val pluginId = 26763
        metrics = metricsFactory.make(this, pluginId)
    }

    @Subscribe
    fun onConnect(event: PlayerChooseInitialServerEvent) {
        findFallback()?.let { event.setInitialServer(it) }
    }

    @Subscribe
    fun onPlayerJoin(event: ServerConnectedEvent) {
        val player = event.player
        updatePolocloudPlayer(
            PlayerJoinEvent(
                PolocloudPlayer(
                    player.username,
                    player.uniqueId,
                    event.server.serverInfo.name,
                    Polocloud.instance().selfServiceName()
                )
            )
        )
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val player = event.player
        updatePolocloudPlayer(PlayerLeaveEvent(PolocloudPlayer(player.username, player.uniqueId, "", "")))
    }

    @Subscribe
    fun onKick(event: KickedFromServerEvent) {
        if (event.player.isActive) {
            if (event.server.serverInfo == null) {
                // Player was not connected to any service
                return
            }
            val server = findFallback()
            if (server == null || server == event.server) {
                return
            }
            event.result = KickedFromServerEvent.RedirectPlayer.create(server)
        }
    }

    override fun generateServerInfo(service: Service): ServerInfo {
        return ServerInfo(service.name(), InetSocketAddress(service.hostname, service.port))
    }

    override fun registerServerInfo(
        identifier: ServerInfo,
        service: Service
    ): RegisteredServer {
        val registerServer = proxyServer.registerServer(identifier)
        updatePing(registerServer)

        return registerServer
    }

    private fun updatePing(server: RegisteredServer) {
        server.ping().thenAccept { ping ->
            val max = ping.players.orElse(null)?.max ?: return@thenAccept
            maxPlayerCache[server.serverInfo.name] = max
        }
    }

    override fun unregister(identifier: RegisteredServer) {
        proxyServer.unregisterServer(identifier.serverInfo)
    }

    override fun findServer(name: String): RegisteredServer? {
        return proxyServer.getServer(name).getOrNull()
    }

    override fun playerCount(info: RegisteredServer): Int {
        return info.playersConnected.size
    }

    override fun maxPlayers(server: RegisteredServer): Int {
        return maxPlayerCache[server.serverInfo.name] ?: 100
    }
}
