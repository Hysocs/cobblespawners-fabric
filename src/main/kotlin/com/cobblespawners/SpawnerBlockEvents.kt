package com.cobblespawners

import com.everlastingutils.command.CommandManager
import com.everlastingutils.utils.logDebug
import com.cobblespawners.utils.*
import com.cobblespawners.api.SpawnerNBTManager
import com.cobblespawners.utils.gui.SpawnerPokemonSelectionGui
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.Blocks
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object SpawnerBlockEvents {
    private val logger = LoggerFactory.getLogger("cobblespawners")

    fun registerEvents() {
        registerUseBlockCallback()
        registerBlockBreakCallback()
    }

    private fun hasPermission(player: ServerPlayerEntity, permission: String, requiredLevel: Int): Boolean {
        return CommandManager.hasPermissionOrOp(player.commandSource, permission, requiredLevel, requiredLevel)
    }

    private fun registerUseBlockCallback() {
        UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
            if (
                player is ServerPlayerEntity &&
                hand == Hand.MAIN_HAND &&
                hitResult is BlockHitResult
            ) {
                val blockPos = hitResult.blockPos
                val blockState = world.getBlockState(blockPos)
                val itemInHand = player.getStackInHand(hand)
                val modelData = itemInHand.get(DataComponentTypes.CUSTOM_MODEL_DATA)

                if (
                    itemInHand.item == Items.SPAWNER &&
                    modelData != null &&
                    modelData.value == 16666
                ) {
                    val blockPosToPlace = hitResult.blockPos.offset(hitResult.side)
                    val blockAtPlacement = world.getBlockState(blockPosToPlace)

                    if (blockAtPlacement.isAir || blockAtPlacement.block.defaultState.isReplaceable) {
                        logDebug("Attempting to place custom spawner at $blockPosToPlace", "cobblespawners")
                        placeCustomSpawner(player, world, blockPosToPlace, itemInHand)
                        return@register ActionResult.SUCCESS
                    }
                }

                if (
                    blockState.block == Blocks.SPAWNER &&
                    CobbleSpawnersConfig.spawners.containsKey(blockPos)
                ) {
                    if (hasPermission(player, "CobbleSpawners.Edit", 2)) {
                        SpawnerPokemonSelectionGui.openSpawnerGui(player, blockPos)
                        return@register ActionResult.SUCCESS
                    } else {
                        player.sendMessage(Text.literal("You don't have permission to manage this spawner."), false)
                    }
                }
            }
            ActionResult.PASS
        }
    }

    private fun getNextSpawnerName(): String {
        val existingNumbers = CobbleSpawnersConfig.spawners.values
            .map { it.spawnerName }
            .filter { it.startsWith("spawner_") }
            .mapNotNull { it.removePrefix("spawner_").toIntOrNull() }
            .toSet()

        var number = 1
        while (existingNumbers.contains(number)) {
            number++
        }
        return "spawner_$number"
    }

    private fun placeCustomSpawner(
        player: ServerPlayerEntity,
        world: World,
        pos: BlockPos,
        itemInHand: ItemStack
    ) {
        if (!hasPermission(player, "CobbleSpawners.Place", 2)) {
            player.sendMessage(Text.literal("You don't have permission to place a custom spawner."), false)
            return
        }

        if (CobbleSpawnersConfig.spawners.containsKey(pos)) {
            player.sendMessage(Text.literal("A spawner already exists at this location!"), false)
            return
        }

        val blockState = world.getBlockState(pos)
        if (blockState.block == Blocks.WATER || blockState.block == Blocks.LAVA) {
            world.setBlockState(pos, Blocks.AIR.defaultState)
        }
        world.setBlockState(pos, Blocks.SPAWNER.defaultState)

        val dimensionString = "${world.registryKey.value.namespace}:${world.registryKey.value.path}"
        val gson = com.google.gson.Gson()
        val spawnerName = getNextSpawnerName()

        val nbtComponent = itemInHand.get(DataComponentTypes.CUSTOM_DATA)
        val spawnerData: SpawnerData = if (nbtComponent != null) {
            val nbt = nbtComponent.getNbt()
            var configJson: String? = null

            if (nbt.contains("CobbleSpawnerConfigCompressed")) {
                val compressedData = nbt.getByteArray("CobbleSpawnerConfigCompressed")
                try {
                    configJson = GZIPInputStream(ByteArrayInputStream(compressedData))
                        .bufferedReader()
                        .use { it.readText() }
                } catch (e: Exception) {
                    logger.error("Failed to decompress spawner config from item!", e)
                    player.sendMessage(Text.literal("§cError: Could not read compressed spawner data."), false)
                }
            } else if (nbt.contains("CobbleSpawnerConfig")) {
                configJson = nbt.getString("CobbleSpawnerConfig")
            }

            val loadedData = if (configJson != null) {
                try {
                    gson.fromJson(configJson, SpawnerData::class.java)
                } catch (e: Exception) {
                    logger.error("Failed to parse spawner JSON from item!", e)
                    player.sendMessage(Text.literal("§cError: Could not parse spawner JSON data."), false)
                    null
                }
            } else null

            // spawnerPos is now SerializableBlockPos — wrap pos correctly
            loadedData?.copy(
                spawnerPos = SerializableBlockPos.fromBlockPos(pos),
                spawnerName = spawnerName,
                dimension = dimensionString
            ) ?: SpawnerData(
                spawnerPos = SerializableBlockPos.fromBlockPos(pos),
                spawnerName = spawnerName,
                selectedPokemon = mutableListOf(),
                dimension = dimensionString,
                spawnTimerTicks = 200,
                spawnRadius = SpawnRadius(width = 4, height = 4),
                spawnLimit = 4,
                spawnAmountPerSpawn = 1,
                visible = true,
                wanderingSettings = WanderingSettings()
            )
        } else {
            SpawnerData(
                spawnerPos = SerializableBlockPos.fromBlockPos(pos),
                spawnerName = spawnerName,
                selectedPokemon = mutableListOf(),
                dimension = dimensionString,
                spawnTimerTicks = 200,
                spawnRadius = SpawnRadius(width = 4, height = 4),
                spawnLimit = 4,
                spawnAmountPerSpawn = 1,
                visible = true,
                wanderingSettings = WanderingSettings()
            )
        }

        CobbleSpawnersConfig.spawners[pos] = spawnerData
        CobbleSpawnersConfig.config.spawners.add(spawnerData)
        CobbleSpawnersConfig.saveSpawnerData()
        CobbleSpawnersConfig.saveConfigBlocking()

        if (spawnerData.forceChunkLoading && world is ServerWorld) {
            val chunkPos = ChunkPos(pos)
            world.chunkManager.addTicket(
                CobbleSpawners.SPAWNER_TICKET_TYPE, chunkPos, spawnerData.chunkLoadRadius, pos
            )
            logDebug("Added chunk ticket for spawner '${spawnerData.spawnerName}' at $pos", "cobblespawners")
        }

        player.sendMessage(Text.literal("Custom spawner '${spawnerData.spawnerName}' placed at $pos!"), false)
        if (!player.abilities.creativeMode) {
            itemInHand.decrement(1)
        }
    }

    private fun registerBlockBreakCallback() {
        PlayerBlockBreakEvents.BEFORE.register { world, player, blockPos, blockState, _ ->
            val serverPlayer = player as? ServerPlayerEntity ?: return@register true
            val spawnerData = CobbleSpawnersConfig.spawners[blockPos]
            if (world !is ServerWorld) return@register true

            if (
                blockState.block == Blocks.SPAWNER &&
                CobbleSpawnersConfig.spawners.containsKey(blockPos)
            ) {
                if (!hasPermission(serverPlayer, "CobbleSpawners.break", 2)) {
                    serverPlayer.sendMessage(Text.literal("You don't have permission to remove this spawner."), false)
                    return@register false
                }

                if (spawnerData != null && spawnerData.forceChunkLoading) {
                    val chunkPos = ChunkPos(blockPos)
                    world.chunkManager.removeTicket(
                        CobbleSpawners.SPAWNER_TICKET_TYPE, chunkPos, spawnerData.chunkLoadRadius, blockPos
                    )
                    logDebug("Removed chunk ticket for spawner '${spawnerData.spawnerName}' at $blockPos", "cobblespawners")
                }

                CobbleSpawnersConfig.spawners.remove(blockPos)
                // spawnerPos is now SerializableBlockPos — compare via toBlockPos()
                CobbleSpawnersConfig.config.spawners.removeIf {
                    it.spawnerPos.toBlockPos() == blockPos
                }
                CobbleSpawnersConfig.saveSpawnerData()
                CobbleSpawnersConfig.saveConfigBlocking()

                SpawnerNBTManager.clearPokemonForSpawner(world, blockPos)
                CobbleSpawners.spawnerValidPositions.remove(blockPos)
                serverPlayer.sendMessage(Text.literal("Custom spawner removed at $blockPos."), false)
                logDebug("Custom spawner removed at $blockPos.", "cobblespawners")
            } else {
                invalidatePositionsIfWithinRadius(world, blockPos)
            }
            true
        }
    }

    private fun invalidatePositionsIfWithinRadius(world: ServerWorld, changedBlockPos: BlockPos) {
        for ((pos, data) in CobbleSpawnersConfig.spawners) {
            val spawnRadius = data.spawnRadius ?: continue
            val distanceSquared = pos.getSquaredDistance(changedBlockPos)
            val maxDistanceSquared = (spawnRadius.width * spawnRadius.width).toDouble()
            if (distanceSquared <= maxDistanceSquared) {
                CobbleSpawners.spawnerValidPositions.remove(pos)
                logDebug("Invalidated cached positions for spawner at $pos due to block change @ $changedBlockPos", "cobblespawners")
            }
        }
    }
}