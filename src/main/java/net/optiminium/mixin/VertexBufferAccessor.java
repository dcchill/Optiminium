package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {
	@Accessor("indexCount") int optiminium$getIndexCount();
	@Accessor("mode") VertexFormat.Mode optiminium$getMode();
	@Invoker("getIndexType") VertexFormat.IndexType optiminium$resolveIndexType();
}
