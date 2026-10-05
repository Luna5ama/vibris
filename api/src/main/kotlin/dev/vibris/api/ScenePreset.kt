package dev.vibris.api

@JvmRecord
data class ScenePreset @JvmOverloads constructor(
    val presetId: String,
    val displayName: String,
    val context: SceneContext,
    val version: String,
    val tags: List<String>,
    val description: String = "",
) {
    constructor(presetId: String, displayName: String, context: SceneContext, version: String) :
        this(presetId, displayName, context, version, emptyList())

    constructor(presetId: String, displayName: String, context: SceneContext) :
        this(presetId, displayName, context, "1", emptyList())

    init {
        require(presetId.isNotBlank() && displayName.isNotBlank() && version.isNotBlank()) {
            "Preset names are blank"
        }
        require(tags.all { it.matches(TAG_PATTERN) } && tags.distinct().size == tags.size) {
            "Preset tags must be unique lowercase identifiers"
        }
    }

    private companion object {
        val TAG_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }
}
