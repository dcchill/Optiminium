package net.optiminium.client.persistence;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

/**
 * Opt-in contract for exact persistent entity rendering.
 *
 * <p>Adapters must return an immutable key containing every value that can change emitted
 * vertices or render types. Returning {@code null} is an immediate, allocation-free vanilla
 * fallback. Registration is explicit so arbitrary mod renderers and their render events are
 * never suppressed by discovery.</p>
 */
public interface PersistentRenderAdapter<E extends Entity> {
	Class<E> entityClass();

	/** Stable identity used by the adaptive policy; normally one value per audited family. */
	Object familyKey(E entity, EntityRenderer<? super E> renderer);

	/** Exact immutable geometry state, or {@code null} when this frame is not cache-safe. */
	Object geometryKey(E entity, EntityRenderer<? super E> renderer, float yaw, float partialTick);

	/** A cheap revision used to reuse the last geometry key without inspecting full state. */
	long revision(E entity);

	/** Preserve the captured per-vertex UV2 light instead of applying one instance light. */
	default boolean usesCapturedVertexLight(E entity) { return false; }

	/** Whether the dispatcher transform can be reused until {@link #transformRevision} changes. */
	default boolean usesRevisionedTransform(E entity) { return false; }

	/** Exact revision for every value contributing to the dispatcher transform this frame. */
	default long transformRevision(E entity, float yaw, float partialTick) { return 0L; }

	/** Whether the complete renderer output may be captured and replayed for this frame. */
	default boolean eligible(E entity) {
		return entity.isAlive() && !entity.isCustomNameVisible() && !entity.displayFireAnimation();
	}
}
