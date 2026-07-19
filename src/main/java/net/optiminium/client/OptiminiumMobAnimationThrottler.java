package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Render-thread-owned pose cache used only by exact-persistence-qualified mobs. */
public final class OptiminiumMobAnimationThrottler {
	private static final long EXPIRE_AFTER_NANOS = 30_000_000_000L;
	private static final Map<UUID, Entry> ENTRIES = new HashMap<>();
	private static final ThreadLocal<Pass> PASS = new ThreadLocal<>();
	private static long eligible, refreshes, reuses, directPaletteReuses, invalidations, fallbacks, avoidedNanos;
	private static long lastCleanupNanos;

	private OptiminiumMobAnimationThrottler() {
	}

	@SuppressWarnings("rawtypes")
	public static void prepare(EntityModel model, Mob mob, float limbPosition, float limbSpeed, float partialTick) {
		Pass pass = begin(model, mob);
		PASS.set(pass);
		if (pass.action == MobAnimationThrottlePolicy.Action.REUSE) {
			long start = System.nanoTime();
			if (pass.entry.pose.restore(((HierarchicalModel<?>)model).root())) {
				reuses++;
				OptiminiumPersistentBlockEntityMeshes.markCurrentMobPoseReused();
				ENTRIES.put(mob.getUUID(), new Entry(pass.entry.state, pass.entry.pose,
					System.nanoTime(), pass.entry.animationNanos));
				avoidedNanos += Math.max(0L, pass.entry.animationNanos);
				return;
			}
			fallbacks++;
			invalidations++;
			ENTRIES.remove(mob.getUUID());
			pass = pass.asRefresh();
			PASS.set(pass);
			avoidedNanos -= Math.min(avoidedNanos, System.nanoTime() - start);
		}
		long start = System.nanoTime();
		model.prepareMobModel(mob, limbPosition, limbSpeed, partialTick);
		pass.animationStartNanos = start;
	}

	@SuppressWarnings("rawtypes")
	public static void setup(EntityModel model, Mob mob, float limbPosition, float limbSpeed,
			float ageInTicks, float netHeadYaw, float headPitch) {
		Pass pass = PASS.get();
		PASS.remove();
		if (pass != null && pass.action == MobAnimationThrottlePolicy.Action.REUSE) return;
		model.setupAnim(mob, limbPosition, limbSpeed, ageInTicks, netHeadYaw, headPitch);
		if (pass == null || pass.action != MobAnimationThrottlePolicy.Action.REFRESH
				|| !(model instanceof HierarchicalModel<?> hierarchical)) return;
		PoseSnapshot pose = PoseSnapshot.capture(hierarchical.root());
		long elapsed = pass.animationStartNanos == 0L ? 0L : System.nanoTime() - pass.animationStartNanos;
		ENTRIES.put(mob.getUUID(), new Entry(pass.state, pose, System.nanoTime(), elapsed));
		refreshes++;
	}

	private static Pass begin(EntityModel<?> model, Mob mob) {
		long now = System.nanoTime();
		cleanup(now);
		if (!(model instanceof HierarchicalModel<?>)
				|| !OptiminiumPersistentBlockEntityMeshes.isCurrentMobAnimationThrottleEligible()) {
			return Pass.fullRate();
		}
		eligible++;
		Entry old = ENTRIES.get(mob.getUUID());
		if (mob.swinging || mob.hurtTime > 0 || mob.deathTime > 0) {
			if (old != null) invalidations++;
			ENTRIES.remove(mob.getUUID());
			return Pass.fullRate();
		}
		long fingerprint = fingerprint(mob);
		MobAnimationThrottlePolicy.Configuration configuration = new MobAnimationThrottlePolicy.Configuration(
			OptiminiumSettings.isEnabled() && OptiminiumSettings.isMobAnimationThrottlingEnabled(),
			OptiminiumSettings.getMobAnimationNearDistanceBlocks(),
			OptiminiumSettings.getMobAnimationFarDistanceBlocks(),
			OptiminiumSettings.getMobAnimationMediumFps(), OptiminiumSettings.getMobAnimationFarFps());
		double distanceSquared = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
			.distanceToSqr(mob.position());
		MobAnimationThrottlePolicy.Decision decision = MobAnimationThrottlePolicy.decide(mob.getUUID(),
			distanceSquared, now, fingerprint, model, configuration, old == null ? null : old.state);
		if (old != null && decision.action() == MobAnimationThrottlePolicy.Action.REFRESH
				&& (old.state.invalidationFingerprint() != fingerprint || old.state.modelIdentity() != model)) invalidations++;
		return new Pass(decision.action(), decision.state(), old);
	}

	private static long fingerprint(Mob mob) {
		long hash = mob.getPose().ordinal();
		hash = 31L * hash + (mob.isBaby() ? 1 : 0);
		hash = 31L * hash + (mob.isPassenger() ? 1 : 0);
		hash = 31L * hash + (mob.getVehicle() == null ? 0 : mob.getVehicle().getType().hashCode());
		hash = 31L * hash + (mob.swinging ? 1 : 0);
		hash = 31L * hash + mob.hurtTime;
		hash = 31L * hash + mob.deathTime;
		hash = 31L * hash + (mob.isAggressive() ? 1 : 0);
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			hash = 31L * hash + ItemStack.hashItemAndComponents(mob.getItemBySlot(slot));
		}
		return hash;
	}

	private static void cleanup(long now) {
		if (now - lastCleanupNanos < 1_000_000_000L) return;
		lastCleanupNanos = now;
		ENTRIES.entrySet().removeIf(entry -> now - entry.getValue().lastSeenNanos > EXPIRE_AFTER_NANOS);
	}

	public static void clear() {
		ENTRIES.clear();
		PASS.remove();
	}

	static void recordDirectPaletteReuse() { directPaletteReuses++; }
	static void recordCompatibilityFallback() { fallbacks++; }

	public static Snapshot snapshot() {
		return new Snapshot(eligible, refreshes, reuses, directPaletteReuses, invalidations,
			fallbacks, avoidedNanos, ENTRIES.size());
	}

	public record Snapshot(long eligible, long refreshes, long reuses, long directPaletteReuses, long invalidations,
			long fallbacks, long estimatedAnimationNanosAvoided, int cachedPoses) {
	}

	private static final class Pass {
		private final MobAnimationThrottlePolicy.Action action;
		private final MobAnimationThrottlePolicy.State state;
		private final Entry entry;
		private long animationStartNanos;

		private Pass(MobAnimationThrottlePolicy.Action action, MobAnimationThrottlePolicy.State state, Entry entry) {
			this.action = action;
			this.state = state;
			this.entry = entry;
		}

		private static Pass fullRate() { return new Pass(MobAnimationThrottlePolicy.Action.FULL_RATE, null, null); }
		private Pass asRefresh() { return new Pass(MobAnimationThrottlePolicy.Action.REFRESH, state, null); }
	}

	private record Entry(MobAnimationThrottlePolicy.State state, PoseSnapshot pose,
			long lastSeenNanos, long animationNanos) {
	}

	static final class PoseSnapshot {
		private final List<ModelPart> parts;
		private final List<PartState> states;

		private PoseSnapshot(List<ModelPart> parts, List<PartState> states) {
			this.parts = parts;
			this.states = states;
		}

		static PoseSnapshot capture(ModelPart root) {
			List<ModelPart> parts = root.getAllParts().toList();
			List<PartState> states = new ArrayList<>(parts.size());
			for (ModelPart part : parts) states.add(PartState.capture(part));
			return new PoseSnapshot(parts, states);
		}

		boolean restore(ModelPart root) {
			List<ModelPart> current = root.getAllParts().toList();
			if (current.size() != parts.size()) return false;
			for (int i = 0; i < current.size(); i++) if (current.get(i) != parts.get(i)) return false;
			for (int i = 0; i < current.size(); i++) states.get(i).restore(current.get(i));
			return true;
		}
	}

	private record PartState(float x, float y, float z, float xRot, float yRot, float zRot,
			float xScale, float yScale, float zScale, boolean visible, boolean skipDraw) {
		static PartState capture(ModelPart part) {
			return new PartState(part.x, part.y, part.z, part.xRot, part.yRot, part.zRot,
				part.xScale, part.yScale, part.zScale, part.visible, part.skipDraw);
		}

		void restore(ModelPart part) {
			part.x = x; part.y = y; part.z = z;
			part.xRot = xRot; part.yRot = yRot; part.zRot = zRot;
			part.xScale = xScale; part.yScale = yScale; part.zScale = zScale;
			part.visible = visible; part.skipDraw = skipDraw;
		}
	}
}
