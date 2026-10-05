package dev.luna5ama.vibris.capture

import dev.vibris.api.CapturePlan
import dev.vibris.api.TextureAccumulation
import org.lwjgl.opengl.*
import org.lwjgl.system.MemoryUtil
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pipeline-owned compute state. Construct, sample, read and close only on the render thread. */
class GpuTextureAccumulator(
    private val request: TextureAccumulation,
    private val region: CapturePlan.TextureRegion?,
    texture: Int,
    private val mip: Int,
) : AutoCloseable {
    val metadata = GlArtifactCapture.describeTexture(texture, mip)
    val width = region?.width ?: metadata.width
    val height = region?.height ?: metadata.height
    val channels = metadata.channelCount
    private val numericClass = metadata.numericClass.uppercase(java.util.Locale.ROOT)
    val batchLengths = IntArray(request.batches) { index ->
        request.frames / request.batches + if (index < request.frames % request.batches) 1 else 0
    }
    val screenBatchMeans = ArrayList<List<Double?>>()
    val screenBatchPixelCounts = ArrayList<List<Long>>()
    val lumaScreenBatchMeans = ArrayList<Double?>()
    val screenBatchNonfiniteSamples = ArrayList<List<Long>>()
    val lumaScreenBatchPixelCounts = ArrayList<Long>()
    val lumaScreenBatchNonfiniteSamples = ArrayList<Long>()
    var startFrame = 0L; private set
    var endFrame = 0L; private set
    private var sampled = 0
    private var batch = 0
    private var batchFrame = 0
    private var state = 0
    private var lumaState = 0
    private var partials = 0
    private var program = 0
    private var samplerObject = 0
    private val groupsX = (width + 15) / 16
    private val groupsY = (height + 7) / 8
    private val partialBytes = groupsX.toLong() * groupsY * 15 * 8

    init {
        require(metadata.textureTarget == "texture_2d") { "GPU accumulation supports only single-layer 2D textures" }
        require(metadata.depth == 1 && channels in 1..4)
        require(numericClass in setOf("FLOAT", "UNORM", "SNORM", "UINT", "SINT", "DEPTH")) {
            "GPU accumulation does not support ${metadata.numericClass}"
        }
        require(request.lumaWeights.isEmpty() || channels >= 3) { "Luma requires RGB channels" }
        region?.validate(metadata.width, metadata.height)
        val bytes = Math.addExact(width.toLong() * height * 14 *
            (16 + if (request.lumaWeights.isEmpty()) 0 else 4), partialBytes)
        require(bytes <= 1024L * 1024 * 1024) { "GPU accumulation requires $bytes bytes, exceeding the 1 GB limit" }
        try {
            state = allocateState(GL30C.GL_RGBA32F)
            if (request.lumaWeights.isNotEmpty()) lumaState = allocateState(GL30C.GL_R32F)
            partials = GL45C.glCreateBuffers()
            GL45C.glNamedBufferData(partials, partialBytes, GL15C.GL_DYNAMIC_READ)
            program = compileProgram()
            samplerObject = GL33C.glGenSamplers()
            GL33C.glSamplerParameteri(samplerObject, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST)
            GL33C.glSamplerParameteri(samplerObject, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST)
            GL33C.glSamplerParameteri(samplerObject, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun allocateState(format: Int): Int {
        val id = GL45C.glCreateTextures(GL30C.GL_TEXTURE_2D_ARRAY)
        try {
            GL45C.glTextureStorage3D(id, 1, format, width, height, 14)
            GL44C.glClearTexImage(id, 0, if (format == GL30C.GL_R32F) GL11C.GL_RED else GL11C.GL_RGBA,
                GL11C.GL_FLOAT, null as ByteBuffer?)
            return id
        } catch (failure: Throwable) { GL11C.glDeleteTextures(id); throw failure }
    }

    private fun compileProgram(): Int {
        val source = javaClass.getResourceAsStream("accumulate_texture.comp")!!.use { it.readAllBytes().toString(Charsets.UTF_8) }
        val sampler = when (numericClass) { "UINT" -> "usampler2D"; "SINT" -> "isampler2D"; else -> "sampler2D" }
        val shader = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER)
        val id = GL20C.glCreateProgram()
        try {
            GL20C.glShaderSource(shader, source.replace("SOURCE_SAMPLER", sampler))
            GL20C.glCompileShader(shader)
            check(GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != 0) { GL20C.glGetShaderInfoLog(shader) }
            GL20C.glAttachShader(id, shader)
            GL20C.glLinkProgram(id)
            check(GL20C.glGetProgrami(id, GL20C.GL_LINK_STATUS) != 0) { GL20C.glGetProgramInfoLog(id) }
            return id
        } catch (failure: Throwable) { GL20C.glDeleteProgram(id); throw failure }
        finally { GL20C.glDeleteShader(shader) }
    }

    /** Returns true once all requested frames have been sampled; duplicate boundaries are ignored. */
    fun sample(texture: Int, frameId: Long): Boolean {
        if (sampled > 0 && frameId == endFrame) return false
        check(sampled < request.frames)
        val current = GlArtifactCapture.describeTexture(texture, mip)
        require(current.width == metadata.width && current.height == metadata.height &&
            current.internalFormat == metadata.internalFormat && current.textureTarget == metadata.textureTarget) {
            "Texture topology changed during accumulation"
        }
        val batchEnd = batchFrame + 1 == batchLengths[batch]
        val oldProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM)
        val oldActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)
        val oldBuffer = GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING)
        val oldBinding = GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, 0)
        val oldBufferStart = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START, 0)
        val oldBufferSize = GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, 0)
        val images = (0..1).map { unit -> intArrayOf(GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, unit),
            GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, unit), GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, unit),
            GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, unit), GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, unit),
            GL30C.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, unit)) }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0)
        val oldTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)
        val oldSampler = GL30C.glGetIntegeri(GL33C.GL_SAMPLER_BINDING, 0)
        try {
            GL20C.glUseProgram(program)
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture)
            GL33C.glBindSampler(0, samplerObject)
            GL42C.glBindImageTexture(0, state, 0, true, 0, GL15C.GL_READ_WRITE, GL30C.GL_RGBA32F)
            GL42C.glBindImageTexture(1, lumaState, 0, true, 0, GL15C.GL_READ_WRITE, GL30C.GL_R32F)
            GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, partials)
            GL20C.glUniform2i(location("extent"), width, height)
            GL20C.glUniform2i(location("origin"), region?.x ?: 0, region?.y ?: 0)
            GL20C.glUniform1i(location("mipLevel"), mip)
            GL20C.glUniform1i(location("batchLength"), batchLengths[batch])
            GL20C.glUniform1i(location("batchEnd"), if (batchEnd) 1 else 0)
            GL20C.glUniform1i(location("channelCount"), channels)
            GL20C.glUniform1i(location("hasLuma"), if (lumaState != 0) 1 else 0)
            if (lumaState != 0) GL20C.glUniform3f(location("lumaWeights"), request.lumaWeights[0].toFloat(),
                request.lumaWeights[1].toFloat(), request.lumaWeights[2].toFloat())
            GL20C.glUniform1f(location("nativeScale"), 1f)
            GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL42C.GL_TEXTURE_FETCH_BARRIER_BIT or
                GL42C.GL_FRAMEBUFFER_BARRIER_BIT or GL43C.GL_SHADER_STORAGE_BARRIER_BIT)
            GL43C.glDispatchCompute(groupsX, groupsY, 1)
            GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL43C.GL_SHADER_STORAGE_BARRIER_BIT or
                GL42C.GL_BUFFER_UPDATE_BARRIER_BIT or GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT)
            if (batchEnd) readScreenPartials()
        } finally {
            images.forEachIndexed { unit, image -> GL42C.glBindImageTexture(unit, image[0], image[1], image[2] != 0,
                image[3], image[4], image[5]) }
            if (oldBinding != 0 && oldBufferSize > 0) GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER,
                0, oldBinding, oldBufferStart, oldBufferSize)
            else GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, 0, oldBinding)
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, oldBuffer)
            GL33C.glBindSampler(0, oldSampler)
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, oldTexture)
            GL13C.glActiveTexture(oldActive)
            GL20C.glUseProgram(oldProgram)
        }
        if (sampled == 0) startFrame = frameId
        endFrame = frameId
        sampled++
        batchFrame++
        if (batchEnd) { batch++; batchFrame = 0 }
        return sampled == request.frames
    }

    private fun location(name: String) = GL20C.glGetUniformLocation(program, name)

    private fun readScreenPartials() {
        val bytes = MemoryUtil.memAlloc(partialBytes.toInt()).order(ByteOrder.nativeOrder())
        try {
            GL45C.glGetNamedBufferSubData(partials, 0, bytes)
            val sums = DoubleArray(15)
            for (group in 0 until groupsX * groupsY) for (component in 0..14) {
                sums[component] += bytes.getDouble((group * 15 + component) * 8)
            }
            screenBatchMeans.add((0 until channels).map { (sums[it] / sums[it + 4]).takeIf(Double::isFinite) })
            screenBatchPixelCounts.add((0 until channels).map { sums[it + 4].toLong() })
            screenBatchNonfiniteSamples.add((0 until channels).map { sums[it + 10].toLong() })
            if (lumaState != 0) {
                lumaScreenBatchMeans.add((sums[8] / sums[9]).takeIf(Double::isFinite))
                lumaScreenBatchPixelCounts.add(sums[9].toLong())
                lumaScreenBatchNonfiniteSamples.add(sums[14].toLong())
            }
        } finally { MemoryUtil.memFree(bytes as Buffer) }
    }

    fun readStates(luma: Boolean): Array<FloatArray> {
        check(sampled == request.frames)
        val count = Math.multiplyExact(Math.multiplyExact(width, height), if (luma) 1 else 4)
        return GlArtifactCapture.withPackState {
            Array(14) { layer ->
                val bytes = MemoryUtil.memAlloc(Math.multiplyExact(count, 4)).order(ByteOrder.nativeOrder())
                try {
                    GL45C.glGetTextureSubImage(if (luma) lumaState else state, 0, 0, 0, layer, width, height, 1,
                        if (luma) GL11C.GL_RED else GL11C.GL_RGBA, GL11C.GL_FLOAT, bytes)
                    FloatArray(count) { bytes.getFloat(it * 4) }
                } finally { MemoryUtil.memFree(bytes as Buffer) }
            }
        }
    }

    override fun close() {
        if (state != 0) GL11C.glDeleteTextures(state)
        if (lumaState != 0) GL11C.glDeleteTextures(lumaState)
        if (partials != 0) GL15C.glDeleteBuffers(partials)
        if (program != 0) GL20C.glDeleteProgram(program)
        if (samplerObject != 0) GL33C.glDeleteSamplers(samplerObject)
        state = 0; lumaState = 0; partials = 0; program = 0; samplerObject = 0
    }
}
