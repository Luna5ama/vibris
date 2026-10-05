package dev.vibris.core

import dev.vibris.api.EffectiveShaderSettings
import dev.vibris.api.RuntimeEnvironment
import dev.vibris.api.SceneContext
import dev.vibris.protocol.v2.EffectiveShaderSetting
import dev.vibris.protocol.v2.EnvironmentProvenance
import dev.vibris.protocol.v2.ResultProvenance
import dev.vibris.protocol.v2.ShaderSettingOrigin
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object BenchmarkProvenance {
    @Volatile
    private var runtimeEnvironment: EnvironmentProvenance? = null

    fun captureRuntimeEnvironment(environment: RuntimeEnvironment) {
        runtimeEnvironment = EnvironmentProvenance.newBuilder()
            .setMinecraftVersion(environment.minecraftVersion)
            .setIrisVersion(environment.irisVersion)
            .setVibrisVersion(environment.vibrisVersion)
            .setJavaVersion(environment.javaVersion)
            .setOperatingSystem(environment.operatingSystem)
            .setGpuVendor(environment.gpuVendor)
            .setGpuRenderer(environment.gpuRenderer)
            .setOpenglVersion(environment.openglVersion)
            .setDriverVersion(environment.driverVersion)
            .build()
    }

    fun result(
        job: CoreJob,
        source: SourceRegistry.Lease,
        settings: EffectiveShaderSettings?,
        scene: SceneContext?,
        shaderLoadedAtUnixMs: Long,
        passMappingSha256: String,
    ): ResultProvenance {
        val reference = source.reference
        val builder = ResultProvenance.newBuilder()
            .setWorkspaceId(job.workspaceId)
            .setBranch(reference.branch)
            .setVcsCheckoutState(reference.vcsCheckoutState)
            .setRequestedRevision(reference.requestedRevision)
            .setResolvedRevision(reference.resolvedRevision)
            .setStartHead(reference.startHead)
            .setCompletionHead(reference.startHead)
            .setHeadChanged(false)
            .setStale(false)
            .setShaderTreeId(reference.shaderTreeId)
            .setPatchSha256(reference.patchSha256)
            .setDirtyShaderDeltaSha256(reference.dirtyShaderDeltaSha256)
            .setSourceSnapshotSha256(source.snapshotSha256)
            .setActiveSourceUuid(source.uuid)
            .setPresetId(job.submission.presetId)
            .setPresetSha256(job.submission.presetSha256)
            .setShaderLoadedAtUnixMs(shaderLoadedAtUnixMs)
            .setEnvironment(requireNotNull(runtimeEnvironment) { "Runtime environment was not captured." })
            .setPassMappingSha256(passMappingSha256)
        builder.worktreeRoot = if (reference.origin.hasWorkspace()) {
            reference.origin.workspace.worktreeRoot
        } else {
            reference.origin.commit.worktreeRoot
        }
        settings?.let {
            builder.configSha256 = it.settingsSha256
            builder.effectiveSettings = effectiveSettings(it)
        }
        scene?.let { builder.sceneSha256 = sceneHash(it) }
        return builder.build()
    }

    fun effectiveSettings(settings: EffectiveShaderSettings): dev.vibris.protocol.v2.EffectiveShaderSettings =
        dev.vibris.protocol.v2.EffectiveShaderSettings.newBuilder()
            .setSettingsSha256(settings.settingsSha256)
            .addAllSettings(settings.settings.map { setting ->
                EffectiveShaderSetting.newBuilder()
                    .setName(setting.name)
                    .setValue(setting.value)
                    .setDefaultValue(setting.defaultValue)
                    .setOrigin(setting.origin.toProtocol())
                    .setChangedFromDefault(setting.changedFromDefault())
                    .build()
            })
            .build()

    fun sceneHash(context: SceneContext): String = sha256(context(context))

    fun presetHash(presetId: String, version: String, displayName: String, context: SceneContext): String =
        sha256(buildJsonObject {
            put("preset_id", presetId)
            put("version", version)
            put("display_name", displayName)
            put("effective_context", context(context))
        })

    private fun context(value: SceneContext): JsonObject = buildJsonObject {
        put("save_id", value.saveId)
        put("dimension_id", value.dimensionId)
        put("time_preset_id", value.timePresetId)
        put("weather_preset_id", value.weatherPresetId)
        put("camera_preset_id", value.cameraPresetId)
        put("fov", value.fov)
        put("resolution", buildJsonObject {
            put("width", value.resolution.width)
            put("height", value.resolution.height)
        })
        put("settings_preset_id", value.settingsPresetId)
    }

    private fun EffectiveShaderSettings.Origin.toProtocol(): ShaderSettingOrigin = when (this) {
        EffectiveShaderSettings.Origin.DEFAULT -> ShaderSettingOrigin.SHADER_SETTING_ORIGIN_DEFAULT
        EffectiveShaderSettings.Origin.PRESERVED_CURRENT ->
            ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESERVED_CURRENT
        EffectiveShaderSettings.Origin.REQUEST_OVERRIDE ->
            ShaderSettingOrigin.SHADER_SETTING_ORIGIN_REQUEST_OVERRIDE
        EffectiveShaderSettings.Origin.PRESET -> ShaderSettingOrigin.SHADER_SETTING_ORIGIN_PRESET
    }

    private fun sha256(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
