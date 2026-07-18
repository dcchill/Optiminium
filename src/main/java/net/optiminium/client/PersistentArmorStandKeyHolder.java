package net.optiminium.client;

/** Runtime mixin bridge for allocation-free exact armor-stand geometry keys. */
public interface PersistentArmorStandKeyHolder {
	long optiminium$getPersistentArmorRevision();
	Object optiminium$getPersistentArmorKeyCache();
	void optiminium$setPersistentArmorKeyCache(Object cache);
	void optiminium$invalidatePersistentArmorKey();
}
