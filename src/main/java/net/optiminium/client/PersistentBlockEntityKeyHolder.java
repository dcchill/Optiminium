package net.optiminium.client;

/** Runtime mixin bridge for a block entity's allocation-free persistent mesh key cache. */
public interface PersistentBlockEntityKeyHolder {
	Object optiminium$getPersistentMeshKeyCache();
	void optiminium$setPersistentMeshKeyCache(Object cache);
	Object optiminium$getPersistentGenericKeyCache();
	void optiminium$setPersistentGenericKeyCache(Object cache);
}
