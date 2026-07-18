package net.optiminium.client;

import java.util.Set;

/** Conservative name guard for vanilla-compatible entity shader/state combinations. */
final class PersistentRenderTypeCompatibility {
	private static final Set<String> COMPATIBLE_NAMES = Set.of(
		"entity_solid",
		"entity_cutout",
		"entity_cutout_no_cull",
		"entity_cutout_no_cull_z_offset",
		"entity_smooth_cutout",
		// Vanilla HumanoidArmorLayer output uses the same NEW_ENTITY vertex contract and
		// is unsorted. Glint remains rejected separately by its own render type.
		"armor_cutout_no_cull"
	);

	private PersistentRenderTypeCompatibility() {
	}

	static boolean isCompatible(String description) {
		if (description == null) return false;
		for (String name : COMPATIBLE_NAMES) {
			if (description.equals(name)
					|| description.startsWith("RenderType[" + name + ":")
					|| description.startsWith("RenderType[" + name + "]")) return true;
		}
		return false;
	}
}
