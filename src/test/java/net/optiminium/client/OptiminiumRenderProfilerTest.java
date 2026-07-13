package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptiminiumRenderProfilerTest {
	@Test
	void recordsUploadBreakdownAndTracksLargestCategory() {
		OptiminiumRenderProfiler.setEnabled(true);
		OptiminiumRenderProfiler.reset();

		OptiminiumRenderProfiler.pushUploadCategory(OptiminiumRenderProfiler.UploadCategory.PROXY_LOD);
		OptiminiumRenderProfiler.recordBufferUpload(OptiminiumRenderProfiler.start(), 64L, OptiminiumRenderProfiler.UploadCategory.PROXY_LOD);
		OptiminiumRenderProfiler.recordBufferUpload(OptiminiumRenderProfiler.start(), 128L, OptiminiumRenderProfiler.UploadCategory.BLOCK_ENTITY_PROXY);
		OptiminiumRenderProfiler.popUploadCategory();

		OptiminiumRenderProfiler.Snapshot snapshot = OptiminiumRenderProfiler.snapshot();
		assertEquals(1L, snapshot.proxyLodUploadCount());
		assertEquals(1L, snapshot.blockEntityProxyUploadCount());
		assertEquals(128L, snapshot.blockEntityProxyUploadBytes());
		assertTrue(snapshot.largestUploadSource().contains("blockEntity"));
	}
}
