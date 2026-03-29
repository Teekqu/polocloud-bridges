package dev.httpmarco.polocloud.bridges.bungeecord

import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.plugin.Plugin
import org.bstats.bungeecord.Metrics
import java.util.concurrent.TimeUnit

class BungeecordBridge : Plugin() {

    override fun onEnable() {
        ProxyServer.getInstance().servers.clear()

        val bridgeInstance = BungeecordBridgeInstance()
        ProxyServer.getInstance().reconnectHandler = BungeecordReconnectHandler(bridgeInstance)
        ProxyServer.getInstance().pluginManager.registerListener(this, bridgeInstance)

        ProxyServer.getInstance().scheduler.schedule(this, {
            ProxyServer.getInstance().servers.values.forEach {
                bridgeInstance.updatePing(it)
            }
        }, 0, 10, TimeUnit.SECONDS)

        val pluginId = 26764
        Metrics(this, pluginId)
        logger.info("bStats Metrics successfully initialized for ${this.javaClass.simpleName} (pluginId=$pluginId)")
    }
}
