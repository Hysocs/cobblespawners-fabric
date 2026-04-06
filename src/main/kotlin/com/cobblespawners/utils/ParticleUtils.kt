package com.cobblespawners.utils

import com.cobblespawners.CobbleSpawners
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import java.util.UUID

object ParticleUtils {

    val activeVisualizations = mutableMapOf<UUID, Pair<BlockPos, Long>>()
    const val visualizationInterval: Long = 10L

    // Cached wireframe edge points per spawner, keyed by BlockPos.
    // Recomputed only when radius changes, not every tick.
    private val wireframeCache = mutableMapOf<BlockPos, List<Triple<Double, Double, Double>>>()

    init {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            activeVisualizations.remove(handler.player.uuid)
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun visualizeSpawnerPositions(player: ServerPlayerEntity, spawnerData: SpawnerData) {
        val spawnerPos = spawnerData.spawnerPos.toBlockPos()
        val categorizedPositions = CobbleSpawners.spawnerValidPositions[spawnerPos] ?: return

        val playerChunkX = player.blockPos.x shr 4
        val playerChunkZ = player.blockPos.z shr 4

        // Send one FLAME packet per valid spawn position that is within 2 chunks of the player.
        // Flatten once, filter once — no intermediate lists.
        categorizedPositions.values.forEach { positions ->
            positions.forEach { pos ->
                if (
                    kotlin.math.abs((pos.x shr 4) - playerChunkX) <= 2 &&
                    kotlin.math.abs((pos.z shr 4) - playerChunkZ) <= 2
                ) {
                    sendParticlePacket(player, pos.x + 0.5, pos.y + 1.0, pos.z + 0.5, flame = true)
                }
            }
        }

        // Wireframe outline
        val width  = spawnerData.spawnRadius?.width  ?: 4
        val height = spawnerData.spawnRadius?.height ?: 4
        val edges  = wireframeCache.getOrPut(spawnerPos) { buildWireframe(spawnerPos, width, height) }

        val playerChunkZ2 = player.blockPos.z shr 4 // same value, named for clarity in filter
        edges.forEach { (x, y, z) ->
            if (
                kotlin.math.abs((x.toInt() shr 4) - playerChunkX) <= 2 &&
                kotlin.math.abs((z.toInt() shr 4) - playerChunkZ2) <= 2
            ) {
                sendParticlePacket(player, x, y, z, flame = false)
            }
        }
    }

    fun toggleVisualization(player: ServerPlayerEntity, spawnerData: SpawnerData) {
        val spawnerPos = spawnerData.spawnerPos.toBlockPos()
        val playerUUID = player.uuid

        if (activeVisualizations[playerUUID]?.first == spawnerPos) {
            activeVisualizations.remove(playerUUID)
            player.sendMessage(
                net.minecraft.text.Text.literal("Stopped visualizing spawn points for spawner '${spawnerData.spawnerName}'"),
                false
            )
            return
        }

        if (CobbleSpawners.spawnerValidPositions[spawnerPos].isNullOrEmpty()) {
            val serverWorld = player.world as? ServerWorld
            if (serverWorld != null) {
                val computed = CobbleSpawners.computeValidSpawnPositions(serverWorld, spawnerData)
                if (computed.isEmpty()) {
                    player.sendMessage(
                        net.minecraft.text.Text.literal("No valid spawn positions found for spawner '${spawnerData.spawnerName}'"),
                        false
                    )
                    return
                }
                CobbleSpawners.spawnerValidPositions[spawnerPos] = computed
            }
        }

        // Invalidate wireframe cache so it rebuilds with current radius
        wireframeCache.remove(spawnerPos)

        activeVisualizations[playerUUID] = spawnerPos to player.world.time
        player.sendMessage(
            net.minecraft.text.Text.literal("Started visualizing spawn points and cube outline for spawner '${spawnerData.spawnerName}'"),
            false
        )
    }

    /** Call this when a spawner's radius is updated so the cache doesn't serve stale data. */
    fun invalidateWireframeCache(spawnerPos: BlockPos) {
        wireframeCache.remove(spawnerPos)
    }

    // Kept for spawn/despawn visual feedback — still packet-only
    fun spawnMonParticles(world: ServerWorld, spawnPos: BlockPos) {
        world.spawnParticles(
            ParticleTypes.CLOUD,
            spawnPos.x + 0.5, spawnPos.y + 1.0, spawnPos.z + 0.5,
            8, 0.3, 0.3, 0.3, 0.01
        )
    }

    fun spawnSpawnerParticles(world: ServerWorld, spawnerPos: BlockPos) {
        world.spawnParticles(
            ParticleTypes.SMOKE,
            spawnerPos.x + 0.5, spawnerPos.y + 1.0, spawnerPos.z + 0.5,
            12, 0.3, 0.3, 0.3, 0.01
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Pre-computes every particle position along the 12 edges of the spawn-radius
     * bounding box. Stored as world-space doubles so the hot path (per-tick send)
     * does zero arithmetic — just iterate and send.
     *
     * Step size of 1.0 block gives a clean wireframe without flooding the client.
     * For very large radii (>16) we increase step size to keep packet count sane.
     */
    private fun buildWireframe(
        center: BlockPos,
        radiusW: Int,
        radiusH: Int
    ): List<Triple<Double, Double, Double>> {
        val cx = center.x.toDouble()
        val cy = center.y.toDouble()
        val cz = center.z.toDouble()

        val minX = cx - radiusW;  val maxX = cx + radiusW
        val minY = cy - radiusH;  val maxY = cy + radiusH
        val minZ = cz - radiusW;  val maxZ = cz + radiusW

        // Adaptive step: 1 block up to radius 16, then scale up to cap packet count
        val step = if (radiusW <= 16) 1.0 else (radiusW / 16.0).coerceAtMost(3.0)

        val points = ArrayList<Triple<Double, Double, Double>>(
            // rough upper bound: 12 edges × (2*radius/step) points each
            (12 * (2 * radiusW / step + 1)).toInt()
        )

        // 4 edges parallel to X axis (at each Y/Z corner)
        for (yCorner in listOf(minY, maxY)) {
            for (zCorner in listOf(minZ, maxZ)) {
                var x = minX
                while (x <= maxX + 0.001) {
                    points.add(Triple(x + 0.5, yCorner + 0.5, zCorner + 0.5))
                    x += step
                }
            }
        }

        // 4 edges parallel to Z axis (at each X/Y corner)
        for (xCorner in listOf(minX, maxX)) {
            for (yCorner in listOf(minY, maxY)) {
                var z = minZ
                while (z <= maxZ + 0.001) {
                    points.add(Triple(xCorner + 0.5, yCorner + 0.5, z + 0.5))
                    z += step
                }
            }
        }

        // 4 edges parallel to Y axis (at each X/Z corner)
        for (xCorner in listOf(minX, maxX)) {
            for (zCorner in listOf(minZ, maxZ)) {
                var y = minY
                while (y <= maxY + 0.001) {
                    points.add(Triple(xCorner + 0.5, y + 0.5, zCorner + 0.5))
                    y += step
                }
            }
        }

        return points
    }

    /**
     * Sends a single particle packet directly to the player's connection.
     * No world involvement — completely client-bound, zero server-side entity or tick cost.
     *
     * [flame] = true  → FLAME  (marks valid spawn positions)
     * [flame] = false → SOUL_FIRE_FLAME  (wireframe outline)
     */
    private fun sendParticlePacket(
        player: ServerPlayerEntity,
        x: Double, y: Double, z: Double,
        flame: Boolean
    ) {
        player.networkHandler.sendPacket(
            ParticleS2CPacket(
                if (flame) ParticleTypes.FLAME else ParticleTypes.SOUL_FIRE_FLAME,
                true,       // long distance — always visible regardless of particle settings
                x, y, z,
                0f, 0f, 0f, // no random spread
                0f,         // speed 0 — stationary dot
                1
            )
        )
    }
}