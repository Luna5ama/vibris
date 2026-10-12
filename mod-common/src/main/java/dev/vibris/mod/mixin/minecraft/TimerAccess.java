package dev.vibris.mod.mixin.minecraft;

import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SystemTimeUniforms.Timer.class, remap = false)
public interface TimerAccess {
	@Accessor("frameTimeCounter") void vibris$setFrameTimeCounter(float value);
	@Accessor("frameTimeCounter") float vibris$getFrameTimeCounter();
	@Accessor("lastFrameTime") void vibris$setLastFrameTime(float value);
}