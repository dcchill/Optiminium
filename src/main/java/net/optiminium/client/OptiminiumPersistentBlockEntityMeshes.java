package net.optiminium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
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
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import com.mojang.math.Axis;
import net.optiminium.optimization.OptiminiumSettings;
import net.optiminium.OptiminiumMod;
import net.optiminium.mixin.ModelPartAccessor;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.ARBBaseInstance;
import org.lwjgl.opengl.ARBMultiDrawIndirect;
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
import java.util.Set;
import java.util.UUID;

/**
 * Render-thread-owned, shared GPU meshes for block-entity renderers.
 *
	 * <p>Known-safe vanilla renderers use specialized keys. Every other renderer is automatically
	 * grouped by serialized state and must produce identical captured geometry across spaced samples;
	 * changing or unsupported output stays on the vanilla path and is periodically re-evaluated.
	 * Furnace, barrel and hopper geometry is already part of the chunk mesh and does not pass through a BER.
	 * The captured vanilla vertex layout still contains light/overlay attributes, but the persistent-mesh
	 * shader intentionally replaces them with per-instance uniforms. World position is never captured:
	 * the cached model is built against an identity pose and the caller's pose is supplied only when drawing.</p>
 */
public final class OptiminiumPersistentBlockEntityMeshes {
	private static final Map<Object, CachedMesh> MESHES = new LinkedHashMap<>(32, 0.75F, true);
	private static final List<AtlasPage> ATLAS_PAGES = new ArrayList<>();
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
	private static final ThreadLocal<MobRenderContext> MOB_RENDER_CONTEXT = new ThreadLocal<>();
	private static final ThreadLocal<ExactGroupKey> MOB_FAST_VANILLA_KEY = new ThreadLocal<>();
	private static final ThreadLocal<PartVanillaTiming> PART_VANILLA_TIMING = new ThreadLocal<>();
	private static long lastDiagnosticNanos;
	private static boolean metricsEnabled;
	private static Map<Object, Integer> candidateCountsThisFrame = new HashMap<>();
	private static Map<Object, Integer> candidateCountsLastFrame = Map.of();
	private static Map<GenericFamilyKey, Integer> genericFamilyCountsThisFrame = new HashMap<>();
	private static Map<GenericFamilyKey, Integer> genericFamilyCountsLastFrame = Map.of();
	private static Map<ArmorStandBoneKey, Integer> armorStandCountsThisFrame = new HashMap<>();
	private static Map<ArmorStandBoneKey, Integer> armorStandCountsLastFrame = Map.of();
	private static final Map<GenericFamilyKey, GenericFamilyDiscoveryPolicy> GENERIC_FAMILY_DISCOVERY = new HashMap<>();
	private static final Map<ArmorStandBoneKey, ArmorStandFamilyState> ARMOR_STAND_FAMILIES = new HashMap<>();
	private static final Map<Object, AdaptivePersistencePolicy> ADAPTIVE_POLICIES = new HashMap<>();
	private static final java.util.Set<Object> ACTIVE_KEYS = new java.util.HashSet<>();
	private static final Map<Object, Long> FAILED_KEYS = new HashMap<>();
	private static final Map<GenericSourceKey, GenericQualification> GENERIC_QUALIFICATIONS = new HashMap<>();
	private static final Map<ExactGroupKey, MobTopologyPolicy> MOB_QUALIFICATIONS = new HashMap<>();
	private static final Map<ExactGroupKey, String> MOB_FALLBACK_REASONS = new HashMap<>();
	private static final java.util.Set<ExactGroupKey> MOB_ANCHORS_THIS_FRAME = new java.util.HashSet<>();
	private static final java.util.Set<Object> BLOCK_ENTITY_ANCHORS_THIS_FRAME = new java.util.HashSet<>();
	private static BlockEntity lastGenericEntity;
	private static GenericSourceKey lastGenericKey;
	private static long genericFamilyRejects;
	private static long genericFamilyDormantSkips;
	private static long genericKeyBuilds;
	private static long genericKeyNanos;
	private static long genericKeyCacheHits;
	private static long genericKeySerializations;
	private static long genericKeyTimingCounter;
	private static long genericVanillaTimingCounter;
	private static int pendingGenericVanillaWeight = 1;
	private static long mobPartsSuppressed;
	private static long mobVanillaParts;
	private static long mobQualifiedGroups;
	private static long mobFallbacks;
	private static long mobBoneUploads;
	private static long residentTransformUploads;
	private static long residentTransformUploadBytes;
	private static String mobSummary = "none";
	private static String armorStandSummary = "none";
	private static long atlasStateSetups;
	private static long atlasFallbackDraws;
	private static long atlasIndirectDraws;
	private static boolean atlasActiveStatePendingUnbind;
	private static boolean persistenceConfiguredThisFrame;
	private static boolean persistenceActiveThisFrame;
	private static boolean mobPersistenceActiveThisFrame;
	private static final PersistenceDiscoveryCooldown PERSISTENCE_DISCOVERY_COOLDOWN =
		new PersistenceDiscoveryCooldown();
	private static final long GENERIC_ACTIVE_KEY_REFRESH_TICKS = 100L;
	private static final long GENERIC_INACTIVE_KEY_REFRESH_TICKS = 100L;
	private static final long FAILURE_COOLDOWN_FRAMES = 600L;
	private static final long GENERIC_SAMPLE_INTERVAL_FRAMES = 10L;
	private static final long GENERIC_VALIDATION_INTERVAL_FRAMES = 120L;
	private static final int GENERIC_STABLE_SAMPLES = 3;
	private static final int NORMALIZED_CAPTURE_LIGHT = 0x00F000F0;
	private static long policyFrame;
	private static long lastPolicyFrameNanos;
	private static String adaptiveSummary = "none";
	private static int hottestCandidateCount;
	private static int lastHottestCandidateCount;
	private static long lastLoggedMeshUploads;
	private static long meshRebuildsPerSecond;
	private static long queueTimingCounter;
	private static long armorPartTimingCounter;
	private static final java.util.Set<CachedMesh> QUEUED_MESHES =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private static final SharedInstanceStream SHARED_INSTANCES = new SharedInstanceStream();
	private static final ResidentTransformStream RESIDENT_TRANSFORMS = new ResidentTransformStream();
	private static final BonePaletteStream BONE_PALETTES = new BonePaletteStream();
	private static final IndirectCommandStream INDIRECT_COMMANDS = new IndirectCommandStream();

	private OptiminiumPersistentBlockEntityMeshes() {
	}

	public static <E extends BlockEntity> boolean tryRender(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, PoseStack instancePose, int packedLight, int packedOverlay) {
		if (!isPersistenceActive()) {
			return false;
		}
		lastGenericEntity = null;
		lastGenericKey = null;
		// Signs already have a safer audited split path which persists only the static board.
		// Running the whole renderer through generic qualification as well serializes sign NBT
		// and captures text geometry that the split path deliberately leaves to vanilla.
		if (isAuditedSignRenderer(renderer, blockEntity)) return false;
		if (!isAuditedVanillaRenderer(renderer, blockEntity)) return tryRenderGeneric(renderer,
			blockEntity, partialTick, instancePose, packedLight, packedOverlay);
		Object variant = supportedVariant(blockEntity, partialTick);
		if (variant == null) {
			return tryRenderGeneric(renderer, blockEntity, partialTick, instancePose, packedLight, packedOverlay);
		}

		MeshKey key = auditedKey(blockEntity, variant, renderer.getClass());
		if (!recordCandidateAndIsDense(key)) return false;
		if (BLOCK_ENTITY_ANCHORS_THIS_FRAME.add(key)) return false;
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
		}
		long queueStart = queueTimingStart(key);
		mesh.queueStaticResident(blockEntity, instancePose.last().pose(), true, packedLight, packedOverlay);
		recordQueueOverhead(key, queueStart);
		return true;
	}

	private static <E extends BlockEntity> boolean tryRenderGeneric(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, PoseStack instancePose, int packedLight, int packedOverlay) {
		GenericFamilyKey family = genericFamily(renderer, blockEntity);
		genericFamilyCountsThisFrame.merge(family, 1, Integer::sum);
		if (!GenericCandidateGate.shouldBuildKey(genericFamilyCountsLastFrame.getOrDefault(family, 0),
				OptiminiumSettings.isBlockEntityPersistenceAdaptive(),
				adaptiveMinThreshold(), densityThreshold())) {
			genericFamilyRejects++;
			return false;
		}
		GenericFamilyDiscoveryPolicy discovery = GENERIC_FAMILY_DISCOVERY.computeIfAbsent(family,
			ignored -> new GenericFamilyDiscoveryPolicy());
		Object stableKey = discovery.stableKey();
		boolean stableActive = stableKey != null && ACTIVE_KEYS.contains(stableKey);
		GenericQualification stableQualification = stableKey instanceof GenericSourceKey source
			? GENERIC_QUALIFICATIONS.get(source) : null;
		boolean qualifying = stableQualification != null && stableQualification.qualifying;
		if (!discovery.shouldScan(policyFrame, stableActive, qualifying)) {
			genericFamilyDormantSkips++;
			return false;
		}
		boolean sampleKeyCost = genericFamilyCountsLastFrame.getOrDefault(family, 0) < 64
			|| (++genericKeyTimingCounter & 63L) == 0L;
		long keyStart = sampleKeyCost ? System.nanoTime() : 0L;
		GenericSourceKey key = genericKey(renderer, blockEntity);
		if (key == null) return false;
		discovery.observe(policyFrame, key);
		long keyElapsed = keyStart == 0L ? 0L : System.nanoTime() - keyStart;
		if (keyStart != 0L && genericFamilyCountsLastFrame.getOrDefault(family, 0) >= 64) keyElapsed *= 64L;
		lastGenericEntity = blockEntity;
		lastGenericKey = key;
		genericKeyBuilds++;
		if (keyStart != 0L) genericKeyNanos += keyElapsed;
		recordCandidate(key);
		// This cost is discarded on vanilla-only frames, but becomes part of the persistent
		// estimate whenever this frame actually queues an instance.
		if (keyElapsed > 0L) policy(key).recordPersistentOverhead(keyElapsed);
		GenericQualification qualification = GENERIC_QUALIFICATIONS.computeIfAbsent(key,
			ignored -> new GenericQualification());
		qualification.lastSeenFrame = policyFrame;
		long sampleClock = blockEntity.getLevel().getGameTime();
		boolean policySelected = ACTIVE_KEYS.contains(key);
		boolean anchor = policySelected && BLOCK_ENTITY_ANCHORS_THIS_FRAME.add(key);
		if (!policySelected && !qualification.qualifying) return false;
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null && policyFrame < failedUntil) return false;

		CachedMesh mesh = MESHES.get(key);
		if (qualification.qualified && mesh != null) {
			if (policySelected && sampleClock >= qualification.nextValidationFrame) {
				Long fingerprint = captureFingerprint(renderer, blockEntity, partialTick);
				if (fingerprint == null || fingerprint.longValue() != qualification.fingerprint) {
					removeMesh(key);
					qualification.reset(fingerprint, sampleClock);
					return false;
				}
				qualification.nextValidationFrame = sampleClock + GENERIC_VALIDATION_INTERVAL_FRAMES;
			}
			if (!policySelected || anchor) return false;
			queueMesh(key, mesh, blockEntity, instancePose, packedLight, packedOverlay);
			return true;
		}

		if (sampleClock < qualification.nextSampleFrame) return false;
		Long fingerprint = captureFingerprint(renderer, blockEntity, partialTick);
		if (fingerprint == null) {
			FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
			qualification.qualifying = false;
			return false;
		}
		qualification.observe(fingerprint, sampleClock);
		if (!qualification.qualified) return false;

		long buildStart = System.nanoTime();
		mesh = build(key, renderer, blockEntity, partialTick, NORMALIZED_CAPTURE_LIGHT, packedOverlay);
		if (mesh == null) {
			FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
			qualification.qualifying = false;
			return false;
		}
		policy(key).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
		MESHES.put(key, mesh);
		gpuBytes += mesh.bytes;
		recordUpload();
		evictIfNeeded();
		if (!anchor) queueMesh(key, mesh, blockEntity, instancePose, packedLight, packedOverlay);
		return !anchor;
	}

	private static <E extends BlockEntity> Long captureFingerprint(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick) {
		CaptureSource capture = new CaptureSource();
		try {
			renderer.render(blockEntity, partialTick, new PoseStack(), capture, NORMALIZED_CAPTURE_LIGHT, 0);
			return capture.captureFingerprint();
		} catch (RuntimeException exception) {
			capture.close();
			return null;
		}
	}

	private static <E extends BlockEntity> GenericSourceKey genericKey(BlockEntityRenderer<E> renderer,
			E blockEntity) {
		try {
			if (blockEntity.getLevel() == null) return null;
			int stateId = Block.getId(blockEntity.getBlockState());
			long gameTime = blockEntity.getLevel().getGameTime();
			PersistentBlockEntityKeyHolder holder = (PersistentBlockEntityKeyHolder)(Object)blockEntity;
			Object cachedValue = holder.optiminium$getPersistentGenericKeyCache();
			GenericKeyCache cached = cachedValue instanceof GenericKeyCache value ? value : null;
			if (cached != null && cached.key != null && cached.stateId == stateId && cached.renderer == renderer.getClass()) {
				long refreshTicks = ACTIVE_KEYS.contains(cached.key)
					? GENERIC_ACTIVE_KEY_REFRESH_TICKS : GENERIC_INACTIVE_KEY_REFRESH_TICKS;
				if (gameTime - cached.sampledAt < refreshTicks) {
					genericKeyCacheHits++;
					return cached.key;
				}
			}
			genericKeySerializations++;
			int dataHash = blockEntity.saveWithoutMetadata(blockEntity.getLevel().registryAccess()).hashCode();
			GenericSourceKey key = new GenericSourceKey(blockEntity.getType(), stateId,
				renderer.getClass(), dataHash);
			GenericFamilyKey family = cached != null && cached.stateId == stateId
				&& cached.renderer == renderer.getClass() ? cached.family
				: new GenericFamilyKey(blockEntity.getType(), stateId, renderer.getClass());
			holder.optiminium$setPersistentGenericKeyCache(
				new GenericKeyCache(key, family, stateId, renderer.getClass(), gameTime));
			return key;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static <E extends BlockEntity> GenericFamilyKey genericFamily(BlockEntityRenderer<E> renderer,
			E blockEntity) {
		int stateId = Block.getId(blockEntity.getBlockState());
		Class<?> rendererClass = renderer.getClass();
		PersistentBlockEntityKeyHolder holder = (PersistentBlockEntityKeyHolder)(Object)blockEntity;
		Object cachedValue = holder.optiminium$getPersistentGenericKeyCache();
		if (cachedValue instanceof GenericKeyCache cached && cached.stateId == stateId
				&& cached.renderer == rendererClass) return cached.family;
		GenericFamilyKey family = new GenericFamilyKey(blockEntity.getType(), stateId, rendererClass);
		holder.optiminium$setPersistentGenericKeyCache(
			new GenericKeyCache(null, family, stateId, rendererClass, Long.MIN_VALUE));
		return family;
	}

	/** Invalidates serialized generic state as soon as vanilla marks a block entity dirty. */
	public static void invalidateGenericState(BlockEntity blockEntity) {
		((PersistentBlockEntityKeyHolder)(Object)blockEntity).optiminium$setPersistentGenericKeyCache(null);
	}

	public static <E extends BlockEntity> void recordGenericVanilla(BlockEntityRenderer<E> renderer,
			E blockEntity, long elapsedNanos) {
		if (elapsedNanos <= 0L || !isPersistenceActive()) return;
		if (isAuditedSignRenderer(renderer, blockEntity)) return;
		if (isAuditedVanillaRenderer(renderer, blockEntity)
				&& supportedVariant(blockEntity, 1.0F) != null) return;
		// tryRenderGeneric and this callback surround the same vanilla renderer invocation.
		// Reuse its key instead of serializing the block entity a second time.
		GenericSourceKey key = lastGenericEntity == blockEntity ? lastGenericKey : null;
		if (key != null) policy(key).recordVanilla(elapsedNanos * pendingGenericVanillaWeight,
			pendingGenericVanillaWeight);
		pendingGenericVanillaWeight = 1;
		lastGenericEntity = null;
		lastGenericKey = null;
	}

	/** True only when the preceding generic qualification pass produced a sample worth timing. */
	public static boolean hasPendingGenericVanillaSample(BlockEntity blockEntity) {
		if (lastGenericEntity != blockEntity || lastGenericKey == null) return false;
		int count = candidateCountsLastFrame.getOrDefault(lastGenericKey, 0);
		int stride = PersistenceTimingSampler.strideForCount(count);
		if (!PersistenceTimingSampler.shouldSample(++genericVanillaTimingCounter, stride)) {
			lastGenericEntity = null;
			lastGenericKey = null;
			pendingGenericVanillaWeight = 1;
			return false;
		}
		pendingGenericVanillaWeight = stride;
		return true;
	}

	private static void queueMesh(Object key, CachedMesh mesh, Object transformOwner,
			PoseStack pose, int light, int overlay) {
		long queueStart = queueTimingStart(key);
		mesh.queueStaticResident(transformOwner, pose.last().pose(), true, light, overlay);
		recordQueueOverhead(key, queueStart);
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
		}
		long queueStart = queueTimingStart(key);
		instancePose.pushPose();
		instancePose.mulPose(Axis.YP.rotationDegrees(-yaw));
		mesh.queueResident(armorStand, instancePose.last().pose(), true, packedLight, 0);
		instancePose.popPose();
		recordQueueOverhead(key, queueStart);
		return true;
	}

	public static void recordArmorStandVanilla(ArmorStand armorStand, long elapsedNanos) {
		if (isPersistenceActive() && OptiminiumSettings.isArmorStandPersistenceEnabled()
				&& isStableArmorStand(armorStand)) {
			policy(new ArmorStandKey(armorStand)).recordVanilla(elapsedNanos, 1);
		}
	}

	/** Begins an exact ModelPart-based mob pass. Unsupported output is forwarded unchanged. */
	public static MultiBufferSource beginMob(EntityRenderer<?> renderer, Mob mob, MultiBufferSource delegate) {
		if (!shouldEvaluateMob(mob)) return delegate;
		MobGroupKey key = new MobGroupKey(mob.getType(), renderer.getClass(), mob.isBaby(),
			mobEquipmentSignature(mob), mobTexture(renderer, mob));
		return beginExactModel(mob.getUUID(), key, delegate, false);
	}

	/**
	 * Begins exact persistence for the wooden armor-stand model only. Equipment and feature layers
	 * are deliberately excluded from the key and continue through vanilla unchanged.
	 */
	public static MultiBufferSource beginArmorStandModel(EntityRenderer<?> renderer, ArmorStand stand,
			MultiBufferSource delegate) {
		if (!persistenceConfiguredThisFrame || !OptiminiumSettings.isArmorStandPersistenceEnabled()
				|| !isStableArmorStandModel(stand)) return delegate;
		ArmorStandBoneKey key = new ArmorStandBoneKey(stand.getType(), renderer.getClass(), stand.isSmall(),
			armorStandTexture(renderer, stand));
		armorStandCountsThisFrame.merge(key, 1, Integer::sum);
		if (armorStandCountsLastFrame.getOrDefault(key, 0) < adaptiveMinThreshold()) return delegate;
		MobRenderContext context = new MobRenderContext(key, stand.getUUID(), delegate, false);
		context.partPersistence = true;
		context.armorStandFamily = ARMOR_STAND_FAMILIES.computeIfAbsent(key, ArmorStandFamilyState::new);
		MOB_RENDER_CONTEXT.set(context);
		return context.buffers;
	}

	private static MultiBufferSource beginExactModel(UUID entityId, ExactGroupKey key,
			MultiBufferSource delegate, boolean candidateAlreadyRecorded) {
		if (!candidateAlreadyRecorded) recordCandidate(key);
		MobTopologyPolicy qualification = MOB_QUALIFICATIONS.computeIfAbsent(key, ignored -> new MobTopologyPolicy());
		qualification.lastSeenFrame = policyFrame;
		boolean selected = qualification.qualified() && ACTIVE_KEYS.contains(key);
		// Keep one exact vanilla base model in the normal BufferSource. Besides being a visual
		// reference sample, it lets the atlas draw reuse the already-active texture/render state.
		if (selected && MOB_ANCHORS_THIS_FRAME.add(key)) selected = false;
		if (!selected && qualification.qualified() && !qualification.shouldValidate(policyFrame)) {
			MOB_FAST_VANILLA_KEY.set(key);
			return delegate;
		}
		MobRenderContext context = new MobRenderContext(key, entityId, delegate, selected);
		MOB_RENDER_CONTEXT.set(context);
		return context.buffers;
	}

	public static boolean hasArmorStandModelPass() {
		ExactGroupKey key = MOB_FAST_VANILLA_KEY.get();
		return MOB_RENDER_CONTEXT.get() != null || key instanceof ArmorStandBoneKey;
	}

	public static boolean shouldEvaluateMob(Mob mob) {
		return isMobPersistenceActive() && !mob.isInvisible()
			&& !Minecraft.getInstance().shouldEntityAppearGlowing(mob);
	}

	/** Completes the pass and records the full renderer cost, including animation and vanilla feature layers. */
	public static void endMob(Mob mob, long elapsedNanos) {
		endExactModel(elapsedNanos);
	}

	public static void endArmorStandModel(long elapsedNanos) {
		endExactModel(elapsedNanos);
	}

	private static void endExactModel(long elapsedNanos) {
		MobRenderContext context = MOB_RENDER_CONTEXT.get();
		MOB_RENDER_CONTEXT.remove();
		if (context == null) {
			ExactGroupKey key = MOB_FAST_VANILLA_KEY.get();
			MOB_FAST_VANILLA_KEY.remove();
			if (key != null) policy(key).recordVanilla(elapsedNanos, 1);
			return;
		}
		if (context.partPersistence) return;
		MobTopologyPolicy qualification = MOB_QUALIFICATIONS.get(context.key);
		if (qualification != null && context.compatibleParts > 0) {
			boolean wasQualified = qualification.qualified();
			qualification.observe(context.topologyHash, context.entityId, policyFrame);
			if (wasQualified && !qualification.qualified()) {
				removeMeshesForPolicy(context.key);
				mobFallbacks++;
				MOB_FALLBACK_REASONS.put(context.key, "topology_changed");
			}
		}
		if (context.suppressedParts > 0 && context.atlasCompatible
				&& context.poseCount == context.compatibleParts) {
			MobAtlasKey atlasKey = new MobAtlasKey(context.key);
			CachedMesh mesh = MESHES.get(atlasKey);
			if (mesh == null && context.capture != null) {
				long buildStart = System.nanoTime();
				mesh = context.capture.upload(atlasKey, context.key, context.compatibleParts);
				context.capture = null;
				if (mesh != null) {
					policy(context.key).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
					MESHES.put(atlasKey, mesh);
					gpuBytes += mesh.bytes;
					recordUpload();
					evictIfNeeded();
				}
			}
			if (mesh != null && mesh.boneCount == context.poseCount) {
				mesh.queueBone(context.packedLight, context.packedOverlay, context.paletteOffset);
				mobBoneUploads += context.poseCount;
			} else {
				context.suppressedParts = 0;
				mobFallbacks++;
				MOB_FALLBACK_REASONS.put(context.key, "bone_palette_mismatch");
			}
		}
		if (context.capture != null) context.capture.close();
		if (context.suppressedParts > 0) {
			MOB_FALLBACK_REASONS.remove(context.key);
			policy(context.key).recordPersistent(elapsedNanos, 1);
		} else {
			policy(context.key).recordVanilla(elapsedNanos, 1);
		}
	}

	/** Called from ModelPart.compile after vanilla has applied the exact animated cumulative pose. */
	public static boolean tryRenderMobPart(ModelPart part, PoseStack.Pose pose, VertexConsumer consumer,
			int packedLight, int packedOverlay, int color) {
		MobRenderContext context = MOB_RENDER_CONTEXT.get();
		if (context == null) return false;
		RenderType renderType = context.renderTypes.get(consumer);
		if (context.partPersistence) {
			return tryRenderArmorStandPart(context, part, pose, renderType, packedLight, packedOverlay, color);
		}
		if (renderType == null || renderType.sortOnUpload()
				|| renderType.format() != DefaultVertexFormat.NEW_ENTITY
				|| !isCompatiblePersistentRenderType(renderType)
				|| renderType != context.primaryRenderType || color != -1) {
			context.fallbackReason = renderType == null ? "untracked_render_type"
				: renderType.sortOnUpload() ? "sorted_translucent"
				: renderType.format() != DefaultVertexFormat.NEW_ENTITY ? "vertex_format"
				: !isCompatiblePersistentRenderType(renderType) ? "shader_incompatible"
				: renderType != context.primaryRenderType ? "feature_render_type" : "vertex_tint";
			MOB_FALLBACK_REASONS.put(context.key, context.fallbackReason);
			mobVanillaParts++;
			// Armor/equipment feature layers remain vanilla and must not fragment the
			// wooden base-model topology used by ArmorStandBoneKey qualification.
			if (!(context.key instanceof ArmorStandBoneKey)) context.mixTopology(part, renderType, false);
			return false;
		}
		context.compatibleParts++;
		context.mixTopology(part, renderType, true);
		if (!context.persist) {
			mobVanillaParts++;
			return false;
		}

		MobAtlasKey atlasKey = new MobAtlasKey(context.key);
		CachedMesh mesh = MESHES.get(atlasKey);
		int boneIndex = context.poseCount++;
		if (context.paletteOffset < 0) context.paletteOffset = BONE_PALETTES.matrixCount();
		BONE_PALETTES.append(pose.pose());
		context.packedLight = packedLight;
		context.packedOverlay = packedOverlay;
		if (mesh == null) {
			if (context.capture == null) context.capture = new CaptureSource();
			try {
				VertexConsumer output = context.capture.getBuffer(renderType);
				PoseStack identity = new PoseStack();
				for (ModelPart.Cube cube : ((ModelPartAccessor)(Object)part).optiminium$getCubes()) {
					// UV1 is not consumed as overlay by the persistent shader; it carries an exact bone index.
					cube.compile(identity.last(), output, NORMALIZED_CAPTURE_LIGHT, boneIndex, -1);
				}
			} catch (RuntimeException exception) {
				context.atlasCompatible = false;
				context.fallbackReason = "capture_incompatible";
				MOB_FALLBACK_REASONS.put(context.key, context.fallbackReason);
				mobFallbacks++;
				return false;
			}
		} else if (mesh.boneCount <= boneIndex) {
			context.atlasCompatible = false;
			context.fallbackReason = "bone_count_changed";
			MOB_FALLBACK_REASONS.put(context.key, context.fallbackReason);
			mobFallbacks++;
			return false;
		} else {
			recordHit(mesh.vertices);
		}
		context.suppressedParts++;
		mobPartsSuppressed++;
		return true;
	}

	private static boolean tryRenderArmorStandPart(MobRenderContext context, ModelPart part,
			PoseStack.Pose pose, RenderType renderType, int packedLight, int packedOverlay, int color) {
		if (renderType == null || renderType.sortOnUpload()
				|| renderType.format() != DefaultVertexFormat.NEW_ENTITY
				|| !isCompatiblePersistentRenderType(renderType)) {
			mobVanillaParts++;
			return false;
		}
		ArmorStandPartKey key = context.armorStandFamily.partKey(part, renderType);
		ArmorStandLayerKey policyKey = key.policyKey;
		recordCandidate(policyKey);
		int timingStride = PersistenceTimingSampler.queueStrideForCount(
			candidateCountsLastFrame.getOrDefault(policyKey, 0));
		boolean sampleTiming = PersistenceTimingSampler.shouldSample(++armorPartTimingCounter, timingStride);
		long start = sampleTiming ? System.nanoTime() : 0L;
		if (!ACTIVE_KEYS.contains(policyKey)) {
			if (sampleTiming) PART_VANILLA_TIMING.set(new PartVanillaTiming(policyKey, start, timingStride));
			mobVanillaParts++;
			return false;
		}
		CachedMesh mesh = MESHES.get(key);
		if (mesh == null) {
			CaptureSource capture = new CaptureSource();
			try {
				VertexConsumer output = capture.getBuffer(renderType);
				PoseStack identity = new PoseStack();
				for (ModelPart.Cube cube : ((ModelPartAccessor)(Object)part).optiminium$getCubes()) {
					cube.compile(identity.last(), output, NORMALIZED_CAPTURE_LIGHT, 0, -1);
				}
				long buildStart = System.nanoTime();
				mesh = capture.upload(key, policyKey, 1);
				if (mesh != null) {
					policy(policyKey).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
					MESHES.put(key, mesh);
					gpuBytes += mesh.bytes;
					recordUpload();
					evictIfNeeded();
				}
			} catch (RuntimeException exception) {
				capture.close();
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				mobFallbacks++;
				return false;
			}
		}
		if (mesh == null) return false;
		int paletteOffset = BONE_PALETTES.matrixCount();
		BONE_PALETTES.append(pose.pose());
		mesh.queueBone(packedLight, packedOverlay, color, paletteOffset);
		if (sampleTiming) policy(policyKey).recordPersistent(
			(System.nanoTime() - start) * timingStride, timingStride);
		mobPartsSuppressed++;
		mobBoneUploads++;
		return true;
	}

	/** Completes per-part vanilla timing after ModelPart.compile returns. */
	public static void finishMobPart() {
		PartVanillaTiming timing = PART_VANILLA_TIMING.get();
		if (timing == null) return;
		PART_VANILLA_TIMING.remove();
		policy(timing.key).recordVanilla(
			(System.nanoTime() - timing.startedNanos) * timing.stride, timing.stride);
	}

	private static boolean isStableArmorStand(ArmorStand stand) {
		return stand.isAlive() && !stand.isMarker() && !stand.isInvisible()
			&& !stand.isCustomNameVisible() && !stand.displayFireAnimation()
			&& !Minecraft.getInstance().shouldEntityAppearGlowing(stand)
			&& stand.level().getGameTime() - stand.lastHit >= 5L;
	}

	private static boolean isStableArmorStandModel(ArmorStand stand) {
		return stand.isAlive() && !stand.isMarker() && !stand.isInvisible()
			&& !Minecraft.getInstance().shouldEntityAppearGlowing(stand);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static ResourceLocation armorStandTexture(EntityRenderer<?> renderer, ArmorStand stand) {
		try {
			return ((EntityRenderer)renderer).getTextureLocation(stand);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static ResourceLocation mobTexture(EntityRenderer<?> renderer, Mob entity) {
		try {
			return ((EntityRenderer)renderer).getTextureLocation(entity);
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private static int mobEquipmentSignature(Mob mob) {
		int hash = 1;
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			hash = 31 * hash + ItemStack.hashItemAndComponents(mob.getItemBySlot(slot));
		}
		return hash;
	}

	private static boolean isCompatiblePersistentRenderType(RenderType renderType) {
		return PersistentRenderTypeCompatibility.isCompatible(renderType.toString());
	}

	private static boolean isMobPersistenceActive() {
		return mobPersistenceActiveThisFrame;
	}

	public static void onFrameStart() {
		RenderSystem.assertOnRenderThread();
		persistenceConfiguredThisFrame = OptiminiumSettings.isEnabled()
			&& OptiminiumSettings.isBlockEntityRenderCache()
			&& OptiminiumSettings.isBlockEntityPersistenceEnabled()
			&& !Boolean.getBoolean("optiminium.persistentMeshBenchmarkMobs")
			&& OptiminiumPersistentMeshShader.get() != null;
		persistenceActiveThisFrame = PERSISTENCE_DISCOVERY_COOLDOWN.beginFrame(
			policyFrame, persistenceConfiguredThisFrame);
		mobPersistenceActiveThisFrame = OptiminiumSettings.isEnabled()
			&& OptiminiumSettings.isMobPersistenceEnabled()
			&& OptiminiumPersistentMeshShader.get() != null;
		if (atlasActiveStatePendingUnbind) finishAtlasStateAfterVanillaDraw();
		if (!persistenceConfiguredThisFrame && !isMobPersistenceActive()
				&& (!MESHES.isEmpty() || !ADAPTIVE_POLICIES.isEmpty() || gpuBytes > 0L)) clear();
		if (!isMobPersistenceActive() && hasMobQualificationState()) clearMobState();
		if (!persistenceConfiguredThisFrame && isMobPersistenceActive() && hasBlockEntityState()) clearBlockEntityState();
		evictIfNeeded();
		// A normal frame flushes at the end of the block-entity pass. Discard stale instances if
		// rendering aborted before that hook (world switch, exception, or minimized-frame edge case).
		if (!QUEUED_MESHES.isEmpty()) {
			for (CachedMesh mesh : QUEUED_MESHES) mesh.instances.reset();
			QUEUED_MESHES.clear();
		}
		BONE_PALETTES.reset();
		RESIDENT_TRANSFORMS.expire(policyFrame);
		MOB_ANCHORS_THIS_FRAME.clear();
		BLOCK_ENTITY_ANCHORS_THIS_FRAME.clear();
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
		long frameNow = System.nanoTime();
		long frameNanos = lastPolicyFrameNanos == 0L ? 0L : frameNow - lastPolicyFrameNanos;
		lastPolicyFrameNanos = frameNow;
		long gpuNanos = OptiminiumGpuTimer.getLatestGpuNanos();
		for (AdaptivePersistencePolicy value : ADAPTIVE_POLICIES.values()) {
			value.recordFrameSafety(gpuNanos, frameNanos, value.active() || value.trial());
		}
		applyDormantGenericFamilyCounts();
		policyFrame++;
		updateAdaptivePolicies(candidateCountsThisFrame);
		updatePersistenceDiscoveryCooldown();
		candidateCountsLastFrame = candidateCountsThisFrame;
		candidateCountsThisFrame = new HashMap<>(Math.max(16, candidateCountsLastFrame.size() * 2));
		genericFamilyCountsLastFrame = genericFamilyCountsThisFrame;
		genericFamilyCountsThisFrame = new HashMap<>(Math.max(16, genericFamilyCountsLastFrame.size() * 2));
		armorStandCountsLastFrame = armorStandCountsThisFrame;
		armorStandCountsThisFrame = new HashMap<>(Math.max(4, armorStandCountsLastFrame.size() * 2));
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

	private static void applyDormantGenericFamilyCounts() {
		for (Map.Entry<GenericFamilyKey, GenericFamilyDiscoveryPolicy> entry : GENERIC_FAMILY_DISCOVERY.entrySet()) {
			GenericFamilyDiscoveryPolicy discovery = entry.getValue();
			int familyCount = genericFamilyCountsThisFrame.getOrDefault(entry.getKey(), 0);
			if (discovery.scanned(policyFrame)) {
				discovery.finishFrame(policyFrame);
			} else if (familyCount > 0 && discovery.stableKey() != null) {
				candidateCountsThisFrame.merge(discovery.stableKey(), familyCount, Integer::sum);
			}
		}
		GENERIC_FAMILY_DISCOVERY.entrySet().removeIf(entry ->
			policyFrame - entry.getValue().lastSeenFrame() > AdaptivePersistencePolicy.EXPIRE_AFTER_FRAMES);
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

	private static MeshKey auditedKey(BlockEntity blockEntity, Object variant, Class<?> rendererClass) {
		int stateId = Block.getId(blockEntity.getBlockState());
		PersistentBlockEntityKeyHolder holder = (PersistentBlockEntityKeyHolder)(Object)blockEntity;
		Object cached = holder.optiminium$getPersistentMeshKeyCache();
		if (cached instanceof AuditedKeyCache entry && entry.stateId == stateId
				&& entry.rendererClass == rendererClass && entry.variant.equals(variant)) {
			return entry.key;
		}
		MeshKey key = new MeshKey(blockEntity.getType(), stateId, variant, rendererClass);
		holder.optiminium$setPersistentMeshKeyCache(new AuditedKeyCache(stateId, variant, rendererClass, key));
		return key;
	}

	private record AuditedKeyCache(int stateId, Object variant, Class<?> rendererClass, MeshKey key) {
	}

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
			Block.getId(blockEntity.getBlockState()), ChestBottomVariant.INSTANCE, renderer.getClass()), blockEntity);
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
			Block.getId(blockEntity.getBlockState()), SignBoardVariant.INSTANCE, renderer.getClass()), blockEntity);
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
		return tryRenderModelPart(context.key, context.blockEntity, context.renderType, root,
			instancePose, packedLight, packedOverlay);
	}

	/** Called only for ordinal 2 (the bottom) of ChestRenderer's three ModelPart renders. */
	public static boolean tryRenderChestBottom(ModelPart bottom, PoseStack instancePose,
			VertexConsumer vanillaConsumer, int packedLight, int packedOverlay) {
		ChestPartContext context = CHEST_PART_CONTEXT.get();
		if (context == null || context.renderType == null) return false;
		return tryRenderModelPart(context.key, context.blockEntity, context.renderType, bottom,
			instancePose, packedLight, packedOverlay);
	}

	private static boolean tryRenderModelPart(MeshKey key, BlockEntity blockEntity,
			RenderType renderType, ModelPart part,
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
		}
		long queueStart = queueTimingStart(key);
		mesh.queueStaticResident(blockEntity, instancePose.last().pose(), true, packedLight, packedOverlay);
		recordQueueOverhead(key, queueStart);
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

	private static void recordQueuedMetrics(CachedMesh mesh, int count) {
		if (!metricsEnabled || count <= 0) return;
		if (!(mesh.policyKey instanceof MobGroupKey)) {
			hits.add(count);
			hitsThisFrame += count;
			verticesAvoidedThisFrame += mesh.vertices * count;
		}
		instancesDrawn.add(count);
		drawnThisFrame += count;
	}

	private static boolean recordCandidateAndIsDense(Object key) {
		recordCandidate(key);
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null) {
			if (policyFrame < failedUntil) return false;
			FAILED_KEYS.remove(key);
		}
		return ACTIVE_KEYS.contains(key);
	}

	private static void recordCandidate(Object key) {
		int count = candidateCountsThisFrame.merge(key, 1, Integer::sum);
		if (count > hottestCandidateCount) hottestCandidateCount = count;
	}

	/** Avoids two high-resolution timer calls per instance in moderate and large groups. */
	private static long queueTimingStart(Object key) {
		int candidates = candidateCountsLastFrame.getOrDefault(key, 0);
		int stride = PersistenceTimingSampler.queueStrideForCount(candidates);
		return PersistenceTimingSampler.shouldSample(++queueTimingCounter, stride) ? System.nanoTime() : 0L;
	}

	private static void recordQueueOverhead(Object key, long start) {
		if (start == 0L) return;
		long elapsed = System.nanoTime() - start;
		int stride = PersistenceTimingSampler.queueStrideForCount(
			candidateCountsLastFrame.getOrDefault(key, 0));
		elapsed *= stride;
		policy(key).recordPersistentOverhead(elapsed);
	}

	/** Flushes all queued visible instances once, after vanilla has enumerated block entities. */
	public static void flushQueued() {
		flushQueued(false, null);
	}

	/** Reuses a vanilla RenderType's active texture/state when every queued atlas layer is compatible. */
	public static void flushQueuedInActiveState(RenderType activeRenderType) {
		if (QUEUED_MESHES.isEmpty()) return;
		for (CachedMesh mesh : QUEUED_MESHES) {
			for (CachedLayer layer : mesh.layers) {
				if (layer.renderType != activeRenderType) return;
			}
		}
		flushQueued(true, activeRenderType);
	}

	public static void finishAtlasStateAfterVanillaDraw() {
		if (!atlasActiveStatePendingUnbind) return;
		atlasActiveStatePendingUnbind = false;
		bindAtlasVertexArray(0);
	}

	/** Keeps vanilla's immediate-buffer VAO cache coherent with raw atlas VAO transitions. */
	private static void bindAtlasVertexArray(int vao) {
		BufferUploader.invalidate();
		GlStateManager._glBindVertexArray(vao);
	}

	private static void flushQueued(boolean stateAlreadyActive, RenderType activeRenderType) {
		if (QUEUED_MESHES.isEmpty()) return;
		RenderSystem.assertOnRenderThread();
		long start = System.nanoTime();
		Map<RenderType, List<QueuedLayer>> groups = new LinkedHashMap<>();
		int totalInstances = 0;
		boolean sharedInstances = supportsBaseInstance();
		if (sharedInstances) SHARED_INSTANCES.begin();
		for (CachedMesh mesh : QUEUED_MESHES) {
			if (mesh.instances.count == 0) continue;
			if (sharedInstances) {
				mesh.sharedBaseInstance = SHARED_INSTANCES.append(mesh.instances);
			} else {
				mesh.instances.upload();
			}
			totalInstances += mesh.instances.count;
			for (CachedLayer layer : mesh.layers) {
				groups.computeIfAbsent(layer.renderType, ignored -> new ArrayList<>())
					.add(new QueuedLayer(mesh, layer));
			}
		}
		if (sharedInstances && totalInstances > 0) SHARED_INSTANCES.upload();
		if (RESIDENT_TRANSFORMS.hasDirtyData()) RESIDENT_TRANSFORMS.upload();
		if (!BONE_PALETTES.isEmpty()) BONE_PALETTES.upload();
		for (Map.Entry<RenderType, List<QueuedLayer>> entry : groups.entrySet()) {
			RenderType renderType = entry.getKey();
			List<QueuedLayer> draws = entry.getValue();
			if (draws.isEmpty()) continue;
			if (!stateAlreadyActive) {
				renderType.setupRenderState();
				atlasStateSetups++;
			} else if (renderType != activeRenderType) {
				continue;
			}
			var shader = OptiminiumPersistentMeshShader.get();
			if (shader != null) {
				shader.setDefaultUniforms(draws.get(0).layer.slice.mode, RenderSystem.getModelViewMatrix(),
					RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
				shader.apply();
				RESIDENT_TRANSFORMS.bind(shader);
				BONE_PALETTES.bind(shader);
				Map<AtlasPage, List<QueuedLayer>> pageGroups = new LinkedHashMap<>();
				for (QueuedLayer draw : draws) {
					pageGroups.computeIfAbsent(draw.layer.slice.page, ignored -> new ArrayList<>()).add(draw);
				}
				for (Map.Entry<AtlasPage, List<QueuedLayer>> pageEntry : pageGroups.entrySet()) {
					List<QueuedLayer> pageDraws = pageEntry.getValue();
					if (PersistentDrawBatchPlanner.choose(sharedInstances, supportsMultiDrawIndirect(),
							pageDraws.size()) == PersistentDrawBatchPlanner.Mode.INDIRECT) {
						pageEntry.getKey().bind(SHARED_INSTANCES.currentGpuBuffer());
						INDIRECT_COMMANDS.draw(pageDraws);
						atlasIndirectDraws++;
						if (metricsEnabled) drawCallsThisFrame++;
					} else {
						for (QueuedLayer draw : pageDraws) {
							draw.layer.drawInstancedRaw(draw.mesh.instances, sharedInstances, draw.mesh.sharedBaseInstance);
							atlasFallbackDraws++;
							if (metricsEnabled) drawCallsThisFrame++;
						}
					}
				}
				shader.clear();
			}
			if (!stateAlreadyActive) {
				renderType.clearRenderState();
				bindAtlasVertexArray(0);
			} else {
				atlasActiveStatePendingUnbind = true;
			}
		}
		long elapsed = System.nanoTime() - start;
		Map<Object, Long> mobFlushShares = new HashMap<>();
		for (CachedMesh mesh : QUEUED_MESHES) {
			int count = mesh.instances.count;
			recordQueuedMetrics(mesh, count);
			if (count > 0 && totalInstances > 0) {
				long share = Math.max(1L, elapsed * count / totalInstances);
				if (mesh.policyKey instanceof MobGroupKey) {
					mobFlushShares.merge(mesh.policyKey, share, Long::sum);
				} else {
					policy(mesh.policyKey).recordPersistent(share, count);
				}
			}
			mesh.instances.reset();
		}
		mobFlushShares.forEach((key, nanos) -> policy(key).recordPersistentOverhead(nanos));
		QUEUED_MESHES.clear();
		BONE_PALETTES.reset();
	}

	private static boolean supportsBaseInstance() {
		return GL.getCapabilities() != null
			&& (GL.getCapabilities().OpenGL42 || GL.getCapabilities().GL_ARB_base_instance);
	}

	private static boolean supportsMultiDrawIndirect() {
		return GL.getCapabilities() != null
			&& (GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_ARB_multi_draw_indirect);
	}

	private record QueuedLayer(CachedMesh mesh, CachedLayer layer) {
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
		final BlockEntity blockEntity;
		RenderType renderType;
		ChestPartContext(MeshKey key, BlockEntity blockEntity) {
			this.key = key;
			this.blockEntity = blockEntity;
		}
	}

	private static final class SignPartContext {
		final MeshKey key;
		final BlockEntity blockEntity;
		RenderType renderType;
		SignPartContext(MeshKey key, BlockEntity blockEntity) {
			this.key = key;
			this.blockEntity = blockEntity;
		}
	}

	private static <E extends BlockEntity> CachedMesh build(Object key, BlockEntityRenderer<E> renderer, E blockEntity,
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
		for (AtlasPage page : List.copyOf(ATLAS_PAGES)) page.close();
		SHARED_INSTANCES.close();
		RESIDENT_TRANSFORMS.close();
		BONE_PALETTES.close();
		INDIRECT_COMMANDS.close();
		MESHES.clear();
		QUEUED_MESHES.clear();
		ADAPTIVE_POLICIES.clear();
		ACTIVE_KEYS.clear();
		FAILED_KEYS.clear();
		GENERIC_QUALIFICATIONS.clear();
		MOB_QUALIFICATIONS.clear();
		MOB_FALLBACK_REASONS.clear();
		MOB_ANCHORS_THIS_FRAME.clear();
		BLOCK_ENTITY_ANCHORS_THIS_FRAME.clear();
		MOB_RENDER_CONTEXT.remove();
		MOB_FAST_VANILLA_KEY.remove();
		genericFamilyCountsThisFrame.clear();
		genericFamilyCountsLastFrame = Map.of();
		armorStandCountsThisFrame.clear();
		armorStandCountsLastFrame = Map.of();
		GENERIC_FAMILY_DISCOVERY.clear();
		ARMOR_STAND_FAMILIES.clear();
		lastGenericEntity = null;
		lastGenericKey = null;
		pendingGenericVanillaWeight = 1;
		genericFamilyRejects = 0L;
		genericFamilyDormantSkips = 0L;
		genericKeyBuilds = 0L;
		genericKeyNanos = 0L;
		genericKeyCacheHits = 0L;
		genericKeySerializations = 0L;
		mobPartsSuppressed = 0L;
		mobVanillaParts = 0L;
		mobQualifiedGroups = 0L;
		mobFallbacks = 0L;
		mobBoneUploads = 0L;
		residentTransformUploads = 0L;
		residentTransformUploadBytes = 0L;
		mobSummary = "none";
		armorStandSummary = "none";
		atlasStateSetups = 0L;
		atlasFallbackDraws = 0L;
		atlasIndirectDraws = 0L;
		atlasActiveStatePendingUnbind = false;
		persistenceConfiguredThisFrame = false;
		persistenceActiveThisFrame = false;
		mobPersistenceActiveThisFrame = false;
		PERSISTENCE_DISCOVERY_COOLDOWN.reset();
		lastPolicyFrameNanos = 0L;
		adaptiveSummary = "none";
		gpuBytes = 0L;
	}

	private static void evictIfNeeded() {
		while (PersistentGpuBudget.shouldEvict(MESHES.size(),
				OptiminiumSettings.getBlockEntityPersistenceMaxMeshes(), gpuBytes)) {
			Map.Entry<Object, CachedMesh> eldest = MESHES.entrySet().iterator().next();
			MESHES.remove(eldest.getKey());
			gpuBytes -= eldest.getValue().bytes;
			eldest.getValue().close();
		}
	}

	private static void clearMobState() {
		for (ExactGroupKey key : List.copyOf(MOB_QUALIFICATIONS.keySet())) {
			if (key instanceof MobGroupKey) removeMeshesForPolicy(key);
		}
		MOB_QUALIFICATIONS.keySet().removeIf(key -> key instanceof MobGroupKey);
		MOB_FALLBACK_REASONS.keySet().removeIf(key -> key instanceof MobGroupKey);
		MOB_ANCHORS_THIS_FRAME.removeIf(key -> key instanceof MobGroupKey);
		ADAPTIVE_POLICIES.keySet().removeIf(key -> key instanceof MobGroupKey);
		ACTIVE_KEYS.removeIf(key -> key instanceof MobGroupKey);
		MOB_RENDER_CONTEXT.remove();
		MOB_FAST_VANILLA_KEY.remove();
		PART_VANILLA_TIMING.remove();
		mobSummary = "none";
	}

	private static boolean hasMobQualificationState() {
		return MOB_QUALIFICATIONS.keySet().stream().anyMatch(key -> key instanceof MobGroupKey);
	}

	private static boolean hasBlockEntityState() {
		return !GENERIC_QUALIFICATIONS.isEmpty()
			|| ADAPTIVE_POLICIES.keySet().stream().anyMatch(key -> !(key instanceof MobGroupKey))
			|| MESHES.values().stream().anyMatch(mesh -> !(mesh.policyKey instanceof MobGroupKey));
	}

	private static void clearBlockEntityState() {
		for (Object key : List.copyOf(ADAPTIVE_POLICIES.keySet())) {
			if (key instanceof MobGroupKey) continue;
			removeMeshesForPolicy(key);
			ADAPTIVE_POLICIES.remove(key);
			ACTIVE_KEYS.remove(key);
		}
		GENERIC_QUALIFICATIONS.clear();
		GENERIC_FAMILY_DISCOVERY.clear();
		for (Object key : List.copyOf(ADAPTIVE_POLICIES.keySet())) {
			if (key instanceof ArmorStandLayerKey) removeMeshesForPolicy(key);
		}
		ADAPTIVE_POLICIES.keySet().removeIf(key -> key instanceof ArmorStandLayerKey);
		ACTIVE_KEYS.removeIf(key -> key instanceof ArmorStandLayerKey);
		ARMOR_STAND_FAMILIES.clear();
		armorStandCountsThisFrame.clear();
		armorStandCountsLastFrame = Map.of();
		FAILED_KEYS.clear();
		RESIDENT_TRANSFORMS.close();
		candidateCountsThisFrame.keySet().removeIf(key -> !(key instanceof MobGroupKey));
		Map<Object, Integer> retained = new HashMap<>();
		candidateCountsLastFrame.forEach((key, count) -> {
			if (key instanceof MobGroupKey) retained.put(key, count);
		});
		candidateCountsLastFrame = retained;
	}

	private static void removeMesh(Object key) {
		CachedMesh removed = MESHES.remove(key);
		if (removed == null) return;
		QUEUED_MESHES.remove(removed);
		gpuBytes -= removed.bytes;
		removed.close();
	}

	private static boolean isPersistenceActive() {
		return persistenceActiveThisFrame;
	}

	private static int densityThreshold() {
		return Math.max(1, Integer.getInteger("optiminium.persistentMeshMinInstances",
			OptiminiumSettings.getBlockEntityPersistenceMinInstances()));
	}

	private static int adaptiveMinThreshold() {
		return Math.max(4, Math.min(128, Integer.getInteger("optiminium.persistentMeshAdaptiveMinInstances",
			OptiminiumSettings.getBlockEntityPersistenceAdaptiveMinInstances())));
	}

	private static int mobAdaptiveMinThreshold() {
		return Math.max(2, Math.min(128, Integer.getInteger("optiminium.persistentMobAdaptiveMinInstances",
			OptiminiumSettings.getMobPersistenceAdaptiveMinInstances())));
	}

	private static AdaptivePersistencePolicy policy(Object key) {
		return ADAPTIVE_POLICIES.computeIfAbsent(key, ignored -> new AdaptivePersistencePolicy());
	}

	private static void updateAdaptivePolicies(Map<Object, Integer> counts) {
		ACTIVE_KEYS.clear();
		boolean adaptive = OptiminiumSettings.isBlockEntityPersistenceAdaptive();
		int adaptiveMin = adaptiveMinThreshold();
		int guaranteed = densityThreshold();
		for (Map.Entry<Object, Integer> entry : counts.entrySet()) {
			AdaptivePersistencePolicy value = policy(entry.getKey());
			boolean mob = entry.getKey() instanceof MobGroupKey;
			int effectiveMin = mob ? mobAdaptiveMinThreshold() : adaptiveMin;
			int effectiveGuaranteed = mob ? Integer.MAX_VALUE : guaranteed;
			if (value.beginFrame(policyFrame, entry.getValue(), mob || adaptive, effectiveMin, effectiveGuaranteed)) {
				ACTIVE_KEYS.add(entry.getKey());
			}
		}
		ADAPTIVE_POLICIES.entrySet().removeIf(entry -> {
			if (!entry.getValue().expired(policyFrame)) return false;
			removeMeshesForPolicy(entry.getKey());
			ACTIVE_KEYS.remove(entry.getKey());
			return true;
		});
		MOB_QUALIFICATIONS.entrySet().removeIf(entry -> {
			if (policyFrame - entry.getValue().lastSeenFrame <= AdaptivePersistencePolicy.EXPIRE_AFTER_FRAMES) return false;
			removeMeshesForPolicy(entry.getKey());
			return true;
		});
		FAILED_KEYS.entrySet().removeIf(entry -> policyFrame >= entry.getValue());
		GENERIC_QUALIFICATIONS.entrySet().removeIf(entry -> {
			if (policyFrame - entry.getValue().lastSeenFrame <= AdaptivePersistencePolicy.EXPIRE_AFTER_FRAMES) return false;
			removeMesh(entry.getKey());
			return true;
		});
		int maxGenericGroups = Math.max(64, OptiminiumSettings.getBlockEntityPersistenceMaxMeshes() * 4);
		while (GENERIC_QUALIFICATIONS.size() > maxGenericGroups) {
			Map.Entry<GenericSourceKey, GenericQualification> oldest = GENERIC_QUALIFICATIONS.entrySet().stream()
				.min(java.util.Comparator.comparingLong(entry -> entry.getValue().lastSeenFrame)).orElse(null);
			if (oldest == null) break;
			removeMesh(oldest.getKey());
			GENERIC_QUALIFICATIONS.remove(oldest.getKey());
			ADAPTIVE_POLICIES.remove(oldest.getKey());
			ACTIVE_KEYS.remove(oldest.getKey());
		}
		adaptiveSummary = ADAPTIVE_POLICIES.entrySet().stream()
			.max(java.util.Comparator.comparingInt(entry -> entry.getValue().lastCount()))
			.map(entry -> {
				AdaptivePersistencePolicy value = entry.getValue();
				return String.format(Locale.ROOT, "%s count=%d mode=%s vanilla=%.0fns persistent=%.0fns trials=%d reason=%s vertices=%d builds=%d",
					keyName(entry.getKey()), value.lastCount(), value.active() ? "persistent" : value.trial() ? "trial" : "vanilla",
					value.vanillaPerInstanceNanos(), value.persistentPerInstanceNanos(), value.trials(), value.reason(), value.meshVertices(), value.builds());
			})
			.orElse("none");
		mobQualifiedGroups = MOB_QUALIFICATIONS.entrySet().stream()
			.filter(entry -> entry.getKey() instanceof MobGroupKey && entry.getValue().qualified()).count();
		mobSummary = ADAPTIVE_POLICIES.entrySet().stream()
			.filter(entry -> entry.getKey() instanceof MobGroupKey)
			.max(java.util.Comparator.comparingInt(entry -> entry.getValue().lastCount()))
			.map(entry -> {
				AdaptivePersistencePolicy value = entry.getValue();
				MobTopologyPolicy qualification = MOB_QUALIFICATIONS.get(entry.getKey());
				return String.format(Locale.ROOT, "%s count=%d mode=%s qualified=%s samples=%d entities=%d cpuVanilla=%.0fns cpuPersistent=%.0fns reason=%s fallback=%s",
					keyName(entry.getKey()), value.lastCount(), value.active() ? "persistent" : value.trial() ? "trial" : "vanilla",
					qualification != null && qualification.qualified(), qualification == null ? 0 : qualification.matchingSamples(),
					qualification == null ? 0 : qualification.distinctEntities(), value.vanillaPerInstanceNanos(),
					value.persistentPerInstanceNanos(), value.reason(),
					MOB_FALLBACK_REASONS.getOrDefault(entry.getKey(), "none"));
			}).orElse("none");
		armorStandSummary = ADAPTIVE_POLICIES.entrySet().stream()
			.filter(entry -> entry.getKey() instanceof ArmorStandLayerKey)
			.max(java.util.Comparator.comparingInt(entry -> entry.getValue().lastCount()))
			.map(entry -> {
				AdaptivePersistencePolicy value = entry.getValue();
				return String.format(Locale.ROOT, "%s count=%d mode=%s cpuVanilla=%.0fns cpuPersistent=%.0fns reason=%s",
					keyName(entry.getKey()), value.lastCount(), value.active() ? "persistent" : value.trial() ? "trial" : "vanilla",
					value.vanillaPerInstanceNanos(), value.persistentPerInstanceNanos(), value.reason());
			}).orElse("none");
	}

	private static void updatePersistenceDiscoveryCooldown() {
		boolean activeOrTrial = ACTIVE_KEYS.stream().anyMatch(key -> !(key instanceof MobGroupKey));
		boolean qualifying = GENERIC_QUALIFICATIONS.values().stream().anyMatch(value -> value.qualifying);
		boolean measuredFallback = ADAPTIVE_POLICIES.entrySet().stream()
			.filter(entry -> !(entry.getKey() instanceof MobGroupKey))
			.map(entry -> entry.getValue().reason())
			.anyMatch(reason -> reason.equals("gpu_safety_veto")
				|| reason.equals("p95_safety_veto") || reason.equals("measured_regression"));
		PERSISTENCE_DISCOVERY_COOLDOWN.considerSleep(policyFrame, activeOrTrial, qualifying, measuredFallback);
	}

	private static void removeMeshesForPolicy(Object policyKey) {
		var iterator = MESHES.entrySet().iterator();
		while (iterator.hasNext()) {
			CachedMesh mesh = iterator.next().getValue();
			if (!mesh.policyKey.equals(policyKey)) continue;
			iterator.remove();
			QUEUED_MESHES.remove(mesh);
			gpuBytes -= mesh.bytes;
			mesh.close();
		}
	}

	private static String keyName(Object key) {
		if (key instanceof MeshKey meshKey) return meshKey.type().toString();
		if (key instanceof GenericSourceKey genericKey) return genericKey.type().toString();
		if (key instanceof MobGroupKey mobKey) return mobKey.type().toString();
		if (key instanceof ArmorStandLayerKey) return "armor_stand_layer";
		return "armor_stand";
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
		String base = String.format(Locale.ROOT,
			"persistentBeMeshes=%d, persistentBeMeshHits=%d, persistentBeMeshMisses=%d, persistentBeMeshUploads=%d, persistentBeMeshRebuildsPerSecond=%d, persistentBeInstanceUploadsFrame=%d, persistentBeInstancesFrame=%d, persistentBeCulledFrame=%d, persistentBeVerticesAvoidedFrame=%d, persistentBeDrawCallsFrame=%d, persistentBeGpuBytes=%d, persistentBeFallbacks=%d, persistentBeFallbacksFrame=%d, persistentBeCandidatesFrame=%d, persistentBeDensityThreshold=%d, persistentBeAdaptiveMin=%d, persistentBeAdaptive=%s, persistentBeShaderLoaded=%s, persistentBeGenericGroups=%d, persistentBeGenericQualified=%d, persistentBeGenericFamilies=%d, persistentBeGenericFamilyRejects=%d, persistentBeGenericKeyLookups=%d, persistentBeGenericKeySerializations=%d, persistentBeGenericKeyCacheHits=%d, persistentBeGenericKeyAvgNs=%.0f, persistentAtlasPages=%d, persistentAtlasStateSetups=%d, persistentAtlasIndirectDraws=%d, persistentAtlasFallbackDraws=%d, persistentTransformSlots=%d, persistentTransformUploads=%d, persistentTransformUploadBytes=%d, persistentMobEnabled=%s, persistentMobAdaptiveMin=%d, persistentMobGroups=%d, persistentMobQualified=%d, persistentMobPartsSuppressed=%d, persistentMobVanillaParts=%d, persistentMobBoneUploads=%d, persistentMobFallbacks=%d, persistentMobPolicy=%s, persistentArmorStandPolicy=%s, persistentBePolicy=%s, beDispatcherAverageMs=%.6f, clientFps=%d, clientCpuFrameMs=%.3f",
			value.cachedMeshes(), value.hits(), value.misses(), value.uploads(), value.meshRebuildsPerSecond(), value.instanceUploadsThisFrame(), value.instancesDrawnThisFrame(),
			value.instancesCulledThisFrame(), value.verticesAvoidedThisFrame(), value.drawCallsThisFrame(),
			value.estimatedGpuBytes(), value.fallbacks(), value.fallbacksThisFrame(), lastHottestCandidateCount,
			densityThreshold(), adaptiveMinThreshold(),
			OptiminiumSettings.isBlockEntityPersistenceAdaptive(), OptiminiumPersistentMeshShader.get() != null,
			GENERIC_QUALIFICATIONS.size(),
			GENERIC_QUALIFICATIONS.values().stream().filter(group -> group.qualified).count(),
			genericFamilyCountsLastFrame.size(), genericFamilyRejects, genericKeyBuilds,
			genericKeySerializations, genericKeyCacheHits,
			genericKeyBuilds == 0L ? 0.0D : genericKeyNanos / (double)genericKeyBuilds,
			ATLAS_PAGES.size(), atlasStateSetups, atlasIndirectDraws, atlasFallbackDraws,
			RESIDENT_TRANSFORMS.size(), residentTransformUploads, residentTransformUploadBytes,
			OptiminiumSettings.isMobPersistenceEnabled(), mobAdaptiveMinThreshold(),
			MOB_QUALIFICATIONS.keySet().stream().filter(key -> key instanceof MobGroupKey).count(),
			mobQualifiedGroups, mobPartsSuppressed, mobVanillaParts,
			mobBoneUploads, mobFallbacks, mobSummary, armorStandSummary, value.adaptiveSummary(),
			OptiminiumBlockEntityVirtualizer.snapshot().averageFullRendererMs(),
			Minecraft.getInstance().getFps(),
			OptiminiumGpuOptimizer.getLatestCpuFrameNanos() / 1_000_000.0D);
		return base + String.format(Locale.ROOT,
			", persistentDiscovery=%s, persistentGenericDormantSkips=%d",
			PERSISTENCE_DISCOVERY_COOLDOWN.state(policyFrame), genericFamilyDormantSkips);
	}

	private record MeshKey(BlockEntityType<?> type, int stateId, Object variant, Class<?> renderer) {
	}

	private record GenericSourceKey(BlockEntityType<?> type, int stateId, Class<?> renderer, int dataHash) {
	}

	private record GenericFamilyKey(BlockEntityType<?> type, int stateId, Class<?> renderer) {
	}

	private sealed interface ExactGroupKey permits MobGroupKey, ArmorStandBoneKey {
	}

	private record MobGroupKey(net.minecraft.world.entity.EntityType<?> type, Class<?> renderer,
		boolean baby, int equipmentSignature, ResourceLocation texture) implements ExactGroupKey {
	}

	private record ArmorStandBoneKey(net.minecraft.world.entity.EntityType<?> type, Class<?> renderer,
		boolean small, ResourceLocation texture) implements ExactGroupKey {
	}

	private record ArmorStandLayerKey(ArmorStandBoneKey family, RenderType renderType) {
	}

	private record ArmorStandPartKey(ArmorStandLayerKey policyKey, ModelPart part) {
	}

	private record PartVanillaTiming(ArmorStandLayerKey key, long startedNanos, int stride) {
	}

	private static final class ArmorStandFamilyState {
		final ArmorStandBoneKey family;
		final IdentityHashMap<RenderType, ArmorStandLayerKey> layers = new IdentityHashMap<>();
		final IdentityHashMap<RenderType, IdentityHashMap<ModelPart, ArmorStandPartKey>> parts = new IdentityHashMap<>();

		ArmorStandFamilyState(ArmorStandBoneKey family) {
			this.family = family;
		}

		ArmorStandPartKey partKey(ModelPart part, RenderType renderType) {
			ArmorStandLayerKey layer = layers.computeIfAbsent(renderType,
				value -> new ArmorStandLayerKey(family, value));
			return parts.computeIfAbsent(renderType, ignored -> new IdentityHashMap<>())
				.computeIfAbsent(part, value -> new ArmorStandPartKey(layer, value));
		}
	}

	private record MobAtlasKey(ExactGroupKey group) {
	}

	private record GenericKeyCache(GenericSourceKey key, GenericFamilyKey family,
		int stateId, Class<?> renderer, long sampledAt) {
	}

	static final class GenericQualification {
		long fingerprint;
		long nextSampleFrame;
		long nextValidationFrame;
		long lastSeenFrame;
		int matchingSamples;
		boolean qualifying;
		boolean qualified;

		void observe(long sample, long frame) {
			qualifying = true;
			if (matchingSamples == 0 || fingerprint != sample) {
				fingerprint = sample;
				matchingSamples = 1;
				qualified = false;
			} else {
				matchingSamples++;
				qualified = matchingSamples >= GENERIC_STABLE_SAMPLES;
			}
			nextSampleFrame = frame + GENERIC_SAMPLE_INTERVAL_FRAMES;
			if (qualified) {
				qualifying = false;
				nextValidationFrame = frame + GENERIC_VALIDATION_INTERVAL_FRAMES;
			}
		}

		void reset(Long sample, long frame) {
			qualified = false;
			qualifying = sample != null;
			matchingSamples = sample == null ? 0 : 1;
			fingerprint = sample == null ? 0L : sample.longValue();
			nextSampleFrame = frame + GENERIC_SAMPLE_INTERVAL_FRAMES;
		}
	}

	private static final class MobRenderContext {
		final ExactGroupKey key;
		final UUID entityId;
		final IdentityHashMap<VertexConsumer, RenderType> renderTypes = new IdentityHashMap<>();
		final MultiBufferSource buffers;
		final boolean persist;
		RenderType primaryRenderType;
		CaptureSource capture;
		int packedLight;
		int packedOverlay;
		int paletteOffset = -1;
		int poseCount;
		boolean atlasCompatible = true;
		String fallbackReason = "none";
		long topologyHash = 0xcbf29ce484222325L;
		int compatibleParts;
		int suppressedParts;
		boolean partPersistence;
		ArmorStandFamilyState armorStandFamily;

		MobRenderContext(ExactGroupKey key, UUID entityId, MultiBufferSource delegate, boolean persist) {
			this.key = key;
			this.entityId = entityId;
			this.persist = persist;
			this.buffers = renderType -> {
				VertexConsumer consumer = delegate.getBuffer(renderType);
				renderTypes.put(consumer, renderType);
				if (primaryRenderType == null) primaryRenderType = renderType;
				return consumer;
			};
		}

		void mixTopology(ModelPart part, RenderType renderType, boolean compatible) {
			topologyHash = (topologyHash ^ System.identityHashCode(part)) * 0x100000001b3L;
			topologyHash = (topologyHash ^ System.identityHashCode(renderType)) * 0x100000001b3L;
			topologyHash = (topologyHash ^ (compatible ? 1L : 0L)) * 0x100000001b3L;
		}
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
			if (renderType.mode() != VertexFormat.Mode.QUADS
				&& renderType.mode() != VertexFormat.Mode.TRIANGLES) {
				throw new IllegalArgumentException("Incompatible persistent mesh primitive mode: " + renderType.mode());
			}
			if (renderType.format() != DefaultVertexFormat.NEW_ENTITY) {
				throw new IllegalArgumentException("Incompatible persistent mesh vertex format: " + renderType.format());
			}
			if (!isCompatiblePersistentRenderType(renderType)) {
				throw new IllegalArgumentException("Incompatible persistent mesh shader/state: " + renderType);
			}
			return layers.computeIfAbsent(renderType, LayerBuilder::new).builder;
		}

		CachedMesh upload(Object key) {
			return upload(key, key);
		}

		CachedMesh upload(Object key, Object policyKey) {
			return upload(key, policyKey, 0);
		}

		CachedMesh upload(Object key, Object policyKey, int boneCount) {
			List<CachedLayer> uploaded = new ArrayList<>(layers.size());
			long vertices = 0L;
			try {
				for (LayerBuilder layer : layers.values()) {
					try (MeshData data = layer.builder.build()) {
						if (data == null) continue;
						vertices += data.drawState().vertexCount();
						uploaded.add(new CachedLayer(layer.renderType, allocateAtlasSlice(layer.renderType, data)));
					}
				}
				if (uploaded.isEmpty()) return null;
				return new CachedMesh(key, policyKey, List.copyOf(uploaded), 0L, vertices, boneCount);
			} catch (RuntimeException exception) {
				for (CachedLayer layer : uploaded) layer.close();
				throw exception;
			} finally {
				close();
			}
		}

		long captureFingerprint() {
			long hash = 0xcbf29ce484222325L;
			try {
				for (LayerBuilder layer : layers.values()) {
					hash = fingerprintMix(hash, System.identityHashCode(layer.renderType));
					try (MeshData data = layer.builder.build()) {
						if (data == null) continue;
						hash = fingerprintMix(hash, data.drawState().vertexCount());
						hash = fingerprintMix(hash, data.drawState().indexCount());
						hash = fingerprintMix(hash, data.drawState().mode().ordinal());
						hash = fingerprintMix(hash, data.vertexBuffer());
						if (data.indexBuffer() != null) hash = fingerprintMix(hash, data.indexBuffer());
					}
				}
				return hash;
			} finally {
				close();
			}
		}

		private static long fingerprintMix(long hash, int value) {
			return (hash ^ value) * 0x100000001b3L;
		}

		private static long fingerprintMix(long hash, ByteBuffer source) {
			ByteBuffer data = source.duplicate();
			while (data.hasRemaining()) hash = (hash ^ (data.get() & 0xFFL)) * 0x100000001b3L;
			return hash;
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

	private static AtlasSlice allocateAtlasSlice(RenderType renderType, MeshData data) {
		MeshData.DrawState state = data.drawState();
		if (state.format() != renderType.format()) {
			throw new IllegalArgumentException("Atlas vertex format changed during capture");
		}
		ByteBuffer vertices = data.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
		int vertexBytes = vertices.remaining();
		int indexBytes = state.indexCount() * Integer.BYTES;
		AtlasPage page = null;
		for (AtlasPage candidate : ATLAS_PAGES) {
			if (candidate.renderType == renderType && candidate.canFit(vertexBytes, indexBytes)) {
				page = candidate;
				break;
			}
		}
		if (page == null) {
			page = new AtlasPage(renderType,
				nextPowerOfTwo(Math.max(256 * 1024, vertexBytes * 2)),
				nextPowerOfTwo(Math.max(128 * 1024, indexBytes * 2)));
			ATLAS_PAGES.add(page);
		}
		int baseVertex = page.vertexUsedBytes / state.format().getVertexSize();
		ByteBuffer indices = MemoryUtil.memAlloc(indexBytes).order(ByteOrder.nativeOrder());
		try {
			ByteBuffer source = data.indexBuffer();
			if (source != null) {
				ByteBuffer input = source.duplicate().order(ByteOrder.nativeOrder());
				for (int i = 0; i < state.indexCount(); i++) {
					int index = state.indexType() == VertexFormat.IndexType.SHORT
						? input.getShort() & 0xFFFF : input.getInt();
					indices.putInt(baseVertex + index);
				}
			} else if (state.mode() == VertexFormat.Mode.QUADS) {
				for (int vertex = 0; vertex < state.vertexCount(); vertex += 4) {
					indices.putInt(baseVertex + vertex);
					indices.putInt(baseVertex + vertex + 1);
					indices.putInt(baseVertex + vertex + 2);
					indices.putInt(baseVertex + vertex + 2);
					indices.putInt(baseVertex + vertex + 3);
					indices.putInt(baseVertex + vertex);
				}
			} else {
				for (int index = 0; index < state.indexCount(); index++) indices.putInt(baseVertex + index);
			}
			indices.flip();
			return page.upload(vertices, indices, state.indexCount(), state.mode());
		} finally {
			MemoryUtil.memFree(indices);
		}
	}

	private static int nextPowerOfTwo(int value) {
		return value <= 1 ? 1 : Integer.highestOneBit(value - 1) << 1;
	}

	private static final class AtlasPage implements AutoCloseable {
		final RenderType renderType;
		final int vertexCapacityBytes;
		final int indexCapacityBytes;
		final int vao = GL30.glGenVertexArrays();
		final int vertexBuffer = GL15.glGenBuffers();
		final int indexBuffer = GL15.glGenBuffers();
		int vertexUsedBytes;
		int indexUsedBytes;
		int allocations;
		int configuredInstanceBuffer;
		boolean closed;

		AtlasPage(RenderType renderType, int vertexCapacityBytes, int indexCapacityBytes) {
			this.renderType = renderType;
			this.vertexCapacityBytes = vertexCapacityBytes;
			this.indexCapacityBytes = indexCapacityBytes;
			bindAtlasVertexArray(vao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexCapacityBytes, GL15.GL_STATIC_DRAW);
			renderType.format().setupBufferState();
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexCapacityBytes, GL15.GL_STATIC_DRAW);
			bindAtlasVertexArray(0);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
			gpuBytes += (long)vertexCapacityBytes + indexCapacityBytes;
		}

		boolean canFit(int vertexBytes, int indexBytes) {
			return !closed && vertexUsedBytes + vertexBytes <= vertexCapacityBytes
				&& indexUsedBytes + indexBytes <= indexCapacityBytes;
		}

		AtlasSlice upload(ByteBuffer vertices, ByteBuffer indices, int indexCount, VertexFormat.Mode mode) {
			int indexOffset = indexUsedBytes;
			bindAtlasVertexArray(vao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
			GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, vertexUsedBytes, vertices);
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
			GL15.glBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexUsedBytes, indices);
			bindAtlasVertexArray(0);
			vertexUsedBytes += vertices.remaining();
			indexUsedBytes += indices.remaining();
			allocations++;
			return new AtlasSlice(this, indexOffset, indexCount, mode);
		}

		void bind(int instanceBuffer) {
			bindAtlasVertexArray(0);
			bindAtlasVertexArray(vao);
			if (configuredInstanceBuffer != instanceBuffer) {
				InstanceBatch.configureAttributes(OptiminiumPersistentMeshShader.get(), instanceBuffer);
				configuredInstanceBuffer = instanceBuffer;
			}
		}

		void release() {
			if (--allocations == 0) close();
		}

		@Override public void close() {
			if (closed) return;
			closed = true;
			ATLAS_PAGES.remove(this);
			bindAtlasVertexArray(0);
			GL30.glDeleteVertexArrays(vao);
			GL15.glDeleteBuffers(vertexBuffer);
			GL15.glDeleteBuffers(indexBuffer);
			gpuBytes -= (long)vertexCapacityBytes + indexCapacityBytes;
		}
	}

	private static final class AtlasSlice implements AutoCloseable {
		final AtlasPage page;
		final long indexOffsetBytes;
		final int indexCount;
		final VertexFormat.Mode mode;
		boolean closed;

		AtlasSlice(AtlasPage page, long indexOffsetBytes, int indexCount, VertexFormat.Mode mode) {
			this.page = page;
			this.indexOffsetBytes = indexOffsetBytes;
			this.indexCount = indexCount;
			this.mode = mode;
		}

		@Override public void close() {
			if (closed) return;
			closed = true;
			page.release();
		}
	}

	private static final class CachedMesh implements AutoCloseable {
		final Object key;
		final Object policyKey;
		final List<CachedLayer> layers;
		final long bytes;
		final long vertices;
		final int boneCount;
		final InstanceBatch instances = new InstanceBatch();
		int sharedBaseInstance;

		CachedMesh(Object key, Object policyKey, List<CachedLayer> layers, long bytes, long vertices, int boneCount) {
			this.key = key;
			this.policyKey = policyKey;
			this.layers = layers;
			this.bytes = bytes;
			this.vertices = vertices;
			this.boneCount = boneCount;
		}

		void queueResident(Object owner, Matrix4f pose, boolean worldSpace,
				int packedLight, int packedOverlay) {
			int transformIndex = RESIDENT_TRANSFORMS.indexFor(owner, pose, worldSpace, policyFrame);
			instances.add(transformIndex, worldSpace, packedLight, packedOverlay, -1, -1);
			if (instances.count == 1) QUEUED_MESHES.add(this);
		}

		void queueStaticResident(Object owner, Matrix4f pose, boolean worldSpace,
				int packedLight, int packedOverlay) {
			int transformIndex = RESIDENT_TRANSFORMS.indexForStatic(owner, pose, worldSpace, policyFrame);
			instances.add(transformIndex, worldSpace, packedLight, packedOverlay, -1, -1);
			if (instances.count == 1) QUEUED_MESHES.add(this);
		}

		void queueBone(int packedLight, int packedOverlay, int paletteOffset) {
			queueBone(packedLight, packedOverlay, -1, paletteOffset);
		}

		void queueBone(int packedLight, int packedOverlay, int color, int paletteOffset) {
			instances.add(0, false, packedLight, packedOverlay, color, paletteOffset);
			if (instances.count == 1) QUEUED_MESHES.add(this);
		}

		@Override public void close() {
			for (CachedLayer layer : layers) layer.close();
			instances.close();
		}
	}

	private static final class CachedLayer {
		final RenderType renderType;
		final AtlasSlice slice;

		CachedLayer(RenderType renderType, AtlasSlice slice) {
			this.renderType = renderType;
			this.slice = slice;
		}

		void drawInstancedRaw(InstanceBatch instances, boolean sharedInstances, int baseInstance) {
			int instanceBuffer = sharedInstances ? SHARED_INSTANCES.currentGpuBuffer() : instances.currentGpuBuffer();
			slice.page.bind(instanceBuffer);
			if (sharedInstances) {
				if (GL.getCapabilities().OpenGL42) {
					GL42.glDrawElementsInstancedBaseInstance(slice.mode.asGLMode,
						slice.indexCount, GL11.GL_UNSIGNED_INT, slice.indexOffsetBytes,
						instances.count, baseInstance);
				} else {
					ARBBaseInstance.glDrawElementsInstancedBaseInstance(slice.mode.asGLMode,
						slice.indexCount, GL11.GL_UNSIGNED_INT, slice.indexOffsetBytes,
						instances.count, baseInstance);
				}
			} else {
				GL31.glDrawElementsInstanced(slice.mode.asGLMode,
					slice.indexCount, GL11.GL_UNSIGNED_INT, slice.indexOffsetBytes, instances.count);
			}
		}

		void close() { slice.close(); }
	}

	private static final class InstanceBatch implements AutoCloseable {
		// transform index, light, overlay, color, bone-palette offset, world-space flag
		private static final int FLOATS_PER_INSTANCE = 11;
		private static final int BYTES_PER_INSTANCE = FLOATS_PER_INSTANCE * Float.BYTES;
		private static final int GPU_RING_SIZE = 3;
		private ByteBuffer data = MemoryUtil.memAlloc(BYTES_PER_INSTANCE * 64).order(ByteOrder.nativeOrder());
		private final int[] gpuBuffers = new int[GPU_RING_SIZE];
		private int currentGpuSlot = -1;
		private int gpuCapacityBytes;
		int count;

		void add(int transformIndex, boolean worldSpace, int packedLight,
				int packedOverlay, int color, int paletteOffset) {
			ensureCpuCapacity((count + 1) * BYTES_PER_INSTANCE);
			int base = count * BYTES_PER_INSTANCE;
			data.putFloat(base, transformIndex);
			data.putFloat(base + 4, packedLight & 0xFFFF);
			data.putFloat(base + 8, packedLight >>> 16 & 0xFFFF);
			data.putFloat(base + 12, packedOverlay & 0xFFFF);
			data.putFloat(base + 16, packedOverlay >>> 16 & 0xFFFF);
			data.putFloat(base + 20, (color >> 16 & 0xFF) / 255.0F);
			data.putFloat(base + 24, (color >> 8 & 0xFF) / 255.0F);
			data.putFloat(base + 28, (color & 0xFF) / 255.0F);
			data.putFloat(base + 32, (color >>> 24) / 255.0F);
			data.putFloat(base + 36, paletteOffset);
			data.putFloat(base + 40, worldSpace ? 1.0F : 0.0F);
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

		static void configureAttributes(net.minecraft.client.renderer.ShaderInstance shader, int buffer) {
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
			configure(shader, "InstanceTransformIndex", 1, 0L);
			configure(shader, "InstanceLight", 2, 4L);
			configure(shader, "InstanceOverlay", 2, 12L);
			configure(shader, "InstanceColor", 4, 20L);
			configure(shader, "InstancePaletteOffset", 1, 36L);
			configure(shader, "InstanceWorldSpace", 1, 40L);
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

		ByteBuffer dataView() {
			ByteBuffer view = data.duplicate().order(ByteOrder.nativeOrder());
			view.position(0).limit(count * BYTES_PER_INSTANCE);
			return view;
		}

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

	/** One compact frame stream shared by every compatible persistent mesh. */
	private static final class SharedInstanceStream implements AutoCloseable {
		private ByteBuffer data = MemoryUtil.memAlloc(InstanceBatch.BYTES_PER_INSTANCE * 256)
			.order(ByteOrder.nativeOrder());
		private final int[] gpuBuffers = new int[3];
		private int currentSlot = -1;
		private int gpuCapacityBytes;
		private int count;

		void begin() {
			count = 0;
			data.clear();
		}

		int append(InstanceBatch batch) {
			int base = count;
			int bytes = batch.count * InstanceBatch.BYTES_PER_INSTANCE;
			ensureCapacity((count + batch.count) * InstanceBatch.BYTES_PER_INSTANCE);
			ByteBuffer source = batch.dataView();
			data.position(count * InstanceBatch.BYTES_PER_INSTANCE);
			data.put(source);
			count += batch.count;
			return base;
		}

		void upload() {
			int bytes = count * InstanceBatch.BYTES_PER_INSTANCE;
			if (gpuCapacityBytes < bytes) {
				int old = gpuCapacityBytes;
				gpuCapacityBytes = Integer.highestOneBit(Math.max(bytes - 1, InstanceBatch.BYTES_PER_INSTANCE)) << 1;
				gpuBytes += (long)(gpuCapacityBytes - old) * gpuBuffers.length;
				for (int i = 0; i < gpuBuffers.length; i++) {
					if (gpuBuffers[i] == 0) gpuBuffers[i] = GL15.glGenBuffers();
					GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpuBuffers[i]);
					GL15.glBufferData(GL15.GL_ARRAY_BUFFER, gpuCapacityBytes, GL15.GL_STREAM_DRAW);
				}
			}
			currentSlot = (currentSlot + 1) % gpuBuffers.length;
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, gpuBuffers[currentSlot]);
			ByteBuffer upload = data.duplicate().order(ByteOrder.nativeOrder());
			upload.position(0).limit(bytes);
			GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, upload);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
			if (metricsEnabled) {
				instanceUploads.increment();
				instanceUploadsThisFrame++;
			}
		}

		int currentGpuBuffer() { return currentSlot < 0 ? 0 : gpuBuffers[currentSlot]; }

		private void ensureCapacity(int required) {
			if (data.capacity() >= required) return;
			int capacity = Integer.highestOneBit(required - 1) << 1;
			ByteBuffer grown = MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
			ByteBuffer old = data.duplicate();
			old.position(0).limit(count * InstanceBatch.BYTES_PER_INSTANCE);
			grown.put(old);
			MemoryUtil.memFree(data);
			data = grown;
		}

		@Override public void close() {
			for (int i = 0; i < gpuBuffers.length; i++) {
				if (gpuBuffers[i] != 0) GL15.glDeleteBuffers(gpuBuffers[i]);
				gpuBuffers[i] = 0;
			}
			gpuBytes -= (long)gpuCapacityBytes * gpuBuffers.length;
			gpuCapacityBytes = 0;
			currentSlot = -1;
			if (data != null) MemoryUtil.memFree(data);
			data = MemoryUtil.memAlloc(InstanceBatch.BYTES_PER_INSTANCE * 256).order(ByteOrder.nativeOrder());
		}
	}

	/** Compact DrawElementsIndirectCommand stream for slices sharing one atlas page. */
	private static final class IndirectCommandStream implements AutoCloseable {
		private static final int COMMAND_BYTES = 5 * Integer.BYTES;
		private int buffer;
		private int capacityBytes;

		void draw(List<QueuedLayer> draws) {
			int bytes = draws.size() * COMMAND_BYTES;
			ByteBuffer commands = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
			try {
				for (QueuedLayer draw : draws) {
					commands.putInt(draw.layer.slice.indexCount);
					commands.putInt(draw.mesh.instances.count);
					commands.putInt((int)(draw.layer.slice.indexOffsetBytes / Integer.BYTES));
					commands.putInt(0);
					commands.putInt(draw.mesh.sharedBaseInstance);
				}
				commands.flip();
				if (buffer == 0) buffer = GL15.glGenBuffers();
				GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, buffer);
				if (capacityBytes < bytes) {
					int old = capacityBytes;
					capacityBytes = nextPowerOfTwo(bytes);
					GL15.glBufferData(GL40.GL_DRAW_INDIRECT_BUFFER, capacityBytes, GL15.GL_STREAM_DRAW);
					gpuBytes += capacityBytes - old;
				}
				GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 0L, commands);
				int mode = draws.get(0).layer.slice.mode.asGLMode;
				if (GL.getCapabilities().OpenGL43) {
					GL43.glMultiDrawElementsIndirect(mode, GL11.GL_UNSIGNED_INT, 0L, draws.size(), 0);
				} else {
					ARBMultiDrawIndirect.glMultiDrawElementsIndirect(mode, GL11.GL_UNSIGNED_INT, 0L, draws.size(), 0);
				}
				GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
			} finally {
				MemoryUtil.memFree(commands);
			}
		}

		@Override public void close() {
			if (buffer != 0) GL15.glDeleteBuffers(buffer);
			buffer = 0;
			gpuBytes -= capacityBytes;
			capacityBytes = 0;
		}
	}

	/**
	 * Long-lived world transforms addressed by the compact visible-instance stream. Static block
	 * entities normally upload a matrix once; later frames send only this slot plus light/state.
	 */
	private static final class ResidentTransformStream implements AutoCloseable {
		private static final int MATRIX_BYTES = 16 * Float.BYTES;
		private static final int TEXTURE_UNIT = 11;
		private static final long EXPIRE_AFTER_FRAMES = 600L;
		private final ResidentTransformIndex index = new ResidentTransformIndex();
		private final Set<Integer> dirtySlots = new java.util.HashSet<>();
		private ByteBuffer data = MemoryUtil.memAlloc(MATRIX_BYTES * 256).order(ByteOrder.nativeOrder());
		private int buffer;
		private int texture;
		private int gpuCapacityBytes;

		int indexFor(Object owner, Matrix4f pose, boolean worldSpace, long frame) {
			Matrix4f stored = new Matrix4f(pose);
			if (worldSpace) {
				var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
				stored.m30(stored.m30() + (float)camera.x);
				stored.m31(stored.m31() + (float)camera.y);
				stored.m32(stored.m32() + (float)camera.z);
			}
			ResidentTransformIndex.Update update = index.observe(owner, stored, frame);
			if (update.changed()) write(update.slot(), update.matrix());
			return update.slot();
		}

		int indexForStatic(Object owner, Matrix4f pose, boolean worldSpace, long frame) {
			int resident = index.touch(owner, frame);
			return resident >= 0 ? resident : indexFor(owner, pose, worldSpace, frame);
		}

		private void write(int slot, Matrix4f matrix) {
			ensureCpuCapacity((slot + 1) * MATRIX_BYTES);
			matrix.get(slot * MATRIX_BYTES, data);
			dirtySlots.add(slot);
		}

		void expire(long frame) {
			for (int slot : index.expire(frame, EXPIRE_AFTER_FRAMES)) dirtySlots.remove(slot);
		}

		boolean hasDirtyData() { return !dirtySlots.isEmpty(); }
		int size() { return index.size(); }

		void upload() {
			if (dirtySlots.isEmpty()) return;
			int required = Math.max(MATRIX_BYTES, index.slotCapacity() * MATRIX_BYTES);
			if (buffer == 0) buffer = GL15.glGenBuffers();
			if (texture == 0) texture = GL11.glGenTextures();
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffer);
			long uploadedBytes = 0L;
			if (gpuCapacityBytes < required) {
				int old = gpuCapacityBytes;
				gpuCapacityBytes = nextPowerOfTwo(required);
				GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, gpuCapacityBytes, GL15.GL_DYNAMIC_DRAW);
				ByteBuffer all = data.duplicate().order(ByteOrder.nativeOrder());
				all.position(0).limit(required);
				GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, all);
				gpuBytes += gpuCapacityBytes - old;
				uploadedBytes = required;
			} else {
				for (int slot : dirtySlots) {
					ByteBuffer matrix = data.duplicate().order(ByteOrder.nativeOrder());
					matrix.position(slot * MATRIX_BYTES).limit((slot + 1) * MATRIX_BYTES);
					GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, (long)slot * MATRIX_BYTES, matrix);
					uploadedBytes += MATRIX_BYTES;
				}
			}
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
			GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
			dirtySlots.clear();
			residentTransformUploads++;
			residentTransformUploadBytes += uploadedBytes;
		}

		void bind(net.minecraft.client.renderer.ShaderInstance shader) {
			int sampler = GL20.glGetUniformLocation(shader.getId(), "TransformPalette");
			if (sampler >= 0 && texture != 0) {
				RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
				GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
				GL20.glUniform1i(sampler, TEXTURE_UNIT);
				RenderSystem.activeTexture(GL13.GL_TEXTURE0);
			}
			int cameraUniform = GL20.glGetUniformLocation(shader.getId(), "PersistentCameraPos");
			if (cameraUniform >= 0) {
				var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
				GL20.glUniform3f(cameraUniform, (float)camera.x, (float)camera.y, (float)camera.z);
			}
		}

		private void ensureCpuCapacity(int required) {
			if (data.capacity() >= required) return;
			int capacity = nextPowerOfTwo(required);
			ByteBuffer grown = MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
			ByteBuffer old = data.duplicate();
			old.clear();
			grown.put(old);
			MemoryUtil.memFree(data);
			data = grown;
		}

		@Override public void close() {
			if (buffer != 0) GL15.glDeleteBuffers(buffer);
			if (texture != 0) GL11.glDeleteTextures(texture);
			buffer = 0;
			texture = 0;
			gpuBytes -= gpuCapacityBytes;
			gpuCapacityBytes = 0;
			index.clear();
			dirtySlots.clear();
			if (data != null) MemoryUtil.memFree(data);
			data = MemoryUtil.memAlloc(MATRIX_BYTES * 256).order(ByteOrder.nativeOrder());
		}
	}

	/** Texture-buffer-backed cumulative bone matrices for exact animated mob instances. */
	private static final class BonePaletteStream implements AutoCloseable {
		private static final int MATRIX_BYTES = 16 * Float.BYTES;
		private static final int TEXTURE_UNIT = 12;
		private ByteBuffer data = MemoryUtil.memAlloc(MATRIX_BYTES * 256).order(ByteOrder.nativeOrder());
		private final int[] buffers = new int[3];
		private final int[] textures = new int[3];
		private int currentSlot = -1;
		private int gpuCapacityBytes;
		private int matrices;

		int matrixCount() { return matrices; }

		void append(Matrix4f matrix) {
			ensureCapacity((matrices + 1) * MATRIX_BYTES);
			matrix.get(matrices * MATRIX_BYTES, data);
			matrices++;
		}

		boolean isEmpty() { return matrices == 0; }

		void upload() {
			int bytes = matrices * MATRIX_BYTES;
			if (gpuCapacityBytes < bytes) {
				int old = gpuCapacityBytes;
				gpuCapacityBytes = Integer.highestOneBit(Math.max(bytes - 1, MATRIX_BYTES)) << 1;
				gpuBytes += (long)(gpuCapacityBytes - old) * buffers.length;
				for (int i = 0; i < buffers.length; i++) {
					if (buffers[i] == 0) buffers[i] = GL15.glGenBuffers();
					if (textures[i] == 0) textures[i] = GL11.glGenTextures();
					GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffers[i]);
					GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, gpuCapacityBytes, GL15.GL_STREAM_DRAW);
					GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textures[i]);
					GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffers[i]);
				}
			}
			currentSlot = (currentSlot + 1) % buffers.length;
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, buffers[currentSlot]);
			ByteBuffer upload = data.duplicate().order(ByteOrder.nativeOrder());
			upload.position(0).limit(bytes);
			GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, upload);
			GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
		}

		void bind(net.minecraft.client.renderer.ShaderInstance shader) {
			int location = GL20.glGetUniformLocation(shader.getId(), "BonePalette");
			if (location < 0 || currentSlot < 0) return;
			RenderSystem.activeTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
			GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textures[currentSlot]);
			GL20.glUniform1i(location, TEXTURE_UNIT);
			RenderSystem.activeTexture(GL13.GL_TEXTURE0);
		}

		void reset() { matrices = 0; }

		private void ensureCapacity(int required) {
			if (data.capacity() >= required) return;
			int capacity = Integer.highestOneBit(required - 1) << 1;
			ByteBuffer grown = MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
			ByteBuffer old = data.duplicate();
			old.position(0).limit(matrices * MATRIX_BYTES);
			grown.put(old);
			MemoryUtil.memFree(data);
			data = grown;
		}

		@Override public void close() {
			for (int i = 0; i < buffers.length; i++) {
				if (buffers[i] != 0) GL15.glDeleteBuffers(buffers[i]);
				if (textures[i] != 0) GL11.glDeleteTextures(textures[i]);
				buffers[i] = 0;
				textures[i] = 0;
			}
			gpuBytes -= (long)gpuCapacityBytes * buffers.length;
			gpuCapacityBytes = 0;
			currentSlot = -1;
			matrices = 0;
			if (data != null) MemoryUtil.memFree(data);
			data = MemoryUtil.memAlloc(MATRIX_BYTES * 256).order(ByteOrder.nativeOrder());
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
