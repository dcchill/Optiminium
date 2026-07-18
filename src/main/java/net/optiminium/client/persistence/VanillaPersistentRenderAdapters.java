package net.optiminium.client.persistence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;

import java.util.IdentityHashMap;

/** Audited vanilla adapters. These intentionally exclude dynamic maps and transient effects. */
public final class VanillaPersistentRenderAdapters {
	private static boolean registered;

	private VanillaPersistentRenderAdapters() {}

	public static synchronized void register() {
		if (registered) return;
		PersistentRenderAdapterRegistry.register(new ItemFrameAdapter());
		PersistentRenderAdapterRegistry.register(new PaintingAdapter());
		PersistentRenderAdapterRegistry.register(new MinecartAdapter());
		registered = true;
	}

	private abstract static class VanillaAdapter<E extends Entity> implements PersistentRenderAdapter<E> {
		private final IdentityHashMap<Class<?>, IdentityHashMap<EntityType<?>, Family>> families =
			new IdentityHashMap<>();

		@Override public Object familyKey(E entity, EntityRenderer<? super E> renderer) {
			return families.computeIfAbsent(renderer.getClass(), ignored -> new IdentityHashMap<>())
				.computeIfAbsent(entity.getType(), type -> new Family(type, renderer.getClass()));
		}

		@Override public boolean eligible(E entity) {
			return PersistentRenderAdapter.super.eligible(entity)
				&& !Minecraft.getInstance().shouldEntityAppearGlowing(entity);
		}
	}

	private static final class ItemFrameAdapter extends VanillaAdapter<ItemFrame> {
		@Override public Class<ItemFrame> entityClass() { return ItemFrame.class; }

		@Override public boolean eligible(ItemFrame frame) {
			// Scarland's measured atlas flush/state cost is larger than the renderer CPU saved
			// for ordinary frames. Glow frames remain a repeatable win and retain exact capture.
			return frame instanceof GlowItemFrame && super.eligible(frame);
		}

		@Override public Object geometryKey(ItemFrame frame, EntityRenderer<? super ItemFrame> renderer,
				float yaw, float partialTick) {
			ItemStack item = frame.getItem();
			// ItemRenderer uses the entity id as a random/model seed and custom frame events may
			// change contained output independently. Persist only the audited wooden backing for
			// non-empty frames; empty glow frames remain safe for whole-render persistence.
			if (!item.isEmpty()) return null;
			return new FrameKey(frame.getType(), renderer.getClass(), frame.getDirection(), frame.getRotation(),
				ItemStack.hashItemAndComponents(item), frame.getId());
		}

		@Override public long revision(ItemFrame frame) {
			return PersistentEntityStateKey.mix(frame.getRotation(),
				ItemStack.hashItemAndComponents(frame.getItem()));
		}

		@Override public boolean usesRevisionedTransform(ItemFrame frame) { return true; }

		@Override public long transformRevision(ItemFrame frame, float yaw, float partialTick) {
			return hangingTransformRevision(frame, partialTick, frame.getDirection());
		}
	}

	private static final class PaintingAdapter extends VanillaAdapter<Painting> {
		@Override public Class<Painting> entityClass() { return Painting.class; }

		@Override public Object geometryKey(Painting painting, EntityRenderer<? super Painting> renderer,
				float yaw, float partialTick) {
			return new PaintingKey(renderer.getClass(), painting.getDirection(), painting.getVariant(),
				paintingLightSignature(painting), Float.floatToRawIntBits(yaw));
		}

		@Override public long revision(Painting painting) {
			return PersistentEntityStateKey.mix(
				31L * painting.getDirection().ordinal() + painting.getVariant().hashCode(),
				paintingLightSignature(painting));
		}

		@Override public boolean usesCapturedVertexLight(Painting painting) { return true; }
		@Override public boolean usesRevisionedTransform(Painting painting) { return true; }

		@Override public long transformRevision(Painting painting, float yaw, float partialTick) {
			return hangingTransformRevision(painting, partialTick, painting.getDirection());
		}
	}

	private static long hangingTransformRevision(Entity entity, float partialTick, Direction direction) {
		double x = Mth.lerp((double)partialTick, entity.xo, entity.getX());
		double y = Mth.lerp((double)partialTick, entity.yo, entity.getY());
		double z = Mth.lerp((double)partialTick, entity.zo, entity.getZ());
		long hash = PersistentEntityStateKey.mix(Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y));
		hash = PersistentEntityStateKey.mix(hash, Double.doubleToRawLongBits(z));
		long orientation = ((long)direction.ordinal() << 32)
			| (Float.floatToRawIntBits(entity.getYRot()) & 0xffffffffL);
		return PersistentEntityStateKey.mix(hash, orientation);
	}

	/** Mirrors PaintingRenderer.renderPainting's tile-center light lookup exactly. */
	private static long paintingLightSignature(Painting painting) {
		int width = painting.getVariant().value().width();
		int height = painting.getVariant().value().height();
		float left = -width / 2.0F;
		float bottom = -height / 2.0F;
		long hash = 0xcbf29ce484222325L;
		Direction direction = painting.getDirection();
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				float rightX = left + x + 1;
				float leftX = left + x;
				float topY = bottom + y + 1;
				float bottomY = bottom + y;
				int blockX = painting.getBlockX();
				int blockY = Mth.floor(painting.getY() + (topY + bottomY) / 2.0F);
				int blockZ = painting.getBlockZ();
				float horizontalCenter = (rightX + leftX) / 2.0F;
				if (direction == Direction.NORTH) blockX = Mth.floor(painting.getX() + horizontalCenter);
				if (direction == Direction.WEST) blockZ = Mth.floor(painting.getZ() - horizontalCenter);
				if (direction == Direction.SOUTH) blockX = Mth.floor(painting.getX() - horizontalCenter);
				if (direction == Direction.EAST) blockZ = Mth.floor(painting.getZ() + horizontalCenter);
				int light = LevelRenderer.getLightColor(painting.level(), new BlockPos(blockX, blockY, blockZ));
				hash = (hash ^ (light & 0xffffffffL)) * 0x100000001b3L;
			}
		}
		return hash;
	}

	private static final class MinecartAdapter extends VanillaAdapter<AbstractMinecart> {
		@Override public Class<AbstractMinecart> entityClass() { return AbstractMinecart.class; }

		@Override public Object geometryKey(AbstractMinecart cart,
				EntityRenderer<? super AbstractMinecart> renderer, float yaw, float partialTick) {
			// The cart model is persisted independently by the audited renderer split. Rail
			// transforms and randomized display-block geometry remain in vanilla control flow.
			return null;
		}

		@Override public long revision(AbstractMinecart cart) {
			long hash = PersistentEntityStateKey.mix(cart.getDisplayOffset(), cart.getDisplayBlockState().hashCode());
			return PersistentEntityStateKey.mix(hash, Float.floatToRawIntBits(cart.getYRot()));
		}
	}

	private record Family(Object type, Class<?> renderer) {}
	private record FrameKey(Object type, Class<?> renderer, Object direction, int rotation, int itemHash,
		int modelSeed) {}
	private record PaintingKey(Class<?> renderer, Object direction, Object variant, long lightSignature,
		int yawBits) {}
}
