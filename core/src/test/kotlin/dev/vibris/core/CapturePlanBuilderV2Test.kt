package dev.vibris.core

import dev.vibris.api.CapturePlan
import dev.vibris.api.ResourceCatalog
import dev.vibris.protocol.v2.Action
import dev.vibris.protocol.v2.ArtifactFormat
import dev.vibris.protocol.v2.DumpBuffer
import dev.vibris.protocol.v2.DumpBufferAfterPass
import dev.vibris.protocol.v2.DumpTexture
import dev.vibris.protocol.v2.DumpTextureAfterPass
import dev.vibris.protocol.v2.ResourceSelector
import dev.vibris.protocol.v2.TextureView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapturePlanBuilderV2Test {
    @Test
    fun cropsAtMipDimensionsAndScalesReservedBytes() {
        val resource = CapturePlan.ResourceSelector(ResourceCatalog.ResourceKind.TEXTURE,
            "iris_custom_image.volume", ResourceCatalog.TextureView.CURRENT, 0, 0)
        fun plan(selector: CapturePlan.ResourceSelector) = CapturePlanBuilder.plan(listOf(
            CapturePlanBuilder.target(selector, CapturePlan.ArtifactFormat.BIN, "crop")), catalog())
        val full = plan(resource)
        val cropped = plan(resource.copy(region = CapturePlan.TextureRegion(1, 1, 1, 1)))
        assertEquals(144L, full.estimatedBytes - cropped.estimatedBytes)
        val failure = assertThrows(RuntimeJobExecutor.Failure::class.java) {
            plan(resource.copy(mipLevel = 1, region = CapturePlan.TextureRegion(1, 0, 1, 1)))
        }
        assertTrue(failure.message!!.contains("1x1"))
    }

    @Test
    fun resolvesAnalysisRequestsAndSuggestsCanonicalImageNames() {
        val action = Action.newBuilder().setTextureStats(dev.vibris.protocol.v2.TextureStats.newBuilder()
            .setResource(ResourceSelector.newBuilder().setLogicalName("iris_custom_image.volume")
                .setView(TextureView.TEXTURE_VIEW_CURRENT))
            .setRegion(dev.vibris.protocol.v2.TextureRegion.newBuilder().setX(1).setY(1).setWidth(1).setHeight(1)))
            .build()
        val request = TextureAnalysisProtocol.request(action, catalog())
        assertEquals(CapturePlan.TextureRegion(1, 1, 1, 1), request.resource.region)
        assertEquals(null, request.passId)
        assertEquals("iris_custom_image.uimg_rcGuideEval", NameSuggestions.suggest("usam_rcGuideEval",
            listOf("colortex0", "iris_custom_image.uimg_rcGuideEval", "iris_ssbo_6")).first())
        val receipt = TextureAnalysisProtocol.stats(dev.vibris.api.TextureAnalysisResult(7,
            listOf(dev.vibris.api.TextureChannelStats(0, 0, null, null, null, null, null, null, null, 2, 1, 1, 0)),
            "{}", null))
        assertEquals(7L, receipt.frameId)
        assertEquals(2L, receipt.channelsList.single().nan)
        assertTrue(!receipt.channelsList.single().hasMean())
    }

    @Test
    fun expandsThreeDimensionalPngAndBufferSidecarsDeterministically() {
        val catalog = catalog()
        val targets = mutableListOf<CapturePlan.Target>()
        CapturePlanBuilder.addAction(targets, Action.newBuilder().setDumpTexture(
            DumpTexture.newBuilder().setResource(
                ResourceSelector.newBuilder()
                    .setLogicalName("iris_custom_image.volume")
                    .setView(TextureView.TEXTURE_VIEW_CURRENT),
            ).setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("volume"),
        ).build(), catalog)
        CapturePlanBuilder.addAction(targets, Action.newBuilder().setDumpBuffer(
            DumpBuffer.newBuilder().setResource(
                ResourceSelector.newBuilder().setLogicalName("iris_ssbo_6"),
            ).setArtifactName("ssbo-6"),
        ).build(), catalog)

        val plan = CapturePlanBuilder.plan(targets, catalog).capture
        assertEquals((0 until 12).map { "volume.layer${it.toString().padStart(4, '0')}.png" } +
            "volume.json", plan.targets[0].outputs.map { it.fileName })
        assertEquals(listOf("ssbo-6.bin", "ssbo-6.json"), plan.targets[1].outputs.map { it.fileName })
        assertEquals((0 until 12).toList(), plan.targets[0].outputs.dropLast(1).map { it.subresourceIndex })
    }

    @Test
    fun resolvesExactAfterPassTextureAndBufferRequests() {
        val catalog = catalog()
        val texture = CapturePlanBuilder.afterPassRequest(
            Action.newBuilder().setDumpTextureAfterPass(
                DumpTextureAfterPass.newBuilder()
                    .setPassId("composite/composite21")
                    .setResource(
                        ResourceSelector.newBuilder()
                            .setLogicalName("iris_custom_image.volume")
                            .setView(TextureView.TEXTURE_VIEW_ALTERNATE)
                            .setMipLevel(1)
                            .setLayer(0),
                    )
                    .setFormat(ArtifactFormat.ARTIFACT_FORMAT_BIN)
                    .setArtifactName("volume-after"),
            ).build(),
            catalog,
        )
        val buffer = CapturePlanBuilder.afterPassRequest(
            Action.newBuilder().setDumpBufferAfterPass(
                DumpBufferAfterPass.newBuilder()
                    .setPassId("composite/composite21")
                    .setResource(ResourceSelector.newBuilder().setLogicalName("iris_ssbo_6"))
                    .setArtifactName("buffer-after"),
            ).build(),
            catalog,
        )

        assertEquals(catalog.mappingSha256, texture.mappingSha256)
        assertEquals(ResourceCatalog.TextureView.ALTERNATE, texture.target.resource.textureView)
        assertEquals(CapturePlan.ArtifactFormat.BIN, buffer.target.format)
        assertEquals(listOf("buffer-after.bin", "buffer-after.json"), buffer.target.outputs.map { it.fileName })
    }

    @Test
    fun rejectsUnknownPassResourceViewAndPhysicalSuffixBeforeScheduling() {
        val catalog = catalog()
        fun texture(passId: String, name: String, view: TextureView) = Action.newBuilder().setDumpTextureAfterPass(
            DumpTextureAfterPass.newBuilder()
                .setPassId(passId)
                .setResource(ResourceSelector.newBuilder().setLogicalName(name).setView(view))
                .setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG)
                .setArtifactName("texture"),
        ).build()

        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.afterPassRequest(
                texture("composite/missing", "iris_custom_image.volume", TextureView.TEXTURE_VIEW_CURRENT),
                catalog,
            )
        }
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.afterPassRequest(
                texture("composite/composite21", "missing", TextureView.TEXTURE_VIEW_CURRENT),
                catalog,
            )
        }
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.afterPassRequest(
                texture("composite/composite21", "iris_custom_image.volume", TextureView.TEXTURE_VIEW_MAIN),
                catalog,
            )
        }
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.afterPassRequest(
                texture("composite/composite21", "iris_custom_image.volume.main", TextureView.TEXTURE_VIEW_MAIN),
                catalog,
            )
        }
    }

    @Test
    fun rejectsUnspecifiedTextureViewAndDuplicateOutputs() {
        val catalog = catalog()
        val unspecified = mutableListOf<CapturePlan.Target>()
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.addAction(unspecified, Action.newBuilder().setDumpTexture(
                DumpTexture.newBuilder().setResource(
                    ResourceSelector.newBuilder().setLogicalName("iris_custom_image.volume"),
                ).setFormat(ArtifactFormat.ARTIFACT_FORMAT_PNG).setArtifactName("texture"),
            ).build(), catalog)
        }

        val target = CapturePlanBuilder.target(
            CapturePlan.ResourceSelector(
                ResourceCatalog.ResourceKind.TEXTURE,
                "iris_custom_image.volume",
                ResourceCatalog.TextureView.CURRENT,
                0,
                0,
            ),
            CapturePlan.ArtifactFormat.BIN,
            "same",
        )
        assertThrows(RuntimeJobExecutor.Failure::class.java) {
            CapturePlanBuilder.plan(listOf(target, target), catalog)
        }
    }

    private fun catalog(): ResourceCatalog {
        val resources = listOf(
            resource("iris_custom_image.volume", ResourceCatalog.ResourceKind.TEXTURE, depth = 12),
            resource("iris_ssbo_6", ResourceCatalog.ResourceKind.BUFFER, depth = 0),
        )
        val pass = ResourceCatalog.PassDescriptor.of(
            ResourceCatalog.PassStage.COMPOSITE,
            "composite21",
            0,
            resources.map(ResourceCatalog.ResourceDescriptor::logicalName),
        )
        return ResourceCatalog.of(resources, listOf(pass))
    }

    private fun resource(name: String, kind: ResourceCatalog.ResourceKind, depth: Int) =
        ResourceCatalog.ResourceDescriptor.of(
            name,
            kind,
            if (kind == ResourceCatalog.ResourceKind.TEXTURE) {
                listOf(ResourceCatalog.TextureView.CURRENT, ResourceCatalog.TextureView.ALTERNATE)
            } else {
                emptyList()
            },
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 2,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 2,
            depth,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 2,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 1,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "binary" else "RGBA8",
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 4,
            ResourceCatalog.ScalarType.UINT8,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 64 else 16L * depth,
            1,
            name,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "shader_storage" else "render_target",
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "" else "TEXTURE_3D",
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "" else "RGBA",
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "" else "unorm",
            if (kind == ResourceCatalog.ResourceKind.BUFFER) 0 else 8,
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "" else "RGBA",
            if (kind == ResourceCatalog.ResourceKind.BUFFER) "" else "UNSIGNED_BYTE",
        )
}
