package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VertexBuffer.class)
public interface VertexBufferAccessor {
	@Accessor("indexCount") int optiminium$getIndexCount();
	@Accessor("mode") VertexFormat.Mode optiminium$getMode();
	@Accessor("indexType") VertexFormat.IndexType optiminium$getIndexType();
}
