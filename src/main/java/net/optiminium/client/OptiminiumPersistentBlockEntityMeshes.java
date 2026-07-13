package net.optiminium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.DecoratedPotRenderer;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.BellRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.model.geom.ModelPart;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import com.mojang.math.Axis;
import net.optiminium.optimization.OptiminiumSettings;
import net.optiminium.OptiminiumMod;
import net.optiminium.mixin.VertexBufferAccessor;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.Locale;

/**
 * Render-thread-owned, shared GPU meshes for explicitly audited vanilla BERs.
 *
 * <p>The initial proof of concept is deliberately limited to non-wobbling decorated pots. Furnace,
 * barrel and hopper geometry is already part of the chunk mesh and does not pass through a BER.
	 * The captured vanilla vertex layout still contains light/overlay attributes, but the persistent-mesh
	 * shader intentionally replaces them with per-instance uniforms. World position is never captured:
	 * the cached model is built against an identity pose and the caller's pose is supplied only when drawing.</p>
 */
public final class OptiminiumPersistentBlockEntityMeshes {
	private static final Map<Object, CachedMesh> MESHES = new LinkedHashMap<>(32, 0.75F, true);
	private static final LongAdder hits = new LongAdder();
	private static final LongAdder misses = new LongAdder();
	private static final LongAdder uploads = new LongAdder();
	private static final LongAdder fallbacks = new LongAdder();
	private static final LongAdder instancesDrawn = new LongAdder();
	private static final LongAdder instanceUploads = new LongAdder();
	private static long gpuBytes;
	private static int hitsThisFrame;
	private static int missesThisFrame;
	private static int uploadsThisFrame;
	private static int drawnThisFrame;
	private static int lastHitsThisFrame;
	private static int lastMissesThisFrame;
	private static int lastUploadsThisFrame;
	private static int lastDrawnThisFrame;
	private static long verticesAvoidedThisFrame;
	private static long lastVerticesAvoidedThisFrame;
	private static int drawCallsThisFrame;
	private static int lastDrawCallsThisFrame;
	private static int culledThisFrame;
	private static int lastCulledThisFrame;
	private static int instanceUploadsThisFrame;
	private static int lastInstanceUploadsThisFrame;
	private static int fallbacksThisFrame;
	private static int lastFallbacksThisFrame;
	private static final ThreadLocal<ChestPartContext> CHEST_PART_CONTEXT = new ThreadLocal<>();
	private static final ThreadLocal<SignPartContext> SIGN_PART_CONTEXT = new ThreadLocal<>();
	private static long lastDiagnosticNanos;
	private static boolean metricsEnabled;
	private static Map<Object, Integer> candidateCountsThisFrame = new HashMap<>();
	private static Map<Object, Integer> candidateCountsLastFrame = Map.of();
	private static final Map<Object, AdaptivePersistencePolicy> ADAPTIVE_POLICIES = new HashMap<>();
	private static final java.util.Set<Object> ACTIVE_KEYS = new java.util.HashSet<>();
	private static final Map<Object, Long> FAILED_KEYS = new HashMap<>();
	private static final long FAILURE_COOLDOWN_FRAMES = 600L;
	private static long policyFrame;
	private static String adaptiveSummary = "none";
	private static int hottestCandidateCount;
	private static int lastHottestCandidateCount;
	private static long lastLoggedMeshUploads;
	private static long meshRebuildsPerSecond;
	private static final java.util.Set<CachedMesh> QUEUED_MESHES =
		Collections.newSetFromMap(new IdentityHashMap<>());

	private OptiminiumPersistentBlockEntityMeshes() {
	}

	public static <E extends BlockEntity> boolean tryRender(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, PoseStack instancePose, int packedLight, int packedOverlay) {
		if (!isPersistenceActive()) {
			return false;
		}
		if (OptiminiumPersistentMeshShader.get() == null) return false;
		RenderSystem.assertOnRenderThread();
		if (!isAuditedVanillaRenderer(renderer, blockEntity)) {
			recordFallback();
			return false;
		}
		Object variant = supportedVariant(blockEntity, partialTick);
		if (variant == null) {
			recordFallback();
			return false;
		}

		MeshKey key = new MeshKey(blockEntity.getType(), Block.getId(blockEntity.getBlockState()),
			variant, renderer.getClass());
		if (!recordCandidateAndIsDense(key)) return false;
		CachedMesh mesh = MESHES.get(key);
		if (mesh == null) {
			recordMiss();
			long buildStart = System.nanoTime();
			mesh = build(key, renderer, blockEntity, partialTick, packedLight, packedOverlay);
			if (mesh == null) {
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				return false;
			}
			policy(key).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		} else {
			recordHit(mesh.vertices);
		}
		long queueStart = System.nanoTime();
		mesh.queue(instancePose, packedLight, packedOverlay);
		policy(key).recordPersistentOverhead(System.nanoTime() - queueStart);
		recordInstanceDrawn();
		return true;
	}

	/** Queues a stable armor stand through the same measured persistent-mesh policy. */
	public static boolean tryRenderArmorStand(EntityRenderer<? super ArmorStand> renderer, ArmorStand armorStand,
			float yaw, float partialTick, PoseStack instancePose, int packedLight) {
		if (!isPersistenceActive() || !OptiminiumSettings.isArmorStandPersistenceEnabled()
				|| OptiminiumPersistentMeshShader.get() == null || !isStableArmorStand(armorStand)) {
			return false;
		}
		ArmorStandKey key = new ArmorStandKey(armorStand);
		if (!recordCandidateAndIsDense(key)) return false;
		CachedMesh mesh = MESHES.get(key);
		if (mesh == null) {
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				renderer.render(armorStand, 0.0F, partialTick, new PoseStack(), capture, packedLight);
				mesh = capture.upload(key);
			} catch (RuntimeException exception) {
				capture.close();
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				return false;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				return false;
			}
			policy(key).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		} else {
			recordHit(mesh.vertices);
		}
		long queueStart = System.nanoTime();
		instancePose.pushPose();
		instancePose.mulPose(Axis.YP.rotationDegrees(-yaw));
		mesh.queue(instancePose, packedLight, 0);
		instancePose.popPose();
		policy(key).recordPersistentOverhead(System.nanoTime() - queueStart);
		recordInstanceDrawn();
		return true;
	}

	public static void recordArmorStandVanilla(ArmorStand armorStand, long elapsedNanos) {
		if (isPersistenceActive() && OptiminiumSettings.isArmorStandPersistenceEnabled()
				&& isStableArmorStand(armorStand)) {
			policy(new ArmorStandKey(armorStand)).recordVanilla(elapsedNanos, 1);
		}
	}

	private static boolean isStableArmorStand(ArmorStand stand) {
		return stand.isAlive() && !stand.isMarker() && !stand.isInvisible()
			&& !stand.isCustomNameVisible() && !stand.displayFireAnimation()
			&& !Minecraft.getInstance().shouldEntityAppearGlowing(stand)
			&& stand.level().getGameTime() - stand.lastHit >= 5L;
	}

	public static void onFrameStart() {
		if (!isPersistenceActive() && (!MESHES.isEmpty() || !ADAPTIVE_POLICIES.isEmpty())) clear();
		evictIfNeeded();
		// A normal frame flushes at the end of the block-entity pass. Discard stale instances if
		// rendering aborted before that hook (world switch, exception, or minimized-frame edge case).
		if (!QUEUED_MESHES.isEmpty()) {
			for (CachedMesh mesh : QUEUED_MESHES) mesh.instances.reset();
			QUEUED_MESHES.clear();
		}
		metricsEnabled = OptiminiumSettings.isBlockEntityRenderCacheDebug() || OptiminiumRenderProfiler.isEnabled();
		lastHitsThisFrame = hitsThisFrame;
		lastMissesThisFrame = missesThisFrame;
		lastUploadsThisFrame = uploadsThisFrame;
		lastDrawnThisFrame = drawnThisFrame;
		lastVerticesAvoidedThisFrame = verticesAvoidedThisFrame;
		lastDrawCallsThisFrame = drawCallsThisFrame;
		lastCulledThisFrame = culledThisFrame;
		lastInstanceUploadsThisFrame = instanceUploadsThisFrame;
		lastFallbacksThisFrame = fallbacksThisFrame;
		policyFrame++;
		updateAdaptivePolicies(candidateCountsThisFrame);
		candidateCountsLastFrame = candidateCountsThisFrame;
		candidateCountsThisFrame = new HashMap<>(Math.max(16, candidateCountsLastFrame.size() * 2));
		lastHottestCandidateCount = hottestCandidateCount;
		hottestCandidateCount = 0;
		hitsThisFrame = 0;
		missesThisFrame = 0;
		uploadsThisFrame = 0;
		drawnThisFrame = 0;
		verticesAvoidedThisFrame = 0L;
		drawCallsThisFrame = 0;
		culledThisFrame = 0;
		instanceUploadsThisFrame = 0;
		fallbacksThisFrame = 0;
		if (OptiminiumSettings.isBlockEntityRenderCacheDebug()) {
			long now = System.nanoTime();
			if (now - lastDiagnosticNanos >= 1_000_000_000L) {
				lastDiagnosticNanos = now;
				long totalUploads = uploads.sum();
				meshRebuildsPerSecond = totalUploads - lastLoggedMeshUploads;
				lastLoggedMeshUploads = totalUploads;
				OptiminiumMod.LOGGER.info(diagnosticLine());
			}
		}
	}

	private static boolean isWobbling(DecoratedPotBlockEntity pot) {
		return pot.getLevel() != null && pot.getLevel().getGameTime() - pot.wobbleStartedAtTick < 20L;
	}

	private static Object supportedVariant(BlockEntity blockEntity, float partialTick) {
		if (blockEntity instanceof BellBlockEntity bell) {
			return bell.shaking ? null : StationaryBellVariant.INSTANCE;
		}
		if (blockEntity instanceof DecoratedPotBlockEntity pot) {
			return isWobbling(pot) ? null : pot.getDecorations();
		}
		BlockEntityType<?> type = blockEntity.getType();
		if (type != BlockEntityType.CHEST && type != BlockEntityType.TRAPPED_CHEST
				&& type != BlockEntityType.ENDER_CHEST) {
			return null;
		}
		if (!(blockEntity instanceof LidBlockEntity lid) || lid.getOpenNess(partialTick) != 0.0F) {
			return null;
		}
		// Double-chest lighting is combined with the neighboring half by ChestRenderer. Retain
		// vanilla until that paired per-instance input is represented explicitly.
		if (blockEntity.getBlockState().hasProperty(ChestBlock.TYPE)
				&& blockEntity.getBlockState().getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			return null;
		}
		return ClosedChestVariant.INSTANCE;
	}

	private enum ClosedChestVariant { INSTANCE }
	private enum ChestBottomVariant { INSTANCE }
	private enum SignBoardVariant { INSTANCE }
	private enum StationaryBellVariant { INSTANCE }

	/** Runs vanilla chest animation while exposing its static bottom ModelPart to ChestRendererMixin. */
	public static <E extends BlockEntity> void renderVanillaWithSplit(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
		if (isAuditedSignRenderer(renderer, blockEntity) && OptiminiumPersistentMeshShader.get() != null
				&& isPersistenceActive()) {
			renderSignWithSplit(renderer, blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
			return;
		}
		if (!isSingleChest(blockEntity) || OptiminiumPersistentMeshShader.get() == null
				|| renderer.getClass() != ChestRenderer.class
				|| !isPersistenceActive()) {
			Object variant = supportedVariant(blockEntity, partialTick);
			if (variant == null || !isAuditedVanillaRenderer(renderer, blockEntity)) {
				renderer.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
			} else {
				MeshKey key = new MeshKey(blockEntity.getType(), Block.getId(blockEntity.getBlockState()), variant, renderer.getClass());
				long start = System.nanoTime();
				renderer.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
				policy(key).recordVanilla(System.nanoTime() - start, 1);
			}
			return;
		}
		if (blockEntity instanceof LidBlockEntity lid && lid.getOpenNess(partialTick) == 0.0F) {
			// A cold, fully closed chest was already counted by tryRender. Keep its entire vanilla
			// renderer intact until the full-mesh key crosses the density threshold.
			MeshKey key = new MeshKey(blockEntity.getType(), Block.getId(blockEntity.getBlockState()),
				ClosedChestVariant.INSTANCE, renderer.getClass());
			long start = System.nanoTime();
			renderer.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
			policy(key).recordVanilla(System.nanoTime() - start, 1);
			return;
		}
		ChestPartContext context = new ChestPartContext(new MeshKey(blockEntity.getType(),
			Block.getId(blockEntity.getBlockState()), ChestBottomVariant.INSTANCE, renderer.getClass()));
		CHEST_PART_CONTEXT.set(context);
		long start = System.nanoTime();
		try {
			renderer.render(blockEntity, partialTick, poseStack,
				renderType -> {
					context.renderType = renderType;
					return bufferSource.getBuffer(renderType);
				}, packedLight, packedOverlay);
		} finally {
			CHEST_PART_CONTEXT.remove();
			policy(context.key).recordVanilla(System.nanoTime() - start, 1);
		}
	}

	private static <E extends BlockEntity> void renderSignWithSplit(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
		SignPartContext context = new SignPartContext(new MeshKey(blockEntity.getType(),
			Block.getId(blockEntity.getBlockState()), SignBoardVariant.INSTANCE, renderer.getClass()));
		SIGN_PART_CONTEXT.set(context);
		long start = System.nanoTime();
		try {
			renderer.render(blockEntity, partialTick, poseStack, renderType -> {
				context.renderType = renderType;
				return bufferSource.getBuffer(renderType);
			}, packedLight, packedOverlay);
		} finally {
			SIGN_PART_CONTEXT.remove();
			policy(context.key).recordVanilla(System.nanoTime() - start, 1);
		}
	}

	public static boolean tryRenderSignBoard(ModelPart root, PoseStack instancePose,
			VertexConsumer vanillaConsumer, int packedLight, int packedOverlay) {
		SignPartContext context = SIGN_PART_CONTEXT.get();
		if (context == null || context.renderType == null) return false;
		return tryRenderModelPart(context.key, context.renderType, root, instancePose, packedLight, packedOverlay);
	}

	/** Called only for ordinal 2 (the bottom) of ChestRenderer's three ModelPart renders. */
	public static boolean tryRenderChestBottom(ModelPart bottom, PoseStack instancePose,
			VertexConsumer vanillaConsumer, int packedLight, int packedOverlay) {
		ChestPartContext context = CHEST_PART_CONTEXT.get();
		if (context == null || context.renderType == null) return false;
		return tryRenderModelPart(context.key, context.renderType, bottom, instancePose, packedLight, packedOverlay);
	}

	private static boolean tryRenderModelPart(MeshKey key, RenderType renderType, ModelPart part,
			PoseStack instancePose, int packedLight, int packedOverlay) {
		if (!recordCandidateAndIsDense(key)) return false;
		CachedMesh mesh = MESHES.get(key);
		if (mesh == null) {
			recordMiss();
			CaptureSource capture = new CaptureSource();
			try {
				part.render(new PoseStack(), capture.getBuffer(renderType), packedLight, packedOverlay);
				long buildStart = System.nanoTime();
				mesh = capture.upload(key);
				if (mesh != null) policy(key).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			} catch (RuntimeException exception) {
				capture.close();
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				return false;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				return false;
			}
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		} else {
			recordHit(mesh.vertices);
		}
		long queueStart = System.nanoTime();
		mesh.queue(instancePose, packedLight, packedOverlay);
		policy(key).recordPersistentOverhead(System.nanoTime() - queueStart);
		recordInstanceDrawn();
		return true;
	}

	private static void recordHit(long avoidedVertices) {
		if (!metricsEnabled) return;
		hits.increment();
		hitsThisFrame++;
		verticesAvoidedThisFrame += avoidedVertices;
	}

	private static void recordMiss() {
		if (!metricsEnabled) return;
		misses.increment();
		missesThisFrame++;
	}

	private static void recordUpload() {
		if (!metricsEnabled) return;
		uploads.increment();
		uploadsThisFrame++;
	}

	private static void recordFallback() {
		if (metricsEnabled) {
			fallbacks.increment();
			fallbacksThisFrame++;
		}
	}

	private static void recordInstanceDrawn() {
		if (!metricsEnabled) return;
		instancesDrawn.increment();
		drawnThisFrame++;
	}

	private static boolean recordCandidateAndIsDense(Object key) {
		int count = candidateCountsThisFrame.merge(key, 1, Integer::sum);
		if (count > hottestCandidateCount) hottestCandidateCount = count;
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null) {
			if (policyFrame < failedUntil) return false;
			FAILED_KEYS.remove(key);
		}
		return ACTIVE_KEYS.contains(key);
	}

	/** Flushes all queued visible instances once, after vanilla has enumerated block entities. */
	public static void flushQueued() {
		if (QUEUED_MESHES.isEmpty()) return;
		RenderSystem.assertOnRenderThread();
		for (CachedMesh mesh : QUEUED_MESHES) mesh.drawQueued();
		QUEUED_MESHES.clear();
	}

	private static boolean isSingleChest(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		if (type != BlockEntityType.CHEST && type != BlockEntityType.TRAPPED_CHEST
				&& type != BlockEntityType.ENDER_CHEST) return false;
		return !blockEntity.getBlockState().hasProperty(ChestBlock.TYPE)
			|| blockEntity.getBlockState().getValue(ChestBlock.TYPE) == ChestType.SINGLE;
	}

	private static boolean isAuditedVanillaRenderer(BlockEntityRenderer<?> renderer, BlockEntity blockEntity) {
		if (blockEntity.getType() == BlockEntityType.BELL) {
			return renderer.getClass() == BellRenderer.class;
		}
		if (blockEntity.getType() == BlockEntityType.DECORATED_POT) {
			return renderer.getClass() == DecoratedPotRenderer.class;
		}
		BlockEntityType<?> type = blockEntity.getType();
		return (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
			|| type == BlockEntityType.ENDER_CHEST) && renderer.getClass() == ChestRenderer.class;
	}

	private static boolean isAuditedSignRenderer(BlockEntityRenderer<?> renderer, BlockEntity blockEntity) {
		return blockEntity.getType() == BlockEntityType.SIGN && renderer.getClass() == SignRenderer.class
			|| blockEntity.getType() == BlockEntityType.HANGING_SIGN
				&& renderer.getClass() == HangingSignRenderer.class;
	}

	private static final class ChestPartContext {
		final MeshKey key;
		RenderType renderType;
		ChestPartContext(MeshKey key) { this.key = key; }
	}

	private static final class SignPartContext {
		final MeshKey key;
		RenderType renderType;
		SignPartContext(MeshKey key) { this.key = key; }
	}

	private static <E extends BlockEntity> CachedMesh build(MeshKey key, BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, int packedLight, int packedOverlay) {
		CaptureSource capture = new CaptureSource();
		try {
			// The vanilla renderer applies facing/model transforms to this identity pose. Instance/world
			// translation remains in the dispatcher-owned pose used later by CachedMesh.draw.
			renderer.render(blockEntity, partialTick, new PoseStack(), capture, packedLight, packedOverlay);
			return capture.upload(key);
		} catch (RuntimeException exception) {
			capture.close();
			return null;
		}
	}

	public static void clear() {
		if (!RenderSystem.isOnRenderThread()) {
			RenderSystem.recordRenderCall(OptiminiumPersistentBlockEntityMeshes::clear);
			return;
		}
		for (CachedMesh mesh : MESHES.values()) mesh.close();
		MESHES.clear();
		QUEUED_MESHES.clear();
		ADAPTIVE_POLICIES.clear();
		ACTIVE_KEYS.clear();
		FAILED_KEYS.clear();
		adaptiveSummary = "none";
		gpuBytes = 0L;
	}

	private static void evictIfNeeded() {
		while (MESHES.size() > OptiminiumSettings.getBlockEntityPersistenceMaxMeshes()) {
			Map.Entry<Object, CachedMesh> eldest = MESHES.entrySet().iterator().next();
			MESHES.remove(eldest.getKey());
			gpuBytes -= eldest.getValue().bytes;
			eldest.getValue().close();
		}
	}

	private static boolean isPersistenceActive() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isBlockEntityRenderCache()
			&& OptiminiumSettings.isBlockEntityPersistenceEnabled();
	}

	private static int densityThreshold() {
		return Math.max(1, Integer.getInteger("optiminium.persistentMeshMinInstances",
			OptiminiumSettings.getBlockEntityPersistenceMinInstances()));
	}

	private static AdaptivePersistencePolicy policy(Object key) {
		return ADAPTIVE_POLICIES.computeIfAbsent(key, ignored -> new AdaptivePersistencePolicy());
	}

	private static void updateAdaptivePolicies(Map<Object, Integer> counts) {
		ACTIVE_KEYS.clear();
		boolean adaptive = OptiminiumSettings.isBlockEntityPersistenceAdaptive();
		int adaptiveMin = OptiminiumSettings.getBlockEntityPersistenceAdaptiveMinInstances();
		int guaranteed = densityThreshold();
		for (Map.Entry<Object, Integer> entry : counts.entrySet()) {
			AdaptivePersistencePolicy value = policy(entry.getKey());
			if (value.beginFrame(policyFrame, entry.getValue(), adaptive, adaptiveMin, guaranteed)) {
				ACTIVE_KEYS.add(entry.getKey());
			}
		}
		ADAPTIVE_POLICIES.entrySet().removeIf(entry -> {
			if (!entry.getValue().expired(policyFrame)) return false;
			CachedMesh mesh = MESHES.remove(entry.getKey());
			if (mesh != null) {
				gpuBytes -= mesh.bytes;
				mesh.close();
			}
			ACTIVE_KEYS.remove(entry.getKey());
			return true;
		});
		FAILED_KEYS.entrySet().removeIf(entry -> policyFrame >= entry.getValue());
		adaptiveSummary = ADAPTIVE_POLICIES.entrySet().stream()
			.max(java.util.Comparator.comparingInt(entry -> entry.getValue().lastCount()))
			.map(entry -> {
				AdaptivePersistencePolicy value = entry.getValue();
				return String.format(Locale.ROOT, "%s count=%d mode=%s vanilla=%.0fns persistent=%.0fns trials=%d reason=%s vertices=%d builds=%d",
					keyName(entry.getKey()), value.lastCount(), value.active() ? "persistent" : value.trial() ? "trial" : "vanilla",
					value.vanillaPerInstanceNanos(), value.persistentPerInstanceNanos(), value.trials(), value.reason(), value.meshVertices(), value.builds());
			})
			.orElse("none");
	}

	private static String keyName(Object key) {
		return key instanceof MeshKey meshKey ? meshKey.type().toString() : "armor_stand";
	}

	public static Snapshot snapshot() {
		return new Snapshot(MESHES.size(), hits.sum(), misses.sum(), uploads.sum(), fallbacks.sum(),
			instancesDrawn.sum(), Math.max(0L, gpuBytes), lastHitsThisFrame, lastMissesThisFrame,
			lastUploadsThisFrame, lastDrawnThisFrame, lastVerticesAvoidedThisFrame, lastDrawCallsThisFrame,
			lastCulledThisFrame, instanceUploads.sum(), lastInstanceUploadsThisFrame,
			lastFallbacksThisFrame, meshRebuildsPerSecond, adaptiveSummary);
	}

	public static String diagnosticLine() {
		Snapshot value = snapshot();
		return String.format(Locale.ROOT,
			"persistentBeMeshes=%d, persistentBeMeshHits=%d, persistentBeMeshMisses=%d, persistentBeMeshUploads=%d, persistentBeMeshRebuildsPerSecond=%d, persistentBeInstanceUploadsFrame=%d, persistentBeInstancesFrame=%d, persistentBeCulledFrame=%d, persistentBeVerticesAvoidedFrame=%d, persistentBeDrawCallsFrame=%d, persistentBeGpuBytes=%d, persistentBeFallbacks=%d, persistentBeFallbacksFrame=%d, persistentBeCandidatesFrame=%d, persistentBeDensityThreshold=%d, persistentBeAdaptiveMin=%d, persistentBeAdaptive=%s, persistentBePolicy=%s, beDispatcherAverageMs=%.6f, clientFps=%d, clientCpuFrameMs=%.3f",
			value.cachedMeshes(), value.hits(), value.misses(), value.uploads(), value.meshRebuildsPerSecond(), value.instanceUploadsThisFrame(), value.instancesDrawnThisFrame(),
			value.instancesCulledThisFrame(), value.verticesAvoidedThisFrame(), value.drawCallsThisFrame(),
			value.estimatedGpuBytes(), value.fallbacks(), value.fallbacksThisFrame(), lastHottestCandidateCount,
			densityThreshold(), OptiminiumSettings.getBlockEntityPersistenceAdaptiveMinInstances(),
			OptiminiumSettings.isBlockEntityPersistenceAdaptive(), value.adaptiveSummary(),
			OptiminiumBlockEntityVirtualizer.snapshot().averageFullRendererMs(),
			Minecraft.getInstance().getFps(),
			OptiminiumGpuOptimizer.getLatestCpuFrameNanos() / 1_000_000.0D);
	}

	private record MeshKey(BlockEntityType<?> type, int stateId, Object variant, Class<?> renderer) {
	}

	private static final class ArmorStandKey {
		private final boolean small;
		private final boolean arms;
		private final boolean noBasePlate;
		private final Object headPose;
		private final Object bodyPose;
		private final Object leftArmPose;
		private final Object rightArmPose;
		private final Object leftLegPose;
		private final Object rightLegPose;
		private final ItemStack[] equipment;
		private final int hash;

		ArmorStandKey(ArmorStand stand) {
			small = stand.isSmall();
			arms = stand.isShowArms();
			noBasePlate = stand.isNoBasePlate();
			headPose = stand.getHeadPose();
			bodyPose = stand.getBodyPose();
			leftArmPose = stand.getLeftArmPose();
			rightArmPose = stand.getRightArmPose();
			leftLegPose = stand.getLeftLegPose();
			rightLegPose = stand.getRightLegPose();
			EquipmentSlot[] slots = EquipmentSlot.values();
			equipment = new ItemStack[slots.length];
			int value = Boolean.hashCode(small);
			value = 31 * value + Boolean.hashCode(arms);
			value = 31 * value + Boolean.hashCode(noBasePlate);
			value = 31 * value + headPose.hashCode();
			value = 31 * value + bodyPose.hashCode();
			value = 31 * value + leftArmPose.hashCode();
			value = 31 * value + rightArmPose.hashCode();
			value = 31 * value + leftLegPose.hashCode();
			value = 31 * value + rightLegPose.hashCode();
			for (int i = 0; i < slots.length; i++) {
				equipment[i] = stand.getItemBySlot(slots[i]).copy();
				value = 31 * value + ItemStack.hashItemAndComponents(equipment[i]);
			}
			hash = value;
		}

		@Override public int hashCode() { return hash; }

		@Override public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof ArmorStandKey other) || hash != other.hash
					|| small != other.small || arms != other.arms || noBasePlate != other.noBasePlate
					|| !headPose.equals(other.headPose) || !bodyPose.equals(other.bodyPose)
					|| !leftArmPose.equals(other.leftArmPose) || !rightArmPose.equals(other.rightArmPose)
					|| !leftLegPose.equals(other.leftLegPose) || !rightLegPose.equals(other.rightLegPose)) return false;
			for (int i = 0; i < equipment.length; i++) {
				if (!ItemStack.isSameItemSameComponents(equipment[i], other.equipment[i])) return false;
			}
			return true;
		}
	}

	private static final class CaptureSource implements MultiBufferSource, AutoCloseable {
		private final Map<RenderType, LayerBuilder> layers = new LinkedHashMap<>();

		@Override
		public BufferBuilder getBuffer(RenderType renderType) {
			if (renderType.sortOnUpload()) {
				throw new IllegalArgumentException("Sorted render types are not persistent-mesh safe");
			}
			return layers.computeIfAbsent(renderType, LayerBuilder::new).builder;
		}

		CachedMesh upload(Object key) {
			List<CachedLayer> uploaded = new ArrayList<>(layers.size());
			long bytes = 0L;
			long vertices = 0L;
			try {
				for (LayerBuilder layer : layers.values()) {
					MeshData data = layer.builder.build();
					if (data == null) continue;
					long layerBytes = data.vertexBuffer().remaining();
					vertices += data.drawState().vertexCount();
					if (data.indexBuffer() != null) layerBytes += data.indexBuffer().remaining();
					VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
					buffer.bind();
					buffer.upload(data);
					VertexBuffer.unbind();
					uploaded.add(new CachedLayer(layer.renderType, buffer));
					bytes += layerBytes;
				}
				if (uploaded.isEmpty()) return null;
				return new CachedMesh(key, List.copyOf(uploaded), bytes, vertices);
			} catch (RuntimeException exception) {
				for (CachedLayer layer : uploaded) layer.buffer.close();
				throw exception;
			} finally {
				close();
			}
		}

		@Override
		public void close() {
			for (LayerBuilder layer : layers.values()) layer.memory.close();
			layers.clear();
		}
	}

	private static final class LayerBuilder {
		final RenderType renderType;
		final ByteBufferBuilder memory;
		final BufferBuilder builder;

		LayerBuilder(RenderType renderType) {
			this.renderType = renderType;
			this.memory = new ByteBufferBuilder(renderType.bufferSize());
			this.builder = new BufferBuilder(memory, renderType.mode(), renderType.format());
		}
	}

	private static final class CachedMesh implements AutoCloseable {
		final Object key;
		final List<CachedLayer> layers;
		final long bytes;
		final long vertices;
		final InstanceBatch instances = new InstanceBatch();

		CachedMesh(Object key, List<CachedLayer> layers, long bytes, long vertices) {
			this.key = key;
			this.layers = layers;
			this.bytes = bytes;
			this.vertices = vertices;
		}

		void queue(PoseStack pose, int packedLight, int packedOverlay) {
			instances.add(pose.last().pose(), packedLight, packedOverlay);
			QUEUED_MESHES.add(this);
		}

		void drawQueued() {
			if (instances.count == 0) return;
			int count = instances.count;
			long start = System.nanoTime();
			instances.upload();
			for (CachedLayer layer : layers) {
				layer.drawInstanced(instances);
				if (metricsEnabled) drawCallsThisFrame++;
			}
			instances.reset();
			policy(key).recordPersistent(System.nanoTime() - start, count);
		}

		@Override public void close() {
			for (CachedLayer layer : layers) layer.buffer.close();
			instances.close();
		}
	}

	private static final class CachedLayer {
		final RenderType renderType;
		final VertexBuffer buffer;
		int configuredInstanceBuffer;

		CachedLayer(RenderType renderType, VertexBuffer buffer) {
			this.renderType = renderType;
			this.buffer = buffer;
		}

		void drawInstanced(InstanceBatch instances) {
			renderType.setupRenderState();
			var shader = OptiminiumPersistentMeshShader.get();
			if (shader != null) {
				buffer.bind();
				if (configuredInstanceBuffer != instances.currentGpuBuffer()) {
					instances.configureAttributes(shader);
					configuredInstanceBuffer = instances.currentGpuBuffer();
				}
				VertexBufferAccessor accessor = (VertexBufferAccessor)(Object)buffer;
				shader.setDefaultUniforms(accessor.optiminium$getMode(), RenderSystem.getModelViewMatrix(),
					RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
				shader.apply();
				GL31.glDrawElementsInstanced(accessor.optiminium$getMode().asGLMode,
					accessor.optiminium$getIndexCount(), accessor.optiminium$resolveIndexType().asGLType,
					0L, instances.count);
				shader.clear();
				VertexBuffer.unbind();
			}
			renderType.clearRenderState();
		}
	}

	private static final class InstanceBatch implements AutoCloseable {
		private static final int FLOATS_PER_INSTANCE = 20;
		private static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES;
		private static final int GPU_RING_SIZE = 3;
		private ByteBuffer data = MemoryUtil.memAlloc(BYTES_PER_INSTANCE * 64).order(ByteOrder.nativeOrder());
		private final int[] gpuBuffers = new int[GPU_RING_SIZE];
		private int currentGpuSlot = -1;
		private int gpuCapacityBytes;
		int count;

		void add(Matrix4f matrix, int packedLight, int packedOverlay) {
			ensureCpuCapacity((count + 1) * BYTES_PER_INSTANCE);
			int base = count * BYTES_PER_INSTANCE;
			matrix.get(base, data);
			data.putFloat(base + 64, packedLight & 0xFFFF);
			data.putFloat(base + 68, packedLight >>> 16 & 0xFFFF);
			data.putFloat(base + 72, packedOverlay & 0xFFFF);
			data.putFloat(base + 76, packedOverlay >>> 16 & 0xFFFF);
			count++;
		}

		void upload() {
			int bytes = count * BYTES_PER_INSTANCE;
			if (gpuCapacityBytes < bytes) {
				int oldCapacity = gpuCapacityBytes;
				gpuCapacityBytes = Integer.highestOneBit(Math.max(bytes - 1, BYTES_PER_INSTANCE)) << 1;
				gpuBytes += (long)(gpuCapacityBytes - oldCapacity) * GPU_RING_SIZE;
				for (int i = 0; i < GPU_RING_SIZE; i++) {
					if (gpuBuffers[i] == 0) gpuBuffers[i] = GL15.glGenBuffers();
					GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpuBuffers[i]);
					GL15.glBufferData(GL15.GL_ARRAY_BUFFER, gpuCapacityBytes, GL15.GL_STREAM_DRAW);
				}
			}
			currentGpuSlot = (currentGpuSlot + 1) % GPU_RING_SIZE;
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpuBuffers[currentGpuSlot]);
			ByteBuffer upload = data.duplicate().order(ByteOrder.nativeOrder());
			upload.position(0).limit(bytes);
			GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, upload);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
			if (metricsEnabled) {
				instanceUploads.increment();
				instanceUploadsThisFrame++;
			}
		}

		void configureAttributes(net.minecraft.client.renderer.ShaderInstance shader) {
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, currentGpuBuffer());
			for (int column = 0; column < 4; column++) {
				configure(shader, "InstanceModel" + column, 4, column * 16L);
			}
			configure(shader, "InstanceLight", 2, 64L);
			configure(shader, "InstanceOverlay", 2, 72L);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		}

		int currentGpuBuffer() {
			return currentGpuSlot < 0 ? 0 : gpuBuffers[currentGpuSlot];
		}

		private static void configure(net.minecraft.client.renderer.ShaderInstance shader,
				String name, int components, long offset) {
			int location = GL20.glGetAttribLocation(shader.getId(), name);
			if (location < 0) throw new IllegalStateException("Missing persistent mesh attribute " + name);
			GL20.glEnableVertexAttribArray(location);
			GL20.glVertexAttribPointer(location, components, GL11.GL_FLOAT, false, BYTES_PER_INSTANCE, offset);
			GL33.glVertexAttribDivisor(location, 1);
		}

		private void ensureCpuCapacity(int required) {
			if (data.capacity() >= required) return;
			int capacity = Integer.highestOneBit(required - 1) << 1;
			ByteBuffer grown = MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
			ByteBuffer old = data.duplicate();
			old.clear();
			grown.put(old);
			// Keep subsequent relative buffer operations rooted at the instance-data origin.
			grown.clear();
			MemoryUtil.memFree(data);
			data = grown;
		}

		void reset() { count = 0; }

		@Override public void close() {
			for (int i = 0; i < GPU_RING_SIZE; i++) {
				if (gpuBuffers[i] != 0) {
					GL15.glDeleteBuffers(gpuBuffers[i]);
					gpuBuffers[i] = 0;
				}
			}
			gpuBytes -= (long)gpuCapacityBytes * GPU_RING_SIZE;
			gpuCapacityBytes = 0;
			if (data != null) {
				MemoryUtil.memFree(data);
				data = null;
			}
		}
	}

	public record Snapshot(int cachedMeshes, long hits, long misses, long uploads, long fallbacks,
		long instancesDrawn, long estimatedGpuBytes, int hitsThisFrame, int missesThisFrame,
		int uploadsThisFrame, int instancesDrawnThisFrame, long verticesAvoidedThisFrame,
		int drawCallsThisFrame, int instancesCulledThisFrame, long instanceUploads,
		int instanceUploadsThisFrame, int fallbacksThisFrame, long meshRebuildsPerSecond,
		String adaptiveSummary) {
	}
}
