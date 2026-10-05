package dev.luna5ama.vibris.capture

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import org.lwjgl.BufferUtils
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR
import org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR
import org.lwjgl.glfw.GLFW.GLFW_FALSE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE
import org.lwjgl.glfw.GLFW.GLFW_VISIBLE
import org.lwjgl.glfw.GLFW.glfwCreateWindow
import org.lwjgl.glfw.GLFW.glfwDefaultWindowHints
import org.lwjgl.glfw.GLFW.glfwDestroyWindow
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFW.glfwMakeContextCurrent
import org.lwjgl.glfw.GLFW.glfwTerminate
import org.lwjgl.glfw.GLFW.glfwWindowHint
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11C.GL_PACK_ALIGNMENT
import org.lwjgl.opengl.GL11C.GL_PACK_LSB_FIRST
import org.lwjgl.opengl.GL11C.GL_PACK_SKIP_PIXELS
import org.lwjgl.opengl.GL11C.GL_PACK_SKIP_ROWS
import org.lwjgl.opengl.GL11C.GL_PACK_SWAP_BYTES
import org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11C.GL_RGBA8
import org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL11C.glClear
import org.lwjgl.opengl.GL11C.glClearColor
import org.lwjgl.opengl.GL11C.glDisable
import org.lwjgl.opengl.GL11C.glEnable
import org.lwjgl.opengl.GL11C.glGetInteger
import org.lwjgl.opengl.GL11C.glIsEnabled
import org.lwjgl.opengl.GL11C.glPixelStorei
import org.lwjgl.opengl.GL11C.glScissor
import org.lwjgl.opengl.GL12C.GL_PACK_IMAGE_HEIGHT
import org.lwjgl.opengl.GL12C.GL_PACK_ROW_LENGTH
import org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES
import org.lwjgl.opengl.GL15C.GL_STATIC_DRAW
import org.lwjgl.opengl.GL15C.GL_WRITE_ONLY
import org.lwjgl.opengl.GL15C.glBindBuffer
import org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER
import org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING
import org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL20C.GL_LINK_STATUS
import org.lwjgl.opengl.GL20C.glAttachShader
import org.lwjgl.opengl.GL20C.glCompileShader
import org.lwjgl.opengl.GL20C.glCreateProgram
import org.lwjgl.opengl.GL20C.glCreateShader
import org.lwjgl.opengl.GL20C.glDeleteProgram
import org.lwjgl.opengl.GL20C.glDeleteShader
import org.lwjgl.opengl.GL20C.glGetProgrami
import org.lwjgl.opengl.GL20C.glGetShaderi
import org.lwjgl.opengl.GL20C.glLinkProgram
import org.lwjgl.opengl.GL20C.glShaderSource
import org.lwjgl.opengl.GL20C.glUseProgram
import org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER
import org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER
import org.lwjgl.opengl.GL43C.glBindBufferBase
import org.lwjgl.opengl.GL43C.glDispatchCompute
import org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER
import org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING
import org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30C.glBindFramebuffer
import org.lwjgl.opengl.GL42C.glBindImageTexture
import org.lwjgl.opengl.GL45C.glCreateBuffers
import org.lwjgl.opengl.GL45C.glCheckNamedFramebufferStatus
import org.lwjgl.opengl.GL45C.glCreateFramebuffers
import org.lwjgl.opengl.GL45C.glCreateTextures
import org.lwjgl.opengl.GL45C.glDeleteBuffers
import org.lwjgl.opengl.GL45C.glDeleteFramebuffers
import org.lwjgl.opengl.GL45C.glDeleteTextures
import org.lwjgl.opengl.GL45C.glNamedBufferData
import org.lwjgl.opengl.GL45C.glNamedFramebufferTexture
import org.lwjgl.opengl.GL45C.glTextureStorage2D
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class GlArtifactCaptureRuntimeTest {
    @Test
    fun recordsInvalidSamplesAndIncompleteBatches() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return
        withGlContext {
            val texture = glCreateTextures(GL_TEXTURE_2D)
            try {
                glTextureStorage2D(texture, 1, org.lwjgl.opengl.GL30C.GL_RGBA32F, 2, 2)
                val options = dev.vibris.api.TextureAccumulation(15, 4, emptyList(),
                    listOf("mean", "variance", "stderr"), "invalid")
                val request = dev.vibris.api.TextureAnalysisRequest(CapturePlan.ResourceSelector(
                    ResourceCatalog.ResourceKind.TEXTURE, "test", ResourceCatalog.TextureView.CURRENT, 0, 0), null, options)
                GpuTextureAccumulator(options, null, texture, 0).use { accumulator ->
                    val color = BufferUtils.createFloatBuffer(4)
                    for (frame in 0 until 15) {
                        color.clear(); color.put(if (frame == 4) Float.NaN else frame.toFloat())
                        repeat(3) { color.put(frame.toFloat()) }; color.flip()
                        org.lwjgl.opengl.GL44C.glClearTexImage(texture, 0, org.lwjgl.opengl.GL11C.GL_RGBA,
                            org.lwjgl.opengl.GL11C.GL_FLOAT, color)
                        accumulator.sample(texture, frame + 1L)
                    }
                    val state = accumulator.readStates(false)
                    assertEquals(14f, state[2][0]); assertEquals(1f, state[10][0])
                    assertEquals(1f, state[11][0]); assertEquals(4L, accumulator.screenBatchNonfiniteSamples[1][0])
                    val files = HashMap<String, java.io.ByteArrayOutputStream>()
                    val output = TextureAccumulationOutput.write(request, accumulator,
                        dev.vibris.api.ArtifactSink { name -> java.io.ByteArrayOutputStream().also { files[name] = it } },
                        dev.vibris.api.CancellationToken.none())
                    val json = kotlinx.serialization.json.Json.parseToJsonElement(files.getValue("invalid.mean.json").toString("UTF-8"))
                        as kotlinx.serialization.json.JsonObject
                    val accumulation = json.getValue("accumulation") as kotlinx.serialization.json.JsonObject
                    assertEquals("[1,3]", accumulation.getValue("incomplete_batch_indices").toString())
                    assertEquals("false", accumulation.getValue("standard_batch_means_se").toString())
                    assertNotNull(output.capture)
                    assertEquals(14f, java.nio.ByteBuffer.wrap(files.getValue("invalid.valid_count.bin").toByteArray())
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).float)
                }
            } finally { org.lwjgl.opengl.GL11C.glDeleteTextures(texture) }
        }
    }

    @Test
    fun accumulatesOnGpuAndMatchesAnalyticBatchMeans() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return
        withGlContext {
            val texture = glCreateTextures(GL_TEXTURE_2D)
            try {
                glTextureStorage2D(texture, 1, org.lwjgl.opengl.GL30C.GL_RGBA32F, 8, 4)
                val options = dev.vibris.api.TextureAccumulation(64, 16, listOf(0.2, 0.7, 0.1),
                    listOf("mean", "variance", "stderr", "flicker", "nonfinite"), "test")
                GpuTextureAccumulator(options, CapturePlan.TextureRegion(2, 1, 4, 2), texture, 0).use { accumulator ->
                    val color = BufferUtils.createFloatBuffer(4)
                    for (frame in 0 until 64) {
                        color.clear(); repeat(4) { color.put(frame.toFloat()) }; color.flip()
                        org.lwjgl.opengl.GL44C.glClearTexImage(texture, 0, org.lwjgl.opengl.GL11C.GL_RGBA,
                            org.lwjgl.opengl.GL11C.GL_FLOAT, color)
                        assertEquals(frame == 63, accumulator.sample(texture, frame + 1L))
                    }
                    val state = accumulator.readStates(false)
                    assertEquals(31.5, state[0][0].toDouble(), 1e-4)
                    assertEquals(64f, state[2][0])
                    assertEquals(64.0 * 65 / 12, state[1][0].toDouble() / 63, 1e-4)
                    assertEquals(16.0 * 16 * 17 / 12 / 16,
                        state[6][0].toDouble() / 15 / 16, 1e-4)
                    assertEquals(63f, state[9][0]); assertEquals(0f, state[10][0])
                    assertEquals(31.5, accumulator.readStates(true)[0][0].toDouble(), 1e-4)
                    assertEquals(1.5, accumulator.screenBatchMeans[0][0]!!, 1e-4)
                    assertEquals(61.5, accumulator.screenBatchMeans[15][0]!!, 1e-4)
                    for (channel in 0 until 4) {
                        assertEquals(8L, accumulator.screenBatchPixelCounts[0][channel])
                        assertEquals(1.5, accumulator.screenBatchMeans[0][channel]!!, 1e-4)
                    }
                    assertEquals(0, org.lwjgl.opengl.GL11C.glGetError())
                }
            } finally { org.lwjgl.opengl.GL11C.glDeleteTextures(texture) }
        }
    }

    @Test
    fun capturesTypedArtifactsAndRestoresPixelPackState() {
        if (!System.getProperty("vibris.runtimeTest").toBoolean()) return

        withGlContext {
            val ssbo = glCreateBuffers()
            val texture = glCreateTextures(GL_TEXTURE_2D)
            val alternateTexture = glCreateTextures(GL_TEXTURE_2D)
            val framebuffer = glCreateFramebuffers()
            val packBuffer = glCreateBuffers()
            try {
                val bufferBytes = byteArrayOf(0, 0x7f, 0x80.toByte(), 0xff.toByte())
                glNamedBufferData(ssbo, BufferUtils.createByteBuffer(4).put(bufferBytes).flip(), GL_STATIC_DRAW)
                val bufferOutput = ByteArrayOutputStream()
                val bufferMetadata = GlArtifactCapture.captureBuffer(ssbo, bufferOutput)
                assertContentEquals(bufferBytes, bufferOutput.toByteArray())
                assertEquals(4, bufferMetadata.byteSize)
                assertEquals(ResourceCatalog.ScalarType.UINT8, bufferMetadata.scalarType)

                writeBufferFromComputeShader(ssbo)
                val gpuOutput = ByteArrayOutputStream()
                GlArtifactCapture.captureBuffer(ssbo, gpuOutput)
                val expectedGpuBytes = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                    .putInt(0x12345678).array()
                assertContentEquals(expectedGpuBytes, gpuOutput.toByteArray())
                assertFalse(bufferBytes.contentEquals(gpuOutput.toByteArray()))

                glTextureStorage2D(texture, 1, GL_RGBA8, 2, 2)
                glTextureStorage2D(alternateTexture, 1, GL_RGBA8, 2, 2)
                writeTextureFromGraphics(framebuffer, texture)
                writeTextureFromComputeShader(alternateTexture)
                setPackState(packBuffer)

                val rawOutput = ByteArrayOutputStream()
                val rawMetadata = GlArtifactCapture.captureTexture(
                    texture,
                    0,
                    0,
                    CapturePlan.ArtifactFormat.BIN,
                    rawOutput,
                )
                GlArtifactCapture.readTexture(texture, 0, CapturePlan.TextureRegion(1, 1, 1, 1)).use { crop ->
                    val bytes = ByteArrayOutputStream()
                    crop.writeBin(bytes)
                    assertContentEquals(rawOutput.toByteArray().copyOfRange(12, 16), bytes.toByteArray())
                    assertEquals(2, crop.metadata.sourceWidth)
                    assertEquals(1, crop.metadata.width)
                }
                assertEquals(2, rawMetadata.width)
                assertEquals(2, rawMetadata.height)
                assertEquals(4, rawMetadata.channelCount)
                assertEquals(ResourceCatalog.ScalarType.UINT8, rawMetadata.scalarType)
                assertEquals(16, rawMetadata.byteSize)
                assertEquals(rawMetadata, GlArtifactCapture.describeTexture(texture, 0))
                assertEquals(rawMetadata.byteSize, rawOutput.size().toLong())
                assertContentEquals(
                    byteArrayOf(
                        255.toByte(), 0, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(),
                        0, 0, 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(),
                    ),
                    rawOutput.toByteArray(),
                )
                assertPackState(packBuffer)

                val pngOutput = ByteArrayOutputStream()
                val pngMetadata = GlArtifactCapture.captureTexture(
                    texture,
                    0,
                    0,
                    CapturePlan.ArtifactFormat.PNG,
                    pngOutput,
                )
                assertEquals(rawMetadata, pngMetadata)
                val image = assertNotNull(ImageIO.read(ByteArrayInputStream(pngOutput.toByteArray())))
                assertEquals(2, image.width)
                assertEquals(2, image.height)
                assertEquals(0xff0000ff.toInt(), image.getRGB(0, 0))
                assertEquals(0xffffffff.toInt(), image.getRGB(1, 0))
                assertEquals(0xffff0000.toInt(), image.getRGB(0, 1))
                assertEquals(0xff00ff00.toInt(), image.getRGB(1, 1))
                assertPackState(packBuffer)

                val alternatePng = ByteArrayOutputStream()
                GlArtifactCapture.captureTexture(
                    alternateTexture,
                    0,
                    0,
                    CapturePlan.ArtifactFormat.PNG,
                    alternatePng,
                )
                val alternateImage = assertNotNull(ImageIO.read(ByteArrayInputStream(alternatePng.toByteArray())))
                assertEquals(0xffff00ff.toInt(), alternateImage.getRGB(0, 0))
                assertEquals(0xff000000.toInt(), alternateImage.getRGB(1, 0))
                assertEquals(0xffffff00.toInt(), alternateImage.getRGB(0, 1))
                assertEquals(0xff00ffff.toInt(), alternateImage.getRGB(1, 1))
                assertPackState(packBuffer)
            } finally {
                resetPackState()
                glDeleteBuffers(packBuffer)
                glDeleteFramebuffers(framebuffer)
                glDeleteTextures(alternateTexture)
                glDeleteTextures(texture)
                glDeleteBuffers(ssbo)
            }
        }
    }

    private fun setPackState(packBuffer: Int) {
        glNamedBufferData(packBuffer, 16L, GL_STATIC_DRAW)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer)
        glPixelStorei(GL_PACK_ALIGNMENT, 8)
        glPixelStorei(GL_PACK_SWAP_BYTES, 1)
        glPixelStorei(GL_PACK_LSB_FIRST, 1)
        glPixelStorei(GL_PACK_ROW_LENGTH, 17)
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 19)
        glPixelStorei(GL_PACK_SKIP_PIXELS, 3)
        glPixelStorei(GL_PACK_SKIP_ROWS, 5)
        glPixelStorei(GL_PACK_SKIP_IMAGES, 7)
    }

    private fun assertPackState(packBuffer: Int) {
        assertEquals(packBuffer, glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING))
        assertEquals(8, glGetInteger(GL_PACK_ALIGNMENT))
        assertEquals(1, glGetInteger(GL_PACK_SWAP_BYTES))
        assertEquals(1, glGetInteger(GL_PACK_LSB_FIRST))
        assertEquals(17, glGetInteger(GL_PACK_ROW_LENGTH))
        assertEquals(19, glGetInteger(GL_PACK_IMAGE_HEIGHT))
        assertEquals(3, glGetInteger(GL_PACK_SKIP_PIXELS))
        assertEquals(5, glGetInteger(GL_PACK_SKIP_ROWS))
        assertEquals(7, glGetInteger(GL_PACK_SKIP_IMAGES))
    }

    private fun resetPackState() {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        glPixelStorei(GL_PACK_ALIGNMENT, 4)
        glPixelStorei(GL_PACK_SWAP_BYTES, 0)
        glPixelStorei(GL_PACK_LSB_FIRST, 0)
        glPixelStorei(GL_PACK_ROW_LENGTH, 0)
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0)
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0)
        glPixelStorei(GL_PACK_SKIP_ROWS, 0)
        glPixelStorei(GL_PACK_SKIP_IMAGES, 0)
    }

    private fun writeTextureFromGraphics(framebuffer: Int, texture: Int) {
        glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0, texture, 0)
        check(glCheckNamedFramebufferStatus(framebuffer, GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "Graphics framebuffer is incomplete"
        }
        val previousFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val previousScissor = glIsEnabled(GL_SCISSOR_TEST)
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer)
            glEnable(GL_SCISSOR_TEST)
            clearPixel(0, 0, 1.0f, 0.0f, 0.0f)
            clearPixel(1, 0, 0.0f, 1.0f, 0.0f)
            clearPixel(0, 1, 0.0f, 0.0f, 1.0f)
            clearPixel(1, 1, 1.0f, 1.0f, 1.0f)
        } finally {
            if (!previousScissor) glDisable(GL_SCISSOR_TEST)
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousFramebuffer)
        }
    }

    private fun clearPixel(x: Int, y: Int, red: Float, green: Float, blue: Float) {
        glScissor(x, y, 1, 1)
        glClearColor(red, green, blue, 1.0f)
        glClear(GL_COLOR_BUFFER_BIT)
    }

    private fun writeTextureFromComputeShader(texture: Int) {
        val shader = glCreateShader(GL_COMPUTE_SHADER)
        val program = glCreateProgram()
        try {
            glShaderSource(shader, """
                #version 430 core
                layout(local_size_x = 1, local_size_y = 1) in;
                layout(rgba8, binding = 0) uniform writeonly image2D outputImage;
                void main() {
                    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
                    vec4 color;
                    if (pixel == ivec2(0, 0)) color = vec4(1.0, 1.0, 0.0, 1.0);
                    else if (pixel == ivec2(1, 0)) color = vec4(0.0, 1.0, 1.0, 1.0);
                    else if (pixel == ivec2(0, 1)) color = vec4(1.0, 0.0, 1.0, 1.0);
                    else color = vec4(0.0, 0.0, 0.0, 1.0);
                    imageStore(outputImage, pixel, color);
                }
            """.trimIndent())
            glCompileShader(shader)
            check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) { "Compute texture shader compilation failed" }
            glAttachShader(program, shader)
            glLinkProgram(program)
            check(glGetProgrami(program, GL_LINK_STATUS) != 0) { "Compute texture program link failed" }
            glBindImageTexture(0, texture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8)
            glUseProgram(program)
            glDispatchCompute(2, 2, 1)
            glUseProgram(0)
            glBindImageTexture(0, 0, 0, false, 0, GL_WRITE_ONLY, GL_RGBA8)
        } finally {
            glDeleteProgram(program)
            glDeleteShader(shader)
        }
    }

    private fun writeBufferFromComputeShader(buffer: Int) {
        val shader = glCreateShader(GL_COMPUTE_SHADER)
        val program = glCreateProgram()
        try {
            glShaderSource(shader, """
                #version 430 core
                layout(local_size_x = 1) in;
                layout(std430, binding = 0) buffer Output { uint value; } outputBuffer;
                void main() { outputBuffer.value = 0x12345678u; }
            """.trimIndent())
            glCompileShader(shader)
            check(glGetShaderi(shader, GL_COMPILE_STATUS) != 0) { "Compute shader compilation failed" }
            glAttachShader(program, shader)
            glLinkProgram(program)
            check(glGetProgrami(program, GL_LINK_STATUS) != 0) { "Compute program link failed" }
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffer)
            glUseProgram(program)
            glDispatchCompute(1, 1, 1)
            glUseProgram(0)
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0)
        } finally {
            glDeleteProgram(program)
            glDeleteShader(shader)
        }
    }

    private fun withGlContext(action: () -> Unit) {
        check(glfwInit()) { "Failed to initialize GLFW" }
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        val window = glfwCreateWindow(64, 64, "vibris-artifact-capture-test", 0L, 0L)
        check(window != 0L) { "Failed to create GLFW window" }
        try {
            glfwMakeContextCurrent(window)
            GL.createCapabilities()
            action()
        } finally {
            GL.setCapabilities(null)
            glfwFreeCallbacks(window)
            glfwDestroyWindow(window)
            glfwTerminate()
        }
    }
}
