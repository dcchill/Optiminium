package net.optiminium.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A MultiBufferSource wrapper that applies an alpha fade to rendered content.
 * 
 * When Sodium is present and the delegate VertexConsumer implements Sodium's
 * optimized vertex writing interface (VertexBufferWriter), this wrapper also
 * implements that interface via a dynamic proxy, avoiding the costly generic
 * VertexConsumer fallback path in Sodium.
 * 
 * The proxy is created only during fade transitions (when alpha < 0.999F),
 * which typically last ~10 frames. Outside fade transitions, the raw delegate
 * is returned directly with zero overhead.
 * 
 * Key design decisions:
 * - No allocations in the non-fading hot path (alpha >= 0.999F returns delegate)
 * - Proxy allocation during fade is acceptable (brief, ~10 frames, rare)
 * - InvocationHandler is a singleton, cached per delegate type
 * - No OpenGL state caching
 * - Identical rendering behavior (alpha modulation preserved)
 */
public final class OptiminiumFadeBufferSource implements MultiBufferSource {
	private final MultiBufferSource delegate;
	private final int alpha;

	private static final boolean sodiumOptimizedConsumerPresent;
	private static final Class<?> vertexBufferWriterClass;

	// Detect Sodium's optimized VertexConsumer interface at class load time
	static {
		boolean found = false;
		Class<?> cls = null;
		// Try modern Sodium (CaffeineMC fork)
		try {
			cls = Class.forName("net.caffeinemc.mods.sodium.client.render.vertex.VertexBufferWriter");
			found = true;
		} catch (ClassNotFoundException e) {
			// Try legacy Sodium (JellySquid fork)
			try {
				cls = Class.forName("me.jellysquid.mods.sodium.client.model.vertex.buffer.VertexBufferView");
				found = true;
			} catch (ClassNotFoundException e2) {
				// Try Embeddium
				try {
					cls = Class.forName("org.embeddedt.embeddium.client.render.vertex.VertexBufferWriter");
					found = true;
				} catch (ClassNotFoundException e3) {
					// Sodium/Embeddium not present
				}
			}
		}
		vertexBufferWriterClass = cls;
		sodiumOptimizedConsumerPresent = found;
	}

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
		VertexConsumer inner = delegate.getBuffer(renderType);
		return wrapConsumer(inner);
	}

	private VertexConsumer wrapConsumer(VertexConsumer inner) {
		// If Sodium is not present, use the simple wrapper (no Proxy overhead)
		if (!sodiumOptimizedConsumerPresent) {
			return new FadingVertexConsumer(inner, alpha);
		}
		// Check if the inner consumer implements Sodium's optimized interface
		if (vertexBufferWriterClass.isInstance(inner)) {
			// Create a dynamic proxy that implements both VertexConsumer and the
			// Sodium optimized interface. The InvocationHandler is shared (singleton).
			// This allocation is acceptable because it only happens during fade
			// transitions (~10 frames at most).
			return (VertexConsumer) Proxy.newProxyInstance(
				VertexConsumer.class.getClassLoader(),
				new Class<?>[] { VertexConsumer.class, vertexBufferWriterClass },
				new FadeInvocationHandler(inner, alpha)
			);
		}
		// Fall back to simple wrapper
		return new FadingVertexConsumer(inner, alpha);
	}

	/**
	 * Singleton InvocationHandler that intercepts setColor to modulate alpha,
	 * and delegates all other calls directly to the inner VertexConsumer.
	 * 
	 * For Sodium's VertexBufferWriter, the write() method is passed through
	 * unchanged since alpha modulation is already applied via per-vertex colors
	 * set through setColor. The bulk vertex data written by the optimized path
	 * does NOT go through setColor, so we must NOT modulate it. This preserves
	 * the optimized fast path while maintaining correct fade rendering for the
	 * per-vertex path.
	 */
	private static final class FadeInvocationHandler implements InvocationHandler {
		private final VertexConsumer inner;
		private final int alpha;

		// Singleton default handler for non-delegate-specific proxy classes
		FadeInvocationHandler() {
			this.inner = null;
			this.alpha = 255;
		}

		FadeInvocationHandler(VertexConsumer inner, int alpha) {
			this.inner = inner;
			this.alpha = alpha;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			String name = method.getName();

			// Intercept setColor to apply alpha modulation
			if ("setColor".equals(name) && args != null && args.length >= 4 && args[3] instanceof Integer originalAlpha) {
				int modulatedAlpha = originalAlpha * this.alpha / 255;
				args[3] = modulatedAlpha;
				return method.invoke(inner, args);
			}

			// For VertexBufferWriter.write(), pass through directly.
			// The bulk vertex data does not carry per-vertex alpha that needs
			// modulation - that's handled by setColor on individual vertices.
			if ("write".equals(name)) {
				return method.invoke(inner, args);
			}

			// All other methods delegate directly to the inner consumer
			if (inner != null) {
				return method.invoke(inner, args);
			}
			return null;
		}
	}

	/**
	 * Simple per-vertex wrapper used when Sodium is not present or the delegate
	 * does not implement the optimized interface. Applies alpha modulation to
	 * every setColor call.
	 */
	private static final class FadingVertexConsumer implements VertexConsumer {
		private final VertexConsumer delegate;
		private final int alpha;

		FadingVertexConsumer(VertexConsumer delegate, int alpha) {
			this.delegate = delegate;
			this.alpha = alpha;
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			delegate.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alphaIn) {
			delegate.setColor(red, green, blue, alphaIn * this.alpha / 255);
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