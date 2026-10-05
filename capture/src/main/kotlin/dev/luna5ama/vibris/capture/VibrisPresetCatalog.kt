package dev.luna5ama.vibris.capture

import dev.vibris.api.ContextValidationResult
import dev.vibris.api.SceneContext
import dev.vibris.api.ScenePreset
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import java.util.HashMap
import java.util.HashSet

class VibrisPresetCatalog private constructor(
    private val path: Path,
    presets: Map<String, Preset>,
    initialFileBytes: ByteArray,
) : AutoCloseable {
    @Volatile
    private var values = java.util.Map.copyOf(presets)
    private var fileBytes = initialFileBytes.copyOf()
    private val externallyChangedIds = HashSet<String>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val watchService = registerWatchService(path)
    private val watchThread = Thread(this::watchFile, "VibrisPresetCatalog-${path.fileName}")

    init {
        watchThread.isDaemon = true
        watchThread.start()
    }

    @Synchronized
    fun save(preset: Preset): String {
        for (attempt in 0 until MAX_SAVE_ATTEMPTS) {
            refreshFromDisk(true)
            val saveId = values.values.firstOrNull { it.saveName == preset.saveName }?.saveId ?: preset.saveId
            val stored = preset.copy(saveId = saveId)
            if (stored.id in externallyChangedIds) {
                return stored.id
            }

            val bytesBeforeWrite = Files.readAllBytes(path)
            if (!bytesBeforeWrite.contentEquals(fileBytes)) {
                continue
            }

            val updated = HashMap(values)
            updated[stored.id] = stored
            val writtenBytes = write(path, updated)
            val bytesAfterWrite = Files.readAllBytes(path)
            if (!bytesAfterWrite.contentEquals(writtenBytes)) {
                refreshFromDisk(true)
                continue
            }

            values = java.util.Map.copyOf(updated)
            fileBytes = bytesAfterWrite
            return stored.id
        }
        throw IOException("Vibris preset file changed while saving: $path")
    }

    @Synchronized
    fun resolve(context: SceneContext): ResolvedContext {
        refreshFromDisk(false)
        val preset = requirePreset(context, false)
        return ResolvedContext(
            preset.saveName,
            preset.tick,
            preset.weather,
            CameraPreset(preset.x, preset.y, preset.z, preset.yaw, preset.pitch),
        )
    }

    @Synchronized
    fun presets(): List<ScenePreset> {
        refreshFromDisk(false)
        return values.values
            .sortedBy { it.id }
            .map { ScenePreset(it.id, it.id, it.context(), SCHEMA_VERSION.toString(), effectiveTags(it), it.description) }
    }

    @Synchronized
    fun validate(context: SceneContext): ContextValidationResult {
        refreshFromDisk(false)
        return try {
            requirePreset(context, true)
            ContextValidationResult.accepted()
        } catch (exception: IllegalArgumentException) {
            ContextValidationResult.invalid(exception.message!!)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        try {
            watchService.close()
        } catch (_: IOException) {
            // The watcher is already being shut down.
        }
        watchThread.interrupt()
    }

    private fun watchFile() {
        while (!closed.get()) {
            val key = try {
                watchService.take()
            } catch (_: InterruptedException) {
                return
            } catch (_: ClosedWatchServiceException) {
                return
            }

            var relevant = false
            for (event in key.pollEvents()) {
                if (event.kind() == OVERFLOW) {
                    relevant = true
                    continue
                }
                val changed = event.context() as? Path
                if (changed == path.fileName &&
                    (event.kind() == ENTRY_CREATE || event.kind() == ENTRY_DELETE || event.kind() == ENTRY_MODIFY)) {
                    relevant = true
                }
            }
            if (!key.reset()) {
                return
            }
            if (relevant) {
                reloadFromDisk()
            }
        }
    }

    private fun reloadFromDisk() {
        repeat(RELOAD_ATTEMPTS) {
            if (closed.get()) {
                return
            }
            val loaded = synchronized(this) {
                refreshFromDisk(false)
            }
            if (loaded) {
                return
            }
            try {
                Thread.sleep(RELOAD_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Refreshes the cache only after a complete, valid file can be read. The file watcher may
     * observe an editor's intermediate/truncated write, so those snapshots are retried and never
     * replace the last valid in-memory copy.
     */
    private fun refreshFromDisk(strict: Boolean): Boolean {
        val bytes = try {
            Files.readAllBytes(path)
        } catch (exception: IOException) {
            if (strict) {
                throw exception
            }
            return false
        }
        if (bytes.contentEquals(fileBytes)) {
            return true
        }

        val loaded = try {
            parse(path, bytes)
        } catch (exception: IOException) {
            if (strict) {
                throw exception
            }
            return false
        }
        rememberExternalChanges(values, loaded)
        values = java.util.Map.copyOf(loaded)
        fileBytes = bytes
        return true
    }

    private fun rememberExternalChanges(previous: Map<String, Preset>, current: Map<String, Preset>) {
        val changedIds = HashSet<String>()
        changedIds.addAll(previous.keys)
        changedIds.addAll(current.keys)
        externallyChangedIds.addAll(changedIds.filter { previous[it] != current[it] })
    }

    private fun requirePreset(context: SceneContext, allowIncomplete: Boolean): Preset {
        val preset = values[context.cameraPresetId]
            ?: throw IllegalArgumentException("Unknown preset: ${context.cameraPresetId}")
        require(context.timePresetId == preset.id) { "Time and camera must select the same preset" }
        require(context.saveId == preset.saveId) { "Preset belongs to another save" }
        require(context.dimensionId == preset.dimensionId) { "Preset belongs to another dimension" }
        require(context.fov == preset.fov) { "Field of view does not match the selected preset" }
        if (!allowIncomplete || context.weatherPresetId.isNotEmpty()) {
            require(context.weatherPresetId == preset.weather) { "Weather does not match the selected preset" }
        }
        if (!allowIncomplete || context.settingsPresetId.isNotEmpty()) {
            require(context.settingsPresetId == preset.settingsPresetId) {
                "Settings do not match the selected preset"
            }
        }
        if (!allowIncomplete || context.resolution.isSpecified()) {
            require(context.resolution == preset.resolution) { "Resolution does not match the selected preset" }
        }
        return preset
    }

    @JvmRecord
    data class ResolvedContext(
        val saveName: String,
        val tick: Long,
        val weather: String,
        val camera: CameraPreset,
    )

    @JvmRecord
    data class CameraPreset(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    )

    @JvmRecord
    data class Preset @JvmOverloads constructor(
        val id: String,
        val saveId: String,
        val saveName: String,
        val dimensionId: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val fov: Double,
        val tick: Long,
        val weather: String,
        val resolution: SceneContext.Resolution,
        val settingsPresetId: String,
        val tags: List<String>,
        val description: String = "",
    ) {
        constructor(
            id: String,
            saveId: String,
            saveName: String,
            dimensionId: String,
            x: Double,
            y: Double,
            z: Double,
            yaw: Float,
            pitch: Float,
            fov: Double,
            tick: Long,
            weather: String,
            resolution: SceneContext.Resolution,
            settingsPresetId: String,
        ) : this(
            id, saveId, saveName, dimensionId, x, y, z, yaw, pitch, fov, tick, weather, resolution,
            settingsPresetId, emptyList(),
        )

        init {
            require(id.isNotBlank() && '/' !in id) { "Invalid preset id" }
            require(saveId.isNotBlank() && saveName.isNotBlank() && dimensionId.isNotBlank()) {
                "World preset fields are blank"
            }
            require(x.isFinite() && y.isFinite() && z.isFinite() && yaw.isFinite() && pitch.isFinite()) {
                "Camera values must be finite"
            }
            require(fov.isFinite() && fov > 0.0 && fov < 180.0) { "Invalid field of view" }
            require(weather == "clear" || weather == "rain" || weather == "thunder") {
                "Unknown weather preset: $weather"
            }
            require(resolution.isSpecified()) { "Preset resolution is unspecified" }
            require(settingsPresetId.isNotBlank()) { "Settings preset is blank" }
            require(tags.all { it.matches(TAG_PATTERN) } && tags.distinct().size == tags.size) {
                "Preset tags must be unique lowercase identifiers"
            }
        }

        fun context(): SceneContext = SceneContext(
            saveId,
            dimensionId,
            id,
            weather,
            id,
            fov,
            resolution,
            settingsPresetId,
        )
    }

    companion object {
        private const val SCHEMA_VERSION = 2
        private const val MAX_SAVE_ATTEMPTS = 4
        private const val RELOAD_ATTEMPTS = 8
        private const val RELOAD_RETRY_DELAY_MS = 25L
        private val TAG_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        internal fun tagsFor(presetId: String): List<String> = buildList {
            if (presetId.startsWith("sky-")) add("sky")
            if (presetId.startsWith("aerial-perspective-")) add("aerial-perspective")
            if (presetId.startsWith("raster-")) add("raster")
            if (presetId.startsWith("shadow-")) add("shadow")
        }

        private fun effectiveTags(preset: Preset): List<String> =
            (preset.tags.ifEmpty { tagsFor(preset.id) }).sorted()

        @JvmStatic
        @Throws(IOException::class)
        fun load(path: Path): VibrisPresetCatalog {
            val absolutePath = path.toAbsolutePath().normalize()
            val bytes = Files.readAllBytes(absolutePath)
            return VibrisPresetCatalog(absolutePath, parse(absolutePath, bytes), bytes)
        }

        private fun registerWatchService(path: Path): WatchService {
            val service = FileSystems.getDefault().newWatchService()
            try {
                path.parent.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                return service
            } catch (failure: IOException) {
                try {
                    service.close()
                } catch (closeFailure: IOException) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        private fun parse(path: Path, bytes: ByteArray): Map<String, Preset> {
            try {
                val root = Json.parseToJsonElement(String(bytes, StandardCharsets.UTF_8)) as JsonObject
                if (integer(root, "schema_version") != SCHEMA_VERSION) {
                    throw IOException("Unsupported Vibris preset schema")
                }
                val presets = HashMap<String, Preset>()
                for (element in array(root, "presets")) {
                    val value = element as JsonObject
                    val id = string(value, "id")
                    val position = array(value, "position")
                    val resolution = array(value, "resolution")
                    require(position.size == 3) { "Preset position must have three values" }
                    require(resolution.size == 2) { "Preset resolution must have two values" }
                    val preset = Preset(
                        id,
                        string(value, "save_id"),
                        string(value, "save_name"),
                        string(value, "dimension_id"),
                        position[0].jsonPrimitive.double,
                        position[1].jsonPrimitive.double,
                        position[2].jsonPrimitive.double,
                        value.getValue("yaw").jsonPrimitive.float,
                        value.getValue("pitch").jsonPrimitive.float,
                        value.getValue("fov").jsonPrimitive.double,
                        value.getValue("tick").jsonPrimitive.long,
                        string(value, "weather"),
                        SceneContext.Resolution(
                            resolution[0].jsonPrimitive.int,
                            resolution[1].jsonPrimitive.int,
                        ),
                        string(value, "settings_preset_id"),
                        tags(value, id),
                        value["description"]?.let {
                            val primitive = it as? JsonPrimitive ?: throw IllegalArgumentException("Invalid preset description")
                            require(primitive.isString) { "Preset description must be a string" }
                            primitive.content
                        }.orEmpty(),
                    )
                    if (presets.putIfAbsent(preset.id, preset) != null) {
                        throw IllegalArgumentException("Duplicate preset id: ${preset.id}")
                    }
                }
                return presets
            } catch (exception: IOException) {
                throw exception
            } catch (exception: RuntimeException) {
                throw IOException("Invalid Vibris preset file: $path", exception)
            }
        }

        private fun array(value: JsonObject, name: String): JsonArray =
            value[name] as? JsonArray ?: throw IllegalArgumentException("Missing array: $name")

        private fun string(value: JsonObject, name: String): String =
            value.getValue(name).jsonPrimitive.content.also {
                require(it.isNotBlank()) { "Blank preset field: $name" }
            }

        private fun tags(value: JsonObject, presetId: String): List<String> {
            val element = value["tags"] ?: return tagsFor(presetId)
            val tags = element as? JsonArray ?: throw IllegalArgumentException("Invalid preset tags")
            return tags.map { tag ->
                val primitive = tag as? JsonPrimitive ?: throw IllegalArgumentException("Invalid preset tag")
                require(primitive.isString) { "Preset tags must be strings" }
                primitive.content
            }
        }

        private fun integer(value: JsonObject, name: String): Int = value.getValue(name).jsonPrimitive.int

        @OptIn(ExperimentalSerializationApi::class)
        private fun write(path: Path, presets: Map<String, Preset>): ByteArray {
            val root = buildJsonObject {
                put("schema_version", JsonPrimitive(SCHEMA_VERSION))
                put("presets", buildJsonArray {
                    for (preset in presets.values.sortedBy { it.id }) add(buildJsonObject {
                        put("id", JsonPrimitive(preset.id))
                        put("save_id", JsonPrimitive(preset.saveId))
                        put("save_name", JsonPrimitive(preset.saveName))
                        put("dimension_id", JsonPrimitive(preset.dimensionId))
                        put("position", buildJsonArray {
                            add(JsonPrimitive(preset.x))
                            add(JsonPrimitive(preset.y))
                            add(JsonPrimitive(preset.z))
                        })
                        put("yaw", JsonPrimitive(preset.yaw))
                        put("pitch", JsonPrimitive(preset.pitch))
                        put("fov", JsonPrimitive(preset.fov))
                        put("tick", JsonPrimitive(preset.tick))
                        put("weather", JsonPrimitive(preset.weather))
                        put("resolution", buildJsonArray {
                            add(JsonPrimitive(preset.resolution.width))
                            add(JsonPrimitive(preset.resolution.height))
                        })
                        put("settings_preset_id", JsonPrimitive(preset.settingsPresetId))
                        put("tags", buildJsonArray {
                            effectiveTags(preset).forEach { add(JsonPrimitive(it)) }
                        })
                        if (preset.description.isNotEmpty()) put("description", JsonPrimitive(preset.description))
                    })
                })
            }
            val absolutePath = path.toAbsolutePath()
            Files.createDirectories(absolutePath.parent)
            val temporary = Files.createTempFile(absolutePath.parent, absolutePath.fileName.toString(), ".tmp")
            val contents = Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(root)
            try {
                Files.writeString(temporary, contents)
                try {
                    Files.move(
                        temporary,
                        absolutePath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: IOException) {
                    Files.move(temporary, absolutePath, StandardCopyOption.REPLACE_EXISTING)
                }
                return contents.toByteArray(StandardCharsets.UTF_8)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
