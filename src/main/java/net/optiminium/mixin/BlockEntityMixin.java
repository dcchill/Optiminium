package net.optiminium.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import net.optiminium.client.PersistentBlockEntityKeyHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements PersistentBlockEntityKeyHolder {
	@Unique private Object optiminium$persistentMeshKeyCache;
	@Unique private Object optiminium$persistentGenericKeyCache;

	@Override
	public Object optiminium$getPersistentMeshKeyCache() {
		return optiminium$persistentMeshKeyCache;
	}

	@Override
	public void optiminium$setPersistentMeshKeyCache(Object cache) {
		optiminium$persistentMeshKeyCache = cache;
	}

	@Override
	public Object optiminium$getPersistentGenericKeyCache() {
		return optiminium$persistentGenericKeyCache;
	}

	@Override
	public void optiminium$setPersistentGenericKeyCache(Object cache) {
		optiminium$persistentGenericKeyCache = cache;
	}

	@Inject(method = "setChanged", at = @At("HEAD"))
	private void optiminium$invalidatePersistentGenericState(CallbackInfo callback) {
		optiminium$persistentMeshKeyCache = null;
		optiminium$persistentGenericKeyCache = null;
		OptiminiumPersistentBlockEntityMeshes.invalidateGenericState((BlockEntity)(Object)this);
	}
}
