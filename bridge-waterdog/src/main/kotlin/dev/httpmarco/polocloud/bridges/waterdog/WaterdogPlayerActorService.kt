package dev.httpmarco.polocloud.bridges.waterdog

import dev.httpmarco.polocloud.bridge.api.actor.BridgeActor
import dev.httpmarco.polocloud.v1.player.PlayerActorResponse
import dev.waterdog.waterdogpe.ProxyServer
import dev.waterdog.waterdogpe.player.ProxiedPlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture

class WaterdogPlayerActorService : BridgeActor {

    override fun message(
        uuid: UUID,
        message: String
    ): PlayerActorResponse {
        val player: ProxiedPlayer =
            ProxyServer.getInstance().getPlayer(uuid) ?: return PlayerActorResponse.newBuilder().setSuccess(false)
                .setErrorMessage("Player is not online.").build()

        if(!player.isConnected){
            return PlayerActorResponse.newBuilder().setSuccess(false)
                .setErrorMessage("Player is not connected to a server.").build()
        }

        player.sendMessage(message)
        return PlayerActorResponse.newBuilder().setSuccess(true).build()
    }

    override fun kick(
        uuid: UUID,
        reason: String
    ): PlayerActorResponse {
        val player = ProxyServer.getInstance().getPlayer(uuid) ?: return PlayerActorResponse.newBuilder().setSuccess(false)
            .setErrorMessage("Player is not online.").build()

        if(!player.isConnected){
            return PlayerActorResponse.newBuilder().setSuccess(false)
                .setErrorMessage("Player is not connected to a server.").build()
        }

        player.disconnect(reason)
        return PlayerActorResponse.newBuilder().setSuccess(true).build()
    }

    override fun connect(
        uuid: UUID,
        server: String
    ): CompletableFuture<PlayerActorResponse> {
        val player = ProxyServer.getInstance().getPlayer(uuid) ?: return CompletableFuture.completedFuture(
            PlayerActorResponse.newBuilder().setSuccess(false)
                .setErrorMessage("Player is not online.").build()
        )

        if(!player.isConnected){
            return CompletableFuture.completedFuture(PlayerActorResponse.newBuilder().setSuccess(false)
                .setErrorMessage("Player is not connected to a server.").build())
        }

        if(player.serverInfo.serverName.equals(server, true)){
            return CompletableFuture.completedFuture(PlayerActorResponse.newBuilder().setSuccess(false).setErrorMessage("The player is already on this server!").build())
        }

        val server = ProxyServer.getInstance().getServerInfo(server) ?: return CompletableFuture.completedFuture(
            PlayerActorResponse.newBuilder().setSuccess(false).setErrorMessage("Server is not present.").build())

        player.connect(server)
        return CompletableFuture.completedFuture(PlayerActorResponse.newBuilder().setSuccess(true).build())
    }
}