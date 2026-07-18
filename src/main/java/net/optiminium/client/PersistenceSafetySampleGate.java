package net.optiminium.client;

/** Rejects whole-frame persistence samples when camera motion makes them incomparable. */
final class PersistenceSafetySampleGate {
	private static final double POSITION_EPSILON_SQR = 0.01D * 0.01D;
	private static final float ROTATION_EPSILON = 0.25F;

	private double lastX = Double.NaN;
	private double lastY;
	private double lastZ;
	private float lastXRot;
	private float lastYRot;

	boolean observe(double x, double y, double z, float xRot, float yRot) {
		boolean initialized = !Double.isNaN(lastX);
		double dx = x - lastX;
		double dy = y - lastY;
		double dz = z - lastZ;
		boolean stable = initialized
			&& dx * dx + dy * dy + dz * dz <= POSITION_EPSILON_SQR
			&& rotationDelta(xRot, lastXRot) <= ROTATION_EPSILON
			&& rotationDelta(yRot, lastYRot) <= ROTATION_EPSILON;
		lastX = x;
		lastY = y;
		lastZ = z;
		lastXRot = xRot;
		lastYRot = yRot;
		return stable;
	}

	void reset() {
		lastX = Double.NaN;
	}

	private static float rotationDelta(float current, float previous) {
		float delta = Math.abs(current - previous) % 360.0F;
		return Math.min(delta, 360.0F - delta);
	}
}
