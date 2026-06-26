package net.optiminium.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

public final class OptiminiumFadeBufferSource implements MultiBufferSource {
	private final MultiBufferSource delegate;
	private final int alpha;

	private OptiminiumFadeBufferSource(MultiBufferSource delegate, float alpha) {
		this.delegate = delegate;
		this.alpha = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
	}

	public static MultiBufferSource wrap(MultiBufferSource delegate, float alpha) {
		if (delegate == null || alpha >= 0.999F) {
			return delegate;
		}
		return new OptiminiumFadeBufferSource(delegate, alpha);
	}

	@Override
	public VertexConsumer getBuffer(RenderType renderType) {
		return new FadingVertexConsumer(delegate.getBuffer(renderType), alpha);
	}

	private record FadingVertexConsumer(VertexConsumer delegate, int alpha) implements VertexConsumer {
		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			delegate.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			delegate.setColor(red, green, blue, alpha * this.alpha / 255);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			delegate.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			delegate.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			delegate.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			delegate.setNormal(x, y, z);
			return this;
		}
	}
}
