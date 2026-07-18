package net.optiminium.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.optiminium.client.PersistentArmorStandKeyHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ArmorStand.class)
public abstract class ArmorStandMixin implements PersistentArmorStandKeyHolder {
	@Unique private long optiminium$persistentArmorRevision;
	@Unique private Object optiminium$persistentArmorKeyCache;

	@Override
	public long optiminium$getPersistentArmorRevision() {
		return optiminium$persistentArmorRevision;
	}

	@Override
	public Object optiminium$getPersistentArmorKeyCache() {
		return optiminium$persistentArmorKeyCache;
	}

	@Override
	public void optiminium$setPersistentArmorKeyCache(Object cache) {
		optiminium$persistentArmorKeyCache = cache;
	}

	@Override
	public void optiminium$invalidatePersistentArmorKey() {
		optiminium$persistentArmorRevision++;
		optiminium$persistentArmorKeyCache = null;
	}

	@Inject(method = "onSyncedDataUpdated", at = @At("HEAD"), require = 0)
	private void optiminium$invalidateOnTrackedState(EntityDataAccessor<?> accessor, CallbackInfo callback) {
		optiminium$invalidatePersistentArmorKey();
	}

	@Inject(method = "setItemSlot", at = @At("HEAD"), require = 0)
	private void optiminium$invalidateOnEquipment(EquipmentSlot slot, ItemStack stack, CallbackInfo callback) {
		optiminium$invalidatePersistentArmorKey();
	}
}
