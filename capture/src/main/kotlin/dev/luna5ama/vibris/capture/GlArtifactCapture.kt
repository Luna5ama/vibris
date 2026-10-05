package dev.luna5ama.vibris.capture

import dev.vibris.api.ResourceCatalog
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL12C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL21C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL31C
import org.lwjgl.opengl.GL33C
import org.lwjgl.opengl.GL42C
import org.lwjgl.opengl.GL43C
import org.lwjgl.opengl.GL45C
import org.lwjgl.system.MemoryUtil
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.Raster
import java.io.OutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import javax.imageio.ImageIO
import kotlin.math.roundToInt

data class GlCaptureMetadata @JvmOverloads constructor(
    val width: Int,
    val height: Int,
    val depth: Int,
    val internalFormat: String,
    val channelCount: Int,
    val scalarType: ResourceCatalog.ScalarType,
    val byteSize: Long,
    val textureTarget: String = "",
    val channelLayout: String = "",
    val numericClass: String = "",
    val componentBits: Int = 0,
    val readbackFormat: String = "",
    val readbackType: String = "",
    val mipLevels: Int = 1,
    val region: dev.vibris.api.CapturePlan.TextureRegion? = null,
    val sourceWidth: Int = width,
    val sourceHeight: Int = height,
)

object GlArtifactCapture {
    @JvmStatic
    fun captureBuffer(bufferId: Int, output: OutputStream): GlCaptureMetadata {
        GL42C.glMemoryBarrier(GL42C.GL_BUFFER_UPDATE_BARRIER_BIT or GL43C.GL_SHADER_STORAGE_BARRIER_BIT)
        val size = GL45C.glGetNamedBufferParameteri64(bufferId, GL15C.GL_BUFFER_SIZE)
        requireAllocation(size, "buffer")
        val data = MemoryUtil.memAlloc(size.toInt())
        try {
            GL45C.glGetNamedBufferSubData(bufferId, 0, data)
            writeDirect(output, data)
        } finally {
            MemoryUtil.memFree(data as Buffer)
        }
        return GlCaptureMetadata(0, 0, 0, "binary", 0, ResourceCatalog.ScalarType.UINT8, size)
    }

    @JvmStatic
    @JvmOverloads
    fun readTexture(textureId: Int, mipLevel: Int, region: dev.vibris.api.CapturePlan.TextureRegion? = null): TextureReadback = withPackState {
        val source = textureLayout(textureId, mipLevel)
        region?.validate(source.metadata.width, source.metadata.height)
        val layout = if (region == null) source else source.copy(metadata = source.metadata.copy(
            width = region.width, height = region.height, region = region,
            byteSize = Math.multiplyExact(Math.multiplyExact(region.width.toLong(), region.height.toLong()),
                source.metadata.depth.toLong() * source.pixelBytes),
        ))
        requireAllocation(layout.metadata.byteSize, "texture")
        val data = MemoryUtil.memAlloc(layout.metadata.byteSize.toInt()).order(ByteOrder.nativeOrder())
        try {
            GL42C.glMemoryBarrier(TEXTURE_READBACK_BARRIER_BITS)
            GL45C.glGetTextureSubImage(textureId, mipLevel, region?.x ?: 0, region?.y ?: 0, 0,
                layout.metadata.width, layout.metadata.height, layout.metadata.depth, layout.pixelFormat, layout.pixelType, data)
            TextureReadback(layout, data)
        } catch (exception: Throwable) {
            MemoryUtil.memFree(data as Buffer)
            throw exception
        }
    }

    @JvmStatic
    fun captureTexture(
        textureId: Int,
        mipLevel: Int,
        layer: Int,
        format: dev.vibris.api.CapturePlan.ArtifactFormat,
        output: OutputStream,
    ): GlCaptureMetadata = readTexture(textureId, mipLevel).use { readback ->
        when (format) {
            dev.vibris.api.CapturePlan.ArtifactFormat.BIN -> readback.writeBin(output)
            dev.vibris.api.CapturePlan.ArtifactFormat.PNG -> readback.writePng(layer, output)
            else -> throw IllegalArgumentException("Texture export format is unsupported: $format")
        }
        readback.metadata
    }

    @JvmStatic
    fun describeTexture(textureId: Int, mipLevel: Int): GlCaptureMetadata = textureLayout(textureId, mipLevel).metadata

    @JvmStatic
    fun describeTextureOrNull(textureId: Int, mipLevel: Int): GlCaptureMetadata? = try {
        describeTexture(textureId, mipLevel)
    } catch (_: IllegalArgumentException) {
        null
    }

    class TextureReadback internal constructor(
        private val layout: TextureLayout,
        private val data: ByteBuffer,
    ) : AutoCloseable {
        val metadata: GlCaptureMetadata get() = layout.metadata

        fun writeBin(output: OutputStream) = writeDirect(output, data)

        fun values(layer: Int): DoubleArray {
            require(layer in 0 until metadata.depth)
            require(!layout.packedColor && layout.numericClass != NumericClass.DEPTH_STENCIL) {
                "Statistics do not support packed depth/stencil or packed color readback"
            }
            val count = Math.multiplyExact(Math.multiplyExact(metadata.width, metadata.height), metadata.channelCount)
            return DoubleArray(count) { index ->
                val offset = (layer * count + index) * layout.componentBytes
                when (layout.numericClass) {
                    NumericClass.UNORM, NumericClass.UINT -> unsigned(offset).toDouble()
                    NumericClass.SNORM, NumericClass.SINT -> signed(offset).toDouble()
                    NumericClass.FLOAT, NumericClass.DEPTH -> floatValue(offset)
                    NumericClass.STENCIL -> unsigned(offset).toDouble()
                    NumericClass.DEPTH_STENCIL -> error("Unsupported packed readback")
                }
            }
        }

        fun writePng(layer: Int, output: OutputStream) {
            require(layer in 0 until metadata.depth) { "Texture layer is out of range: $layer" }
            require(!layout.packedColor) {
                "PNG export does not support ${metadata.internalFormat}; use format=bin"
            }
            require(layout.numericClass != NumericClass.STENCIL) {
                "PNG export does not support stencil-only textures; use format=bin"
            }
            require(!(layout.numericClass == NumericClass.SINT || layout.numericClass == NumericClass.UINT) ||
                metadata.componentBits <= 16) {
                "PNG export does not support 32-bit integer textures; use format=bin"
            }
            val pngChannels = when (layout.channelLayout) {
                "R", "DEPTH", "DEPTH_STENCIL" -> 1
                "RG" -> 2
                "RGB" -> 3
                "RGBA" -> 4
                else -> throw IllegalArgumentException(
                    "PNG export does not support ${layout.channelLayout}; use format=bin",
                )
            }
            val bitDepth = when (layout.numericClass) {
                NumericClass.FLOAT, NumericClass.DEPTH, NumericClass.DEPTH_STENCIL -> 16
                else -> if (metadata.componentBits <= 8) 8 else 16
            }
            val image = componentImage(metadata.width, metadata.height, pngChannels, bitDepth)
            val raster = image.raster
            for (y in 0 until metadata.height) {
                val sourceY = metadata.height - 1 - y
                for (x in 0 until metadata.width) {
                    for (channel in 0 until pngChannels) {
                        raster.setSample(x, y, channel, pngSample(layer, sourceY, x, channel, bitDepth))
                    }
                }
            }
            check(ImageIO.write(image, "png", output)) { "No PNG writer is available" }
        }

        private fun pngSample(layer: Int, y: Int, x: Int, channel: Int, outputBits: Int): Int {
            val outputMax = (1 shl outputBits) - 1
            if (layout.numericClass == NumericClass.DEPTH_STENCIL) {
                val pixel = ((layer * metadata.height + y) * metadata.width + x) * layout.pixelBytes
                return when (layout.pixelType) {
                    GL30C.GL_UNSIGNED_INT_24_8 -> {
                        val depth = data.getInt(pixel).ushr(8)
                        ((depth.toDouble() / 0xFFFFFF) * outputMax).roundToInt()
                    }
                    GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV ->
                        (data.getFloat(pixel).coerceIn(0.0f, 1.0f) * outputMax).roundToInt()
                    else -> error("Unsupported depth-stencil readback type")
                }
            }
            val component = (((layer * metadata.height + y) * metadata.width + x) * metadata.channelCount) + channel
            val offset = component * layout.componentBytes
            val normalized = when (layout.numericClass) {
                NumericClass.UNORM, NumericClass.UINT -> unsigned(offset).toDouble() / unsignedMax(metadata.componentBits)
                NumericClass.SNORM -> {
                    val value = signed(offset)
                    val maximum = signedMax(metadata.componentBits)
                    val signedNormalized = if (value <= -maximum) -1.0 else value.toDouble() / maximum
                    signedNormalized * 0.5 + 0.5
                }
                NumericClass.SINT -> {
                    val value = signed(offset)
                    if (value < 0) {
                        0.5 + 0.5 * value.toDouble() / -signedMin(metadata.componentBits).toDouble()
                    } else {
                        0.5 + 0.5 * value.toDouble() / signedMax(metadata.componentBits).toDouble()
                    }
                }
                NumericClass.FLOAT, NumericClass.DEPTH -> floatValue(offset).coerceIn(0.0, 1.0)
                NumericClass.DEPTH_STENCIL, NumericClass.STENCIL -> error("Handled separately")
            }
            return (normalized.coerceIn(0.0, 1.0) * outputMax).roundToInt()
        }

        private fun unsigned(offset: Int): Long = when (layout.componentBytes) {
            1 -> data.get(offset).toLong() and 0xFF
            2 -> data.getShort(offset).toLong() and 0xFFFF
            4 -> data.getInt(offset).toLong() and 0xFFFFFFFFL
            else -> error("Unsupported component size")
        }

        private fun signed(offset: Int): Long = when (layout.componentBytes) {
            1 -> data.get(offset).toLong()
            2 -> data.getShort(offset).toLong()
            4 -> data.getInt(offset).toLong()
            else -> error("Unsupported component size")
        }

        private fun floatValue(offset: Int): Double = when (layout.componentBytes) {
            2 -> halfToFloat(data.getShort(offset)).toDouble()
            4 -> data.getFloat(offset).toDouble()
            else -> error("Unsupported floating-point component size")
        }

        override fun close() {
            MemoryUtil.memFree(data as Buffer)
        }
    }

    private fun componentImage(width: Int, height: Int, channels: Int, bits: Int): BufferedImage {
        val colorSpace = ColorSpace.getInstance(if (channels <= 2) ColorSpace.CS_GRAY else ColorSpace.CS_sRGB)
        val hasAlpha = channels == 2 || channels == 4
        val dataType = if (bits == 8) DataBuffer.TYPE_BYTE else DataBuffer.TYPE_USHORT
        val colorModel = ComponentColorModel(
            colorSpace,
            IntArray(channels) { bits },
            hasAlpha,
            false,
            if (hasAlpha) Transparency.TRANSLUCENT else Transparency.OPAQUE,
            dataType,
        )
        val raster = Raster.createInterleavedRaster(dataType, width, height, channels, null)
        return BufferedImage(colorModel, raster, false, null)
    }

    private fun textureLayout(textureId: Int, mipLevel: Int): TextureLayout {
        val target = GL45C.glGetTextureParameteri(textureId, GL45C.GL_TEXTURE_TARGET)
        require(target in supportedTargets) { "Unsupported texture target: ${enumName(target)}" }
        require(GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL13C.GL_TEXTURE_COMPRESSED) == 0) {
            "Compressed texture export is unsupported"
        }
        val width = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL11C.GL_TEXTURE_WIDTH)
        val height = when (target) {
            GL11C.GL_TEXTURE_1D -> 1
            else -> GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL11C.GL_TEXTURE_HEIGHT)
        }
        val depth = when (target) {
            GL12C.GL_TEXTURE_3D -> GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL12C.GL_TEXTURE_DEPTH)
            else -> 1
        }
        require(width > 0 && height > 0 && depth > 0) { "Texture has no requested mip image" }
        val internal = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL11C.GL_TEXTURE_INTERNAL_FORMAT)
        val mapping = packedMapping(internal) ?: componentMapping(textureId, mipLevel, internal)
        val byteSize = Math.multiplyExact(
            Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), depth.toLong()),
            mapping.pixelBytes.toLong(),
        )
        val metadata = GlCaptureMetadata(
            width,
            height,
            depth,
            internalFormatName(internal),
            mapping.channels,
            scalarType(mapping.numericClass, mapping.componentBits),
            byteSize,
            targetName(target),
            mapping.channelLayout,
            mapping.numericClass.label,
            mapping.componentBits,
            enumName(mapping.pixelFormat),
            enumName(mapping.pixelType),
            mipLevelCount(textureId),
        )
        return TextureLayout(
            metadata,
            mapping.pixelFormat,
            mapping.pixelType,
            mapping.componentBytes,
            mapping.pixelBytes,
            mapping.numericClass,
            mapping.channelLayout,
            mapping.packedColor,
        )
    }

    private fun componentMapping(textureId: Int, mipLevel: Int, internal: Int): Mapping {
        val depthBits = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL14C.GL_TEXTURE_DEPTH_SIZE)
        val stencilBits = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL30C.GL_TEXTURE_STENCIL_SIZE)
        if (depthBits > 0 && stencilBits > 0) {
            return if (internal == GL30C.GL_DEPTH32F_STENCIL8) {
                Mapping(2, 32, 4, 8, GL30C.GL_DEPTH_STENCIL, GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV,
                    NumericClass.DEPTH_STENCIL, "DEPTH_STENCIL")
            } else {
                Mapping(2, 24, 4, 4, GL30C.GL_DEPTH_STENCIL, GL30C.GL_UNSIGNED_INT_24_8,
                    NumericClass.DEPTH_STENCIL, "DEPTH_STENCIL")
            }
        }
        if (depthBits > 0) {
            val type = if (internal == GL30C.GL_DEPTH_COMPONENT32F) GL11C.GL_FLOAT else when {
                depthBits <= 16 -> GL11C.GL_UNSIGNED_SHORT
                else -> GL11C.GL_UNSIGNED_INT
            }
            val bits = if (type == GL11C.GL_FLOAT) 32 else if (type == GL11C.GL_UNSIGNED_SHORT) 16 else 32
            return Mapping(1, bits, bits / 8, bits / 8, GL11C.GL_DEPTH_COMPONENT, type,
                if (type == GL11C.GL_FLOAT) NumericClass.DEPTH else NumericClass.UNORM, "DEPTH")
        }
        if (stencilBits > 0) {
            return Mapping(1, 8, 1, 1, GL11C.GL_STENCIL_INDEX, GL11C.GL_UNSIGNED_BYTE,
                NumericClass.STENCIL, "STENCIL")
        }
        val sizes = intArrayOf(
            GL11C.GL_TEXTURE_RED_SIZE,
            GL11C.GL_TEXTURE_GREEN_SIZE,
            GL11C.GL_TEXTURE_BLUE_SIZE,
            GL11C.GL_TEXTURE_ALPHA_SIZE,
        ).map { GL45C.glGetTextureLevelParameteri(textureId, mipLevel, it) }
        val channels = sizes.count { it > 0 }.coerceAtLeast(1)
        val bits = sizes.filter { it > 0 }.distinct().singleOrNull()
            ?: throw IllegalArgumentException("Packed texture format is unsupported: ${internalFormatName(internal)}")
        require(bits == 8 || bits == 16 || bits == 32) {
            "Unsupported component width $bits for ${internalFormatName(internal)}"
        }
        val redType = GL45C.glGetTextureLevelParameteri(textureId, mipLevel, GL30C.GL_TEXTURE_RED_TYPE)
        val numericClass = when (redType) {
            GL11C.GL_FLOAT -> NumericClass.FLOAT
            GL11C.GL_INT -> NumericClass.SINT
            GL11C.GL_UNSIGNED_INT -> NumericClass.UINT
            SIGNED_NORMALIZED -> NumericClass.SNORM
            else -> NumericClass.UNORM
        }
        val type = when (numericClass) {
            NumericClass.FLOAT -> if (bits == 16) GL30C.GL_HALF_FLOAT else GL11C.GL_FLOAT
            NumericClass.SINT, NumericClass.SNORM -> when (bits) {
                8 -> GL11C.GL_BYTE
                16 -> GL11C.GL_SHORT
                else -> GL11C.GL_INT
            }
            NumericClass.UINT, NumericClass.UNORM -> when (bits) {
                8 -> GL11C.GL_UNSIGNED_BYTE
                16 -> GL11C.GL_UNSIGNED_SHORT
                else -> GL11C.GL_UNSIGNED_INT
            }
            else -> error("Unexpected numeric class")
        }
        val integer = numericClass == NumericClass.SINT || numericClass == NumericClass.UINT
        val format = pixelFormat(channels, integer)
        return Mapping(
            channels, bits, bits / 8, channels * bits / 8, format, type, numericClass,
            channelLayout(channels),
        )
    }

    private fun mipLevelCount(textureId: Int): Int {
        val immutable = GL45C.glGetTextureParameteri(textureId, GL_TEXTURE_IMMUTABLE_LEVELS)
        if (immutable > 0) return immutable
        val maximum = GL45C.glGetTextureParameteri(textureId, GL12C.GL_TEXTURE_MAX_LEVEL)
        var levels = 1
        while (levels <= maximum &&
            GL45C.glGetTextureLevelParameteri(textureId, levels, GL11C.GL_TEXTURE_WIDTH) > 0
        ) levels++
        return levels
    }

    private fun packedMapping(internal: Int): Mapping? = when (internal) {
        GL30C.GL_RGB10_A2 -> Mapping(4, 10, 4, 4, GL11C.GL_RGBA, GL30C.GL_UNSIGNED_INT_2_10_10_10_REV,
            NumericClass.UNORM, "RGBA", true)
		GL33C.GL_RGB10_A2UI -> Mapping(4, 10, 4, 4, GL30C.GL_RGBA_INTEGER,
            GL30C.GL_UNSIGNED_INT_2_10_10_10_REV, NumericClass.UINT, "RGBA", true)
        GL30C.GL_R11F_G11F_B10F -> Mapping(3, 11, 4, 4, GL11C.GL_RGB,
            GL30C.GL_UNSIGNED_INT_10F_11F_11F_REV, NumericClass.FLOAT, "RGB", true)
        GL30C.GL_RGB9_E5 -> Mapping(3, 9, 4, 4, GL11C.GL_RGB,
            GL30C.GL_UNSIGNED_INT_5_9_9_9_REV, NumericClass.FLOAT, "RGB", true)
        GL11C.GL_RGB5_A1 -> Mapping(4, 5, 2, 2, GL11C.GL_RGBA,
            GL12C.GL_UNSIGNED_SHORT_5_5_5_1, NumericClass.UNORM, "RGBA", true)
        GL11C.GL_RGBA4 -> Mapping(4, 4, 2, 2, GL11C.GL_RGBA,
            GL12C.GL_UNSIGNED_SHORT_4_4_4_4, NumericClass.UNORM, "RGBA", true)
        else -> null
    }

    private fun pixelFormat(channels: Int, integer: Boolean): Int = when (channels) {
        1 -> if (integer) GL30C.GL_RED_INTEGER else GL11C.GL_RED
        2 -> if (integer) GL30C.GL_RG_INTEGER else GL30C.GL_RG
        3 -> if (integer) GL30C.GL_RGB_INTEGER else GL11C.GL_RGB
        4 -> if (integer) GL30C.GL_RGBA_INTEGER else GL11C.GL_RGBA
        else -> error("Unsupported channel count")
    }

    private fun channelLayout(channels: Int) = when (channels) {
        1 -> "R"
        2 -> "RG"
        3 -> "RGB"
        4 -> "RGBA"
        else -> ""
    }

    private fun scalarType(numericClass: NumericClass, bits: Int): ResourceCatalog.ScalarType = when (numericClass) {
        NumericClass.FLOAT, NumericClass.DEPTH -> if (bits <= 16) ResourceCatalog.ScalarType.FLOAT16 else ResourceCatalog.ScalarType.FLOAT32
        NumericClass.SINT, NumericClass.SNORM -> when {
            bits <= 8 -> ResourceCatalog.ScalarType.SINT8
            bits <= 16 -> ResourceCatalog.ScalarType.SINT16
            else -> ResourceCatalog.ScalarType.SINT32
        }
        else -> when {
            bits <= 8 -> ResourceCatalog.ScalarType.UINT8
            bits <= 16 -> ResourceCatalog.ScalarType.UINT16
            else -> ResourceCatalog.ScalarType.UINT32
        }
    }

    private fun targetName(target: Int) = when (target) {
        GL11C.GL_TEXTURE_1D -> "texture_1d"
        GL11C.GL_TEXTURE_2D -> "texture_2d"
        GL12C.GL_TEXTURE_3D -> "texture_3d"
        GL31_TEXTURE_RECTANGLE -> "texture_rectangle"
        else -> enumName(target)
    }

    private fun internalFormatName(format: Int): String = INTERNAL_FORMAT_NAMES[format] ?: enumName(format)

    private fun enumName(value: Int) = "0x${value.toString(16).uppercase()}"

    private fun unsignedMax(bits: Int): Double = when (bits) {
        8 -> 255.0
        16 -> 65535.0
        else -> 4294967295.0
    }

    private fun signedMax(bits: Int): Long = (1L shl (bits - 1)) - 1
    private fun signedMin(bits: Int): Long = -(1L shl (bits - 1))

    private fun halfToFloat(value: Short): Float {
        val bits = value.toInt() and 0xFFFF
        val sign = (bits ushr 15) and 1
        val exponent = (bits ushr 10) and 0x1F
        val mantissa = bits and 0x3FF
        val floatBits = when (exponent) {
            0 -> if (mantissa == 0) sign shl 31 else {
                var normalized = mantissa
                var shift = 0
                while (normalized and 0x400 == 0) {
                    normalized = normalized shl 1
                    shift++
                }
                (sign shl 31) or ((127 - 15 - shift) shl 23) or ((normalized and 0x3FF) shl 13)
            }
            0x1F -> (sign shl 31) or 0x7F800000 or (mantissa shl 13)
            else -> (sign shl 31) or ((exponent + 127 - 15) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(floatBits)
    }

    private fun writeDirect(output: OutputStream, data: ByteBuffer) {
        val channel = Channels.newChannel(output)
        val bytes = data.duplicate().clear()
        while (bytes.hasRemaining()) channel.write(bytes)
    }

    private fun requireAllocation(bytes: Long, resource: String) {
        require(bytes in 0..MAX_CAPTURE_BYTES) { "$resource capture is too large: $bytes bytes" }
    }

    internal fun <T> withPackState(action: () -> T): T {
        val buffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING)
        val alignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT)
        val swapBytes = GL11C.glGetInteger(GL11C.GL_PACK_SWAP_BYTES)
        val lsbFirst = GL11C.glGetInteger(GL11C.GL_PACK_LSB_FIRST)
        val rowLength = GL11C.glGetInteger(GL12C.GL_PACK_ROW_LENGTH)
        val imageHeight = GL11C.glGetInteger(GL12C.GL_PACK_IMAGE_HEIGHT)
        val skipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS)
        val skipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS)
        val skipImages = GL11C.glGetInteger(GL12C.GL_PACK_SKIP_IMAGES)
        GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1)
        GL11C.glPixelStorei(GL11C.GL_PACK_SWAP_BYTES, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_LSB_FIRST, 0)
        GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, 0)
        GL11C.glPixelStorei(GL12C.GL_PACK_IMAGE_HEIGHT, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0)
        GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0)
        GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_IMAGES, 0)
        try {
            return action()
        } finally {
            GL15C.glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffer)
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, alignment)
            GL11C.glPixelStorei(GL11C.GL_PACK_SWAP_BYTES, swapBytes)
            GL11C.glPixelStorei(GL11C.GL_PACK_LSB_FIRST, lsbFirst)
            GL11C.glPixelStorei(GL12C.GL_PACK_ROW_LENGTH, rowLength)
            GL11C.glPixelStorei(GL12C.GL_PACK_IMAGE_HEIGHT, imageHeight)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, skipPixels)
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, skipRows)
            GL11C.glPixelStorei(GL12C.GL_PACK_SKIP_IMAGES, skipImages)
        }
    }

    private data class Mapping(
        val channels: Int,
        val componentBits: Int,
        val componentBytes: Int,
        val pixelBytes: Int,
        val pixelFormat: Int,
        val pixelType: Int,
        val numericClass: NumericClass,
        val channelLayout: String,
        val packedColor: Boolean = false,
    )

    internal data class TextureLayout(
        val metadata: GlCaptureMetadata,
        val pixelFormat: Int,
        val pixelType: Int,
        val componentBytes: Int,
        val pixelBytes: Int,
        val numericClass: NumericClass,
        val channelLayout: String,
        val packedColor: Boolean,
    )

    internal enum class NumericClass(val label: String) {
        UNORM("unorm"),
        SNORM("snorm"),
        SINT("sint"),
        UINT("uint"),
        FLOAT("float"),
        DEPTH("depth"),
        DEPTH_STENCIL("depth_stencil"),
        STENCIL("stencil"),
    }

    private const val SIGNED_NORMALIZED = 0x8F9C
    private const val GL31_TEXTURE_RECTANGLE = 0x84F5
    private const val GL_TEXTURE_IMMUTABLE_LEVELS = 0x82DF
    private const val MAX_CAPTURE_BYTES = 2_147_483_647L
    private const val TEXTURE_READBACK_BARRIER_BITS = GL42C.GL_TEXTURE_UPDATE_BARRIER_BIT or
        GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL42C.GL_TEXTURE_FETCH_BARRIER_BIT or
        GL42C.GL_FRAMEBUFFER_BARRIER_BIT
    private val supportedTargets = setOf(
        GL11C.GL_TEXTURE_1D,
        GL11C.GL_TEXTURE_2D,
        GL12C.GL_TEXTURE_3D,
        GL31_TEXTURE_RECTANGLE,
    )
    private val INTERNAL_FORMAT_NAMES = mapOf(
        GL30C.GL_R8 to "R8", GL30C.GL_R16 to "R16", GL30C.GL_RG8 to "RG8", GL30C.GL_RG16 to "RG16",
        GL11C.GL_RGB8 to "RGB8", GL11C.GL_RGB16 to "RGB16", GL11C.GL_RGBA8 to "RGBA8", GL11C.GL_RGBA16 to "RGBA16",
		GL31C.GL_R8_SNORM to "R8_SNORM", GL31C.GL_R16_SNORM to "R16_SNORM",
		GL31C.GL_RG8_SNORM to "RG8_SNORM", GL31C.GL_RG16_SNORM to "RG16_SNORM",
		GL31C.GL_RGB8_SNORM to "RGB8_SNORM", GL31C.GL_RGB16_SNORM to "RGB16_SNORM",
		GL31C.GL_RGBA8_SNORM to "RGBA8_SNORM", GL31C.GL_RGBA16_SNORM to "RGBA16_SNORM",
        GL30C.GL_R8I to "R8I", GL30C.GL_R16I to "R16I", GL30C.GL_R32I to "R32I",
        GL30C.GL_R8UI to "R8UI", GL30C.GL_R16UI to "R16UI", GL30C.GL_R32UI to "R32UI",
        GL30C.GL_RG8I to "RG8I", GL30C.GL_RG16I to "RG16I", GL30C.GL_RG32I to "RG32I",
        GL30C.GL_RG8UI to "RG8UI", GL30C.GL_RG16UI to "RG16UI", GL30C.GL_RG32UI to "RG32UI",
        GL30C.GL_RGB8I to "RGB8I", GL30C.GL_RGB16I to "RGB16I", GL30C.GL_RGB32I to "RGB32I",
        GL30C.GL_RGB8UI to "RGB8UI", GL30C.GL_RGB16UI to "RGB16UI", GL30C.GL_RGB32UI to "RGB32UI",
        GL30C.GL_RGBA8I to "RGBA8I", GL30C.GL_RGBA16I to "RGBA16I", GL30C.GL_RGBA32I to "RGBA32I",
        GL30C.GL_RGBA8UI to "RGBA8UI", GL30C.GL_RGBA16UI to "RGBA16UI", GL30C.GL_RGBA32UI to "RGBA32UI",
        GL30C.GL_R16F to "R16F", GL30C.GL_R32F to "R32F", GL30C.GL_RG16F to "RG16F", GL30C.GL_RG32F to "RG32F",
        GL30C.GL_RGB16F to "RGB16F", GL30C.GL_RGB32F to "RGB32F", GL30C.GL_RGBA16F to "RGBA16F", GL30C.GL_RGBA32F to "RGBA32F",
		GL30C.GL_RGB10_A2 to "RGB10_A2", GL33C.GL_RGB10_A2UI to "RGB10_A2UI",
        GL30C.GL_R11F_G11F_B10F to "R11F_G11F_B10F", GL30C.GL_RGB9_E5 to "RGB9_E5",
        GL30C.GL_DEPTH_COMPONENT16 to "DEPTH_COMPONENT16", GL30C.GL_DEPTH_COMPONENT24 to "DEPTH_COMPONENT24",
        GL30C.GL_DEPTH_COMPONENT32F to "DEPTH_COMPONENT32F", GL30C.GL_DEPTH24_STENCIL8 to "DEPTH24_STENCIL8",
        GL30C.GL_DEPTH32F_STENCIL8 to "DEPTH32F_STENCIL8",
    )
}
