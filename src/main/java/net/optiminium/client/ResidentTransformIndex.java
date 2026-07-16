package net.optiminium.client;

import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Deterministic identity/slot policy for long-lived block-entity transforms. */
final class ResidentTransformIndex {
	private static final float MATRIX_EPSILON = 1.0E-4F;
	private final IdentityHashMap<Object, Entry> entries = new IdentityHashMap<>();
	private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
	private int nextSlot;

	Update observe(Object owner, Matrix4f matrix, long frame) {
		Entry entry = entries.get(owner);
		if (entry == null) {
			int slot = freeSlots.isEmpty() ? nextSlot++ : freeSlots.removeFirst();
			entry = new Entry(slot, new Matrix4f(matrix), frame);
			entries.put(owner, entry);
			return new Update(slot, entry.matrix, true);
		}
		entry.lastSeenFrame = frame;
		if (!entry.matrix.equals(matrix, MATRIX_EPSILON)) {
			entry.matrix.set(matrix);
			return new Update(entry.slot, entry.matrix, true);
		}
		return new Update(entry.slot, entry.matrix, false);
	}

	/**
	 * Marks a transform as visible without constructing or comparing another matrix.
	 * Returns {@code -1} when the owner has not been assigned a resident slot yet.
	 */
	int touch(Object owner, long frame) {
		Entry entry = entries.get(owner);
		if (entry == null) return -1;
		entry.lastSeenFrame = frame;
		return entry.slot;
	}

	List<Integer> expire(long frame, long maxInactiveFrames) {
		List<Integer> expired = new ArrayList<>();
		var iterator = entries.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry entry = iterator.next().getValue();
			if (frame - entry.lastSeenFrame <= maxInactiveFrames) continue;
			iterator.remove();
			freeSlots.addLast(entry.slot);
			expired.add(entry.slot);
		}
		return expired;
	}

	int size() { return entries.size(); }
	int slotCapacity() { return nextSlot; }

	void clear() {
		entries.clear();
		freeSlots.clear();
		nextSlot = 0;
	}

	record Update(int slot, Matrix4f matrix, boolean changed) {
	}

	private static final class Entry {
		final int slot;
		final Matrix4f matrix;
		long lastSeenFrame;

		Entry(int slot, Matrix4f matrix, long lastSeenFrame) {
			this.slot = slot;
			this.matrix = matrix;
			this.lastSeenFrame = lastSeenFrame;
		}
	}
}
