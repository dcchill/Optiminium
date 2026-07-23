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
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.DecoratedPotRenderer;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.BellRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.model.Model;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.model.geom.ModelPart;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.math.Axis;
import net.optiminium.optimization.OptiminiumSettings;
import net.optiminium.OptiminiumMod;
import net.optiminium.mixin.ModelPartAccessor;
import net.optiminium.client.persistence.PersistentRenderAdapter;
import net.optiminium.client.persistence.PersistentRenderAdapterRegistry;
import net.optiminium.client.persistence.VanillaPersistentRenderAdapters;
import net.optiminium.client.persistence.PersistentEntityMetrics;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
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
import java.util.function.BiConsumer;

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
	private static final String IMPLEMENTATION_REVISION = "expanded-block-entities-v26";
	private static final int ARMOR_STAND_BUILD_BUDGET_PER_FRAME = 8;
	private static final Map<Object, CachedMesh> MESHES = new LinkedHashMap<>(32, 0.75F, true);
	private static final Map<Object, EntityPolicyKey> ENTITY_POLICY_KEYS = new HashMap<>();
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
	private static int itemFrameBackingCandidatesThisFrame;
	private static int lastItemFrameBackingCandidates;
	private static int itemFrameBackingCachedThisFrame;
	private static int lastItemFrameBackingCached;
	private static long itemFrameBackingBuilds;
	private static long itemFrameBackingFailures;
	private static int armorFeatureCandidatesThisFrame;
	private static int lastArmorFeatureCandidates;
	private static int armorFeatureCachedThisFrame;
	private static int lastArmorFeatureCached;
	private static long armorFeatureBuilds;
	private static long armorFeatureFailures;
	private static long armorWholeMeshHits;
	private static long armorBasePoseReplays;
	private static long armorFeatureMeshHits;
	private static long armorSortedFallbacks;
	private static long armorDeterministicSkips;
	private static long armorCooldownSkips;
	private static long armorFirstBuilds;
	private static long armorVanillaResidualNanos;
	private static long armorVanillaResidualSamples;
	private static int minecartModelCandidatesThisFrame;
	private static int lastMinecartModelCandidates;
	private static int minecartModelCachedThisFrame;
	private static int lastMinecartModelCached;
	private static long minecartModelBuilds;
	private static long minecartModelFailures;
	private static int frameItemCandidatesThisFrame, lastFrameItemCandidates;
	private static int frameItemCachedThisFrame, lastFrameItemCached;
	private static long frameItemBuilds, frameItemFailures;
	private static int displayBlockCandidatesThisFrame, lastDisplayBlockCandidates;
	private static int displayBlockCachedThisFrame, lastDisplayBlockCached;
	private static long displayBlockBuilds, displayBlockFailures;
	private static final ThreadLocal<ChestPartContext> CHEST_PART_CONTEXT = new ThreadLocal<>();
	private static final ThreadLocal<SignPartContext> SIGN_PART_CONTEXT = new ThreadLocal<>();
	private static final ThreadLocal<BannerPartContext> BANNER_PART_CONTEXT = new ThreadLocal<>();
	private static final ThreadLocal<MobRenderContext> MOB_RENDER_CONTEXT = new ThreadLocal<>();
	private static final ThreadLocal<ExactGroupKey> MOB_FAST_VANILLA_KEY = new ThreadLocal<>();
	private static final ThreadLocal<PartVanillaTiming> PART_VANILLA_TIMING = new ThreadLocal<>();
	private static long lastDiagnosticNanos;
	private static boolean metricsEnabled;
	private static Map<Object, Integer> candidateCountsThisFrame = new HashMap<>();
	private static Map<Object, Integer> candidateCountsLastFrame = new HashMap<>();
	private static Map<GenericFamilyKey, Integer> genericFamilyCountsThisFrame = new HashMap<>();
	private static Map<GenericFamilyKey, Integer> genericFamilyCountsLastFrame = new HashMap<>();
	private static Map<ArmorStandBoneKey, Integer> armorStandCountsThisFrame = new HashMap<>();
	private static Map<ArmorStandBoneKey, Integer> armorStandCountsLastFrame = new HashMap<>();
	private static final Map<GenericFamilyKey, GenericFamilyDiscoveryPolicy> GENERIC_FAMILY_DISCOVERY = new HashMap<>();
	private static final Map<ArmorStandBoneKey, ArmorStandFamilyState> ARMOR_STAND_FAMILIES = new HashMap<>();
	private static long armorStandDormantUntilFrame;
	private static String armorStandDormantReason = "none";
	private static final Map<Object, AdaptivePersistencePolicy> ADAPTIVE_POLICIES = new HashMap<>();
	private static final java.util.Set<Object> ACTIVE_KEYS = new java.util.HashSet<>();
	private static final Map<Object, Long> FAILED_KEYS = new HashMap<>();
	private static final Map<String, Long> ARMOR_CAPTURE_FAILURES = new HashMap<>();
	private static final LinkedHashMap<ArmorStandPoseMeshKey, CpuArmorMesh> CPU_ARMOR_MESHES = new LinkedHashMap<>();
	private static final Map<GenericSourceKey, GenericQualification> GENERIC_QUALIFICATIONS = new HashMap<>();
	private static final Map<ExactGroupKey, MobTopologyPolicy> MOB_QUALIFICATIONS = new HashMap<>();
	private static final Map<ExactGroupKey, String> MOB_FALLBACK_REASONS = new HashMap<>();
	private static final Map<UUID, CachedMobPalette> MOB_POSE_PALETTES = new HashMap<>();
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
	private static boolean genericDiscoveryActiveThisFrame;
	private static boolean armorStandPersistenceActiveThisFrame;
	private static boolean mobPersistenceActiveThisFrame;
	private static boolean modelPartContextActive;
	private static int wholeEntityCaptureDepth;
	private static final PersistenceDiscoveryCooldown PERSISTENCE_DISCOVERY_COOLDOWN =
		new PersistenceDiscoveryCooldown();
	private static final PersistenceSafetySampleGate SAFETY_SAMPLE_GATE = new PersistenceSafetySampleGate();
	private static final long GENERIC_ACTIVE_KEY_REFRESH_TICKS = 100L;
	private static final long GENERIC_INACTIVE_KEY_REFRESH_TICKS = 100L;
	private static final long FAILURE_COOLDOWN_FRAMES = 600L;
	private static final String ARMOR_STAND_REJECTION_REASON_PREFIX = "armor_stand_rejection:";
	private static final long GENERIC_SAMPLE_INTERVAL_FRAMES = 10L;
	private static final long GENERIC_VALIDATION_INTERVAL_FRAMES = 120L;
	private static final long ENTITY_VALIDATION_INTERVAL_FRAMES = Math.max(60L,
		Long.getLong("optiminium.entityValidationIntervalFrames", 1_200L));
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
	private static int armorStandBuildsThisFrame;
	private static final java.util.Set<CachedMesh> QUEUED_MESHES =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private static final Set<Object> ENTITY_ANCHORS_THIS_FRAME = new java.util.HashSet<>();
	private static final IdentityHashMap<Object, IdentityHashMap<Object, Object>> SUBMESH_TRANSFORM_OWNERS =
		new IdentityHashMap<>();
	private static final Object FRAME_BACKING_TRANSFORM = new Object();
	private static final Object FRAME_ITEM_TRANSFORM = new Object();
	private static final Object MINECART_MODEL_TRANSFORM = new Object();
	private static final Object MINECART_DISPLAY_TRANSFORM = new Object();
	private static final SharedInstanceStream SHARED_INSTANCES = new SharedInstanceStream();
	private static final ResidentTransformStream RESIDENT_TRANSFORMS = new ResidentTransformStream();
	private static final BonePaletteStream BONE_PALETTES = new BonePaletteStream();
	private static final IndirectCommandStream INDIRECT_COMMANDS = new IndirectCommandStream();

	private OptiminiumPersistentBlockEntityMeshes() {
	}

	static {
		VanillaPersistentRenderAdapters.register();
	}

	/**
	 * Attempts exact whole-render persistence for an explicitly registered entity adapter.
	 * Unknown renderers never enter capture and therefore preserve mod render-event semantics.
	 */
	public static <E extends Entity> boolean tryRenderPersistentEntity(EntityRenderer<? super E> renderer,
			E entity, float yaw, float partialTick, PoseStack instancePose, int packedLight) {
		if (!OptiminiumSettings.isEnabled() || !OptiminiumSettings.isEntityPersistenceEnabled()
				|| OptiminiumPersistentMeshShader.get() == null) return false;
		PersistentRenderAdapter<E> adapter = PersistentRenderAdapterRegistry.find(entity);
		if (adapter == null) return false;
		Object adapterFamily = adapter.familyKey(entity, renderer);
		PersistentEntityMetrics.eligible(adapterFamily);
		if (!adapter.eligible(entity)) {
			PersistentEntityMetrics.dynamicStateFallback(adapterFamily);
			return false;
		}
		EntityPolicyKey family = entityPolicyKey(adapterFamily);
		PersistentEntityMetrics.candidate(family.adapterFamily());
		recordCandidate(family);
		if (!ACTIVE_KEYS.contains(family)) return false;
		PersistentEntityMetrics.active(family.adapterFamily());
		// Keep one exact vanilla anchor per family. Besides validating behavior, this ensures
		// renderer events are not silently eliminated from an active audited family.
		boolean anchor = ENTITY_ANCHORS_THIS_FRAME.add(family);
		if (anchor) {
			PersistentEntityMetrics.anchor(family.adapterFamily());
		}
		long queueStart = queueTimingStart(family);
		Object geometry = adapter.geometryKey(entity, renderer, yaw, partialTick);
		if (geometry == null) {
			PersistentEntityMetrics.unsupportedRenderType(family.adapterFamily());
			return false;
		}
		EntityMeshKey key = new EntityMeshKey(family, geometry);
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null && policyFrame < failedUntil) return false;
		CachedMesh mesh = MESHES.get(key);
		if (mesh != null && policyFrame >= mesh.nextValidationFrame) {
			validatePersistentEntityMesh(renderer, entity, yaw, partialTick,
				packedLight, adapter.usesCapturedVertexLight(entity), key, mesh,
				family.adapterFamily());
			// The validation capture is deliberately not submitted. Let this exact instance
			// render vanilla so validation never changes visible event/layer behavior.
			return false;
		}
		if (anchor) {
			return false;
		}
		if (mesh == null) {
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				wholeEntityCaptureDepth++;
				try {
					renderer.render(entity, yaw, partialTick, new PoseStack(), capture,
						adapter.usesCapturedVertexLight(entity) ? packedLight : 0);
				} finally {
					wholeEntityCaptureDepth--;
				}
				mesh = capture.upload(key, family);
			} catch (RuntimeException exception) {
				capture.close();
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				PersistentEntityMetrics.fallback(family.adapterFamily());
				return false;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				return false;
			}
			long buildNanos = System.nanoTime() - buildStart;
			policy(family).recordBuild(buildNanos, mesh.vertices);
			PersistentEntityMetrics.build(family.adapterFamily(), buildNanos);
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		}
		int instanceLight = adapter.usesCapturedVertexLight(entity) ? -1 : packedLight;
		if (adapter.usesRevisionedTransform(entity)) {
			mesh.queueRevisionedResident(entity, instancePose.last().pose(), true, instanceLight, 0,
				adapter.transformRevision(entity, yaw, partialTick));
		} else {
			mesh.queueResident(entity, instancePose.last().pose(), true, instanceLight, 0);
		}
		recordQueueOverhead(family, queueStart);
		PersistentEntityMetrics.cached(family.adapterFamily());
		return true;
	}

	private static <E extends Entity> boolean validatePersistentEntityMesh(
			EntityRenderer<? super E> renderer, E entity, float yaw, float partialTick,
			int packedLight, boolean usesCapturedVertexLight, EntityMeshKey key,
			CachedMesh mesh, Object family) {
		CaptureSource capture = new CaptureSource();
		long fingerprint;
		try {
			wholeEntityCaptureDepth++;
			try {
				renderer.render(entity, yaw, partialTick, new PoseStack(), capture,
					usesCapturedVertexLight ? packedLight : 0);
			} finally {
				wholeEntityCaptureDepth--;
			}
			fingerprint = capture.captureFingerprint();
		} catch (RuntimeException exception) {
			capture.close();
			FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
			PersistentEntityMetrics.safetyVeto(family, "validation_exception");
			return false;
		}
		if (fingerprint != mesh.captureFingerprint) {
			if (MESHES.remove(key, mesh)) mesh.close();
			// The immutable adapter key failed to describe all emitted geometry. Do not cycle
			// rebuilds for the same unchanged state; resource/world cleanup clears this veto,
			// while a real tracked-state change naturally produces a different key.
			FAILED_KEYS.put(key, Long.MAX_VALUE);
			PersistentEntityMetrics.safetyVeto(family, "fingerprint_mismatch");
			return false;
		}
		mesh.nextValidationFrame = nextEntityValidationFrame(key);
		PersistentEntityMetrics.validationMatch(family);
		return true;
	}

	private static long nextEntityValidationFrame(Object key) {
		long stagger = Integer.toUnsignedLong(key.hashCode()) % ENTITY_VALIDATION_INTERVAL_FRAMES;
		return policyFrame + ENTITY_VALIDATION_INTERVAL_FRAMES + stagger;
	}

	/** Records sampled vanilla CPU for a registered family so adaptive activation is evidence based. */
	public static <E extends Entity> void recordPersistentEntityVanilla(EntityRenderer<? super E> renderer,
			E entity, long elapsedNanos) {
		if (elapsedNanos <= 0L || !OptiminiumSettings.isEntityPersistenceEnabled()) return;
		PersistentRenderAdapter<E> adapter = PersistentRenderAdapterRegistry.find(entity);
		if (adapter != null && adapter.eligible(entity)) {
			Object adapterFamily = adapter.familyKey(entity, renderer);
			policy(entityPolicyKey(adapterFamily)).recordVanilla(elapsedNanos, 1);
			PersistentEntityMetrics.vanilla(adapterFamily, elapsedNanos);
		}
	}

	/**
	 * Persists only an ordinary item frame's shared wooden backing. The contained item, maps,
	 * glint, frame render event and name tag remain in ItemFrameRenderer's vanilla control flow.
	 */
	public static void renderItemFrameBacking(ItemFrame frame, BakedModel model,
			PoseStack.Pose instancePose, int packedLight, int packedOverlay,
			VertexConsumer vanillaConsumer, BiConsumer<PoseStack.Pose, VertexConsumer> renderModel) {
		if (wholeEntityCaptureDepth > 0
				|| !OptiminiumSettings.isEnabled() || !OptiminiumSettings.isEntityPersistenceEnabled()
				|| OptiminiumPersistentMeshShader.get() == null
				|| frame.isInvisible()
				|| Minecraft.getInstance().shouldEntityAppearGlowing(frame)
				|| model == null || model.getClass() != SimpleBakedModel.class) {
			renderModel.accept(instancePose, vanillaConsumer);
			return;
		}
		Object policyKey = ItemFrameBackingPolicyKey.INSTANCE;
		itemFrameBackingCandidatesThisFrame++;
		recordCandidate(policyKey);
		if (!ACTIVE_KEYS.contains(policyKey) || ENTITY_ANCHORS_THIS_FRAME.add(policyKey)) {
			long start = System.nanoTime();
			renderModel.accept(instancePose, vanillaConsumer);
			policy(policyKey).recordVanilla(System.nanoTime() - start, 1);
			return;
		}
		ItemFrameBackingMeshKey key = new ItemFrameBackingMeshKey(model);
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null) {
			if (policyFrame < failedUntil) {
				renderModel.accept(instancePose, vanillaConsumer);
				return;
			}
			FAILED_KEYS.remove(key);
		}
		CachedMesh mesh = MESHES.get(key);
		if (mesh == null) {
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				VertexConsumer captureConsumer = capture.getBuffer(Sheets.solidBlockSheet());
				renderModel.accept(new PoseStack().last(), captureConsumer);
				mesh = capture.upload(key, policyKey);
			} catch (RuntimeException exception) {
				capture.close();
				itemFrameBackingFailures++;
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				renderModel.accept(instancePose, vanillaConsumer);
				return;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				renderModel.accept(instancePose, vanillaConsumer);
				return;
			}
			policy(policyKey).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			itemFrameBackingBuilds++;
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		}
		long queueStart = queueTimingStart(policyKey);
		mesh.queueStaticResident(submeshTransformOwner(frame, FRAME_BACKING_TRANSFORM),
			instancePose.pose(), true, packedLight, packedOverlay);
		itemFrameBackingCachedThisFrame++;
		recordQueueOverhead(policyKey, queueStart);
	}

	/**
	 * Persists only the immutable minecart model emitted by vanilla's final model call. Rail
	 * interpolation, entity jitter, damage wobble, display blocks and outline selection have
	 * already run and remain represented by the supplied pose, light and render type.
	 */
	public static void renderMinecartModel(AbstractMinecart cart, Model model, PoseStack instancePose,
			VertexConsumer vanillaConsumer, RenderType renderType, int packedLight, int packedOverlay,
			MinecartModelRender renderModel) {
		Object family = MinecartModelPolicyKey.INSTANCE;
		PersistentEntityMetrics.eligible("minecart_model");
		if (wholeEntityCaptureDepth > 0
				|| !OptiminiumSettings.isEnabled() || !OptiminiumSettings.isEntityPersistenceEnabled()
				|| OptiminiumPersistentMeshShader.get() == null || model == null || renderType == null
				|| Minecraft.getInstance().shouldEntityAppearGlowing(cart)
				|| !PersistentRenderTypeCompatibility.isCompatible(renderType.toString())) {
			PersistentEntityMetrics.dynamicStateFallback("minecart_model");
			renderModel.render(instancePose, vanillaConsumer, packedLight, packedOverlay);
			return;
		}
		minecartModelCandidatesThisFrame++;
		PersistentEntityMetrics.candidate("minecart_model");
		recordCandidate(family);
		boolean anchor = ENTITY_ANCHORS_THIS_FRAME.add(family);
		if (!ACTIVE_KEYS.contains(family) || anchor) {
			long start = System.nanoTime();
			renderModel.render(instancePose, vanillaConsumer, packedLight, packedOverlay);
			long elapsed = System.nanoTime() - start;
			policy(family).recordVanilla(elapsed, 1);
			PersistentEntityMetrics.vanilla("minecart_model", elapsed);
			if (anchor) PersistentEntityMetrics.anchor("minecart_model");
			return;
		}
		PersistentEntityMetrics.active("minecart_model");
		MinecartModelMeshKey key = new MinecartModelMeshKey(model, renderType);
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null && policyFrame < failedUntil) {
			renderModel.render(instancePose, vanillaConsumer, packedLight, packedOverlay);
			return;
		}
		CachedMesh mesh = MESHES.get(key);
		if (mesh != null && policyFrame >= mesh.nextValidationFrame) {
			CaptureSource validation = new CaptureSource();
			try {
				renderModel.render(new PoseStack(), validation.getBuffer(renderType), 0, packedOverlay);
				if (validation.captureFingerprint() != mesh.captureFingerprint) {
					if (MESHES.remove(key, mesh)) mesh.close();
					FAILED_KEYS.put(key, Long.MAX_VALUE);
					PersistentEntityMetrics.safetyVeto("minecart_model", "fingerprint_mismatch");
				} else {
					mesh.nextValidationFrame = nextEntityValidationFrame(key);
					PersistentEntityMetrics.validationMatch("minecart_model");
				}
			} catch (RuntimeException exception) {
				validation.close();
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				PersistentEntityMetrics.safetyVeto("minecart_model", "validation_exception");
			}
			renderModel.render(instancePose, vanillaConsumer, packedLight, packedOverlay);
			return;
		}
		if (mesh == null) {
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				renderModel.render(new PoseStack(), capture.getBuffer(renderType), 0, packedOverlay);
				mesh = capture.upload(key, family);
			} catch (RuntimeException exception) {
				capture.close();
				minecartModelFailures++;
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				PersistentEntityMetrics.fallback("minecart_model");
				renderModel.render(instancePose, vanillaConsumer, packedLight, packedOverlay);
				return;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				renderModel.render(instancePose, vanillaConsumer, packedLight, packedOverlay);
				return;
			}
			long buildNanos = System.nanoTime() - buildStart;
			policy(family).recordBuild(buildNanos, mesh.vertices);
			PersistentEntityMetrics.build("minecart_model", buildNanos);
			minecartModelBuilds++;
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		}
		long queueStart = queueTimingStart(family);
		mesh.queueResident(submeshTransformOwner(cart, MINECART_MODEL_TRANSFORM),
			instancePose.last().pose(), true, packedLight, packedOverlay);
		recordQueueOverhead(family, queueStart);
		minecartModelCachedThisFrame++;
		PersistentEntityMetrics.cached("minecart_model");
	}

	@FunctionalInterface
	public interface MinecartModelRender {
		void render(PoseStack pose, VertexConsumer consumer, int packedLight, int packedOverlay);
	}

	/** Persists an audited vanilla frame item's resolved fixed-display model. */
	public static void renderItemFrameItem(ItemFrame frame, ItemRenderer renderer, ItemStack stack,
			PoseStack instancePose, MultiBufferSource vanillaBuffers, int packedLight, int packedOverlay,
			Level level, int modelSeed, ItemFrameItemRender renderItem) {
		Object family = ItemFrameItemPolicyKey.INSTANCE;
		if (wholeEntityCaptureDepth > 0 || !OptiminiumSettings.isEnabled()
				|| !OptiminiumSettings.isEntityPersistenceEnabled()
				|| OptiminiumPersistentMeshShader.get() == null || stack.isEmpty() || stack.hasFoil()
				|| !BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft")) {
			renderItem.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			return;
		}
		BakedModel model = renderer.getModel(stack, level, null, modelSeed);
		if (model == null || model.isCustomRenderer() || model.getClass() != SimpleBakedModel.class) {
			renderItem.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			return;
		}
		frameItemCandidatesThisFrame++;
		recordCandidate(family);
		if (!ACTIVE_KEYS.contains(family) || ENTITY_ANCHORS_THIS_FRAME.add(family)) {
			long start = System.nanoTime();
			renderItem.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			policy(family).recordVanilla(System.nanoTime() - start, 1);
			return;
		}
		ItemFrameItemMeshKey key = new ItemFrameItemMeshKey(model, stack);
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null && policyFrame < failedUntil) {
			renderItem.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			return;
		}
		CachedMesh mesh = MESHES.get(key);
		if (mesh != null && policyFrame >= mesh.nextValidationFrame) {
			CaptureSource validation = new CaptureSource();
			try {
				renderItem.render(new PoseStack(), validation, 0, packedOverlay);
				if (validation.captureFingerprint() != mesh.captureFingerprint) {
					if (MESHES.remove(key, mesh)) mesh.close();
					FAILED_KEYS.put(key, Long.MAX_VALUE);
				} else mesh.nextValidationFrame = nextEntityValidationFrame(key);
			} catch (RuntimeException exception) {
				validation.close();
				FAILED_KEYS.put(key, Long.MAX_VALUE);
			}
			renderItem.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			return;
		}
		if (mesh == null) {
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				renderItem.render(new PoseStack(), capture, 0, packedOverlay);
				mesh = capture.upload(key, family);
			} catch (RuntimeException exception) {
				capture.close();
				frameItemFailures++;
				FAILED_KEYS.put(key, Long.MAX_VALUE);
				recordFallback();
				renderItem.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
				return;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, Long.MAX_VALUE);
				renderItem.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
				return;
			}
			policy(family).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			frameItemBuilds++;
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		}
		long queueStart = queueTimingStart(family);
		mesh.queueResident(submeshTransformOwner(frame, FRAME_ITEM_TRANSFORM),
			instancePose.last().pose(), true, packedLight, packedOverlay);
		recordQueueOverhead(family, queueStart);
		frameItemCachedThisFrame++;
	}

	@FunctionalInterface
	public interface ItemFrameItemRender {
		void render(PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay);
	}

	/** Persists the deterministic model block displayed by ordinary/hopper minecarts. */
	public static void renderMinecartDisplayBlock(AbstractMinecart cart, BlockRenderDispatcher renderer,
			BlockState state, PoseStack instancePose, MultiBufferSource vanillaBuffers,
			int packedLight, int packedOverlay, MinecartDisplayBlockRender renderBlock) {
		Object family = MinecartDisplayBlockPolicyKey.INSTANCE;
		if (wholeEntityCaptureDepth > 0 || !OptiminiumSettings.isEnabled()
				|| !OptiminiumSettings.isEntityPersistenceEnabled()
				|| OptiminiumPersistentMeshShader.get() == null
				|| state.getRenderShape() != RenderShape.MODEL) {
			renderBlock.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			return;
		}
		BakedModel model = renderer.getBlockModel(state);
		displayBlockCandidatesThisFrame++;
		recordCandidate(family);
		if (!ACTIVE_KEYS.contains(family) || ENTITY_ANCHORS_THIS_FRAME.add(family)) {
			long start = System.nanoTime();
			renderBlock.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			policy(family).recordVanilla(System.nanoTime() - start, 1);
			return;
		}
		MinecartDisplayBlockMeshKey key = new MinecartDisplayBlockMeshKey(state, model);
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null && policyFrame < failedUntil) {
			renderBlock.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			return;
		}
		CachedMesh mesh = MESHES.get(key);
		if (mesh != null && policyFrame >= mesh.nextValidationFrame) {
			CaptureSource validation = new CaptureSource();
			try {
				renderBlock.render(new PoseStack(), validation, 0, packedOverlay);
				if (validation.captureFingerprint() != mesh.captureFingerprint) {
					if (MESHES.remove(key, mesh)) mesh.close();
					FAILED_KEYS.put(key, Long.MAX_VALUE);
				} else mesh.nextValidationFrame = nextEntityValidationFrame(key);
			} catch (RuntimeException exception) {
				validation.close();
				FAILED_KEYS.put(key, Long.MAX_VALUE);
			}
			renderBlock.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
			return;
		}
		if (mesh == null) {
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				renderBlock.render(new PoseStack(), capture, 0, packedOverlay);
				mesh = capture.upload(key, family);
			} catch (RuntimeException exception) {
				capture.close();
				displayBlockFailures++;
				FAILED_KEYS.put(key, Long.MAX_VALUE);
				recordFallback();
				renderBlock.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
				return;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, Long.MAX_VALUE);
				renderBlock.render(instancePose, vanillaBuffers, packedLight, packedOverlay);
				return;
			}
			policy(family).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			displayBlockBuilds++;
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		}
		long queueStart = queueTimingStart(family);
		mesh.queueResident(submeshTransformOwner(cart, MINECART_DISPLAY_TRANSFORM),
			instancePose.last().pose(), true, packedLight, packedOverlay);
		recordQueueOverhead(family, queueStart);
		displayBlockCachedThisFrame++;
	}

	@FunctionalInterface
	public interface MinecartDisplayBlockRender {
		void render(PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay);
	}

	public static <E extends BlockEntity> boolean tryRender(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, PoseStack instancePose, int packedLight, int packedOverlay) {
		if (!isPersistenceActive()) {
			return false;
		}
		lastGenericEntity = null;
		lastGenericKey = null;
		if (!genericDiscoveryActiveThisFrame && !isAuditedPersistenceType(blockEntity.getType())) {
			genericFamilyDormantSkips++;
			return false;
		}
		// Signs already have a safer audited split path which persists only the static board.
		// Running the whole renderer through generic qualification as well serializes sign NBT
		// and captures text geometry that the split path deliberately leaves to vanilla.
		if (isAuditedSignRenderer(renderer, blockEntity)) return false;
		// Banner cloth and its pattern layers are animated/sorted. Persist only the static pole
		// and crossbar through the audited split renderer below.
		if (isAuditedBannerRenderer(renderer, blockEntity)) return false;
		if (!isAuditedVanillaRenderer(renderer, blockEntity)) return tryRenderGeneric(renderer,
			blockEntity, partialTick, instancePose, packedLight, packedOverlay);
		Object variant = supportedVariant(blockEntity, partialTick);
		if (variant == null) {
			// Player skins use a sorted translucent layer, while powered dragon and piglin heads
			// animate. Neither may fall through to whole-renderer generic capture.
			if (isAuditedSkullRenderer(renderer, blockEntity)) return false;
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
		if (!genericDiscoveryActiveThisFrame) {
			genericFamilyDormantSkips++;
			return false;
		}
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
		Object policyKey = ArmorStandWholePolicyKey.INSTANCE;
		PersistentEntityMetrics.eligible("armor_stand_whole");
		if (!isArmorStandWholeCacheEnabled() || !isArmorStandPersistenceActive()
				|| !isStableArmorStand(armorStand)) {
			PersistentEntityMetrics.dynamicStateFallback("armor_stand_whole");
			return false;
		}
		// Do not reject invisible decorative stands as a class. Most emit only exact unsorted
		// equipment/feature geometry and dominate dense builds. If a player-visible body really
		// emits a sorted translucent layer, CaptureSource rejects that exact key and vanilla wins.
		recordCandidate(policyKey);
		if (!ACTIVE_KEYS.contains(policyKey)) return false;
		PersistentEntityMetrics.active("armor_stand_whole");
		if (ENTITY_ANCHORS_THIS_FRAME.add(policyKey)) {
			PersistentEntityMetrics.anchor("armor_stand_whole");
			return false;
		}
		ArmorStandKey key = armorStandKey(armorStand);
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null) {
			if (policyFrame < failedUntil) {
				if (failedUntil == Long.MAX_VALUE) armorDeterministicSkips++;
				else armorCooldownSkips++;
				return false;
			}
			FAILED_KEYS.remove(key);
		}
		CachedMesh mesh = MESHES.get(key);
		if (mesh == null) {
			if (armorStandBuildsThisFrame >= ARMOR_STAND_BUILD_BUDGET_PER_FRAME) return false;
			armorStandBuildsThisFrame++;
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				wholeEntityCaptureDepth++;
				try {
					renderer.render(armorStand, 0.0F, partialTick, new PoseStack(), capture, packedLight);
				} finally {
					wholeEntityCaptureDepth--;
				}
				mesh = capture.upload(key, policyKey);
			} catch (RuntimeException exception) {
				capture.close();
				String reason = captureFailureReason(exception);
				ARMOR_CAPTURE_FAILURES.merge(reason, 1L, Long::sum);
				if (reason.startsWith("sorted:")) armorSortedFallbacks++;
				FAILED_KEYS.put(key, ArmorStandOptimizationPolicy.isDeterministicCaptureFailure(reason)
					? Long.MAX_VALUE : policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				return false;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, policyFrame + FAILURE_COOLDOWN_FRAMES);
				return false;
			}
			policy(policyKey).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			MESHES.put(key, mesh);
			armorFirstBuilds++;
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		}
		long queueStart = queueTimingStart(policyKey);
		instancePose.pushPose();
		instancePose.mulPose(Axis.YP.rotationDegrees(-yaw));
		mesh.queueResident(armorStand, instancePose.last().pose(), false,
			packedLight, OverlayTexture.NO_OVERLAY);
		instancePose.popPose();
		recordQueueOverhead(policyKey, queueStart);
		PersistentEntityMetrics.cached("armor_stand_whole");
		armorWholeMeshHits++;
		return true;
	}

	public static void recordArmorStandVanilla(ArmorStand armorStand, long elapsedNanos) {
		if (isArmorStandWholeCacheEnabled() && isArmorStandPersistenceActive()
				&& isStableArmorStand(armorStand)) {
			policy(ArmorStandWholePolicyKey.INSTANCE).recordVanilla(elapsedNanos, 1);
			PersistentEntityMetrics.vanilla("armor_stand_whole", elapsedNanos);
			armorVanillaResidualNanos += Math.max(0L, elapsedNanos);
			armorVanillaResidualSamples++;
		}
	}

	/**
	 * Caches only the exact opaque/cutout armor feature output. The translucent wooden body
	 * remains in vanilla's globally sorted buffer, preserving invisible-stand blending order.
	 */
	public static void renderArmorStandFeatureLayer(RenderLayer<?, ?> layer, ArmorStand stand,
			PoseStack instancePose, MultiBufferSource vanillaBuffers, int packedLight,
			java.util.function.BiConsumer<PoseStack, MultiBufferSource> renderLayer) {
		if (wholeEntityCaptureDepth > 0
				|| !isAuditedArmorStandFeatureLayer(layer)
				|| !isArmorStandWholeCacheEnabled() || !isArmorStandPersistenceActive()
				|| !isStableArmorStandFeatures(stand)) {
			renderLayer.accept(instancePose, vanillaBuffers);
			return;
		}
		ArmorStandFeaturePolicyKey policyKey = new ArmorStandFeaturePolicyKey(layer.getClass());
		armorFeatureCandidatesThisFrame++;
		recordCandidate(policyKey);
		if (!ACTIVE_KEYS.contains(policyKey) || ENTITY_ANCHORS_THIS_FRAME.add(policyKey)) {
			long start = System.nanoTime();
			renderLayer.accept(instancePose, vanillaBuffers);
			policy(policyKey).recordVanilla(System.nanoTime() - start, 1);
			return;
		}
		ArmorStandOptimizationPolicy.FeatureKey key = new ArmorStandOptimizationPolicy.FeatureKey(layer,
			armorStandKey(stand));
		Long failedUntil = FAILED_KEYS.get(key);
		if (failedUntil != null) {
			if (policyFrame < failedUntil) {
				if (failedUntil == Long.MAX_VALUE) armorDeterministicSkips++;
				else armorCooldownSkips++;
				renderLayer.accept(instancePose, vanillaBuffers);
				return;
			}
			FAILED_KEYS.remove(key);
		}
		CachedMesh mesh = MESHES.get(key);
		if (mesh == null) {
			if (armorStandBuildsThisFrame >= ARMOR_STAND_BUILD_BUDGET_PER_FRAME) {
				renderLayer.accept(instancePose, vanillaBuffers);
				return;
			}
			armorStandBuildsThisFrame++;
			long buildStart = System.nanoTime();
			CaptureSource capture = new CaptureSource();
			try {
				renderLayer.accept(new PoseStack(), capture);
				mesh = capture.upload(key, policyKey);
			} catch (RuntimeException exception) {
				capture.close();
				armorFeatureFailures++;
				String reason = captureFailureReason(exception);
				ARMOR_CAPTURE_FAILURES.merge("feature:" + reason, 1L, Long::sum);
				if (reason.startsWith("sorted:")) armorSortedFallbacks++;
				FAILED_KEYS.put(key, ArmorStandOptimizationPolicy.isDeterministicCaptureFailure(reason)
					? Long.MAX_VALUE : policyFrame + FAILURE_COOLDOWN_FRAMES);
				recordFallback();
				renderLayer.accept(instancePose, vanillaBuffers);
				return;
			}
			if (mesh == null) {
				FAILED_KEYS.put(key, Long.MAX_VALUE);
				renderLayer.accept(instancePose, vanillaBuffers);
				return;
			}
			policy(policyKey).recordBuild(System.nanoTime() - buildStart, mesh.vertices);
			armorFeatureBuilds++;
			armorFirstBuilds++;
			MESHES.put(key, mesh);
			gpuBytes += mesh.bytes;
			recordUpload();
			evictIfNeeded();
		}
		long queueStart = queueTimingStart(policyKey);
		// LivingEntityRenderer supplies a camera-relative pose. Even a stationary armor stand's
		// matrix changes as the camera moves, so this must be observed every frame rather than
		// treated as a static resident transform.
		mesh.queueResident(submeshTransformOwner(stand, layer),
			instancePose.last().pose(), false, packedLight, OverlayTexture.NO_OVERLAY);
		recordQueueOverhead(policyKey, queueStart);
		armorFeatureCachedThisFrame++;
		armorFeatureMeshHits++;
	}

	private static boolean isAuditedArmorStandFeatureLayer(RenderLayer<?, ?> layer) {
		// Limit automatic replacement to Minecraft's known LivingEntityRenderer feature stack.
		// Modded/custom layers retain their Java traversal and event behavior unless adapted later.
		return layer.getClass().getName().startsWith("net.minecraft.client.renderer.entity.layers.");
	}

	private static boolean isStableArmorStandFeatures(ArmorStand stand) {
		// Custom-name rendering happens after these layers in EntityRenderer and remains vanilla.
		return stand.isAlive() && !stand.displayFireAnimation()
			&& !Minecraft.getInstance().shouldEntityAppearGlowing(stand)
			&& stand.level().getGameTime() - stand.lastHit >= 5L;
	}

	private static ArmorStandKey armorStandKey(ArmorStand stand) {
		PersistentArmorStandKeyHolder holder = (PersistentArmorStandKeyHolder)(Object)stand;
		long revision = holder.optiminium$getPersistentArmorRevision();
		Object cachedValue = holder.optiminium$getPersistentArmorKeyCache();
		if (cachedValue instanceof ArmorStandKeyCache cached && cached.revision == revision) {
			return cached.key;
		}
		ArmorStandKey key = new ArmorStandKey(stand);
		holder.optiminium$setPersistentArmorKeyCache(new ArmorStandKeyCache(revision, key));
		return key;
	}

	private static String captureFailureReason(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
		if (message.startsWith("Sorted render type: ")) {
			String type = message.substring("Sorted render type: ".length());
			return "sorted:" + type.substring(0, Math.min(120, type.length()));
		}
		if (message.startsWith("Incompatible persistent mesh primitive")) return "primitive_mode";
		if (message.startsWith("Incompatible persistent mesh vertex format")) return "vertex_format";
		if (message.startsWith("Incompatible persistent mesh shader/state")) return "shader_state";
		return exception.getClass().getSimpleName() + ":" + message.substring(0, Math.min(80, message.length()));
	}

	/** Avoids all persistence bookkeeping while armor-stand persistence is under a safety cooldown. */
	public static boolean shouldEvaluateArmorStand() {
		return armorStandDormantUntilFrame <= policyFrame;
	}

	public static boolean isArmorStandWholeCacheEnabled() {
		return OptiminiumSettings.isEntityPersistenceEnabled()
			&& OptiminiumSettings.isArmorStandPersistenceEnabled();
	}

	/** Keeps the complete-render trial isolated from the older per-part experiment. */
	public static boolean isWholeArmorStandPolicySelected() {
		return ACTIVE_KEYS.contains(ArmorStandWholePolicyKey.INSTANCE);
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
			MultiBufferSource delegate, PoseStack instancePose) {
		if (delegate instanceof CaptureSource || !isArmorStandPersistenceActive()
				|| armorStandDormantUntilFrame > policyFrame
				|| !isStableArmorStandModel(stand)) {
			PersistentEntityMetrics.dynamicStateFallback("armor_stand_parts");
			return delegate;
		}
		PersistentEntityMetrics.eligible("armor_stand_parts");
		ArmorStandBoneKey key = new ArmorStandBoneKey(stand.getType(), renderer.getClass(), stand.isSmall(),
			stand.isShowArms(), stand.isNoBasePlate(), armorStandTexture(renderer, stand));
		// The GPU bone-palette path remains faster than CPU pose replay in the fixed-world benchmark.
		// Keep exact per-part matrices on the existing adaptive atlas path.
		return beginExactModel(stand.getUUID(), key, delegate, false);
	}

	private static MultiBufferSource beginExactModel(UUID entityId, ExactGroupKey key,
			MultiBufferSource delegate, boolean candidateAlreadyRecorded) {
		return beginExactModel(entityId, key, delegate, candidateAlreadyRecorded, null, null, null);
	}

	private static MultiBufferSource beginExactModel(UUID entityId, ExactGroupKey key,
			MultiBufferSource delegate, boolean candidateAlreadyRecorded, Object staticOwner,
			Matrix4f staticInstancePose, ArmorStandPoseMeshKey staticMeshKey) {
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
		if (staticOwner != null && staticInstancePose != null && staticMeshKey != null) {
			context.staticOwner = staticOwner;
			context.staticInstancePose = new Matrix4f(staticInstancePose);
			context.staticRootInverse = new Matrix4f(staticInstancePose).invert();
			context.staticMeshKey = staticMeshKey;
		}
		MOB_RENDER_CONTEXT.set(context);
		modelPartContextActive = true;
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

	/** True only while the current mob's exact base-model topology is selected for persistence. */
	public static boolean isCurrentMobAnimationThrottleEligible() {
		MobRenderContext context = MOB_RENDER_CONTEXT.get();
		return context != null && context.persist && context.key instanceof MobGroupKey;
	}

	/** Marks a successfully restored model pose for cumulative bone-palette replay. */
	public static void markCurrentMobPoseReused() {
		MobRenderContext context = MOB_RENDER_CONTEXT.get();
		if (context != null && context.persist && context.key instanceof MobGroupKey) context.reusePose = true;
	}

	/** Replays cached cumulative bones and skips base-model traversal when exact topology still matches. */
	public static boolean tryRenderReusedMobBaseModel(PoseStack poseStack, int packedLight,
			int packedOverlay, int color) {
		MobRenderContext context = MOB_RENDER_CONTEXT.get();
		if (context == null) return false;
		context.baseModelPose = new Matrix4f(poseStack.last().pose());
		if (!context.reusePose || color != -1 || context.primaryRenderType == null) return false;
		CachedMesh mesh = MESHES.get(new MobAtlasKey(context.key));
		CachedMobPalette palette = MOB_POSE_PALETTES.get(context.entityId);
		if (mesh == null || palette == null || !palette.key.equals(context.key)
				|| palette.matrices.size() != mesh.boneCount) {
			context.reusePose = false;
			MOB_POSE_PALETTES.remove(context.entityId);
			mobFallbacks++;
			OptiminiumMobAnimationThrottler.recordCompatibilityFallback();
			return false;
		}
		context.paletteOffset = BONE_PALETTES.matrixCount();
		for (Matrix4f matrix : palette.matrices) {
			BONE_PALETTES.append(new Matrix4f(context.baseModelPose).mul(matrix));
		}
		context.poseCount = palette.matrices.size();
		context.compatibleParts = context.poseCount;
		context.suppressedParts = context.poseCount;
		context.packedLight = packedLight;
		context.packedOverlay = packedOverlay;
		palette.lastSeenFrame = policyFrame;
		mobPartsSuppressed += context.poseCount;
		mobBoneUploads += context.poseCount;
		OptiminiumMobAnimationThrottler.recordDirectPaletteReuse();
		return true;
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
		modelPartContextActive = false;
		if (context == null) {
			ExactGroupKey key = MOB_FAST_VANILLA_KEY.get();
			MOB_FAST_VANILLA_KEY.remove();
			if (key != null) policy(key).recordVanilla(elapsedNanos, 1);
			return;
		}
		if (context.partPersistence) return;
		MobTopologyPolicy qualification = MOB_QUALIFICATIONS.get(context.key);
		if (qualification != null && context.compatibleParts > 0 && !context.reusePose) {
			boolean wasQualified = qualification.qualified();
			qualification.observe(context.topologyHash, context.entityId, policyFrame);
			if (wasQualified && !qualification.qualified()) {
				removeMeshesForPolicy(context.key);
				mobFallbacks++;
				MOB_FALLBACK_REASONS.put(context.key, "topology_changed");
			}
		}
		if (context.staticMeshKey != null) {
			CpuArmorMesh mesh = CPU_ARMOR_MESHES.get(context.staticMeshKey);
			if (mesh == null && context.cpuCapture != null && !context.cpuCapture.vertices.isEmpty()) {
				mesh = new CpuArmorMesh(List.copyOf(context.cpuCapture.vertices), context.primaryRenderType);
				CPU_ARMOR_MESHES.put(context.staticMeshKey, mesh);
				while (CPU_ARMOR_MESHES.size() > 256) {
					CPU_ARMOR_MESHES.remove(CPU_ARMOR_MESHES.keySet().iterator().next());
				}
			}
			if (context.suppressedParts > 0) {
				MOB_FALLBACK_REASONS.remove(context.key);
				policy(context.key).recordPersistent(elapsedNanos, 1);
			} else {
				policy(context.key).recordVanilla(elapsedNanos, 1);
			}
			return;
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
				if (!context.reusePose) mobBoneUploads += context.poseCount;
				if (!context.reusePose && context.baseModelPose != null
						&& context.capturedPoseMatrices.size() == context.poseCount) {
					Matrix4f inverseBase = new Matrix4f(context.baseModelPose).invert();
					List<Matrix4f> localMatrices = context.capturedPoseMatrices.stream()
						.map(matrix -> new Matrix4f(inverseBase).mul(matrix)).toList();
					MOB_POSE_PALETTES.put(context.entityId, new CachedMobPalette(context.key,
						localMatrices, policyFrame));
				}
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
		if (context.staticMeshKey != null) {
			return tryRenderStaticArmorStandPart(context, part, pose, consumer, renderType,
				packedLight, packedOverlay, color);
		}
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
		context.capturedPoseMatrices.add(new Matrix4f(pose.pose()));
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

	private static boolean tryRenderStaticArmorStandPart(MobRenderContext context, ModelPart part,
			PoseStack.Pose pose, VertexConsumer consumer, RenderType renderType,
			int packedLight, int packedOverlay, int color) {
		if (renderType == null || renderType.sortOnUpload()
				|| renderType.format() != DefaultVertexFormat.NEW_ENTITY
				|| !isCompatiblePersistentRenderType(renderType)
				|| renderType != context.primaryRenderType || color != -1) {
			mobVanillaParts++;
			return false;
		}
		context.compatibleParts++;
		context.mixTopology(part, renderType, true);
		if (!context.persist) {
			mobVanillaParts++;
			return false;
		}
		CpuArmorMesh mesh = CPU_ARMOR_MESHES.get(context.staticMeshKey);
		context.packedLight = packedLight;
		context.packedOverlay = packedOverlay;
		if (mesh == null) {
			if (context.cpuCapture == null) context.cpuCapture = new RecordingVertexConsumer();
			try {
				Matrix4f localPose = new Matrix4f(context.staticRootInverse).mul(pose.pose());
				PoseStack normalized = new PoseStack();
				normalized.mulPose(localPose);
				for (ModelPart.Cube cube : ((ModelPartAccessor)(Object)part).optiminium$getCubes()) {
					cube.compile(normalized.last(), context.cpuCapture,
						NORMALIZED_CAPTURE_LIGHT, packedOverlay, color);
				}
			} catch (RuntimeException exception) {
				context.atlasCompatible = false;
				context.fallbackReason = "static_pose_capture_incompatible";
				MOB_FALLBACK_REASONS.put(context.key, context.fallbackReason);
				mobFallbacks++;
			}
			// The build frame remains vanilla; suppression begins only after the complete pose mesh exists.
			return false;
		}
		if (!context.staticReplayComplete) {
			mesh.replay(consumer, context.staticInstancePose, packedLight, packedOverlay);
			context.staticReplayComplete = true;
			armorBasePoseReplays++;
		}
		context.suppressedParts++;
		mobPartsSuppressed++;
		return true;
	}

	public static boolean hasActiveModelPartContext() {
		return modelPartContextActive;
	}

	private static boolean tryRenderArmorStandPart(MobRenderContext context, ModelPart part,
			PoseStack.Pose pose, RenderType renderType, int packedLight, int packedOverlay, int color) {
		if (renderType == null || renderType.sortOnUpload()
				|| renderType.format() != DefaultVertexFormat.NEW_ENTITY
				|| !isCompatiblePersistentRenderType(renderType)) {
			mobVanillaParts++;
			if (context.key instanceof ArmorStandBoneKey) {
				PersistentEntityMetrics.unsupportedRenderType("armor_stand_parts");
			}
			return false;
		}
		ArmorStandPartKey key = context.armorStandFamily.partKey(part, renderType);
		ArmorStandLayerKey policyKey = key.policyKey;
		recordCandidate(policyKey);
		int timingStride = PersistenceTimingSampler.queueStrideForCount(
			candidateCountsLastFrame.getOrDefault(policyKey, 0));
		boolean sampleTiming = PersistenceTimingSampler.shouldSample(++armorPartTimingCounter, timingStride);
		long start = sampleTiming ? System.nanoTime() : 0L;
		PersistentEntityMetrics.active("armor_stand_parts");
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
		PersistentEntityMetrics.anchor("armor_stand_parts");
		mesh.queueDirectBone(packedLight, packedOverlay, color, pose.pose());
		PersistentEntityMetrics.cached("armor_stand_parts");
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
		// Invisible/marker stands still render equipped head/hand feature layers and are
		// especially common in decorative worlds. A complete-render capture preserves
		// that exact output without ever introducing the hidden wooden base model.
		return stand.isAlive() && !stand.isCustomNameVisible() && !stand.displayFireAnimation()
			&& !Minecraft.getInstance().shouldEntityAppearGlowing(stand)
			&& stand.level().getGameTime() - stand.lastHit >= 5L;
	}

	private static String armorStandRejectionReason(ArmorStand stand) {
		if (!stand.isAlive()) return "not_alive";
		if (stand.isMarker()) return "marker";
		if (stand.isInvisible()) return "invisible";
		if (stand.isCustomNameVisible()) return "custom_name_visible";
		if (stand.displayFireAnimation()) return "fire_animation";
		if (Minecraft.getInstance().shouldEntityAppearGlowing(stand)) return "glowing";
		if (stand.level().getGameTime() - stand.lastHit < 5L) return "recent_hit";
		return "unknown";
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

	private static boolean isArmorStandPersistenceActive() {
		return armorStandPersistenceActiveThisFrame;
	}

	public static void onFrameStart() {
		RenderSystem.assertOnRenderThread();
		persistenceConfiguredThisFrame = OptiminiumSettings.isEnabled()
			&& OptiminiumSettings.isBlockEntityRenderCache()
			&& OptiminiumSettings.isBlockEntityPersistenceEnabled()
			&& !Boolean.getBoolean("optiminium.persistentMeshBenchmarkMobs")
			&& OptiminiumPersistentMeshShader.get() != null;
		persistenceActiveThisFrame = persistenceConfiguredThisFrame;
		genericDiscoveryActiveThisFrame = PERSISTENCE_DISCOVERY_COOLDOWN.beginFrame(
			policyFrame, persistenceConfiguredThisFrame);
		armorStandPersistenceActiveThisFrame = OptiminiumSettings.isEnabled()
			&& OptiminiumSettings.isArmorStandPersistenceEnabled()
			&& OptiminiumPersistentMeshShader.get() != null;
		mobPersistenceActiveThisFrame = OptiminiumSettings.isEnabled()
			&& OptiminiumSettings.isMobPersistenceEnabled()
			&& OptiminiumPersistentMeshShader.get() != null;
		if (atlasActiveStatePendingUnbind) finishAtlasStateAfterVanillaDraw();
		if (!persistenceConfiguredThisFrame && !isArmorStandPersistenceActive() && !isMobPersistenceActive()
				&& (!MESHES.isEmpty() || !ADAPTIVE_POLICIES.isEmpty() || gpuBytes > 0L)) clear();
		if (!isMobPersistenceActive() && hasMobQualificationState()) clearMobState();
		if (!isArmorStandPersistenceActive() && hasArmorStandState()) clearArmorStandState();
		if (!persistenceConfiguredThisFrame && (isArmorStandPersistenceActive() || isMobPersistenceActive())
				&& hasBlockEntityState()) clearBlockEntityState();
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
		ENTITY_ANCHORS_THIS_FRAME.clear();
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
		lastItemFrameBackingCandidates = itemFrameBackingCandidatesThisFrame;
		lastItemFrameBackingCached = itemFrameBackingCachedThisFrame;
		lastArmorFeatureCandidates = armorFeatureCandidatesThisFrame;
		lastArmorFeatureCached = armorFeatureCachedThisFrame;
		lastMinecartModelCandidates = minecartModelCandidatesThisFrame;
		lastMinecartModelCached = minecartModelCachedThisFrame;
		lastFrameItemCandidates = frameItemCandidatesThisFrame;
		lastFrameItemCached = frameItemCachedThisFrame;
		lastDisplayBlockCandidates = displayBlockCandidatesThisFrame;
		lastDisplayBlockCached = displayBlockCachedThisFrame;
		long frameNow = System.nanoTime();
		long frameNanos = lastPolicyFrameNanos == 0L ? 0L : frameNow - lastPolicyFrameNanos;
		lastPolicyFrameNanos = frameNow;
		long gpuNanos = OptiminiumGpuTimer.getLatestGpuNanos();
		var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		var cameraPosition = camera.getPosition();
		boolean representativeSafetyFrame = SAFETY_SAMPLE_GATE.observe(cameraPosition.x, cameraPosition.y,
			cameraPosition.z, camera.getXRot(), camera.getYRot());
		for (Map.Entry<Object, AdaptivePersistencePolicy> entry : ADAPTIVE_POLICIES.entrySet()) {
			// Upload/build frames are warmup, not representative steady-state GPU evidence.
			if (entry.getKey() instanceof ArmorStandWholePolicyKey && armorStandBuildsThisFrame > 0) continue;
			// Whole-frame GPU timing cannot be attributed to one entity family. The previous
			// attribution rejected glow frames despite an 86% measured CPU saving.
			if (isEntityPolicyKey(entry.getKey())) continue;
			AdaptivePersistencePolicy value = entry.getValue();
			// Camera motion adds terrain/chunk work to these whole-frame measurements. Mixing
			// moving persistent frames with stationary vanilla frames can falsely veto a cache
			// that is providing a large isolated CPU saving.
			if (representativeSafetyFrame) {
				value.recordFrameSafety(gpuNanos, frameNanos, value.active() || value.trial());
			}
		}
		armorStandBuildsThisFrame = 0;
		applyDormantGenericFamilyCounts();
		policyFrame++;
		updateAdaptivePolicies(candidateCountsThisFrame);
		updatePersistenceDiscoveryCooldown();
		Map<Object, Integer> reusableCandidateCounts = candidateCountsLastFrame;
		candidateCountsLastFrame = candidateCountsThisFrame;
		candidateCountsThisFrame = reusableCandidateCounts;
		candidateCountsThisFrame.clear();
		Map<GenericFamilyKey, Integer> reusableFamilyCounts = genericFamilyCountsLastFrame;
		genericFamilyCountsLastFrame = genericFamilyCountsThisFrame;
		genericFamilyCountsThisFrame = reusableFamilyCounts;
		genericFamilyCountsThisFrame.clear();
		Map<ArmorStandBoneKey, Integer> reusableArmorCounts = armorStandCountsLastFrame;
		armorStandCountsLastFrame = armorStandCountsThisFrame;
		armorStandCountsThisFrame = reusableArmorCounts;
		armorStandCountsThisFrame.clear();
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
		itemFrameBackingCandidatesThisFrame = 0;
		itemFrameBackingCachedThisFrame = 0;
		armorFeatureCandidatesThisFrame = 0;
		armorFeatureCachedThisFrame = 0;
		minecartModelCandidatesThisFrame = 0;
		minecartModelCachedThisFrame = 0;
		frameItemCandidatesThisFrame = 0;
		frameItemCachedThisFrame = 0;
		displayBlockCandidatesThisFrame = 0;
		displayBlockCachedThisFrame = 0;
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
		if (blockEntity instanceof SkullBlockEntity skull
				&& skull.getBlockState().getBlock() instanceof AbstractSkullBlock skullBlock) {
			SkullBlock.Type skullType = skullBlock.getType();
			boolean vanillaType = skullType == SkullBlock.Types.SKELETON
				|| skullType == SkullBlock.Types.WITHER_SKELETON
				|| skullType == SkullBlock.Types.ZOMBIE
				|| skullType == SkullBlock.Types.CREEPER
				|| skullType == SkullBlock.Types.DRAGON
				|| skullType == SkullBlock.Types.PIGLIN
				|| skullType == SkullBlock.Types.PLAYER;
			VanillaBlockEntityPersistencePolicy.SkullGeometry geometry =
				VanillaBlockEntityPersistencePolicy.classifySkull(
					vanillaType,
					skullType == SkullBlock.Types.PLAYER,
					skullType == SkullBlock.Types.DRAGON || skullType == SkullBlock.Types.PIGLIN);
			if (!VanillaBlockEntityPersistencePolicy.supportsWholeSkullMesh(geometry)) return null;
			int animationBits = 0;
			if (VanillaBlockEntityPersistencePolicy.hasAnimatedSkullGeometry(geometry)) {
				float animation = skull.getAnimation(0.0F);
				if (Float.floatToIntBits(animation) != Float.floatToIntBits(skull.getAnimation(1.0F))) {
					return null;
				}
				animationBits = Float.floatToIntBits(animation);
			}
			return new SkullVariant(skullType, animationBits);
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
	private enum BannerPoleVariant { INSTANCE }
	private enum BannerBarVariant { INSTANCE }
	private enum StationaryBellVariant { INSTANCE }
	private record SkullVariant(SkullBlock.Type type, int animationBits) {
	}

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
		if (!genericDiscoveryActiveThisFrame && !isAuditedPersistenceType(blockEntity.getType())) {
			renderer.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
			return;
		}
		if (isAuditedSignRenderer(renderer, blockEntity) && OptiminiumPersistentMeshShader.get() != null
				&& isPersistenceActive()) {
			renderSignWithSplit(renderer, blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
			return;
		}
		if (isAuditedBannerRenderer(renderer, blockEntity) && OptiminiumPersistentMeshShader.get() != null
				&& isPersistenceActive()) {
			renderBannerWithSplit(renderer, blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
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

	private static <E extends BlockEntity> void renderBannerWithSplit(BlockEntityRenderer<E> renderer, E blockEntity,
			float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
		// Rotation and wall/standing transforms are already present in the instance pose.
		// The bar is identical for every banner; the pole is attempted only while visible.
		BannerPartContext context = new BannerPartContext(
			new MeshKey(blockEntity.getType(), 0, BannerPoleVariant.INSTANCE, renderer.getClass()),
			new MeshKey(blockEntity.getType(), 0, BannerBarVariant.INSTANCE, renderer.getClass()),
			blockEntity);
		BANNER_PART_CONTEXT.set(context);
		try {
			renderer.render(blockEntity, partialTick, poseStack, renderType -> {
				context.renderType = renderType;
				return bufferSource.getBuffer(renderType);
			}, packedLight, packedOverlay);
		} finally {
			BANNER_PART_CONTEXT.remove();
		}
	}

	/**
	 * Called for BannerRenderer's pole and crossbar only. Animated cloth and sorted pattern
	 * layers remain in vanilla control flow.
	 */
	public static boolean tryRenderBannerPart(ModelPart part, PoseStack instancePose,
			VertexConsumer vanillaConsumer, int packedLight, int packedOverlay) {
		BannerPartContext context = BANNER_PART_CONTEXT.get();
		if (context == null || context.renderType == null) return false;
		MeshKey key = context.nextPartKey();
		context.currentVanillaKey = part.visible ? key : null;
		if (key == null || !part.visible) return false;
		return tryRenderModelPart(key, context.blockEntity, context.renderType, part,
			instancePose, packedLight, packedOverlay);
	}

	/** Records the exact vanilla cost of the banner part that was not persisted. */
	public static void recordBannerPartVanilla(long elapsedNanos) {
		BannerPartContext context = BANNER_PART_CONTEXT.get();
		if (context == null || context.currentVanillaKey == null || elapsedNanos <= 0L) return;
		policy(context.currentVanillaKey).recordVanilla(elapsedNanos, 1);
		context.currentVanillaKey = null;
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
				// RenderType binds the textures, but this custom ShaderInstance still needs its
				// sampler values populated. Otherwise every sampler defaults to texture unit 0.
				shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
				shader.setSampler("Sampler1", RenderSystem.getShaderTexture(1));
				shader.setSampler("Sampler2", RenderSystem.getShaderTexture(2));
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
						pageEntry.getKey().bind(SHARED_INSTANCES.currentGpuBuffer(),
							SHARED_INSTANCES.currentDirectMatrixGpuBuffer());
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
		if (blockEntity.getType() == BlockEntityType.SKULL) {
			return renderer.getClass() == SkullBlockRenderer.class;
		}
		BlockEntityType<?> type = blockEntity.getType();
		return (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
			|| type == BlockEntityType.ENDER_CHEST) && renderer.getClass() == ChestRenderer.class;
	}

	private static boolean isAuditedPersistenceType(BlockEntityType<?> type) {
		return type == BlockEntityType.SIGN || type == BlockEntityType.HANGING_SIGN
			|| type == BlockEntityType.BANNER || type == BlockEntityType.SKULL
			|| type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
			|| type == BlockEntityType.ENDER_CHEST || type == BlockEntityType.BELL
			|| type == BlockEntityType.DECORATED_POT;
	}

	private static boolean isAuditedSignRenderer(BlockEntityRenderer<?> renderer, BlockEntity blockEntity) {
		return blockEntity.getType() == BlockEntityType.SIGN && renderer.getClass() == SignRenderer.class
			|| blockEntity.getType() == BlockEntityType.HANGING_SIGN
				&& renderer.getClass() == HangingSignRenderer.class;
	}

	private static boolean isAuditedBannerRenderer(BlockEntityRenderer<?> renderer, BlockEntity blockEntity) {
		return blockEntity.getType() == BlockEntityType.BANNER && renderer.getClass() == BannerRenderer.class;
	}

	private static boolean isAuditedSkullRenderer(BlockEntityRenderer<?> renderer, BlockEntity blockEntity) {
		return blockEntity.getType() == BlockEntityType.SKULL && renderer.getClass() == SkullBlockRenderer.class;
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

	private static final class BannerPartContext {
		final MeshKey poleKey;
		final MeshKey barKey;
		final BlockEntity blockEntity;
		RenderType renderType;
		MeshKey currentVanillaKey;
		int partIndex;

		BannerPartContext(MeshKey poleKey, MeshKey barKey, BlockEntity blockEntity) {
			this.poleKey = poleKey;
			this.barKey = barKey;
			this.blockEntity = blockEntity;
		}

		MeshKey nextPartKey() {
			return switch (partIndex++) {
				case 0 -> poleKey;
				case 1 -> barKey;
				default -> null;
			};
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
		OptiminiumMobAnimationThrottler.clear();
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
		ARMOR_CAPTURE_FAILURES.clear();
		CPU_ARMOR_MESHES.clear();
		GENERIC_QUALIFICATIONS.clear();
		MOB_QUALIFICATIONS.clear();
		MOB_FALLBACK_REASONS.clear();
		MOB_POSE_PALETTES.clear();
		MOB_ANCHORS_THIS_FRAME.clear();
		ENTITY_ANCHORS_THIS_FRAME.clear();
		BLOCK_ENTITY_ANCHORS_THIS_FRAME.clear();
		SUBMESH_TRANSFORM_OWNERS.clear();
		MOB_RENDER_CONTEXT.remove();
		modelPartContextActive = false;
		MOB_FAST_VANILLA_KEY.remove();
		candidateCountsThisFrame.clear();
		candidateCountsLastFrame.clear();
		genericFamilyCountsThisFrame.clear();
		genericFamilyCountsLastFrame.clear();
		armorStandCountsThisFrame.clear();
		armorStandCountsLastFrame.clear();
		GENERIC_FAMILY_DISCOVERY.clear();
		ARMOR_STAND_FAMILIES.clear();
		armorStandDormantUntilFrame = 0L;
		armorStandDormantReason = "none";
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
		itemFrameBackingCandidatesThisFrame = 0;
		lastItemFrameBackingCandidates = 0;
		itemFrameBackingCachedThisFrame = 0;
		lastItemFrameBackingCached = 0;
		itemFrameBackingBuilds = 0L;
		itemFrameBackingFailures = 0L;
		armorFeatureCandidatesThisFrame = 0;
		lastArmorFeatureCandidates = 0;
		armorFeatureCachedThisFrame = 0;
		lastArmorFeatureCached = 0;
		armorFeatureBuilds = 0L;
		armorFeatureFailures = 0L;
		resetArmorStandDiagnostics();
		minecartModelCandidatesThisFrame = 0;
		lastMinecartModelCandidates = 0;
		minecartModelCachedThisFrame = 0;
		lastMinecartModelCached = 0;
		minecartModelBuilds = 0L;
		minecartModelFailures = 0L;
		frameItemCandidatesThisFrame = lastFrameItemCandidates = 0;
		frameItemCachedThisFrame = lastFrameItemCached = 0;
		frameItemBuilds = frameItemFailures = 0L;
		displayBlockCandidatesThisFrame = lastDisplayBlockCandidates = 0;
		displayBlockCachedThisFrame = lastDisplayBlockCached = 0;
		displayBlockBuilds = displayBlockFailures = 0L;
		mobSummary = "none";
		armorStandSummary = "none";
		atlasStateSetups = 0L;
		atlasFallbackDraws = 0L;
		atlasIndirectDraws = 0L;
		atlasActiveStatePendingUnbind = false;
		persistenceConfiguredThisFrame = false;
		persistenceActiveThisFrame = false;
		armorStandPersistenceActiveThisFrame = false;
		mobPersistenceActiveThisFrame = false;
		PERSISTENCE_DISCOVERY_COOLDOWN.reset();
		SAFETY_SAMPLE_GATE.reset();
		lastPolicyFrameNanos = 0L;
		adaptiveSummary = "none";
		gpuBytes = 0L;
	}

	private static Object submeshTransformOwner(Object renderedObject, Object renderPart) {
		return SUBMESH_TRANSFORM_OWNERS
			.computeIfAbsent(renderedObject, ignored -> new IdentityHashMap<>())
			.computeIfAbsent(renderPart, ignored -> new Object());
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
		modelPartContextActive = false;
		MOB_FAST_VANILLA_KEY.remove();
		PART_VANILLA_TIMING.remove();
		mobSummary = "none";
	}

	private static boolean hasMobQualificationState() {
		for (ExactGroupKey key : MOB_QUALIFICATIONS.keySet()) {
			if (key instanceof MobGroupKey) return true;
		}
		return false;
	}

	private static boolean hasBlockEntityState() {
		if (!GENERIC_QUALIFICATIONS.isEmpty()) return true;
		for (Object key : ADAPTIVE_POLICIES.keySet()) {
			if (!isEntityPolicyKey(key)) return true;
		}
		for (CachedMesh mesh : MESHES.values()) {
			if (!isEntityPolicyKey(mesh.policyKey)) return true;
		}
		return false;
	}

	private static boolean hasArmorStandState() {
		if (!ARMOR_STAND_FAMILIES.isEmpty()) return true;
		for (Object key : ADAPTIVE_POLICIES.keySet()) {
			if (isArmorStandPolicyKey(key)) return true;
		}
		return false;
	}

	private static boolean isEntityPolicyKey(Object key) {
		return key instanceof EntityPolicyKey || key instanceof MobGroupKey
			|| key instanceof ItemFrameBackingPolicyKey || key instanceof ItemFrameItemPolicyKey
			|| key instanceof MinecartModelPolicyKey || key instanceof MinecartDisplayBlockPolicyKey
			|| isArmorStandPolicyKey(key);
	}

	private static boolean isArmorStandPolicyKey(Object key) {
		// Armor feature layers are independently qualified because their cutout output can be
		// batched while the invisible stand's wooden body must remain globally sorted.
		return key instanceof ArmorStandWholePolicyKey || key instanceof ArmorStandBoneKey
			|| key instanceof ArmorStandLayerKey
			|| key instanceof ArmorStandFeaturePolicyKey;
	}

	private static void clearArmorStandState() {
		for (Object key : List.copyOf(ADAPTIVE_POLICIES.keySet())) {
			if (!isArmorStandPolicyKey(key)) continue;
			removeMeshesForPolicy(key);
			ADAPTIVE_POLICIES.remove(key);
			ACTIVE_KEYS.remove(key);
		}
		ARMOR_STAND_FAMILIES.clear();
		armorStandCountsThisFrame.clear();
		armorStandCountsLastFrame.clear();
		armorStandDormantUntilFrame = 0L;
		armorStandDormantReason = "none";
		FAILED_KEYS.keySet().removeIf(key -> key instanceof ArmorStandKey
			|| key instanceof ArmorStandPartKey || key instanceof ArmorStandOptimizationPolicy.FeatureKey);
		ARMOR_CAPTURE_FAILURES.clear();
		CPU_ARMOR_MESHES.clear();
		candidateCountsThisFrame.keySet().removeIf(OptiminiumPersistentBlockEntityMeshes::isArmorStandPolicyKey);
		candidateCountsLastFrame.keySet().removeIf(OptiminiumPersistentBlockEntityMeshes::isArmorStandPolicyKey);
		MOB_RENDER_CONTEXT.remove();
		modelPartContextActive = false;
		MOB_FAST_VANILLA_KEY.remove();
		PART_VANILLA_TIMING.remove();
		armorStandSummary = "none";
		resetArmorStandDiagnostics();
	}

	private static void resetArmorStandDiagnostics() {
		armorWholeMeshHits = 0L;
		armorBasePoseReplays = 0L;
		armorFeatureMeshHits = 0L;
		armorSortedFallbacks = 0L;
		armorDeterministicSkips = 0L;
		armorCooldownSkips = 0L;
		armorFirstBuilds = 0L;
		armorVanillaResidualNanos = 0L;
		armorVanillaResidualSamples = 0L;
	}

	private static void clearBlockEntityState() {
		for (Object key : List.copyOf(ADAPTIVE_POLICIES.keySet())) {
			if (isEntityPolicyKey(key)) continue;
			removeMeshesForPolicy(key);
			ADAPTIVE_POLICIES.remove(key);
			ACTIVE_KEYS.remove(key);
		}
		GENERIC_QUALIFICATIONS.clear();
		GENERIC_FAMILY_DISCOVERY.clear();
		candidateCountsThisFrame.keySet().removeIf(key -> !isEntityPolicyKey(key));
		candidateCountsLastFrame.keySet().removeIf(key -> !isEntityPolicyKey(key));
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
		int configured = Math.max(4, Math.min(128,
			Integer.getInteger("optiminium.persistentMeshAdaptiveMinInstances",
				OptiminiumSettings.getBlockEntityPersistenceAdaptiveMinInstances())));
		// Sparse mixed scenes lose to key discovery, mesh setup and extra atlas draws even
		// when a short CPU-only trial looks promising. Persistence is a dense-scene tool:
		// never let adaptive pressure bypass the user's guaranteed density threshold.
		return Math.max(configured, densityThreshold());
	}

	private static int mobAdaptiveMinThreshold() {
		return Math.max(2, Math.min(128, Integer.getInteger("optiminium.persistentMobAdaptiveMinInstances",
			OptiminiumSettings.getMobPersistenceAdaptiveMinInstances())));
	}

	private static AdaptivePersistencePolicy policy(Object key) {
		return ADAPTIVE_POLICIES.computeIfAbsent(key, ignored -> new AdaptivePersistencePolicy());
	}

	private static EntityPolicyKey entityPolicyKey(Object adapterFamily) {
		return ENTITY_POLICY_KEYS.computeIfAbsent(adapterFamily, EntityPolicyKey::new);
	}

	private static void updateAdaptivePolicies(Map<Object, Integer> counts) {
		ACTIVE_KEYS.clear();
		boolean adaptive = OptiminiumSettings.isBlockEntityPersistenceAdaptive();
		int adaptiveMin = adaptiveMinThreshold();
		int guaranteed = densityThreshold();
		for (Map.Entry<Object, Integer> entry : counts.entrySet()) {
			AdaptivePersistencePolicy value = policy(entry.getKey());
			boolean entityPolicy = isEntityPolicyKey(entry.getKey());
			boolean mobPolicy = entry.getKey() instanceof MobGroupKey
				|| entry.getKey() instanceof ArmorStandBoneKey
				|| entry.getKey() instanceof ArmorStandWholePolicyKey
				|| entry.getKey() instanceof ArmorStandLayerKey
				|| entry.getKey() instanceof ArmorStandFeaturePolicyKey;
			int effectiveMin = mobPolicy ? mobAdaptiveMinThreshold()
				: entityPolicy ? OptiminiumSettings.getBlockEntityPersistenceAdaptiveMinInstances() : adaptiveMin;
			int effectiveGuaranteed = mobPolicy ? Integer.MAX_VALUE : guaranteed;
			if (value.beginFrame(policyFrame, entry.getValue(), mobPolicy || adaptive,
					effectiveMin, effectiveGuaranteed, entityPolicy)) {
				ACTIVE_KEYS.add(entry.getKey());
			} else if (entry.getKey() instanceof ArmorStandWholePolicyKey
					&& isHardSafetyVeto(value.reason())) {
				removeMeshesForPolicy(entry.getKey());
				armorStandDormantUntilFrame = Math.max(armorStandDormantUntilFrame,
					policyFrame + AdaptivePersistencePolicy.SAFETY_VETO_INTERVAL_FRAMES);
				armorStandDormantReason = value.reason();
			} else if (entry.getKey() instanceof ArmorStandLayerKey layerKey
					&& isHardSafetyVeto(value.reason())) {
				removeMeshesForPolicy(layerKey);
				armorStandDormantUntilFrame = Math.max(armorStandDormantUntilFrame,
					policyFrame + AdaptivePersistencePolicy.SAFETY_VETO_INTERVAL_FRAMES);
				armorStandDormantReason = value.reason();
				ArmorStandFamilyState family = ARMOR_STAND_FAMILIES.get(layerKey.family());
				if (family != null) {
					family.dormantUntilFrame = Math.max(family.dormantUntilFrame,
						policyFrame + AdaptivePersistencePolicy.SAFETY_VETO_INTERVAL_FRAMES);
					family.dormantReason = value.reason();
				}
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
		MOB_POSE_PALETTES.entrySet().removeIf(entry ->
			policyFrame - entry.getValue().lastSeenFrame > AdaptivePersistencePolicy.EXPIRE_AFTER_FRAMES);
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
		mobQualifiedGroups = 0L;
		for (Map.Entry<ExactGroupKey, MobTopologyPolicy> entry : MOB_QUALIFICATIONS.entrySet()) {
			if (entry.getKey() instanceof MobGroupKey && entry.getValue().qualified()) mobQualifiedGroups++;
		}
	}

	private static void updateDiagnosticSummaries() {
		adaptiveSummary = ADAPTIVE_POLICIES.entrySet().stream()
			.max(java.util.Comparator.comparingInt(entry -> entry.getValue().lastCount()))
			.map(entry -> {
				AdaptivePersistencePolicy value = entry.getValue();
				return String.format(Locale.ROOT, "%s count=%d mode=%s vanilla=%.0fns persistent=%.0fns trials=%d reason=%s vertices=%d builds=%d",
					keyName(entry.getKey()), value.lastCount(), value.active() ? "persistent" : value.trial() ? "trial" : "vanilla",
					value.vanillaPerInstanceNanos(), value.persistentPerInstanceNanos(), value.trials(), value.reason(), value.meshVertices(), value.builds());
			})
			.orElse("none");
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
			.filter(entry -> entry.getKey() instanceof ArmorStandWholePolicyKey
				|| entry.getKey() instanceof ArmorStandBoneKey
				|| entry.getKey() instanceof ArmorStandLayerKey)
			.max(java.util.Comparator.<Map.Entry<Object, AdaptivePersistencePolicy>>comparingInt(
				entry -> entry.getKey() instanceof ArmorStandWholePolicyKey ? 1 : 0)
				.thenComparingInt(entry -> entry.getValue().lastCount()))
			.map(entry -> {
				AdaptivePersistencePolicy value = entry.getValue();
				return String.format(Locale.ROOT, "%s count=%d mode=%s cpuVanilla=%.0fns cpuPersistent=%.0fns reason=%s builds=%d",
					keyName(entry.getKey()), value.lastCount(), value.active() ? "persistent" : value.trial() ? "trial" : "vanilla",
					value.vanillaPerInstanceNanos(), value.persistentPerInstanceNanos(), value.reason(), value.builds());
			}).orElse("none");
	}

	private static void updatePersistenceDiscoveryCooldown() {
		boolean activeOrTrial = false;
		for (Object key : ACTIVE_KEYS) {
			if (key instanceof GenericSourceKey) {
				activeOrTrial = true;
				break;
			}
		}
		boolean qualifying = false;
		for (GenericQualification value : GENERIC_QUALIFICATIONS.values()) {
			if (value.qualifying) {
				qualifying = true;
				break;
			}
		}
		boolean measuredFallback = false;
		for (Map.Entry<Object, AdaptivePersistencePolicy> entry : ADAPTIVE_POLICIES.entrySet()) {
			if (!(entry.getKey() instanceof GenericSourceKey)) continue;
			String reason = entry.getValue().reason();
			if ("gpu_safety_veto".equals(reason) || "p95_safety_veto".equals(reason)
					|| "measured_regression".equals(reason)) {
				measuredFallback = true;
				break;
			}
		}
		int minimumFamilySize = OptiminiumSettings.isBlockEntityPersistenceAdaptive()
			? adaptiveMinThreshold() : densityThreshold();
		int largestFamily = 0;
		for (int count : genericFamilyCountsThisFrame.values()) {
			if (count > largestFamily) largestFamily = count;
		}
		boolean sparseScene = largestFamily < minimumFamilySize;
		PERSISTENCE_DISCOVERY_COOLDOWN.considerSleep(
			policyFrame, activeOrTrial, qualifying, measuredFallback, sparseScene);
	}

	private static boolean isHardSafetyVeto(String reason) {
		return "gpu_safety_veto".equals(reason) || "p95_safety_veto".equals(reason);
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
		if (key instanceof ArmorStandWholePolicyKey) return "armor_stand_whole";
		if (key instanceof ArmorStandBoneKey) return "armor_stand_base_model";
		if (key instanceof ItemFrameBackingPolicyKey) return "item_frame_backing";
		if (key instanceof ItemFrameItemPolicyKey) return "item_frame_item";
		if (key instanceof MinecartModelPolicyKey) return "minecart_model";
		if (key instanceof MinecartDisplayBlockPolicyKey) return "minecart_display_block";
		if (key instanceof ArmorStandLayerKey) return "armor_stand_layer";
		if (key instanceof ArmorStandFeaturePolicyKey) return "armor_stand_armor_layer";
		if (key instanceof EntityPolicyKey entityKey) return String.valueOf(entityKey.adapterFamily());
		return "armor_stand";
	}

	public static Snapshot snapshot() {
		return new Snapshot(MESHES.size(), hits.sum(), misses.sum(), uploads.sum(), fallbacks.sum(),
			instancesDrawn.sum(), Math.max(0L, gpuBytes), lastHitsThisFrame, lastMissesThisFrame,
			lastUploadsThisFrame, lastDrawnThisFrame, lastVerticesAvoidedThisFrame, lastDrawCallsThisFrame,
			lastCulledThisFrame, instanceUploads.sum(), lastInstanceUploadsThisFrame,
			lastFallbacksThisFrame, meshRebuildsPerSecond, adaptiveSummary);
	}

	public static PersistentEntityMetrics.Snapshot entityMetricsSnapshot() {
		return PersistentEntityMetrics.snapshot();
	}

	public static String diagnosticLine() {
		updateDiagnosticSummaries();
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
		PersistentEntityMetrics.Snapshot entityMetrics = PersistentEntityMetrics.snapshot();
		String persistence = base + String.format(Locale.ROOT,
			", persistentDiscovery=%s, persistentGenericDormantSkips=%d, persistentArmorStandDormant=%s, persistentArmorCaptureFailures=%s, armorWholeMeshHits=%d, armorBasePoseReplays=%d, armorFeatureMeshHits=%d, armorSortedFallbacks=%d, armorDeterministicSkips=%d, armorCooldownSkips=%d, armorFirstBuilds=%d, armorPoseCacheSize=%d, armorVanillaResidualMs=%.3f, armorVanillaResidualSamples=%d, persistentEntityEnabled=%s, persistentEntityFamilies=%d, persistentEntityEligible=%d, persistentEntityActive=%d, persistentEntityCandidates=%d, persistentEntityCachedDraws=%d, persistentEntityAnchors=%d, persistentEntityUnsupportedRenderTypes=%d, persistentEntityDynamicStateFallbacks=%d, persistentEntitySafetyVetoes=%d, persistentEntityVanillaDraws=%d, persistentEntityFallbacks=%d, persistentEntityBuilds=%d, persistentEntityVanillaMs=%.3f, persistentEntityBuildMs=%.3f, persistentItemFrameBackingCandidates=%d, persistentItemFrameBackingCached=%d, persistentItemFrameBackingBuilds=%d, persistentItemFrameBackingFailures=%d, persistentArmorFeatureCandidates=%d, persistentArmorFeatureCached=%d, persistentArmorFeatureBuilds=%d, persistentArmorFeatureFailures=%d, persistentMinecartModelCandidates=%d, persistentMinecartModelCached=%d, persistentMinecartModelBuilds=%d, persistentMinecartModelFailures=%d, persistentFrameItemCandidates=%d, persistentFrameItemCached=%d, persistentFrameItemBuilds=%d, persistentFrameItemFailures=%d, persistentDisplayBlockCandidates=%d, persistentDisplayBlockCached=%d, persistentDisplayBlockBuilds=%d, persistentDisplayBlockFailures=%d, persistentImplementationRevision=%s",
			PERSISTENCE_DISCOVERY_COOLDOWN.state(policyFrame), genericFamilyDormantSkips,
			armorStandDormantUntilFrame > policyFrame
				? armorStandDormantReason + ":" + (armorStandDormantUntilFrame - policyFrame)
				: "none",
			ARMOR_CAPTURE_FAILURES.isEmpty() ? "none" : ARMOR_CAPTURE_FAILURES,
			armorWholeMeshHits, armorBasePoseReplays, armorFeatureMeshHits, armorSortedFallbacks,
			armorDeterministicSkips, armorCooldownSkips, armorFirstBuilds, CPU_ARMOR_MESHES.size(),
			armorVanillaResidualNanos / 1_000_000.0D, armorVanillaResidualSamples,
			OptiminiumSettings.isEntityPersistenceEnabled(), entityMetrics.families(), entityMetrics.eligible(),
			entityMetrics.active(), entityMetrics.candidates(), entityMetrics.cachedDraws(),
			entityMetrics.anchors(), entityMetrics.unsupportedRenderTypes(),
			entityMetrics.dynamicStateFallbacks(), entityMetrics.safetyVetoes(), entityMetrics.vanillaDraws(),
			entityMetrics.fallbacks(), entityMetrics.builds(), entityMetrics.vanillaNanos() / 1_000_000.0D,
			entityMetrics.buildNanos() / 1_000_000.0D,
			lastItemFrameBackingCandidates, lastItemFrameBackingCached,
			itemFrameBackingBuilds, itemFrameBackingFailures,
			lastArmorFeatureCandidates, lastArmorFeatureCached,
			armorFeatureBuilds, armorFeatureFailures,
			lastMinecartModelCandidates, lastMinecartModelCached,
			minecartModelBuilds, minecartModelFailures,
			lastFrameItemCandidates, lastFrameItemCached, frameItemBuilds, frameItemFailures,
			lastDisplayBlockCandidates, lastDisplayBlockCached, displayBlockBuilds, displayBlockFailures,
			IMPLEMENTATION_REVISION);
		OptiminiumMobAnimationThrottler.Snapshot animation = OptiminiumMobAnimationThrottler.snapshot();
		return persistence + String.format(Locale.ROOT,
			", mobAnimationEnabled=%s, mobAnimationEligible=%d, mobAnimationRefreshes=%d, mobAnimationReuses=%d, mobAnimationPaletteReuses=%d, mobAnimationInvalidations=%d, mobAnimationFallbacks=%d, mobAnimationCachedPoses=%d, mobAnimationEstimatedAvoidedMs=%.3f",
			OptiminiumSettings.isMobAnimationThrottlingEnabled(), animation.eligible(), animation.refreshes(),
			animation.reuses(), animation.directPaletteReuses(), animation.invalidations(),
			animation.fallbacks(), animation.cachedPoses(),
			animation.estimatedAnimationNanosAvoided() / 1_000_000.0D);
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
		boolean small, boolean showArms, boolean noBasePlate, ResourceLocation texture) implements ExactGroupKey {
	}

	private record ArmorStandLayerKey(ArmorStandBoneKey family, RenderType renderType) {
	}

	private record ArmorStandPoseMeshKey(ArmorStandBoneKey family, Object headPose, Object bodyPose,
		Object leftArmPose, Object rightArmPose, Object leftLegPose, Object rightLegPose) {
	}

	private record ArmorStandKeyCache(long revision, ArmorStandKey key) {
	}

	private enum ArmorStandWholePolicyKey {
		INSTANCE
	}

	private record ArmorStandFeaturePolicyKey(Class<?> layerClass) {
	}

	private enum ItemFrameBackingPolicyKey {
		INSTANCE
	}

	private enum MinecartModelPolicyKey {
		INSTANCE
	}

	private enum ItemFrameItemPolicyKey { INSTANCE }
	private enum MinecartDisplayBlockPolicyKey { INSTANCE }

	private static final class ItemFrameItemMeshKey {
		final BakedModel model;
		final ItemStack stack;
		final int hash;
		ItemFrameItemMeshKey(BakedModel model, ItemStack stack) {
			this.model = model;
			this.stack = stack.copy();
			this.hash = 31 * System.identityHashCode(model) + ItemStack.hashItemAndComponents(this.stack);
		}
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object other) {
			return other instanceof ItemFrameItemMeshKey key && model == key.model
				&& ItemStack.isSameItemSameComponents(stack, key.stack);
		}
	}

	private record MinecartDisplayBlockMeshKey(BlockState state, BakedModel model) {
		@Override public boolean equals(Object other) {
			return other instanceof MinecartDisplayBlockMeshKey key
				&& state.equals(key.state) && model == key.model;
		}
		@Override public int hashCode() {
			return 31 * state.hashCode() + System.identityHashCode(model);
		}
	}

	/** Model and render type use identity semantics within one resource generation. */
	private record MinecartModelMeshKey(Model model, RenderType renderType) {
		@Override public boolean equals(Object other) {
			return other instanceof MinecartModelMeshKey key
				&& model == key.model && renderType == key.renderType;
		}

		@Override public int hashCode() {
			return 31 * System.identityHashCode(model) + System.identityHashCode(renderType);
		}
	}

	/** Baked models intentionally retain identity semantics across one resource generation. */
	private record ItemFrameBackingMeshKey(BakedModel model) {
		@Override public boolean equals(Object other) {
			return other instanceof ItemFrameBackingMeshKey key && model == key.model;
		}

		@Override public int hashCode() {
			return System.identityHashCode(model);
		}
	}

	private record EntityPolicyKey(Object adapterFamily) {}
	private record EntityMeshKey(EntityPolicyKey family, Object geometry) {}

	private record ArmorStandPartKey(ArmorStandLayerKey policyKey, ModelPart part) {
	}

	private record PartVanillaTiming(ArmorStandLayerKey key, long startedNanos, int stride) {
	}

	private static final class ArmorStandFamilyState {
		final ArmorStandBoneKey family;
		final IdentityHashMap<RenderType, ArmorStandLayerKey> layers = new IdentityHashMap<>();
		final IdentityHashMap<RenderType, IdentityHashMap<ModelPart, ArmorStandPartKey>> parts = new IdentityHashMap<>();
		long dormantUntilFrame;
		String dormantReason = "none";

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

	private static final class CachedMobPalette {
		final ExactGroupKey key;
		final List<Matrix4f> matrices;
		long lastSeenFrame;

		CachedMobPalette(ExactGroupKey key, List<Matrix4f> matrices, long lastSeenFrame) {
			this.key = key;
			this.matrices = matrices;
			this.lastSeenFrame = lastSeenFrame;
		}
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
		VertexConsumer primaryConsumer;
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
		final List<Matrix4f> capturedPoseMatrices = new ArrayList<>();
		boolean reusePose;
		Matrix4f baseModelPose;
		boolean partPersistence;
		ArmorStandFamilyState armorStandFamily;
		Object staticOwner;
		Matrix4f staticInstancePose;
		Matrix4f staticRootInverse;
		ArmorStandPoseMeshKey staticMeshKey;
		RecordingVertexConsumer cpuCapture;
		boolean staticReplayComplete;

		MobRenderContext(ExactGroupKey key, UUID entityId, MultiBufferSource delegate, boolean persist) {
			this.key = key;
			this.entityId = entityId;
			this.persist = persist;
			this.buffers = renderType -> {
				VertexConsumer consumer = delegate.getBuffer(renderType);
				renderTypes.put(consumer, renderType);
				if (primaryRenderType == null) {
					primaryRenderType = renderType;
					primaryConsumer = consumer;
				}
				return consumer;
			};
		}

		void mixTopology(ModelPart part, RenderType renderType, boolean compatible) {
			topologyHash = (topologyHash ^ System.identityHashCode(part)) * 0x100000001b3L;
			topologyHash = (topologyHash ^ System.identityHashCode(renderType)) * 0x100000001b3L;
			topologyHash = (topologyHash ^ (compatible ? 1L : 0L)) * 0x100000001b3L;
		}
	}

	private static final class RecordingVertexConsumer implements VertexConsumer {
		final List<CpuVertex> vertices = new ArrayList<>();
		float x;
		float y;
		float z;
		int red = 255;
		int green = 255;
		int blue = 255;
		int alpha = 255;
		float u;
		float v;
		int overlayU;
		int overlayV;
		int lightU;
		int lightV;

		@Override public VertexConsumer addVertex(float x, float y, float z) {
			this.x = x;
			this.y = y;
			this.z = z;
			return this;
		}

		@Override public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			this.red = red;
			this.green = green;
			this.blue = blue;
			this.alpha = alpha;
			return this;
		}

		@Override public VertexConsumer setUv(float u, float v) {
			this.u = u;
			this.v = v;
			return this;
		}

		@Override public VertexConsumer setUv1(int u, int v) {
			overlayU = u;
			overlayV = v;
			return this;
		}

		@Override public VertexConsumer setUv2(int u, int v) {
			lightU = u;
			lightV = v;
			return this;
		}

		@Override public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
			vertices.add(new CpuVertex(x, y, z, red, green, blue, alpha, u, v,
				overlayU, overlayV, lightU, lightV, normalX, normalY, normalZ));
			return this;
		}
	}

	private record CpuVertex(float x, float y, float z, int red, int green, int blue, int alpha,
		float u, float v, int overlayU, int overlayV, int lightU, int lightV,
		float normalX, float normalY, float normalZ) {
	}

	private static final class CpuArmorMesh {
		final List<CpuVertex> vertices;
		final RenderType renderType;

		CpuArmorMesh(List<CpuVertex> vertices, RenderType renderType) {
			this.vertices = vertices;
			this.renderType = renderType;
		}

		void replay(VertexConsumer output, Matrix4f pose, int packedLight, int packedOverlay) {
			Matrix3f normalMatrix = new Matrix3f(pose).invert().transpose();
			Vector3f position = new Vector3f();
			Vector3f normal = new Vector3f();
			int overlayU = packedOverlay & 0xFFFF;
			int overlayV = packedOverlay >>> 16 & 0xFFFF;
			int lightU = packedLight & 0xFFFF;
			int lightV = packedLight >>> 16 & 0xFFFF;
			for (CpuVertex vertex : vertices) {
				pose.transformPosition(vertex.x, vertex.y, vertex.z, position);
				normalMatrix.transform(vertex.normalX, vertex.normalY, vertex.normalZ, normal).normalize();
				output.addVertex(position.x, position.y, position.z)
					.setColor(vertex.red, vertex.green, vertex.blue, vertex.alpha)
					.setUv(vertex.u, vertex.v)
					.setUv1(overlayU, overlayV)
					.setUv2(lightU, lightV)
					.setNormal(normal.x, normal.y, normal.z);
			}
		}
	}

	private static final class ArmorStandKey {
		private final boolean small;
		private final boolean marker;
		private final boolean invisible;
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
			marker = stand.isMarker();
			invisible = stand.isInvisible();
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
			value = 31 * value + Boolean.hashCode(marker);
			value = 31 * value + Boolean.hashCode(invisible);
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
					|| small != other.small || marker != other.marker || invisible != other.invisible
					|| arms != other.arms || noBasePlate != other.noBasePlate
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
				throw new IllegalArgumentException("Sorted render type: " + renderType);
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
			long fingerprint = 0xcbf29ce484222325L;
			try {
				for (LayerBuilder layer : layers.values()) {
					try (MeshData data = layer.builder.build()) {
						if (data == null) continue;
						vertices += data.drawState().vertexCount();
						fingerprint = fingerprintMix(fingerprint, data.drawState().vertexCount());
						fingerprint = fingerprintMix(fingerprint, data.drawState().indexCount());
						fingerprint = fingerprintMix(fingerprint, data.drawState().mode().ordinal());
						fingerprint = fingerprintMix(fingerprint, data.vertexBuffer());
						if (data.indexBuffer() != null) fingerprint = fingerprintMix(fingerprint, data.indexBuffer());
						uploaded.add(new CachedLayer(layer.renderType, allocateAtlasSlice(layer.renderType, data)));
					}
				}
				if (uploaded.isEmpty()) return null;
				return new CachedMesh(key, policyKey, List.copyOf(uploaded), 0L, vertices,
					boneCount, fingerprint, nextEntityValidationFrame(key));
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
		int configuredDirectMatrixBuffer;
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

		void bind(int instanceBuffer, int directMatrixBuffer) {
			bindAtlasVertexArray(0);
			bindAtlasVertexArray(vao);
			if (configuredInstanceBuffer != instanceBuffer
					|| configuredDirectMatrixBuffer != directMatrixBuffer) {
				InstanceBatch.configureAttributes(OptiminiumPersistentMeshShader.get(),
					instanceBuffer, directMatrixBuffer);
				configuredInstanceBuffer = instanceBuffer;
				configuredDirectMatrixBuffer = directMatrixBuffer;
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
		final long captureFingerprint;
		final InstanceBatch instances = new InstanceBatch();
		long nextValidationFrame;
		int sharedBaseInstance;

		CachedMesh(Object key, Object policyKey, List<CachedLayer> layers, long bytes, long vertices,
				int boneCount, long captureFingerprint, long nextValidationFrame) {
			this.key = key;
			this.policyKey = policyKey;
			this.layers = layers;
			this.bytes = bytes;
			this.vertices = vertices;
			this.boneCount = boneCount;
			this.captureFingerprint = captureFingerprint;
			this.nextValidationFrame = nextValidationFrame;
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

		void queueRevisionedResident(Object owner, Matrix4f pose, boolean worldSpace,
				int packedLight, int packedOverlay, long revision) {
			int transformIndex = RESIDENT_TRANSFORMS.indexForRevision(owner, pose, worldSpace, revision, policyFrame);
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

		void queueDirectBone(int packedLight, int packedOverlay, int color, Matrix4f pose) {
			instances.addDirect(packedLight, packedOverlay, color, pose);
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
			OptiminiumRenderProfiler.recordOptiminiumDraw(renderType, false, false);
			int instanceBuffer = sharedInstances ? SHARED_INSTANCES.currentGpuBuffer() : instances.currentGpuBuffer();
			int directMatrixBuffer = sharedInstances
				? SHARED_INSTANCES.currentDirectMatrixGpuBuffer() : instances.currentDirectMatrixGpuBuffer();
			slice.page.bind(instanceBuffer, directMatrixBuffer);
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
		private static final int MATRIX_BYTES = 16 * Float.BYTES;
		private static final int GPU_RING_SIZE = 3;
		private ByteBuffer data = MemoryUtil.memAlloc(BYTES_PER_INSTANCE * 64).order(ByteOrder.nativeOrder());
		private ByteBuffer directMatrices;
		private final int[] gpuBuffers = new int[GPU_RING_SIZE];
		private final int[] directMatrixGpuBuffers = new int[GPU_RING_SIZE];
		private int currentGpuSlot = -1;
		private int gpuCapacityBytes;
		private int directMatrixGpuCapacityBytes;
		private boolean hasDirectMatrices;
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

		void addDirect(int packedLight, int packedOverlay, int color, Matrix4f pose) {
			if (count > 0 && !hasDirectMatrices) {
				throw new IllegalStateException("Cannot mix direct and palette/resident instances");
			}
			add(0, false, packedLight, packedOverlay, color, -2);
			hasDirectMatrices = true;
			ensureDirectMatrixCpuCapacity(count * MATRIX_BYTES);
			pose.get((count - 1) * MATRIX_BYTES, directMatrices);
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
			if (hasDirectMatrices) {
				int matrixBytes = count * MATRIX_BYTES;
				if (directMatrixGpuCapacityBytes < matrixBytes) {
					int oldCapacity = directMatrixGpuCapacityBytes;
					directMatrixGpuCapacityBytes = nextPowerOfTwo(matrixBytes);
					gpuBytes += (long)(directMatrixGpuCapacityBytes - oldCapacity) * GPU_RING_SIZE;
					for (int i = 0; i < GPU_RING_SIZE; i++) {
						if (directMatrixGpuBuffers[i] == 0) {
							directMatrixGpuBuffers[i] = GL15.glGenBuffers();
						}
						GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, directMatrixGpuBuffers[i]);
						GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
							directMatrixGpuCapacityBytes, GL15.GL_STREAM_DRAW);
					}
				}
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, directMatrixGpuBuffers[currentGpuSlot]);
				ByteBuffer matrixUpload = directMatrices.duplicate().order(ByteOrder.nativeOrder());
				matrixUpload.position(0).limit(matrixBytes);
				GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, matrixUpload);
			}
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
			if (metricsEnabled) {
				instanceUploads.increment();
				instanceUploadsThisFrame++;
			}
		}

		static void configureAttributes(net.minecraft.client.renderer.ShaderInstance shader,
				int buffer, int directMatrixBuffer) {
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
			configure(shader, "InstanceTransformIndex", 1, 0L);
			configure(shader, "InstanceLight", 2, 4L);
			configure(shader, "InstanceOverlay", 2, 12L);
			configure(shader, "InstanceColor", 4, 20L);
			configure(shader, "InstancePaletteOffset", 1, 36L);
			configure(shader, "InstanceWorldSpace", 1, 40L);
			configureDirectMatrix(shader, directMatrixBuffer);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		}

		int currentGpuBuffer() {
			return currentGpuSlot < 0 ? 0 : gpuBuffers[currentGpuSlot];
		}

		int currentDirectMatrixGpuBuffer() {
			return !hasDirectMatrices || currentGpuSlot < 0 ? 0 : directMatrixGpuBuffers[currentGpuSlot];
		}

		private static void configure(net.minecraft.client.renderer.ShaderInstance shader,
				String name, int components, long offset) {
			int location = GL20.glGetAttribLocation(shader.getId(), name);
			if (location < 0) throw new IllegalStateException("Missing persistent mesh attribute " + name);
			GL20.glEnableVertexAttribArray(location);
			GL20.glVertexAttribPointer(location, components, GL11.GL_FLOAT, false, BYTES_PER_INSTANCE, offset);
			GL33.glVertexAttribDivisor(location, 1);
		}

		private static void configureDirectMatrix(net.minecraft.client.renderer.ShaderInstance shader,
				int buffer) {
			String[] names = {"InstanceDirectMatrix0", "InstanceDirectMatrix1",
				"InstanceDirectMatrix2", "InstanceDirectMatrix3"};
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
			for (int column = 0; column < names.length; column++) {
				int location = GL20.glGetAttribLocation(shader.getId(), names[column]);
				if (location < 0) {
					throw new IllegalStateException("Missing persistent mesh attribute " + names[column]);
				}
				if (buffer != 0) {
					GL20.glEnableVertexAttribArray(location);
					GL20.glVertexAttribPointer(location, 4, GL11.GL_FLOAT, false,
						MATRIX_BYTES, (long)column * 4L * Float.BYTES);
					GL33.glVertexAttribDivisor(location, 1);
				} else {
					GL20.glDisableVertexAttribArray(location);
					GL33.glVertexAttribDivisor(location, 0);
					GL20.glVertexAttrib4f(location,
						column == 0 ? 1.0F : 0.0F,
						column == 1 ? 1.0F : 0.0F,
						column == 2 ? 1.0F : 0.0F,
						column == 3 ? 1.0F : 0.0F);
				}
			}
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

		private void ensureDirectMatrixCpuCapacity(int required) {
			if (directMatrices != null && directMatrices.capacity() >= required) return;
			int capacity = nextPowerOfTwo(Math.max(required, MATRIX_BYTES * 64));
			ByteBuffer grown = MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
			if (directMatrices != null) {
				ByteBuffer old = directMatrices.duplicate();
				old.position(0).limit((count - 1) * MATRIX_BYTES);
				grown.put(old);
				grown.clear();
				MemoryUtil.memFree(directMatrices);
			}
			directMatrices = grown;
		}

		void reset() {
			count = 0;
			hasDirectMatrices = false;
		}

		ByteBuffer dataView() {
			ByteBuffer view = data.duplicate().order(ByteOrder.nativeOrder());
			view.position(0).limit(count * BYTES_PER_INSTANCE);
			return view;
		}

		ByteBuffer directMatrixDataView() {
			if (!hasDirectMatrices || directMatrices == null) return null;
			ByteBuffer view = directMatrices.duplicate().order(ByteOrder.nativeOrder());
			view.position(0).limit(count * MATRIX_BYTES);
			return view;
		}

		boolean hasDirectMatrices() {
			return hasDirectMatrices;
		}

		@Override public void close() {
			for (int i = 0; i < GPU_RING_SIZE; i++) {
				if (gpuBuffers[i] != 0) {
					GL15.glDeleteBuffers(gpuBuffers[i]);
					gpuBuffers[i] = 0;
				}
				if (directMatrixGpuBuffers[i] != 0) {
					GL15.glDeleteBuffers(directMatrixGpuBuffers[i]);
					directMatrixGpuBuffers[i] = 0;
				}
			}
			gpuBytes -= (long)gpuCapacityBytes * GPU_RING_SIZE;
			gpuBytes -= (long)directMatrixGpuCapacityBytes * GPU_RING_SIZE;
			gpuCapacityBytes = 0;
			directMatrixGpuCapacityBytes = 0;
			if (data != null) {
				MemoryUtil.memFree(data);
				data = null;
			}
			if (directMatrices != null) {
				MemoryUtil.memFree(directMatrices);
				directMatrices = null;
			}
		}
	}

	/** One compact frame stream shared by every compatible persistent mesh. */
	private static final class SharedInstanceStream implements AutoCloseable {
		private ByteBuffer data = MemoryUtil.memAlloc(InstanceBatch.BYTES_PER_INSTANCE * 256)
			.order(ByteOrder.nativeOrder());
		private ByteBuffer directMatrices = MemoryUtil.memAlloc(InstanceBatch.MATRIX_BYTES * 256)
			.order(ByteOrder.nativeOrder());
		private final int[] gpuBuffers = new int[3];
		private final int[] directMatrixGpuBuffers = new int[3];
		private int currentSlot = -1;
		private int gpuCapacityBytes;
		private int directMatrixGpuCapacityBytes;
		private int count;
		private boolean hasDirectMatrices;

		void begin() {
			count = 0;
			hasDirectMatrices = false;
			data.clear();
			directMatrices.clear();
		}

		int append(InstanceBatch batch) {
			int base = count;
			int bytes = batch.count * InstanceBatch.BYTES_PER_INSTANCE;
			ensureCapacity((count + batch.count) * InstanceBatch.BYTES_PER_INSTANCE);
			ByteBuffer source = batch.dataView();
			data.position(count * InstanceBatch.BYTES_PER_INSTANCE);
			data.put(source);
			appendDirectMatrices(batch, base);
			count += batch.count;
			return base;
		}

		private void appendDirectMatrices(InstanceBatch batch, int base) {
			if (!batch.hasDirectMatrices() && !hasDirectMatrices) return;
			ensureDirectMatrixCapacity((base + batch.count) * InstanceBatch.MATRIX_BYTES);
			if (batch.hasDirectMatrices()) {
				if (!hasDirectMatrices) {
					for (int index = 0; index < base; index++) writeIdentity(index);
					hasDirectMatrices = true;
				}
				directMatrices.position(base * InstanceBatch.MATRIX_BYTES);
				directMatrices.put(batch.directMatrixDataView());
			} else {
				for (int index = base; index < base + batch.count; index++) writeIdentity(index);
			}
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
			if (hasDirectMatrices) {
				int matrixBytes = count * InstanceBatch.MATRIX_BYTES;
				if (directMatrixGpuCapacityBytes < matrixBytes) {
					int oldCapacity = directMatrixGpuCapacityBytes;
					directMatrixGpuCapacityBytes = nextPowerOfTwo(matrixBytes);
					gpuBytes += (long)(directMatrixGpuCapacityBytes - oldCapacity)
						* directMatrixGpuBuffers.length;
					for (int i = 0; i < directMatrixGpuBuffers.length; i++) {
						if (directMatrixGpuBuffers[i] == 0) {
							directMatrixGpuBuffers[i] = GL15.glGenBuffers();
						}
						GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, directMatrixGpuBuffers[i]);
						GL15.glBufferData(GL15.GL_ARRAY_BUFFER,
							directMatrixGpuCapacityBytes, GL15.GL_STREAM_DRAW);
					}
				}
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, directMatrixGpuBuffers[currentSlot]);
				ByteBuffer matrixUpload = directMatrices.duplicate().order(ByteOrder.nativeOrder());
				matrixUpload.position(0).limit(matrixBytes);
				GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, matrixUpload);
			}
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
			if (metricsEnabled) {
				instanceUploads.increment();
				instanceUploadsThisFrame++;
			}
		}

		int currentGpuBuffer() { return currentSlot < 0 ? 0 : gpuBuffers[currentSlot]; }

		int currentDirectMatrixGpuBuffer() {
			return !hasDirectMatrices || currentSlot < 0 ? 0 : directMatrixGpuBuffers[currentSlot];
		}

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

		private void ensureDirectMatrixCapacity(int required) {
			if (directMatrices.capacity() >= required) return;
			int capacity = nextPowerOfTwo(Math.max(required, InstanceBatch.MATRIX_BYTES * 256));
			ByteBuffer grown = MemoryUtil.memAlloc(capacity).order(ByteOrder.nativeOrder());
			ByteBuffer old = directMatrices.duplicate().order(ByteOrder.nativeOrder());
			old.position(0).limit(count * InstanceBatch.MATRIX_BYTES);
			grown.put(old);
			grown.clear();
			MemoryUtil.memFree(directMatrices);
			directMatrices = grown;
		}

		private void writeIdentity(int index) {
			int base = index * InstanceBatch.MATRIX_BYTES;
			for (int component = 0; component < 16; component++) {
				directMatrices.putFloat(base + component * Float.BYTES,
					component % 5 == 0 ? 1.0F : 0.0F);
			}
		}

		@Override public void close() {
			for (int i = 0; i < gpuBuffers.length; i++) {
				if (gpuBuffers[i] != 0) GL15.glDeleteBuffers(gpuBuffers[i]);
				gpuBuffers[i] = 0;
				if (directMatrixGpuBuffers[i] != 0) {
					GL15.glDeleteBuffers(directMatrixGpuBuffers[i]);
				}
				directMatrixGpuBuffers[i] = 0;
			}
			gpuBytes -= (long)gpuCapacityBytes * gpuBuffers.length;
			gpuBytes -= (long)directMatrixGpuCapacityBytes * directMatrixGpuBuffers.length;
			gpuCapacityBytes = 0;
			directMatrixGpuCapacityBytes = 0;
			currentSlot = -1;
			if (data != null) MemoryUtil.memFree(data);
			if (directMatrices != null) MemoryUtil.memFree(directMatrices);
			data = MemoryUtil.memAlloc(InstanceBatch.BYTES_PER_INSTANCE * 256).order(ByteOrder.nativeOrder());
			directMatrices = MemoryUtil.memAlloc(InstanceBatch.MATRIX_BYTES * 256)
				.order(ByteOrder.nativeOrder());
			hasDirectMatrices = false;
			count = 0;
		}
	}

	/** Compact DrawElementsIndirectCommand stream for slices sharing one atlas page. */
	private static final class IndirectCommandStream implements AutoCloseable {
		private static final int COMMAND_BYTES = 5 * Integer.BYTES;
		private int buffer;
		private int capacityBytes;

		void draw(List<QueuedLayer> draws) {
			OptiminiumRenderProfiler.recordOptiminiumDraw(draws.get(0).layer.renderType, false, false);
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

		int indexForRevision(Object owner, Matrix4f pose, boolean worldSpace, long revision, long frame) {
			int resident = index.touchVersioned(owner, revision, frame);
			if (resident >= 0) return resident;
			Matrix4f stored = worldMatrix(pose, worldSpace);
			ResidentTransformIndex.Update update = index.observeVersioned(owner, stored, revision, frame);
			if (update.changed()) write(update.slot(), update.matrix());
			return update.slot();
		}

		private Matrix4f worldMatrix(Matrix4f pose, boolean worldSpace) {
			Matrix4f stored = new Matrix4f(pose);
			if (worldSpace) {
				var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
				stored.m30(stored.m30() + (float)camera.x);
				stored.m31(stored.m31() + (float)camera.y);
				stored.m32(stored.m32() + (float)camera.z);
			}
			return stored;
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
