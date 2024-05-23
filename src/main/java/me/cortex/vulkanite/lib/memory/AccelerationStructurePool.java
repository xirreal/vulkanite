package me.cortex.vulkanite.lib.memory;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static me.cortex.vulkanite.lib.other.VUtil._CHECK_;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR;
import static org.lwjgl.vulkan.KHRAccelerationStructure.vkCreateAccelerationStructureKHR;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class AccelerationStructurePool {
    private static final int PAGE_SIZE = 1024;
    private static final int BLOCK_NUM_PAGES = 128 * 1024;
    private static final int BUFFER_USAGE = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;

    public static class Block {
        private final VRef<VBuffer> buffer;

        private final BitSet vacant;
        public int maxIndex = 0;

        private Block(MemoryManager memoryManager) {
            buffer = memoryManager.createBuffer(
                    PAGE_SIZE * BLOCK_NUM_PAGES,
                    BUFFER_USAGE,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    PAGE_SIZE,
                    0);
            vacant = new BitSet(BLOCK_NUM_PAGES);
            vacant.set(0, BLOCK_NUM_PAGES);
        }

        private int allocate_n_pages(int count) {
            int pos = vacant.nextSetBit(0);
            outer: while (pos != -1) {
                for (int offset = 1; offset < count; offset++) {
                    if (!vacant.get(offset + pos)) {
                        pos = vacant.nextSetBit(offset + pos + 1);
                        continue outer;
                    }
                }
                break;
            }
            if (pos == -1) {
                throw new IllegalStateException();
            }
            vacant.clear(pos, pos + count);
            maxIndex = Math.max(maxIndex, pos + count);
            return pos;
        }

        public void free_n_pages(int pos, int count) {
            vacant.set(pos, pos + count);

            maxIndex = vacant.previousClearBit(maxIndex) + 1;
        }

        public long allocate(long size) {
            int count = (int) Math.ceil((double) size / PAGE_SIZE);
            try {
                long pos = allocate_n_pages(count);
                return pos * PAGE_SIZE;
            } catch (IllegalStateException e) {
                return -1;
            }
        }

        public void free(long offset, long size) {
            int count = (int) Math.ceil((double) size / PAGE_SIZE);
            free_n_pages((int) (offset / PAGE_SIZE), count);
        }
    }

    public static class AccelerationStructurePooled extends VAccelerationStructure {
        private final Block block;
        private final long offset;
        private final long size;

        public AccelerationStructurePooled(VkDevice device, long structure, Block block, long offset, long size) {
            super(device, structure, block.buffer.addRef());
            this.block = block;
            this.offset = offset;
            this.size = size;
        }

        @Override
        public void free() {
            super.free();
            block.free(offset, size);
        }
    }

    private final List<Block> blocks = new ArrayList<>();

    private final VContext ctx;

    public AccelerationStructurePool(VContext ctx) {
        this.ctx = ctx;
        blocks.add(new Block(ctx.memory));
    }

    public VRef<VAccelerationStructure> createAcceleration(long size, int type) {
        if (size > PAGE_SIZE * BLOCK_NUM_PAGES) {
            return ctx.memory.createAcceleration(size, 256, BUFFER_USAGE, type);
        }

        Block block = null;
        long offset = -1;

        for (Block b : blocks) {
            offset = b.allocate(size);
            if (offset != -1) {
                block = b;
                break;
            }
        }

        if (offset == -1) {
            block = new Block(ctx.memory);
            blocks.add(block);
            offset = block.allocate(size);
        }

        AccelerationStructurePooled structure;

        try (var stack = stackPush()) {
            LongBuffer pAccelerationStructure = stack.mallocLong(1);
            _CHECK_(vkCreateAccelerationStructureKHR(ctx.device, VkAccelerationStructureCreateInfoKHR
                            .calloc(stack)
                            .sType$Default()
                            .type(type)
                            .size(size)
                            .buffer(block.buffer.get().buffer())
                            .offset(offset), null, pAccelerationStructure),
                    "Failed to create acceleration acceleration structure");
            structure = new AccelerationStructurePooled(ctx.device, pAccelerationStructure.get(0), block, offset, size);
        }

        return new VRef<>(structure);
    }

}
