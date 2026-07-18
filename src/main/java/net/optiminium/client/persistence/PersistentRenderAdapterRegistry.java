package net.optiminium.client.persistence;

import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Thread-safe-at-registration, render-thread-at-lookup registry for opt-in persistence. */
public final class PersistentRenderAdapterRegistry {
	private static final List<PersistentRenderAdapter<?>> ADAPTERS = new ArrayList<>();

	private PersistentRenderAdapterRegistry() {}

	public static synchronized <E extends Entity> void register(PersistentRenderAdapter<E> adapter) {
		Objects.requireNonNull(adapter, "adapter");
		ADAPTERS.removeIf(existing -> existing.entityClass() == adapter.entityClass());
		ADAPTERS.add(adapter);
	}

	public static synchronized void unregister(Class<? extends Entity> entityClass) {
		ADAPTERS.removeIf(adapter -> adapter.entityClass() == entityClass);
	}

	@SuppressWarnings("unchecked")
	public static synchronized <E extends Entity> PersistentRenderAdapter<E> find(E entity) {
		for (PersistentRenderAdapter<?> adapter : ADAPTERS) {
			if (adapter.entityClass().isInstance(entity)) return (PersistentRenderAdapter<E>)adapter;
		}
		return null;
	}

	static synchronized void clearForTests() {
		ADAPTERS.clear();
	}
}
