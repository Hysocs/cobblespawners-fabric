package com.cobblespawners.utils

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import com.everlastingutils.config.ConfigData
import com.everlastingutils.config.ConfigManager
import com.everlastingutils.config.ConfigMetadata
import com.everlastingutils.config.WatcherSettings
import com.everlastingutils.utils.LogDebug
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.runBlocking
import net.minecraft.util.math.BlockPos
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.memberProperties

// --- Plain data class: Gson reflects on x/y/z Int fields directly.
// No obfuscation possible because this is our own class, not a Minecraft internal.
data class SerializableBlockPos(val x: Int = 0, val y: Int = 0, val z: Int = 0) {
    fun toBlockPos(): BlockPos = BlockPos(x, y, z)

    companion object {
        fun fromBlockPos(pos: BlockPos) = SerializableBlockPos(pos.x, pos.y, pos.z)
    }
}

data class GlobalConfig(
    var debugEnabled: Boolean = false,
    var cullSpawnerPokemonOnServerStop: Boolean = true,
    var showUnimplementedPokemonInGui: Boolean = false,
    var showFormsInGui: Boolean = true,
    var showAspectsInGui: Boolean = true
)

data class CobbleSpawnersConfigData(
    override val version: String = "2.1.7",
    override val configId: String = "cobblespawners",
    var globalConfig: GlobalConfig = GlobalConfig()
) : ConfigData {
    val spawners: MutableList<SpawnerData>
        get() = SpawnerListProxy
}

private object SpawnerListProxy : AbstractMutableList<SpawnerData>() {
    override val size: Int get() = CobbleSpawnersConfig.spawners.size

    override fun get(index: Int): SpawnerData = CobbleSpawnersConfig.spawners.values.elementAt(index)

    override fun add(element: SpawnerData): Boolean {
        CobbleSpawnersConfig.spawners[element.spawnerPos.toBlockPos()] = element
        return true
    }

    override fun add(index: Int, element: SpawnerData) {
        add(element)
    }

    override fun removeAt(index: Int): SpawnerData {
        val item = get(index)
        CobbleSpawnersConfig.spawners.remove(item.spawnerPos.toBlockPos())
        return item
    }

    override fun set(index: Int, element: SpawnerData): SpawnerData {
        val old = get(index)
        if (old.spawnerPos != element.spawnerPos) {
            CobbleSpawnersConfig.spawners.remove(old.spawnerPos.toBlockPos())
        }
        CobbleSpawnersConfig.spawners[element.spawnerPos.toBlockPos()] = element
        return old
    }

    override fun remove(element: SpawnerData): Boolean {
        return CobbleSpawnersConfig.spawners.remove(element.spawnerPos.toBlockPos()) != null
    }

    override fun removeIf(filter: Predicate<in SpawnerData>): Boolean {
        var removed = false
        val keys = CobbleSpawnersConfig.spawners.keys.toList()
        for (pos in keys) {
            val data = CobbleSpawnersConfig.spawners[pos]
            if (data != null && filter.test(data)) {
                if (CobbleSpawnersConfig.spawners.remove(pos) != null) {
                    removed = true
                }
            }
        }
        return removed
    }

    override fun iterator(): MutableIterator<SpawnerData> {
        return CobbleSpawnersConfig.spawners.values.iterator()
    }
}

data class SpawnerData(
    override val version: String = "2.1.7",
    override val configId: String = "cobblespawners",
    // SerializableBlockPos instead of BlockPos — Gson writes clean x/y/z always
    val spawnerPos: SerializableBlockPos = SerializableBlockPos(),
    var spawnerName: String = "default_spawner",
    var selectedPokemon: MutableList<PokemonSpawnEntry> = mutableListOf(),
    val dimension: String = "minecraft:overworld",
    var spawnTimerTicks: Long = 200,
    var spawnRadius: SpawnRadius? = SpawnRadius(),
    var spawnLimit: Int = 4,
    var spawnAmountPerSpawn: Int = 1,
    var visible: Boolean = true,
    var lowLevelEntitySpawn: Boolean = false,
    var wanderingSettings: WanderingSettings? = WanderingSettings(),
    var forceChunkLoading: Boolean = true,
    var chunkLoadRadius: Int = 1,
    var requirePlayerInRange: Boolean = true,
    var playerActivationRange: Int = 30
) : ConfigData

data class CaptureSettings(
    var isCatchable: Boolean = true,
    var restrictCaptureToLimitedBalls: Boolean = false,
    var requiredPokeBalls: List<String> = listOf("safari_ball")
)

data class IVSettings(
    var allowCustomIvs: Boolean = false,
    var minIVHp: Int = 0, var maxIVHp: Int = 31,
    var minIVAttack: Int = 0, var maxIVAttack: Int = 31,
    var minIVDefense: Int = 0, var maxIVDefense: Int = 31,
    var minIVSpecialAttack: Int = 0, var maxIVSpecialAttack: Int = 31,
    var minIVSpecialDefense: Int = 0, var maxIVSpecialDefense: Int = 31,
    var minIVSpeed: Int = 0, var maxIVSpeed: Int = 31
)

data class EVSettings(
    var allowCustomEvsOnDefeat: Boolean = false,
    var evHp: Int = 0, var evAttack: Int = 0, var evDefense: Int = 0,
    var evSpecialAttack: Int = 0, var evSpecialDefense: Int = 0, var evSpeed: Int = 0
)

data class SpawnSettings(
    var spawnTime: String = "ALL",
    var spawnWeather: String = "ALL",
    var spawnLocation: String = "ALL"
)

data class SizeSettings(
    var allowCustomSize: Boolean = false,
    var minSize: Float = 1.0f,
    var maxSize: Float = 1.0f
)

data class HeldItemsOnSpawn(
    var allowHeldItemsOnSpawn: Boolean = false,
    var itemsWithChance: Map<String, Double> = mapOf("minecraft:cobblestone" to 0.1, "cobblemon:pokeball" to 100.0)
)

data class PokemonSpawnEntry(
    val pokemonName: String,
    var formName: String? = null,
    var aspects: Set<String> = emptySet(),
    var spawnChance: Double,
    var spawnChanceType: SpawnChanceType = SpawnChanceType.COMPETITIVE,
    var minLevel: Int,
    var maxLevel: Int,
    var sizeSettings: SizeSettings = SizeSettings(),
    val captureSettings: CaptureSettings,
    val ivSettings: IVSettings,
    val evSettings: EVSettings,
    val spawnSettings: SpawnSettings,
    var heldItemsOnSpawn: HeldItemsOnSpawn = HeldItemsOnSpawn(),
    var moves: MovesSettings? = null
)

data class MovesSettings(
    val allowCustomInitialMoves: Boolean = false,
    val selectedMoves: List<LeveledMove> = emptyList()
) {
    val initialMoves: List<String> get() = selectedMoves.map { it.moveId }
    val initialMovesWithLevels: List<LeveledMove> get() = selectedMoves
}

data class LeveledMove(val level: Int, val moveId: String, val forced: Boolean = false)
enum class SpawnChanceType { COMPETITIVE, INDEPENDENT }
data class SpawnRadius(var width: Int = 4, var height: Int = 4)
data class WanderingSettings(var enabled: Boolean = true, var wanderType: String = "RADIUS", var wanderDistance: Int = 6)

object CobbleSpawnersConfig {
    private val logger = LoggerFactory.getLogger("CobbleSpawnersConfig")
    private const val CURRENT_VERSION = "2.1.7"
    private const val MOD_ID = "cobblespawners"

    private val mainConfigDir = File("config/cobblespawners")
    private val spawnersDir = File(mainConfigDir, "spawners")

    private lateinit var configManager: ConfigManager<CobbleSpawnersConfigData>
    private var isInitialized = false

    // This gson is used ONLY for reading files from disk during loadSpawnersFromDisk,
    // specifically to handle legacy field_XXXXX keys. ConfigManager uses its own gson
    // for all saving, which is fine because SerializableBlockPos has plain x/y/z fields.
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .registerTypeAdapter(SerializableBlockPos::class.java, SerializableBlockPosAdapter())
        .create()

    private val spawnerFileMap = ConcurrentHashMap<BlockPos, String>()

    val lastSpawnTicks: ConcurrentHashMap<BlockPos, Long> = ConcurrentHashMap()

    val config: CobbleSpawnersConfigData
        get() = if (::configManager.isInitialized) configManager.getCurrentConfig() else CobbleSpawnersConfigData()

    // Runtime map is still keyed by BlockPos for convenience everywhere else in the mod
    val spawners: MutableMap<BlockPos, SpawnerData> = object : java.util.AbstractMap<BlockPos, SpawnerData>() {
        override val entries: MutableSet<MutableMap.MutableEntry<BlockPos, SpawnerData>>
            get() = spawnerFileMap.keys.mapNotNull { pos ->
                val data = get(pos) ?: return@mapNotNull null
                java.util.AbstractMap.SimpleEntry(pos, data)
            }.toMutableSet()

        override fun get(key: BlockPos): SpawnerData? {
            val fileName = spawnerFileMap[key] ?: return null
            return configManager.getSecondaryConfig(fileName)
        }

        override fun put(key: BlockPos, value: SpawnerData): SpawnerData? {
            registerOrUpdateSpawner(key, value)
            return value
        }

        override fun remove(key: BlockPos): SpawnerData? {
            val data = get(key)
            if (removeSpawnerFile(key)) return data
            return null
        }

        override fun containsKey(key: BlockPos): Boolean = spawnerFileMap.containsKey(key)
        override val size: Int get() = spawnerFileMap.size
    }

    private fun logDebug(message: String) {
        LogDebug.debug(message, MOD_ID)
    }

    fun initializeAndLoad() {
        if (isInitialized) return
        LogDebug.init(MOD_ID, false)

        mainConfigDir.mkdirs()
        spawnersDir.mkdirs()

        performMigrationIfNeeded()

        configManager = ConfigManager(
            currentVersion = CURRENT_VERSION,
            defaultConfig = CobbleSpawnersConfigData(),
            configClass = CobbleSpawnersConfigData::class,
            configDir = Paths.get("config"),
            metadata = ConfigMetadata(
                headerComments = listOf("CobbleSpawners Main Configuration"),
                watcherSettings = WatcherSettings(enabled = true, autoSaveEnabled = true)
            )
        )

        updateDebugState()

        runBlocking {
            loadSpawnersFromDisk()
        }

        isInitialized = true
    }

    fun reloadBlocking() {
        runBlocking {
            if (::configManager.isInitialized) {
                configManager.reloadConfig()
                loadSpawnersFromDisk()
            }
        }
        updateDebugState()
    }

    private fun performMigrationIfNeeded() {
        val configFile = File(mainConfigDir, "config.jsonc")
        if (!configFile.exists()) return

        try {
            val content = configFile.readText()
            if (!content.contains("\"spawners\"")) return

            logger.info("Legacy 'spawners' list found. Attempting migration to multi-file format...")

            try {
                configFile.copyTo(File(mainConfigDir, "config.jsonc.backup"), overwrite = true)
                logger.info("Successfully created a backup of config.jsonc before migration.")
            } catch (e: Exception) {
                logger.error("Failed to create backup before migration. Aborting to protect data.", e)
                return
            }

            // Use a lenient JsonReader so comments and trailing commas in .jsonc don't crash parsing
            val lenientReader = com.google.gson.stream.JsonReader(java.io.StringReader(content)).apply {
                isLenient = true
            }
            val jsonObject = com.google.gson.JsonParser.parseReader(lenientReader).asJsonObject

            if (jsonObject.has("spawners") && jsonObject.get("spawners").isJsonArray) {
                val spawnersArray = jsonObject.getAsJsonArray("spawners")
                var allSuccess = true
                var migratedCount = 0

                if (spawnersArray.size() > 0) {
                    spawnersArray.forEach { element ->
                        try {
                            val data = gson.fromJson(element, SpawnerData::class.java)
                            val pos = data.spawnerPos.toBlockPos()
                            val fileName = getFileNameForPos(pos)
                            val file = File(spawnersDir, fileName.substringAfter("spawners/"))
                            file.parentFile.mkdirs()
                            file.writeText(gson.toJson(data))
                            migratedCount++
                        } catch (e: Exception) {
                            logger.error("Failed to migrate a specific spawner entry.", e)
                            allSuccess = false
                        }
                    }

                    if (allSuccess) {
                        jsonObject.remove("spawners")
                        configFile.writeText(gson.toJson(jsonObject))
                        logger.info("Migration successful. $migratedCount spawners moved to /spawners/ folder.")
                    } else {
                        logger.warn("Migration completed with errors. $migratedCount succeeded but some failed.")
                        logger.warn("The 'spawners' list was NOT removed from config.jsonc to prevent data loss.")
                    }
                } else {
                    jsonObject.remove("spawners")
                    configFile.writeText(gson.toJson(jsonObject))
                    logger.info("Found an empty legacy 'spawners' list. Removed from config.jsonc.")
                }
            }
        } catch (e: Exception) {
            logger.error("Critical error during configuration migration. No files were modified.", e)
        }
    }

    private suspend fun loadSpawnersFromDisk() {
        spawnerFileMap.clear()

        val files = spawnersDir.listFiles { _, name -> name.endsWith(".json") || name.endsWith(".jsonc") }
        if (files.isNullOrEmpty()) return

        files.forEach { file ->
            try {
                val relativeName = "spawners/${file.name}"

                val lenientReader = com.google.gson.stream.JsonReader(file.reader()).apply {
                    isLenient = true
                }

                // Explicit type required — fromJson(JsonReader, Class<T>) returns a platform type
                // that Kotlin can't infer, causing spawnerPos to be unresolvable without it
                val rawData: SpawnerData = gson.fromJson(lenientReader, SpawnerData::class.java)
                    ?: return@forEach

                applyDefaultsToSpawner(rawData)
                val pos = rawData.spawnerPos.toBlockPos()
                spawnerFileMap[pos] = relativeName

                configManager.registerSecondaryConfig(
                    fileName = relativeName,
                    configClass = SpawnerData::class,
                    defaultConfig = rawData,
                    fileMetadata = ConfigMetadata(
                        watcherSettings = WatcherSettings(enabled = true, autoSaveEnabled = true)
                    )
                )

                configManager.saveSecondaryConfig(relativeName, rawData)

            } catch (e: Exception) {
                logger.error("Failed to load or repair spawner file: ${file.name}", e)
            }
        }
    }

    private fun registerOrUpdateSpawner(pos: BlockPos, data: SpawnerData) {
        val fileName = spawnerFileMap[pos] ?: getFileNameForPos(pos)
        spawnerFileMap[pos] = fileName

        runBlocking {
            configManager.registerSecondaryConfig(
                fileName = fileName,
                configClass = SpawnerData::class,
                defaultConfig = data,
                fileMetadata = ConfigMetadata(
                    watcherSettings = WatcherSettings(enabled = true, autoSaveEnabled = true)
                )
            )
        }
    }

    private fun saveSpecificSpawner(pos: BlockPos) {
        val fileName = spawnerFileMap[pos] ?: return
        val data = spawners[pos] ?: return
        runBlocking {
            configManager.saveSecondaryConfig(fileName, data)
        }
    }

    private fun getFileNameForPos(pos: BlockPos): String {
        return "spawners/spawner_${pos.x}_${pos.y}_${pos.z}.jsonc"
    }

    private fun removeSpawnerFile(pos: BlockPos): Boolean {
        val fileName = spawnerFileMap.remove(pos) ?: return false
        configManager.unregisterConfig(fileName)
        val file = File(mainConfigDir, fileName)
        if (file.exists()) {
            file.delete()
            return true
        }
        return false
    }

    private fun updateDebugState() {
        LogDebug.setDebugEnabledForMod(MOD_ID, config.globalConfig.debugEnabled)
    }

    private fun applyDefaultsToSpawner(spawner: SpawnerData) {
        if (spawner.spawnTimerTicks <= 0) spawner.spawnTimerTicks = 200
        if (spawner.spawnRadius == null) spawner.spawnRadius = SpawnRadius()
        if (spawner.wanderingSettings == null) spawner.wanderingSettings = WanderingSettings()
        spawner.spawnRadius?.let { setNullFieldsToDefaultsForNested(it, SpawnRadius::class) }
        spawner.wanderingSettings?.let { setNullFieldsToDefaultsForNested(it, WanderingSettings::class) }
    }

    private fun <T : Any> setNullFieldsToDefaultsForNested(instance: T, clazz: KClass<T>) {
        val defaultInstance = clazz.createInstance()
        clazz.memberProperties.forEach { property ->
            if (property is KMutableProperty<*>) {
                val currentValue = property.getter.call(instance)
                if (currentValue == null) {
                    property.setter.call(instance, property.getter.call(defaultInstance))
                }
            }
        }
    }

    fun saveSpawnerData() {
        runBlocking { configManager.saveConfig(config) }
    }

    fun saveConfigBlocking() {
        runBlocking { configManager.saveConfig(config) }
    }

    fun updateLastSpawnTick(spawnerPos: BlockPos, tick: Long) {
        lastSpawnTicks[spawnerPos] = tick
    }

    fun getLastSpawnTick(spawnerPos: BlockPos): Long {
        return lastSpawnTicks[spawnerPos] ?: 0L
    }

    fun addSpawner(spawnerPos: BlockPos, dimension: String): Boolean {
        if (spawners.containsKey(spawnerPos)) {
            logDebug("Spawner at $spawnerPos already exists.")
            return false
        }
        val spawnerData = SpawnerData(
            spawnerPos = SerializableBlockPos.fromBlockPos(spawnerPos),
            dimension = dimension
        )
        spawners[spawnerPos] = spawnerData
        registerOrUpdateSpawner(spawnerPos, spawnerData)
        saveSpecificSpawner(spawnerPos)
        return true
    }

    fun getSpawner(spawnerPos: BlockPos): SpawnerData? {
        return spawners[spawnerPos]
    }

    fun removeSpawner(spawnerPos: BlockPos): Boolean {
        if (spawners.remove(spawnerPos) != null) {
            lastSpawnTicks.remove(spawnerPos)
            logDebug("Removed spawner at $spawnerPos.")
            return true
        }
        return false
    }

    fun updateSpawner(spawnerPos: BlockPos, update: (SpawnerData) -> Unit): SpawnerData? {
        val spawnerData = spawners[spawnerPos] ?: return null
        update(spawnerData)
        saveSpecificSpawner(spawnerPos)
        ParticleUtils.invalidateWireframeCache(spawnerPos)
        return spawnerData
    }

    fun updatePokemonSpawnEntry(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        additionalAspects: Set<String> = emptySet(),
        update: (PokemonSpawnEntry) -> Unit
    ): PokemonSpawnEntry? {
        var result: PokemonSpawnEntry? = null
        updateSpawner(spawnerPos) { spawner ->
            val selectedEntry = spawner.selectedPokemon.find {
                it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                        (it.formName?.equals(formName, ignoreCase = true) ?: (formName == null)) &&
                        it.aspects.map { a -> a.lowercase() }.toSet() == additionalAspects.map { a -> a.lowercase() }.toSet()
            }
            if (selectedEntry != null) {
                update(selectedEntry)
                selectedEntry.sizeSettings.minSize = roundToOneDecimal(selectedEntry.sizeSettings.minSize)
                selectedEntry.sizeSettings.maxSize = roundToOneDecimal(selectedEntry.sizeSettings.maxSize)
                result = selectedEntry
            }
        }
        return result
    }

    fun getPokemonSpawnEntry(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String,
        aspects: Set<String> = emptySet()
    ): PokemonSpawnEntry? {
        val spawnerData = spawners[spawnerPos] ?: return null
        return spawnerData.selectedPokemon.find {
            it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                    (it.formName?.equals(formName, ignoreCase = true) ?: false) &&
                    it.aspects.map { a -> a.lowercase() }.toSet() == aspects.map { a -> a.lowercase() }.toSet()
        }
    }

    fun addPokemonSpawnEntry(spawnerPos: BlockPos, entry: PokemonSpawnEntry): Boolean {
        var success = false
        updateSpawner(spawnerPos) { spawner ->
            if (!spawner.selectedPokemon.any {
                    it.pokemonName.equals(entry.pokemonName, ignoreCase = true) &&
                            (it.formName?.equals(entry.formName, ignoreCase = true) ?: (entry.formName == null))
                }) {
                spawner.selectedPokemon.add(entry)
                success = true
            }
        }
        return success
    }

    fun removePokemonSpawnEntry(spawnerPos: BlockPos, pokemonName: String, formName: String?): Boolean {
        var success = false
        updateSpawner(spawnerPos) { spawner ->
            val removed = spawner.selectedPokemon.removeIf {
                it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                        (it.formName?.equals(formName, ignoreCase = true) ?: (formName == null))
            }
            if (removed) success = true
        }
        return success
    }

    fun addDefaultPokemonToSpawner(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        aspects: Set<String> = emptySet()
    ): Boolean {
        var success = false
        updateSpawner(spawnerPos) { spawner ->
            if (!spawner.selectedPokemon.any {
                    it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                            (it.formName?.equals(formName, ignoreCase = true) ?: (formName == null)) &&
                            it.aspects.map { a -> a.lowercase() }.toSet() == aspects.map { a -> a.lowercase() }.toSet()
                }) {
                val newEntry = createDefaultPokemonEntry(pokemonName, formName, aspects)
                spawner.selectedPokemon.add(newEntry)
                success = true
            }
        }
        return success
    }

    fun removeAndSavePokemonFromSpawner(
        spawnerPos: BlockPos,
        pokemonName: String,
        formName: String?,
        aspects: Set<String> = emptySet()
    ): Boolean {
        var success = false
        updateSpawner(spawnerPos) { spawner ->
            val removed = spawner.selectedPokemon.removeIf {
                it.pokemonName.equals(pokemonName, ignoreCase = true) &&
                        (it.formName?.equals(formName, ignoreCase = true) ?: (formName == null)) &&
                        it.aspects.map { a -> a.lowercase() }.toSet() == aspects.map { a -> a.lowercase() }.toSet()
            }
            if (removed) success = true
        }
        return success
    }

    fun removeAndSaveSpawner(spawnerPos: BlockPos): Boolean {
        return removeSpawner(spawnerPos)
    }

    fun createDefaultSpawner(spawnerPos: BlockPos, dimension: String, spawnerName: String): SpawnerData {
        val spawnerData = SpawnerData(
            spawnerPos = SerializableBlockPos.fromBlockPos(spawnerPos),
            spawnerName = spawnerName,
            dimension = dimension
        )
        spawners[spawnerPos] = spawnerData
        registerOrUpdateSpawner(spawnerPos, spawnerData)
        saveSpecificSpawner(spawnerPos)
        return spawnerData
    }

    fun createDefaultPokemonEntry(
        pokemonName: String,
        formName: String? = null,
        aspects: Set<String> = emptySet()
    ): PokemonSpawnEntry {
        val species = PokemonSpecies.getByName(pokemonName.lowercase())
            ?: throw IllegalArgumentException("Unknown Pokémon: $pokemonName")
        val defaultMoves = getDefaultInitialMoves(species)

        return PokemonSpawnEntry(
            pokemonName = pokemonName,
            formName = formName,
            aspects = aspects,
            spawnChance = if (aspects.any { it.equals("shiny", ignoreCase = true) }) 0.0122 else 50.0,
            spawnChanceType = SpawnChanceType.COMPETITIVE,
            minLevel = 1,
            maxLevel = 100,
            sizeSettings = SizeSettings(allowCustomSize = false, minSize = 1.0f, maxSize = 1.0f),
            captureSettings = CaptureSettings(
                isCatchable = true,
                restrictCaptureToLimitedBalls = false,
                requiredPokeBalls = listOf("poke_ball")
            ),
            ivSettings = IVSettings(),
            evSettings = EVSettings(),
            spawnSettings = SpawnSettings(),
            heldItemsOnSpawn = HeldItemsOnSpawn(),
            moves = MovesSettings(
                allowCustomInitialMoves = false,
                selectedMoves = defaultMoves
            )
        )
    }

    fun getDefaultInitialMoves(species: Species): List<LeveledMove> {
        val movesByLevel = mutableListOf<LeveledMove>()
        species.moves.levelUpMoves.forEach { (level, movesAtLevel) ->
            if (level > 0) {
                movesAtLevel.forEach { move ->
                    movesByLevel.add(LeveledMove(level, move.name))
                }
            }
        }
        movesByLevel.sortBy { it.level }
        return movesByLevel
    }

    private fun roundToOneDecimal(value: Float): Float {
        return (value * 10).roundToInt() / 10f
    }

    // Handles legacy files that contain obfuscated field_XXXXX keys.
    // Once a file is loaded and re-saved, it will always have clean x/y/z going forward.
    private class SerializableBlockPosAdapter : TypeAdapter<SerializableBlockPos>() {
        override fun write(out: JsonWriter, value: SerializableBlockPos?) {
            if (value == null) { out.nullValue(); return }
            out.beginObject()
            out.name("x").value(value.x)
            out.name("y").value(value.y)
            out.name("z").value(value.z)
            out.endObject()
        }

        override fun read(reader: JsonReader): SerializableBlockPos {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return SerializableBlockPos()
            }
            val map = mutableMapOf<String, Int>()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                map[name] = reader.nextInt()
            }
            reader.endObject()
            return SerializableBlockPos(
                x = map["x"] ?: map["field_11175"] ?: map["field_11176"] ?: 0,
                y = map["y"] ?: map["field_11174"] ?: map["field_11177"] ?: 0,
                z = map["z"] ?: map["field_11173"] ?: map["field_11178"] ?: 0
            )
        }
    }
}