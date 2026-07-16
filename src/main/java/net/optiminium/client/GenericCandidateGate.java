package net.optiminium.client;

/** Cheap first-stage gate that avoids serializing sparse generic block entities. */
final class GenericCandidateGate {
	private GenericCandidateGate() {
	}

	static boolean shouldBuildKey(int previousFamilyCount, boolean adaptive,
			int adaptiveMin, int guaranteed) {
		return previousFamilyCount >= (adaptive ? adaptiveMin : guaranteed);
	}
}
