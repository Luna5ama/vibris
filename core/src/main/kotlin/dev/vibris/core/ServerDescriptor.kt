package dev.vibris.core

import dev.vibris.api.RuntimeStatus
import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.Capability
import dev.vibris.protocol.v2.PassDescriptor
import dev.vibris.protocol.v2.PassStage
import dev.vibris.protocol.v2.ResourceCatalog
import dev.vibris.protocol.v2.ResourceDescriptor
import dev.vibris.protocol.v2.ResourceKind
import dev.vibris.protocol.v2.RuntimePhase
import dev.vibris.protocol.v2.RuntimeReadiness
import dev.vibris.protocol.v2.ScalarType
import dev.vibris.protocol.v2.ServerHello
import dev.vibris.protocol.v2.ServerLimits
import dev.vibris.protocol.v2.ServerState
import dev.vibris.protocol.v2.ServerStatus
import dev.vibris.protocol.v2.StatusDetail
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal class ServerDescriptor @JvmOverloads constructor(
    pending: Path,
    private val artifacts: ArtifactManager,
    private val runtime: VibrisRuntimeAdapter,
    maxSourceBytes: Long = ServerConfiguration.DEFAULT_MAX_SOURCE_BYTES,
    maxSourceFiles: Int = ServerConfiguration.DEFAULT_MAX_SOURCE_FILES,
    maxQueuedJobs: Int = ServerConfiguration.DEFAULT_MAX_GLOBAL_QUEUE,
    maxActionsPerJob: Int = ServerConfiguration.DEFAULT_MAX_ACTIONS_PER_JOB,
) {
    @Volatile
    private var lastReadyStatus: RuntimeStatus? = null

    private val baseHello = ServerHello.newBuilder()
        .setServerVersion("vibris-core")
        .addCapabilities(Capability.CAPABILITY_CONTROL_STREAM)
        .addCapabilities(Capability.CAPABILITY_RUNTIME_LEASE)
        .addCapabilities(Capability.CAPABILITY_STATUS_WAIT)
        .addCapabilities(Capability.CAPABILITY_TRANSACTIONAL_RESTORE)
        .addCapabilities(Capability.CAPABILITY_GRACEFUL_RESTART)
        .apply { if (runtime.supportsCameraMotion()) addCapabilities(Capability.CAPABILITY_CAMERA_MOTION) }
        .setLimits(
            ServerLimits.newBuilder()
                .setMaxSourceBytes(maxSourceBytes)
                .setMaxSourceFiles(maxSourceFiles)
                .setMaxQueuedJobs(maxQueuedJobs)
                .setMaxActionsPerJob(maxActionsPerJob)
                .setMaxStatusWaitMs(VibrisCoreEngine.MAX_STATUS_WAIT_MS),
        )
        .setPendingSourceRoot(pending.toString())
        .build()

    @JvmOverloads
    fun status(
        engine: VibrisCoreEngine,
        detail: StatusDetail = StatusDetail.STATUS_DETAIL_FULL,
    ): ServerStatus {
        val observation = if (engine.activeJob() == null) runtimeStatus() else cachedRuntimeStatus()
        engine.observeRuntimeStatus(observation.status, observation.unavailableDetail)
        val snapshot = engine.statusSnapshot()
        val current = snapshot.runtimeStatus
        val shaderReady = current.ready && snapshot.phase != RuntimePhase.RUNTIME_PHASE_RELOADING_SHADERS
        val builder = ServerStatus.newBuilder()
            .setState(snapshot.state)
            .setReadiness(
                RuntimeReadiness.newBuilder()
                    .setCoreOnline(snapshot.coreOnline)
                    .setMinecraftConnected(current.ready)
                    .setWorldLoaded(current.ready && current.currentSaveId.isNotBlank())
                    .setSceneApplied(current.ready && current.currentDimensionId.isNotBlank())
                    .setShaderReloadComplete(shaderReady)
                    .setGpuTimingAvailable(false)
                    .setPhase(snapshot.phase)
                    .setDetail(readinessDetail(snapshot)),
            )
            .setCanAcceptJob(snapshot.canAcceptJob)
            .setCanStartJob(snapshot.canStartJob)
            .setArtifactCapacity(
                artifacts.capacity(),
            )
            .setActiveSourceUuid(snapshot.activeSourceUuid)
        snapshot.activeLease?.let(builder::setActiveLease)
        snapshot.recovery?.let(builder::setRecovery)
        snapshot.restart?.let(builder::setRestart)

        if (detail == StatusDetail.STATUS_DETAIL_JOBS || detail == StatusDetail.STATUS_DETAIL_FULL) {
            builder.addAllQueue(snapshot.queue)
            builder.addAllJobs(snapshot.jobs)
        }
        if (detail == StatusDetail.STATUS_DETAIL_FULL) {
            snapshot.lastError?.let(builder::setLastError)
            builder.addAllTransitions(snapshot.transitions)
        } else if (snapshot.state == ServerState.SERVER_STATE_FAILED) {
            snapshot.lastError?.let(builder::setLastError)
        }
        return builder.build()
    }

    fun hello(engine: VibrisCoreEngine): ServerHello = baseHello.toBuilder()
        .setStatus(status(engine, StatusDetail.STATUS_DETAIL_SUMMARY))
        .build()

    fun resources(): ResourceCatalog {
        val current = runtime.getResourceCatalog()
        val catalog = ResourceCatalog.newBuilder()
            .setMappingSha256(current.mappingSha256)
            .setCatalogContext(dev.vibris.protocol.v2.CatalogContext.newBuilder().setShaderGeneration(current.shaderGeneration))
        current.resources.forEach { resource ->
            catalog.addResources(
                ResourceDescriptor.newBuilder()
                    .setLogicalName(resource.logicalName)
                    .setKind(resourceKind(resource.kind))
                    .addAllAvailableViews(resource.availableViews.map { view ->
                        dev.vibris.protocol.v2.TextureView.valueOf("TEXTURE_VIEW_" + view.name)
                    })
                    .setWidth(resource.width)
                    .setHeight(resource.height)
                    .setDepth(resource.depth)
                    .setMipLevels(resource.mipLevels)
                    .setLayers(resource.layers)
                    .setInternalFormat(resource.internalFormat)
                    .setChannelCount(resource.channelCount)
                    .setScalarType(ScalarType.valueOf("SCALAR_TYPE_" + resource.scalarType.name))
                    .setByteSize(resource.byteSize)
                    .setFrameId(resource.frameId)
                    .setSourceWidth(resource.sourceWidth)
                    .setSourceHeight(resource.sourceHeight)
                    .apply {
                        resource.region?.let {
                            setRegion(dev.vibris.protocol.v2.TextureRegion.newBuilder()
                                .setX(it.x).setY(it.y).setWidth(it.width).setHeight(it.height))
                        }
                    }
                    .build(),
            )
        }
        current.passes.forEach { pass ->
            catalog.addPasses(
                PassDescriptor.newBuilder()
                    .setPassId(pass.passId)
                    .setStage(PassStage.valueOf("PASS_STAGE_" + pass.stage.name))
                    .setProgramId(pass.programId)
                    .setOrder(pass.order)
                    .addAllReadableResources(pass.readableResources)
                    .addAllPrograms(pass.programs.map {
                        dev.vibris.protocol.v2.PassProgram.newBuilder().setProgramId(it.programId)
                            .setStage(it.stage).setEntryFile(it.entryFile).setMainSource(it.mainSource).build()
                    }),
            )
        }
        return catalog.build()
    }

    private fun runtimeStatus(): Observation {
        val current = try {
            runtime.getStatus().toCompletableFuture().get(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Observation(unavailable(), "Runtime status query was interrupted.")
        } catch (failure: Exception) {
            return Observation(
                unavailable(),
                "Runtime status query failed: " +
                    (failure.message?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName),
            )
        }
        if (current.ready) {
            lastReadyStatus = current
            return Observation(current, "")
        }
        return Observation(current, "Minecraft runtime reported unavailable.")
    }

    private fun cachedRuntimeStatus(): Observation = lastReadyStatus?.let { Observation(it, "") }
        ?: Observation(unavailable(), "No ready runtime status has been observed.")

    private fun readinessDetail(snapshot: VibrisCoreEngine.StatusSnapshot): String = when {
        !snapshot.coreOnline -> "Core source activation is unavailable."
        snapshot.restart != null ->
            "A graceful Minecraft restart is ${snapshot.restart.phase.name.removePrefix("RESTART_PHASE_").lowercase()}; " +
                "${snapshot.restart.remainingJobs} accepted job(s) remain."
        snapshot.state == ServerState.SERVER_STATE_FAILED && snapshot.recovery != null ->
            "Runtime recovery failed for job ${snapshot.recovery.jobId} " +
                "from workspace ${snapshot.recovery.workspaceId}; explicit recovery is required."
        snapshot.recovery != null ->
            "Runtime recovery is pending for job ${snapshot.recovery.jobId} " +
                "from workspace ${snapshot.recovery.workspaceId}."
        snapshot.activeLease != null ->
            "Runtime lease ${snapshot.activeLease.leaseId} is owned by workspace ${snapshot.activeLease.workspaceId}."
        snapshot.queue.isNotEmpty() -> "Runtime work is queued."
        !snapshot.runtimeStatus.ready -> snapshot.lastError?.message ?: "Minecraft runtime is unavailable."
        else -> "Runtime is available."
    }.take(512)

    private fun resourceKind(kind: dev.vibris.api.ResourceCatalog.ResourceKind): ResourceKind = when (kind) {
        dev.vibris.api.ResourceCatalog.ResourceKind.FINAL_FRAMEBUFFER ->
            ResourceKind.RESOURCE_KIND_FINAL_FRAMEBUFFER
        dev.vibris.api.ResourceCatalog.ResourceKind.TEXTURE -> ResourceKind.RESOURCE_KIND_TEXTURE
        dev.vibris.api.ResourceCatalog.ResourceKind.BUFFER -> ResourceKind.RESOURCE_KIND_BUFFER
        dev.vibris.api.ResourceCatalog.ResourceKind.PATCHED_SHADERS ->
            ResourceKind.RESOURCE_KIND_PATCHED_SHADERS
    }

    private fun unavailable(): RuntimeStatus = RuntimeStatus(false, "", "", "")

    private data class Observation(val status: RuntimeStatus, val unavailableDetail: String)
}