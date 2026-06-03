package net.optiminium.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.optiminium.optimization.OptiminiumOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Level.class)
public abstract class LevelBlockEntityTickMixin {
	@Redirect(
		method = "tickBlockEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V")
	)
	private void optiminium$sleepStableBlockEntity(TickingBlockEntity tickingBlockEntity) {
		if ((Object)this instanceof ServerLevel level && OptiminiumOptimizer.shouldSleepBlockEntity(level, tickingBlockEntity)) {
			return;
		}
		tickingBlockEntity.tick();
	}
}
