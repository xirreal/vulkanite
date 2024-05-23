package me.cortex.vulkanite.lib.descriptors;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import org.lwjgl.vulkan.VkCopyDescriptorSet;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkUpdateDescriptorSets;

public class VDescriptorSet extends VObject {
    private final VRef<VDescriptorPool> pool;
    public final long poolHandle;
    public final long set;

    private final Map<Integer, VRef<VObject>> refs = new HashMap<>();

    public void addRef(int binding, VRef<VObject> ref) {
        var old = refs.put(binding, ref);
        if (old != null) {
            old.close();
        }
    }

    protected VDescriptorSet(VRef<VDescriptorPool> pool, long poolHandle, long set) {
        this.pool = pool;
        this.poolHandle = poolHandle;
        this.set = set;
    }

    @Override
    protected void free() {
        pool.get().freeSet(this);
        refs.values().forEach(VRef::close);
    }

    public void copyFrom(VContext ctx, VRef<VDescriptorSet> other, int setCapacity) {
        for (var entry : other.get().refs.entrySet()) {
            refs.put(entry.getKey(), entry.getValue().addRef());
        }

        try (var stack = stackPush()) {
            var setCopy = VkCopyDescriptorSet.calloc(1, stack);
            setCopy.get(0)
                    .sType$Default()
                    .srcSet(other.get().set)
                    .dstSet(set)
                    .descriptorCount(setCapacity);
            vkUpdateDescriptorSets(ctx.device, null, setCopy);
        }
    }
}
