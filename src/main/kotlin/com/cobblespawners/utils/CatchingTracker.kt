package com.cobblespawners.utils

import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.events.pokeball.PokeBallCaptureCalculatedEvent
import com.cobblemon.mod.common.api.pokeball.catching.CaptureContext
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblespawners.api.SpawnerNBTManager
import com.everlastingutils.utils.logDebug
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.ItemEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import net.minecraft.registry.Registries

class CatchingTracker {

    data class PokeballTrackingInfo(
        val pokeBallUuid: UUID,
        val pokeBallEntity: EmptyPokeBallEntity
    )

    private val playerTrackingMap = ConcurrentHashMap<ServerPlayerEntity, ConcurrentLinkedQueue<PokeballTrackingInfo>>()

    fun registerEvents() {
        CobblemonEvents.POKE_BALL_CAPTURE_CALCULATED.subscribe { event ->
            handlePokeBallCaptureCalculated(event)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            val mapIterator = playerTrackingMap.entries.iterator()
            while (mapIterator.hasNext()) {
                val entry = mapIterator.next()
                val player = entry.key
                val queue = entry.value
                val world = player.world as? ServerWorld ?: continue

                val queueIterator = queue.iterator()
                while (queueIterator.hasNext()) {
                    val trackingInfo = queueIterator.next()
                    if (world.getEntity(trackingInfo.pokeBallUuid) == null) {
                        returnPokeballToPlayer(player, trackingInfo.pokeBallEntity)
                        queueIterator.remove()
                    }
                }

                if (queue.isEmpty()) {
                    mapIterator.remove()
                }
            }
        }
    }

    private fun handlePokeBallCaptureCalculated(event: PokeBallCaptureCalculatedEvent) {
        val pokeBallEntity: EmptyPokeBallEntity = event.pokeBallEntity
        val pokemonEntity: PokemonEntity = event.pokemonEntity
        val thrower: ServerPlayerEntity = pokeBallEntity.owner as? ServerPlayerEntity ?: return

        val spawnerInfo = SpawnerNBTManager.getPokemonInfo(pokemonEntity)
        if (spawnerInfo != null) {
            val spawnerData = CobbleSpawnersConfig.getSpawner(spawnerInfo.spawnerPos)
            if (spawnerData != null) {
                val speciesName = pokemonEntity.pokemon.species.name
                val formName = pokemonEntity.pokemon.form.name.let { if (it.equals("Standard", ignoreCase = true)) "Normal" else it }
                val pokemonAspects = pokemonEntity.pokemon.aspects.toSet()

                val defaultGenderAspects = setOf("male", "female", "genderless")

                val pokemonSpawnEntry = spawnerData.selectedPokemon.find { entry ->
                    val configAspectsLower = entry.aspects.map { it.lowercase() }.toSet()
                    val pokemonAspectsLower = pokemonAspects.map { it.lowercase() }.toSet()
                    val pokemonAspectsWithoutGender = pokemonAspectsLower.filter { it !in defaultGenderAspects }.toSet()

                    val speciesMatch = entry.pokemonName.equals(speciesName, ignoreCase = true)
                    val formMatch = (entry.formName?.equals(formName, ignoreCase = true) ?: (formName.equals("Normal", ignoreCase = true)))
                    val aspectsMatch = configAspectsLower == pokemonAspectsWithoutGender

                    speciesMatch && formMatch && aspectsMatch
                }

                if (pokemonSpawnEntry != null) {
                    val captureSettings = pokemonSpawnEntry.captureSettings
                    var blockCapture = false

                    // Logic to decide if we should BLOCK the capture
                    if (!captureSettings.isCatchable) {
                        thrower.sendMessage(Text.literal("This Pokémon cannot be captured!").formatted(Formatting.RED), false)
                        blockCapture = true
                    } else {
                        if (captureSettings.restrictCaptureToLimitedBalls) {
                            val usedPokeBallIdentifier = Registries.ITEM.getId(pokeBallEntity.pokeBall.item()).toString()
                            val allowedPokeBalls = prepareAllowedPokeBallList(captureSettings.requiredPokeBalls)

                            if (!allowedPokeBalls.contains("ALL") && !allowedPokeBalls.any { allowed -> allowed.equals(usedPokeBallIdentifier, ignoreCase = true) }) {
                                val allowedBallsDisplay = allowedPokeBalls.joinToString { it.substringAfter(":") }
                                thrower.sendMessage(Text.literal("Only specific Poké Balls work! Allowed: $allowedBallsDisplay").formatted(Formatting.RED), false)
                                blockCapture = true
                            }
                        }
                    }

                    // Apply the block if necessary
                    if (blockCapture) {
                        event.captureResult = CaptureContext(
                            numberOfShakes = 0,
                            isSuccessfulCapture = false,
                            isCriticalCapture = false
                        )
                        val queue = playerTrackingMap.computeIfAbsent(thrower) { ConcurrentLinkedQueue() }
                        queue.add(PokeballTrackingInfo(pokeBallEntity.uuid, pokeBallEntity))
                    }

                }
            }
        }
    }

    private fun prepareAllowedPokeBallList(allowedPokeBalls: List<String>): List<String> {
        return allowedPokeBalls.map {
            val lower = it.lowercase()
            when {
                lower == "all" -> "ALL"
                !lower.contains(":") -> "cobblemon:$lower"
                else -> lower
            }
        }
    }

    private fun returnPokeballToPlayer(player: ServerPlayerEntity, pokeBallEntity: EmptyPokeBallEntity) {
        val pokeBallStack = pokeBallEntity.pokeBall.item().defaultStack
        if (pokeBallStack.isEmpty) {
            return
        }
        if (!pokeBallEntity.isRemoved) {
            pokeBallEntity.discard()
        }
        val ballPos = pokeBallEntity.blockPos
        if (!player.inventory.insertStack(pokeBallStack)) {
            val itemEntity = ItemEntity(player.world, ballPos.x + 0.5, ballPos.y + 0.5, ballPos.z + 0.5, pokeBallStack)
            itemEntity.setToDefaultPickupDelay()
            player.world.spawnEntity(itemEntity)
        }
    }
}