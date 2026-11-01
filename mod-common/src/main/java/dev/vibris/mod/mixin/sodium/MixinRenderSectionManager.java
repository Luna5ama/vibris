package dev.vibris.mod.mixin.sodium;

import dev.vibris.mod.mixinterface.VibrisTerrainQuiescence;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.irisshaders.iris.mixinterface.ShadowTerrainState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager implements VibrisTerrainQuiescence {
	@Shadow @Final private ChunkBuilder builder;
	@Shadow @Final private ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> buildResults;
	@Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;
	@Shadow @Final private Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists;
	@Shadow private boolean needsGraphUpdate;
	@Shadow private int thisFrameBlockingTasks;
	@Shadow private int nextFrameBlockingTasks;
	@Shadow private int deferredTasks;

	@Override
	public TerrainSnapshot iris$captureTerrainSnapshot() {
		int scheduled = builder.getScheduledJobCount();
		int busy = builder.getBusyThreadCount();
		int running = 0;
		for (RenderSection section : sectionByPosition.values()) if (section == null || section.getRunningJob() != null) running++;
		int queued = taskLists.values().stream().mapToInt(ArrayDeque::size).sum();
		int shadowQueued = ((Object) this instanceof ShadowTerrainState shadow) ? shadow.iris$getPendingShadowTerrainTasks() : 0;
		boolean ready = !needsGraphUpdate && scheduled == 0 && busy == 0 && buildResults.isEmpty() &&
			thisFrameBlockingTasks == 0 && nextFrameBlockingTasks == 0 && deferredTasks == 0 && running == 0 && queued == 0 && shadowQueued == 0;
		String mismatch = ready ? "" : "terrain work is pending: graph_update=" + needsGraphUpdate +
			", scheduled=" + scheduled + ", busy=" + busy + ", results=" + buildResults.size() +
			", running=" + running + ", queued=" + queued + ", shadow_queued=" + shadowQueued + ", blocking_now=" + thisFrameBlockingTasks +
			", blocking_next=" + nextFrameBlockingTasks + ", deferred=" + deferredTasks;
		return new TerrainSnapshot(ready, List.of(), List.of(), List.of(), false, List.of(), mismatch);
	}
}
