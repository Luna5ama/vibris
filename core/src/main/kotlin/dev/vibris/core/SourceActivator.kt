package dev.vibris.core

import dev.vibris.protocol.v2.ErrorCode
import dev.vibris.api.CancellationToken

internal class SourceActivator(
    private val sources: SourceRegistry,
    private val link: ShaderLink,
) : AutoCloseable {
    @Volatile
    private var ready = true

    @Volatile
    private var closed = false

    @Synchronized
    @Throws(Failure::class)
    fun begin(next: SourceRegistry.Lease): Activation = begin(next, CancellationToken.none(), Long.MAX_VALUE)

    @Synchronized
    @Throws(Failure::class)
    fun begin(next: SourceRegistry.Lease, cancellation: CancellationToken, deadline: Long): Activation {
        requireReady()
        val activation: SourceRegistry.Activation
        try {
            sources.materialize(next, cancellation, deadline)
            activation = sources.beginActivation(next)
            link.switchTo(next) { requireOwned(next) }
        } catch (failure: SourceRegistry.Failure) {
            sources.failActivation(next)
            throw Failure(failure.code, failure.message, failure)
        } catch (failure: ShaderLink.Failure) {
            throw fail(next, ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.message, failure.stable(), failure)
        }
        return Activation(activation)
    }

    @Synchronized
    @Throws(Failure::class)
    fun commit(activation: Activation) {
        try {
            sources.requireMaterializedOwned(activation.state.next)
            sources.commitActivation(activation.state)
        } catch (failure: SourceRegistry.Failure) {
            throw Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.message, failure)
        }
    }

    @Synchronized
    fun rollback(activation: Activation): Boolean {
        val previous = activation.state.previous
        return try {
            if (previous == null) {
                link.detach()
            } else {
                link.switchTo(previous) { requireOwned(previous) }
            }
            sources.retryActivation(activation.state)
            true
        } catch (_: ShaderLink.Failure) {
            ready = false
            false
        }
    }

    @Synchronized
    fun fail(activation: Activation) {
        sources.failActivation(activation.state)
    }

    @Synchronized
    fun release(leases: List<SourceRegistry.Lease>) {
        var retainActive = false
        try {
            retainActive = link.retainsActiveSource()
        } catch (_: ShaderLink.Failure) {
            ready = false
        }
        sources.release(leases, retainActive)
    }

    @Synchronized
    @Throws(Failure::class)
    fun verifyActiveSource(): SourceRegistry.Lease? = try {
        link.retainsActiveSource()
        sources.requireActiveOwned()
    } catch (failure: SourceRegistry.Failure) {
        ready = false
        throw Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.message, failure)
    } catch (failure: ShaderLink.Failure) {
        ready = false
        throw Failure(ErrorCode.ERROR_CODE_SOURCE_ACTIVATION_FAILED, failure.message, failure)
    }

    @Synchronized
    @Throws(Failure::class)
    fun activeSnapshot(): SourceRegistry.Lease? = try {
        sources.activeSnapshot()
    } catch (failure: SourceRegistry.Failure) {
        ready = false
        throw Failure(failure.code, failure.message, failure)
    }

    @Synchronized
    @Throws(Failure::class)
    fun restore(source: SourceRegistry.Lease?) {
        if (closed) {
            throw Failure(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, "Source activation is closed.")
        }
        try {
            if (source == null) {
                link.detach()
                sources.detachActive()
                return
            }
            if (sources.isActive(source)) {
                sources.requireMaterializedOwned(source)
                return
            }
            sources.materialize(source, CancellationToken.none(), Long.MAX_VALUE)
            val activation = sources.beginActivation(source)
            try {
                link.switchTo(source) { requireOwned(source) }
                sources.requireMaterializedOwned(source)
                sources.commitActivation(activation)
            } catch (failure: ShaderLink.Failure) {
                if (failure.stable()) {
                    sources.retryActivation(activation)
                } else {
                    sources.failActivation(activation)
                }
                throw failure
            }
        } catch (failure: SourceRegistry.Failure) {
            ready = false
            throw Failure(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.message, failure)
        } catch (failure: ShaderLink.Failure) {
            ready = false
            throw Failure(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.message, failure)
        } catch (failure: RuntimeException) {
            ready = false
            throw Failure(ErrorCode.ERROR_CODE_RESTORE_FAILED, failure.message, failure)
        }
    }

    fun ready(): Boolean = ready && !closed

    @Synchronized
    fun markNotReady() {
        ready = false
    }

    @Synchronized
    @Throws(Failure::class)
    fun markReadyAfterVerification(): SourceRegistry.Lease? {
        if (closed) {
            throw Failure(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, "Source activation is closed.")
        }
        try {
            link.retainsActiveSource()
            val source = sources.requireActiveOwned()
            ready = true
            return source
        } catch (failure: SourceRegistry.Failure) {
            ready = false
            throw Failure(ErrorCode.ERROR_CODE_RECOVERY_FAILED, failure.message, failure)
        } catch (failure: ShaderLink.Failure) {
            ready = false
            throw Failure(ErrorCode.ERROR_CODE_RECOVERY_FAILED, failure.message, failure)
        }
    }

    @Synchronized
    fun isActive(source: SourceRegistry.Lease): Boolean = sources.isActive(source)

    @Synchronized
    @Throws(Failure::class)
    fun retainActive(): SourceRegistry.Lease? = try {
        sources.retainActive()
    } catch (failure: SourceRegistry.Failure) {
        ready = false
        throw Failure(failure.code, failure.message, failure)
    }

    @Synchronized
    fun releaseRetained(source: SourceRegistry.Lease) {
        sources.releaseRetained(source)
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            link.detach()
        } catch (_: ShaderLink.Failure) {
            ready = false
        }
        sources.close()
    }

    @Throws(ShaderLink.Failure::class)
    private fun requireOwned(source: SourceRegistry.Lease) {
        try {
            sources.requireOwned(source)
        } catch (failure: SourceRegistry.Failure) {
            throw ShaderLink.Failure(failure.message, true, failure)
        }
    }

    private fun fail(
        source: SourceRegistry.Lease,
        code: ErrorCode,
        message: String?,
        stable: Boolean,
        cause: Exception,
    ): Failure {
        if (stable) {
            sources.retryActivation(source)
        } else {
            sources.failActivation(source)
        }
        if (!stable) {
            ready = false
        }
        return Failure(code, message, cause)
    }

    @Throws(Failure::class)
    private fun requireReady() {
        if (!ready || closed) {
            throw Failure(ErrorCode.ERROR_CODE_SERVER_NOT_AVAILABLE, "Source activation is not ready.")
        }
    }

    data class Activation(val state: SourceRegistry.Activation) {
        fun state(): SourceRegistry.Activation = state

        fun previous(): SourceRegistry.Lease? = state.previous
    }

    class Failure @JvmOverloads constructor(
        @JvmField val code: ErrorCode,
        message: String?,
        cause: Throwable? = null,
    ) : Exception(message, cause)
}
