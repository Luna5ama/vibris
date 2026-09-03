package dev.vibris.core

import dev.vibris.api.VibrisRuntimeAdapter
import dev.vibris.protocol.v2.ErrorCode
import io.grpc.BindableService
import io.grpc.Server
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration
import java.util.Objects
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
private annotation class NormalizePathAtRecordBoundary

class VibrisBootstrap private constructor(
    private val service: AutoCloseable,
    private val pendingSources: PendingSourceRoot?,
    private val listener: Listener,
    private val readyValue: Boolean,
    private val pendingShadersRootValue: Path?,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun port(): Int = listener.port()

    fun ready(): Boolean = readyValue

    fun pendingShadersRoot(): Path? = pendingShadersRootValue

    @Throws(Failure::class)
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        var failure: Failure? = null
        try {
            listener.stopAdmission()
        } catch (exception: Exception) {
            failure = Failure(ErrorCode.ERROR_CODE_INTERNAL, "Failed to stop Vibris admission.", exception)
        }
        try {
            service.close()
        } catch (exception: Exception) {
            failure = append(failure, "Failed to close the Vibris runtime.", exception)
        }
        try {
            listener.awaitTermination()
        } catch (exception: Exception) {
            failure = append(failure, "Failed to finish Vibris listener shutdown.", exception)
        }
        if (pendingSources != null) {
            try {
                pendingSources.clear()
            } catch (exception: Exception) {
                failure = append(failure, "Failed to clear pending Vibris sources.", exception)
            }
        }
        if (failure != null) {
            throw failure
        }
    }

    @JvmRecord
    data class Config(
        val port: Int,
        @field:NormalizePathAtRecordBoundary val pendingShadersRoot: Path,
        @field:NormalizePathAtRecordBoundary val artifactRoot: Path,
        @field:NormalizePathAtRecordBoundary val shaderpackRoot: Path,
    ) {
        init {
            if (port < 0 || port > 65_535) {
                throw IllegalArgumentException("port is outside 0..65535")
            }
            for ((path, name) in listOf(
                pendingShadersRoot to "pendingShadersRoot",
                artifactRoot to "artifactRoot",
                shaderpackRoot to "shaderpackRoot",
            )) {
                require(path == path.toAbsolutePath().normalize()) {
                    "$name must be absolute and normalized"
                }
            }
        }
    }

    fun interface ListenerFactory {
        @Throws(IOException::class)
        fun start(address: InetSocketAddress, service: BindableService): Listener
    }

    fun interface RestartHandler {
        fun restart(executable: Path)
    }

    interface Listener {
        fun port(): Int

        @Throws(Exception::class)
        fun stopAdmission()

        @Throws(Exception::class)
        fun awaitTermination()
    }

    class Failure internal constructor(
        private val codeValue: ErrorCode,
        message: String?,
        cause: Throwable,
    ) : Exception(message, cause) {
        fun code(): ErrorCode = codeValue
    }

    private class GrpcListener(private val server: Server) : Listener {
        override fun port(): Int = server.port

        override fun stopAdmission() {
            server.shutdown()
        }

        @Throws(InterruptedException::class)
        override fun awaitTermination() {
            if (server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return
            }
            server.shutdownNow()
            server.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private val SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        @Throws(Failure::class)
        fun start(config: Config?, runtime: VibrisRuntimeAdapter?): VibrisBootstrap {
            return start(config, runtime, ListenerFactory(::startListener))
        }

        @JvmStatic
        @Throws(Failure::class)
        fun start(gameDirectory: Path?, runtime: VibrisRuntimeAdapter?): VibrisBootstrap {
            return start(gameDirectory, runtime, RestartHandler(::restartUnavailable))
        }

        @JvmStatic
        @Throws(Failure::class)
        fun start(
            gameDirectory: Path?,
            runtime: VibrisRuntimeAdapter?,
            restartHandler: RestartHandler?,
        ): VibrisBootstrap {
            return start(gameDirectory, runtime, restartHandler, JobActivityObserver.none())
        }

        @JvmStatic
        @Throws(Failure::class)
        fun start(
            gameDirectory: Path?,
            runtime: VibrisRuntimeAdapter?,
            restartHandler: RestartHandler?,
            jobActivityObserver: JobActivityObserver?,
        ): VibrisBootstrap {
            return start(gameDirectory, runtime, restartHandler, jobActivityObserver, ListenerFactory(::startListener))
        }

        @JvmStatic
        @Throws(Failure::class)
        fun start(
            gameDirectory: Path?,
            runtime: VibrisRuntimeAdapter?,
            listenerFactory: ListenerFactory?,
        ): VibrisBootstrap = start(
            gameDirectory,
            runtime,
            RestartHandler(::restartUnavailable),
            JobActivityObserver.none(),
            listenerFactory,
        )

        @JvmStatic
        @Throws(Failure::class)
        fun start(
            gameDirectory: Path?,
            runtime: VibrisRuntimeAdapter?,
            restartHandler: RestartHandler?,
            listenerFactory: ListenerFactory?,
        ): VibrisBootstrap = start(
            gameDirectory,
            runtime,
            restartHandler,
            JobActivityObserver.none(),
            listenerFactory,
        )

        @JvmStatic
        @Throws(Failure::class)
        fun start(
            gameDirectory: Path?,
            runtime: VibrisRuntimeAdapter?,
            restartHandler: RestartHandler?,
            jobActivityObserver: JobActivityObserver?,
            listenerFactory: ListenerFactory?,
        ): VibrisBootstrap {
            val game = Objects.requireNonNull(gameDirectory, "gameDirectory")!!.toAbsolutePath().normalize()
            val actualRuntime = Objects.requireNonNull(runtime, "runtime")!!
            val actualRestartHandler = Objects.requireNonNull(restartHandler, "restartHandler")!!
            val actualFactory = Objects.requireNonNull(listenerFactory, "listenerFactory")!!
            val actualObserver = Objects.requireNonNull(jobActivityObserver, "jobActivityObserver")!!
            val configuration = try {
                ServerConfiguration.load(game)
            } catch (exception: Exception) {
                val address = (exception as? ServerConfiguration.Failure)?.address
                    ?: InetSocketAddress("127.0.0.1", ServerConfiguration.DEFAULT_PORT)
                return startUnavailable(
                    address,
                    actualRuntime,
                    actualFactory,
                    startupReason(exception),
                )
            }
            return startConfigured(
                configuration,
                actualRuntime,
                actualRestartHandler,
                actualObserver,
                actualFactory,
                createRoots = false,
                notReady = true,
            )
        }

        @JvmStatic
        @Throws(Failure::class)
        fun start(
            config: Config?,
            runtime: VibrisRuntimeAdapter?,
            listenerFactory: ListenerFactory?,
        ): VibrisBootstrap {
            val actualConfig = Objects.requireNonNull(config, "config")!!
            val actualRuntime = Objects.requireNonNull(runtime, "runtime")!!
            val actualFactory = Objects.requireNonNull(listenerFactory, "listenerFactory")!!
            return startConfigured(
                ServerConfiguration.defaults(actualConfig),
                actualRuntime,
                RestartHandler(::restartUnavailable),
                JobActivityObserver.none(),
                actualFactory,
                createRoots = true,
                notReady = false,
            )
        }

        private fun startConfigured(
            configuration: ServerConfiguration,
            runtime: VibrisRuntimeAdapter,
            restartHandler: RestartHandler,
            jobActivityObserver: JobActivityObserver,
            listenerFactory: ListenerFactory,
            createRoots: Boolean,
            notReady: Boolean,
        ): VibrisBootstrap {
            val paths = configuration.paths
            val pendingSources = PendingSourceRoot(paths.pendingShadersRoot)
            val link = FixedShaderLink(paths.pendingShadersRoot, paths.shaderpackRoot)
            var service: VibrisControlService? = null
            try {
                if (createRoots) {
                    OwnedPathIdentity.createDirectoriesSafely(paths.pendingShadersRoot)
                    OwnedPathIdentity.createDirectoriesSafely(paths.shaderpackRoot)
                    OwnedPathIdentity.createDirectoriesSafely(paths.artifactRoot)
                    OwnedPathIdentity.createDirectoriesSafely(configuration.replayCaptureRoot)
                }
                link.prepare()
                pendingSources.prepare()
                service = VibrisControlService(configuration, runtime, link, restartHandler, jobActivityObserver)
                val listener = listenerFactory.start(configuration.address, service)
                return VibrisBootstrap(service, pendingSources, listener, true, paths.pendingShadersRoot)
            } catch (exception: Exception) {
                if (notReady && service == null) {
                    return startUnavailable(
                        configuration.address,
                        runtime,
                        listenerFactory,
                        startupReason(exception),
                    )
                }
                val failure = startupFailure(exception)
                closeFailedStart(service, runtime, failure)
                throw failure
            }
        }

        @Throws(IOException::class)
        private fun startListener(address: InetSocketAddress, service: BindableService): Listener {
            val server = NettyServerBuilder.forAddress(address).addService(service).build().start()
            return GrpcListener(server)
        }

        private fun startUnavailable(
            address: InetSocketAddress,
            runtime: VibrisRuntimeAdapter,
            listenerFactory: ListenerFactory,
            reason: String,
        ): VibrisBootstrap {
            val service = UnavailableVibrisControlService(runtime, reason)
            try {
                val listener = listenerFactory.start(address, service)
                return VibrisBootstrap(service, null, listener, false, null)
            } catch (exception: Exception) {
                val failure = Failure(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, "Vibris listener could not start.", exception)
                closeFailedStart(service, runtime, failure)
                throw failure
            }
        }

        private fun startupFailure(exception: Exception): Failure =
            when (exception) {
                is ShaderLink.Failure ->
                    Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, exception.message, exception)
                else -> Failure(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, startupReason(exception), exception)
            }

        private fun startupReason(exception: Exception): String {
            val detail = exception.message?.takeIf(String::isNotBlank) ?: exception.javaClass.simpleName
            return "Vibris is not ready: $detail"
        }

        private fun closeFailedStart(
            service: AutoCloseable?,
            runtime: VibrisRuntimeAdapter,
            failure: Failure,
        ) {
            try {
                if (service != null) {
                    service.close()
                } else {
                    runtime.close()
                }
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
        }

        private fun append(current: Failure?, message: String, exception: Exception): Failure {
            if (current == null) {
                return Failure(ErrorCode.ERROR_CODE_INTERNAL, message, exception)
            }
            current.addSuppressed(exception)
            return current
        }

        private fun restartUnavailable(executable: Path) {
            throw IllegalStateException("No Minecraft restart handler is installed for $executable")
        }
    }
}
