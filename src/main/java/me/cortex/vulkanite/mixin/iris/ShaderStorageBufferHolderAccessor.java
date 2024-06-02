package me.cortex.vulkanite.mixin.iris;

import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ShaderStorageBufferHolder.class, remap = false)
public interface ShaderStorageBufferHolderAccessor {
    @Accessor
    ShaderStorageBuffer[] getBuffers();
}
