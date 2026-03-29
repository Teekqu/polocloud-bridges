package dev.httpmarco.polocloud.bridge.api

import dev.httpmarco.polocloud.shared.PolocloudShared
import dev.httpmarco.polocloud.shared.events.Event
import dev.httpmarco.polocloud.shared.events.definitions.service.ServiceChangeStateEvent
import dev.httpmarco.polocloud.shared.service.Service
import dev.httpmarco.polocloud.v1.groups.GroupType
import dev.httpmarco.polocloud.v1.services.ServiceState
import dev.httpmarco.polocloud.sdk.java.Polocloud
import kotlin.random.Random

/**
 * Abstract class for bridging services to a proxy (e.g., Velocity, BungeeCord).
 * F = internal representation of registered server (e.g., RegisteredServer)
 * T = server info type used by the proxy (e.g., ServerInfo)
 */
abstract class BridgeInstance<F, T>(protected val polocloud: PolocloudShared = Polocloud.instance()) {

    /** List of registered fallback servers */
    val registeredFallbacks = hashMapOf<Service, F>()

    fun processBind() {
        // Register all currently online servers of type SERVER at startup
        polocloud.serviceProvider()
            .findByType(GroupType.SERVER)
            .filter { it.state == ServiceState.ONLINE }
            .forEach { registerNewServer(it) }

        // Subscribe to service state change events
        polocloud.eventProvider().subscribe(ServiceChangeStateEvent::class.java) {
            if (it.service.type == GroupType.SERVER) {
                handleServiceStateChange(it.service)
            }
        }
    }

    /**
     * Handle a service state change event.
     * - If a server goes ONLINE, register it.
     * - If a server is STOPPING, unregister it.
     */
    private fun handleServiceStateChange(service: Service) {
        when (service.state) {
            ServiceState.ONLINE -> if (service.type == GroupType.SERVER) registerNewServer(service)
            ServiceState.STOPPING -> unregisterServer(service)
            else -> {} // Other states can be ignored
        }
    }

    /**
     * Registers a new server with the proxy.
     * Adds it to fallback list if configured as fallback.
     */
    protected open fun registerNewServer(service: Service) {
        val serverInfo = registerServerInfo(generateServerInfo(service), service)

        if (isFallback(service)) {
            registeredFallbacks[service] = serverInfo
        }
    }

    /**
     * Unregisters a server from the proxy.
     * Removes it from the fallback list if necessary.
     */
    private fun unregisterServer(service: Service) {
        findServer(service.name())?.let { server ->
            unregister(server)
        }
        registeredFallbacks.remove(service)
    }

    /**
     * Updates a Polocloud player by firing the given event.
     */
    fun updatePolocloudPlayer(event: Event) {
        polocloud.eventProvider().call(event)
    }

    fun hasFallbacks(): Boolean {
        return registeredFallbacks.isNotEmpty()
    }

    /**
     * Determines if a service is configured as a fallback server.
     */
    protected fun isFallback(service: Service): Boolean {
        // TODO use new property system
        return service.properties["fallback"]?.equals("true", ignoreCase = true) == true
    }

    /**
     * Selects the best available fallback server using a weighted load-balancing strategy.
     *
     * <p>This method distributes players dynamically across all available fallback servers
     * based on their current load (players / maxPlayers), while avoiding hard limits or
     * static selection (e.g. "top 3"). Instead of picking a single best server, it uses
     * a weighted random algorithm to ensure fair and smooth distribution, even during
     * join spikes.</p>
     *
     * <h3>Selection Algorithm</h3>
     * <ul>
     *   <li>All registered fallback servers are considered.</li>
     *   <li>Servers that are offline or already full are excluded.</li>
     *   <li>The load of each server is calculated as:
     *       <pre>load = players / maxPlayers</pre>
     *   </li>
     *   <li>A weight is assigned to each server based on its load:
     *       <pre>weight = (1 - load)^2</pre>
     *       This ensures:
     *       <ul>
     *         <li>Empty or low-populated servers are strongly preferred</li>
     *         <li>Nearly full servers are very unlikely to be selected</li>
     *         <li>No hard cutoffs are used (smooth balancing)</li>
     *       </ul>
     *   </li>
     *   <li>An optional fallback priority can slightly influence selection:
     *       <pre>priorityBoost = 1 / (priority + 1)</pre>
     *       This acts as a soft bias and does not override load balancing.</li>
     *   <li>A small constant is added to prevent zero-weight edge cases.</li>
     *   <li>Finally, a weighted random selection is performed.</li>
     * </ul>
     *
     * <h3>Advantages</h3>
     * <ul>
     *   <li>No static limits (e.g. no "top 3" or fixed ranges)</li>
     *   <li>Automatically adapts to any network size</li>
     *   <li>Prevents player clumping during mass joins</li>
     *   <li>Respects server capacity and avoids overfilling</li>
     *   <li>Provides smooth and realistic distribution behavior</li>
     * </ul>
     *
     * <h3>Behavior Examples</h3>
     * <ul>
     *   <li>If all servers are empty → players are evenly distributed</li>
     *   <li>If one server is more full → it becomes less likely to be chosen</li>
     *   <li>During join spikes → players are spread across multiple servers</li>
     * </ul>
     *
     * @return the selected fallback server, or {@code null} if none are available
     */
    fun findFallback(): F? {
        val candidates = registeredFallbacks.keys
            .mapNotNull { service ->
                val server = findServer(service.name()) ?: return@mapNotNull null

                val players = playerCount(server)
                val maxPlayers = maxPlayers(server)

                val load = if (maxPlayers <= 0) 1.0 else players.toDouble() / maxPlayers.toDouble()
                val priority = service.properties["fallbackPriority"]?.toIntOrNull() ?: 0

                Triple(server, load, priority)
            }

        if (candidates.isEmpty()) return null

        val weighted = candidates.map { (server, load, priority) ->
            val base = (1.0 - load) * (1.0 - load)
            val priorityBoost = 1.0 / (priority + 1)
            val weight = base + (priorityBoost * 0.2) + 0.05

            server to weight
        }

        val totalWeight = weighted.sumOf { it.second }
        var random = Random.nextDouble() * totalWeight

        for ((server, weight) in weighted) {
            random -= weight
            if (random <= 0) {
                return server
            }
        }

        return weighted.lastOrNull()?.first
    }

    /**
     * Generate server info (proxy-specific) for a service.
     */
    abstract fun generateServerInfo(service: Service): T

    /**
     * Register server info with the proxy.
     * Returns the internal representation (F) of the registered server.
     */
    abstract fun registerServerInfo(identifier: T, service: Service): F

    /**
     * Unregister the given internal server representation from the proxy.
     */
    abstract fun unregister(identifier: F)

    /**
     * Find the internal server representation by its name.
     */
    abstract fun findServer(name: String): F?

    /**
     * Returns the current player count of the given server.
     */
    abstract fun playerCount(info: F): Int


    /**
     * Returns the maximum player capacity of the given server.
     */
    abstract fun maxPlayers(server: F): Int
}
