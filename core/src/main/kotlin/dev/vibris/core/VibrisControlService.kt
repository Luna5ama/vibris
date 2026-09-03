package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ClientMessage
import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.protocol.v2.GetServerInfoRequest
import dev.vibris.protocol.v2.GetServerInfoResponse
import dev.vibris.protocol.v2.GetStatusRequest
import dev.vibris.protocol.v2.GetStatusResponse
import dev.vibris.protocol.v2.ListPresetsRequest
import dev.vibris.protocol.v2.ListPresetsResponse
import dev.vibris.protocol.v2.ListResourcesRequest
import dev.vibris.protocol.v2.ListResourcesResponse
import dev.vibris.protocol.v2.ManageArtifactsRequest
import dev.vibris.protocol.v2.ManageArtifactsResponse
import dev.vibris.protocol.v2.RequestRestartRequest
import dev.vibris.protocol.v2.RequestRestartResponse
import dev.vibris.protocol.v2.ArtifactOperation
import dev.vibris.protocol.v2.Pong
import dev.vibris.protocol.v2.ScenePreset
import dev.vibris.protocol.v2.ServerMessage
import dev.vibris.protocol.v2.ServerShuttingDown
import dev.vibris.protocol.v2.StatusDetail
import dev.vibris.protocol.v2.StatusWaitCondition
import dev.vibris.protocol.v2.ValidateContextRequest
import dev.vibris.protocol.v2.ValidateContextResponse
import dev.vibris.protocol.v2.VibrisControlGrpc
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class VibrisControlService internal constructor(
    configuration: ServerConfiguration,
    private val runtime: VibrisRuntimeAdapter,
    shaderLink: ShaderLink,
    private val restartHandler: VibrisBootstrap.RestartHandler,
    jobActivityObserver: JobActivityObserver = JobActivityObserver.none(),
) : VibrisControlGrpc.VibrisControlImplBase(), AutoCloseable {
    private val logger = System.getLogger(VibrisControlService::class.java.name)
    private val restartExecutable = configuration.restartExecutable
    private val sessions = ConcurrentHashMap.newKeySet<ControlSession>()
    private val restartCoordinator = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Vibris Restart Coordinator").apply { isDaemon = true }
    }
    private val artifacts = ArtifactManager(
        configuration.paths.artifactRoot,
        configuration.artifactQuotaBytes,
        configuration.artifactTtl,
    )
    private val engine = VibrisCoreEngine(
        configuration.paths.pendingShadersRoot,
        runtime,
        shaderLink,
        artifacts,
        configuration.maxSourceBytes,
        configuration.maxSourceFiles,
        configuration.maxGlobalQueue,
        configuration.maxActionsPerJob,
        configuration.replayCaptureRoot,
        configuration.replayerRoot,
        configuration.vibrisRoot.resolve("runtime/java/bin/java.exe"),
        jobActivityObserver,
    )
    private val descriptor = ServerDescriptor(
        configuration.paths.pendingShadersRoot,
        artifacts,
        runtime,
        configuration.maxSourceBytes,
        configuration.maxSourceFiles,
        configuration.maxGlobalQueue,
        configuration.maxActionsPerJob,
    )

    internal constructor(
        pendingRoot: Path,
        artifactRoot: Path,
        runtime: VibrisRuntimeAdapter,
        shaderLink: ShaderLink,
    ) : this(
        ServerConfiguration.defaults(pendingRoot, artifactRoot),
        runtime,
        shaderLink,
        VibrisBootstrap.RestartHandler { executable ->
            throw IllegalStateException("No Minecraft restart handler is installed for $executable")
        },
    )

    constructor(pendingRoot: Path, artifactRoot: Path, runtime: VibrisRuntimeAdapter) :
        this(pendingRoot, artifactRoot, runtime, ShaderLink.transientLink())

    fun probe(): CoreProbe = engine.probe()

    override fun getServerInfo(
        request: GetServerInfoRequest,
        observer: StreamObserver<GetServerInfoResponse>,
    ) {
        observer.onNext(
            GetServerInfoResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setServer(descriptor.hello(engine))
                .build(),
        )
        observer.onCompleted()
    }

    override fun listPresets(
        request: ListPresetsRequest,
        observer: StreamObserver<ListPresetsResponse>,
    ) {
        runtime.listPresets().whenComplete { presets, failure ->
            if (failure != null) {
                observer.onError(Status.INTERNAL.withDescription("PRESET_LIST_FAILED").asRuntimeException())
                return@whenComplete
            }
            val response = ListPresetsResponse.newBuilder().setProtocolVersion(ProtocolMessages.V2)
            presets.forEach { preset ->
                val tags = preset.tags.sorted()
                response.addPresets(
                    ScenePreset.newBuilder()
                        .setPresetId(preset.presetId)
                        .setDisplayName(preset.displayName)
                        .setVersion(preset.version)
                        .setContext(RuntimeJobContext.toProtocol(preset.context))
                        .addAllTags(tags)
                        .setPresetSha256(BenchmarkProvenance.presetHash(
                            preset.presetId,
                            preset.version,
                            preset.displayName,
                            preset.context,
                        )),
                )
            }
            observer.onNext(response.build())
            observer.onCompleted()
        }
    }

    override fun listResources(
        request: ListResourcesRequest,
        observer: StreamObserver<ListResourcesResponse>,
    ) {
        val resources = descriptor.resources()
        val filtered = if (!request.hasFilter()) {
            resources
        } else {
            val filter = request.filter
            resources.toBuilder()
                .clearResources()
                .addAllResources(resources.resourcesList.filter { resource ->
                    (filter.kindsCount == 0 || filter.kindsList.contains(resource.kind)) &&
                        (!filter.hasLogicalName() || resource.logicalName == filter.logicalName)
                })
                .build()
        }
        observer.onNext(
            ListResourcesResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setCatalog(filtered)
                .build(),
        )
        observer.onCompleted()
    }

    override fun validateContext(
        request: ValidateContextRequest,
        observer: StreamObserver<ValidateContextResponse>,
    ) {
        if (!request.hasContext()) {
            observer.onNext(
                ValidateContextResponse.newBuilder()
                    .setProtocolVersion(ProtocolMessages.V2)
                    .setValid(false)
                    .build(),
            )
            observer.onCompleted()
            return
        }
        val context = RuntimeJobContext.toApi(request.context)
        runtime.validateContext(context).whenComplete { validation, failure ->
            if (failure != null) {
                observer.onError(Status.INTERNAL.withDescription("PRESET_VALIDATION_FAILED").asRuntimeException())
                return@whenComplete
            }
            val response = ValidateContextResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setValid(validation.valid)
            validation.errors.forEach { error ->
                response.addErrors(
                    dev.vibris.protocol.v2.ProtocolError.newBuilder()
                        .setCode(ErrorCode.ERROR_CODE_INVALID_PRESET)
                        .setMessage(error)
                        .setRetryable(false),
                )
            }
            observer.onNext(response.build())
            observer.onCompleted()
        }
    }

    override fun getStatus(
        request: GetStatusRequest,
        observer: StreamObserver<GetStatusResponse>,
    ) {
        val detail = request.detail.takeUnless { it == StatusDetail.STATUS_DETAIL_UNSPECIFIED }
            ?: StatusDetail.STATUS_DETAIL_SUMMARY
        descriptor.status(engine, detail)
        val wait = request.waitUntil
        if (wait == StatusWaitCondition.STATUS_WAIT_CONDITION_JOB_TERMINAL &&
            (!request.hasJobId() || request.jobId.isBlank())
        ) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("JOB_ID_REQUIRED").asRuntimeException())
            return
        }
        val result = try {
            if (wait == StatusWaitCondition.STATUS_WAIT_CONDITION_UNSPECIFIED) {
                null
            } else {
                engine.awaitStatus(
                    wait,
                    request.jobId,
                    minOf(request.timeoutMs, VibrisCoreEngine.MAX_STATUS_WAIT_MS),
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            observer.onError(Status.CANCELLED.withDescription("STATUS_WAIT_INTERRUPTED").asRuntimeException())
            return
        }
        val response = GetStatusResponse.newBuilder()
            .setProtocolVersion(ProtocolMessages.V2)
            .setStatus(descriptor.status(engine, detail))
        if (result != null) {
            response.setWaitSatisfied(result.satisfied)
            response.setWaitTimedOut(result.timedOut)
        }
        observer.onNext(response.build())
        observer.onCompleted()
    }

    override fun manageArtifacts(
        request: ManageArtifactsRequest,
        observer: StreamObserver<ManageArtifactsResponse>,
    ) {
        if (!request.hasProtocolVersion() || request.protocolVersion.major != 2) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("UNSUPPORTED_VERSION").asRuntimeException())
            return
        }
        if (request.workspaceId.isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("WORKSPACE_ID_REQUIRED").asRuntimeException())
            return
        }
        try {
            val response = ManageArtifactsResponse.newBuilder().setProtocolVersion(ProtocolMessages.V2)
            when (request.operation) {
                ArtifactOperation.ARTIFACT_OPERATION_LIST -> {
                    artifacts.manifests(
                        request.workspaceId,
                        request.jobId.takeIf { request.hasJobId() },
                        request.requestId.takeIf { request.hasRequestId() },
                    ).forEach { response.addManifests(artifactManifest(it)) }
                }
                ArtifactOperation.ARTIFACT_OPERATION_GET -> {
                    if (!request.hasManifestId() || request.manifestId.isBlank()) {
                        throw IllegalArgumentException("MANIFEST_ID_REQUIRED")
                    }
                    response.setManifest(artifactManifest(artifacts.manifest(request.workspaceId, request.manifestId)))
                }
                ArtifactOperation.ARTIFACT_OPERATION_CAPACITY -> response.setCapacity(artifacts.capacity())
                ArtifactOperation.ARTIFACT_OPERATION_DELETE -> {
                    if (!request.hasManifestId() || request.manifestId.isBlank() ||
                        !request.hasExpectedManifestSha256() || request.expectedManifestSha256.isBlank()
                    ) throw IllegalArgumentException("MANIFEST_ID_AND_EXPECTED_SHA_REQUIRED")
                    artifacts.delete(request.workspaceId, request.manifestId, request.expectedManifestSha256)
                    response.deleted = true
                }
                else -> throw IllegalArgumentException("ARTIFACT_OPERATION_REQUIRED")
            }
            observer.onNext(response.build())
            observer.onCompleted()
        } catch (exception: ArtifactManager.OwnershipException) {
            observer.onError(Status.PERMISSION_DENIED.withDescription("ARTIFACT_WORKSPACE_MISMATCH").asRuntimeException())
        } catch (exception: ArtifactManager.DeletionRaceException) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("ARTIFACT_MANIFEST_CHANGED").asRuntimeException())
        } catch (exception: IllegalArgumentException) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription(exception.message).asRuntimeException())
        } catch (exception: java.io.IOException) {
            observer.onError(Status.NOT_FOUND.withDescription(exception.message).asRuntimeException())
        }
    }

    override fun requestRestart(
        request: RequestRestartRequest,
        observer: StreamObserver<RequestRestartResponse>,
    ) {
        if (!request.hasProtocolVersion() || request.protocolVersion.major != 2) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("UNSUPPORTED_VERSION").asRuntimeException())
            return
        }
        if (request.workspaceId.isBlank()) {
            observer.onError(Status.INVALID_ARGUMENT.withDescription("WORKSPACE_ID_REQUIRED").asRuntimeException())
            return
        }
        val executable = restartExecutable
        if (executable == null) {
            observer.onError(Status.FAILED_PRECONDITION.withDescription("RESTART_NOT_CONFIGURED").asRuntimeException())
            return
        }
        val result = engine.requestRestart(
            request.workspaceId,
            request.reason.ifBlank { "Minecraft restart requested through Vibris MCP." },
        )
        observer.onNext(
            RequestRestartResponse.newBuilder()
                .setProtocolVersion(ProtocolMessages.V2)
                .setRestart(result.status)
                .setAlreadyScheduled(result.alreadyScheduled)
                .build(),
        )
        observer.onCompleted()
        broadcastRestart(result.status)
        if (!result.alreadyScheduled) {
            restartCoordinator.execute { drainAndRestart(executable) }
        }
    }

    private fun artifactManifest(managed: ArtifactManager.ManagedManifest): dev.vibris.protocol.v2.ArtifactManifest {
        val document = managed.document
        return dev.vibris.protocol.v2.ArtifactManifest.newBuilder()
            .setManifestId(document.manifestId)
            .setWorkspaceId(document.workspaceId)
            .setJobId(document.jobId)
            .setRequestId(document.requestId)
            .setRecipe(document.recipe)
            .setCreatedAtUnixMs(document.createdAtUnixMs)
            .setCompletedAtUnixMs(document.completedAtUnixMs)
            .setExpiresAtUnixMs(document.expiresAtUnixMs)
            .setTotalBytes(document.totalBytes)
            .addAllFiles(managed.files)
            .setManifestSha256(managed.manifestSha256)
            .setManifestPath(managed.manifestPath.toAbsolutePath().normalize().toString())
            .setExpired(false)
            .build()
    }

    override fun control(responses: StreamObserver<ServerMessage>): StreamObserver<ClientMessage> {
        val session = ControlSession(responses)
        return object : StreamObserver<ClientMessage> {
            private var greeted = false
            private var terminated = false

            override fun onNext(message: ClientMessage) {
                if (terminated) {
                    return
                }
                if (!greeted) {
                    greet(message)
                    return
                }
                if (!message.hasProtocolVersion() || message.protocolVersion.major != 2) {
                    fail(Status.FAILED_PRECONDITION.withDescription("UNSUPPORTED_VERSION"))
                    return
                }
                if (message.workspaceId != session.workspaceId()) {
                    fail(Status.PERMISSION_DENIED.withDescription("WORKSPACE_MISMATCH"))
                    return
                }
                if (message.hasPing()) {
                    val pong = Pong.newBuilder()
                        .setSequence(message.ping.sequence)
                        .setClientTimeUnixMs(message.ping.clientTimeUnixMs)
                        .setServerTimeUnixMs(message.ping.clientTimeUnixMs)
                        .build()
                    session.send(
                        ProtocolMessages.envelope(
                            message.messageId,
                            message.requestId,
                            session.workspaceId(),
                        ).setPong(pong).build(),
                    )
                } else if (message.hasSubmitJob()) {
                    engine.submit(session, message)
                } else if (message.hasCancelJob()) {
                    engine.cancel(session, message.cancelJob.jobId)
                } else if (message.hasResumeJob()) {
                    engine.resume(session, message)
                }
            }

            private fun greet(message: ClientMessage) {
                if (!message.hasClientHello()) {
                    fail(Status.INVALID_ARGUMENT.withDescription("CLIENT_HELLO_REQUIRED"))
                    return
                }
                if (!message.hasProtocolVersion() || message.protocolVersion.major != 2) {
                    fail(Status.FAILED_PRECONDITION.withDescription("UNSUPPORTED_VERSION"))
                    return
                }
                val workspace = message.workspaceId
                if (workspace.isBlank()) {
                    fail(Status.INVALID_ARGUMENT.withDescription("WORKSPACE_ID_REQUIRED"))
                    return
                }
                greeted = true
                session.identify(workspace, message.clientHello.processInstanceId)
                sessions.add(session)
                session.send(
                    ProtocolMessages.envelope(message.messageId, message.requestId, workspace)
                        .setServerHello(descriptor.hello(engine))
                        .build(),
                )
                engine.statusSnapshot().restart?.let { broadcastRestart(it, listOf(session)) }
            }

            private fun fail(status: Status) {
                if (terminated) {
                    return
                }
                terminated = true
                sessions.remove(session)
                engine.disconnected(session)
                responses.onError(status.asRuntimeException())
            }

            override fun onError(throwable: Throwable) {
                if (terminated) {
                    return
                }
                terminated = true
                sessions.remove(session)
                engine.disconnected(session)
            }

            override fun onCompleted() {
                if (terminated) {
                    return
                }
                terminated = true
                sessions.remove(session)
                session.complete()
                engine.disconnected(session)
            }
        }
    }

    override fun close() {
        restartCoordinator.shutdownNow()
        engine.close()
    }

    private fun drainAndRestart(executable: Path) {
        try {
            if (!engine.awaitRestartDrain()) return
            val launching = engine.markRestartLaunching() ?: return
            broadcastRestart(launching)
            Thread.sleep(RESTART_DISPATCH_GRACE_MS)
            restartHandler.restart(executable)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            logger.log(System.Logger.Level.ERROR, "Scheduled Minecraft restart launch failed.", failure)
            engine.restartFailed(failure.message ?: failure.javaClass.simpleName)
            sessions.forEach { session ->
                session.send(
                    ProtocolMessages.envelope("restart-cancelled", "", session.workspaceId())
                        .setServerHello(descriptor.hello(engine))
                        .build(),
                )
            }
        }
    }

    private fun broadcastRestart(
        status: dev.vibris.protocol.v2.RestartStatus,
        targets: Collection<ControlSession> = sessions,
    ) {
        val event = ServerShuttingDown.newBuilder()
            .setReason(
                "Planned Minecraft restart ${status.phase.name.removePrefix("RESTART_PHASE_").lowercase()}; " +
                    "accepted jobs are draining and MCP clients should wait and retry.",
            )
            .setRetryAfterMs(status.retryAfterMs)
            .build()
        targets.forEach { session ->
            session.send(
                ProtocolMessages.envelope("restart-notice", "", session.workspaceId())
                    .setServerShuttingDown(event)
                    .build(),
            )
        }
    }

    private companion object {
        const val RESTART_DISPATCH_GRACE_MS = 250L
    }

}
