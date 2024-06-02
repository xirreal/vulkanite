package me.cortex.vulkanite.acceleration;

//TLAS manager, ingests blas build requests and manages builds and syncs the tlas

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.base.VObject;
import me.cortex.vulkanite.lib.base.VRef;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.descriptors.*;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Pair;
import net.minecraft.util.math.ChunkSectionPos;
import org.joml.Matrix4x3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class AccelerationTLASManager {
    private final EntityBlasBuilder entityBlasBuilder;
    private final TLASSectionManager buildDataManager = new TLASSectionManager();
    private final VContext context;
    private final int queue;
    private List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> entityData;

    public AccelerationTLASManager(VContext context, int queue) {
        this.context = context;
        this.queue = queue;
        this.buildDataManager.resizeBindlessSet(0);
        this.entityBlasBuilder = new EntityBlasBuilder(context);
    }

    private static int roundUpPow2(int v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v++;
        return v;
    }

    // Returns a sync semaphore to chain in the next command submit
    public void updateSections(List<AccelerationBlasBuilder.BLASBuildResult> results) {
        for (var result : results) {

            // boolean canAcceptResult = (!result.section().isDisposed()) && result.time()
            // >= result.section().lastAcceptedBuildTime;

            buildDataManager.update(result);
        }
    }

    public void setEntityData(List<Pair<RenderLayer, BufferBuilder.BuiltBuffer>> data) {
        this.entityData = data;
    }

    public void removeSection(RenderSection section) {
        buildDataManager.remove(section);
    }

    // TODO: cleanup, this is very messy
    // FIXME: in the case of no geometry create an empty tlas or something???
    public VRef<VAccelerationStructure> buildTLAS(VCmdBuff cmd) {
        RenderSystem.assertOnRenderThread();

        // NOTE: renderLink is required to ensure that we are not overriding memory that
        // is actively being used for frames
        // should have a VK_PIPELINE_STAGE_TRANSFER_BIT blocking bit
        try (var stack = stackPush()) {
            // The way the tlas build works is that terrain data is split up into regions,
            // each region is its own geometry input
            // this is done for performance reasons when updating (adding/removing) sections

            VkAccelerationStructureGeometryKHR geometry = VkAccelerationStructureGeometryKHR.calloc(stack);

            if (entityData != null) {
                var entityBuild = entityBlasBuilder.buildBlas(entityData, cmd);

                for (var entityBatch : entityBuild) {
                    if (entityBatch.offsets().isEmpty()) {
                        continue;
                    }

                    var entityASI = VkAccelerationStructureInstanceKHR.calloc(stack)
                            .mask(~0)
                            .instanceShaderBindingTableRecordOffset(1);
                    entityASI.transform().matrix(new Matrix4x3f().getTransposed(stack.mallocFloat(12)));

                    buildDataManager.addEphemeralInstance(cmd, entityASI, entityBatch.structure(), entityBatch.geometry(), entityBatch.offsets());
                    entityBatch.geometry().close();
                    entityBatch.structure().close();
                }
            }

            // getInstanceBuffer also builds / updates the geometry desc set
            var rets = buildDataManager.getInstanceBuffer();
            var instanceBuffer = rets.getLeft();
            int numInstances = rets.getRight();

            for (var holderRef : buildDataManager.activeSections.values()) {
                // Let the cmdbuf manage the lifetime of the holder & desc set entry
                cmd.moveRefGeneric(holderRef.addRefGeneric());
            }

            geometry.sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0);

            geometry.geometry()
                    .instances()
                    .sType$Default()
                    .arrayOfPointers(false);

            geometry.geometry()
                    .instances()
                    .data()
                    .deviceAddress(instanceBuffer.get().deviceAddress());

            // TLAS always rebuild & PREFER_FAST_TRACE according to Nvidia
            var buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                    .sType$Default()
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                    .pGeometries(VkAccelerationStructureGeometryKHR.create(geometry.address(), 1))
                    .geometryCount(1);

            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .calloc(stack)
                    .sType$Default();

            int[] instanceCounts = new int[]{numInstances};
            vkGetAccelerationStructureBuildSizesKHR(
                    context.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo.get(0), // The reason its a buffer is cause of pain and that
                    // vkCmdBuildAccelerationStructuresKHR requires a buffer of
                    // VkAccelerationStructureBuildGeometryInfoKHR
                    stack.ints(instanceCounts),
                    buildSizesInfo);

            var tlas = context.memory.createAcceleration(buildSizesInfo.accelerationStructureSize(),
                    256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);

            // TODO: instead of making a new scratch buffer, try to reuse
            // ACTUALLY wait since we doing the on fence free thing, we dont have to worry
            // about that and it should
            // get automatically freed since we using vma dont have to worry about
            // performance _too_ much i think
            var scratchBuffer = context.memory.createBuffer(buildSizesInfo.buildScratchSize(),
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);
            scratchBuffer.get().setDebugUtilsObjectName("TLAS Scratch Buffer");

            buildInfo.dstAccelerationStructure(tlas.get().structure)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack)
                            .deviceAddress(scratchBuffer.get().deviceAddress()));

            var buildRanges = VkAccelerationStructureBuildRangeInfoKHR.calloc(instanceCounts.length, stack);
            for (int count : instanceCounts) {
                buildRanges.get().primitiveCount(count);
            }
            buildRanges.rewind();

            cmd.encodeMemoryBarrier();

            vkCmdBuildAccelerationStructuresKHR(cmd.buffer(),
                    buildInfo,
                    stack.pointers(buildRanges));
            cmd.addBufferRef(instanceBuffer);
            cmd.addBufferRef(scratchBuffer);
            instanceBuffer.close();
            scratchBuffer.close();

            cmd.encodeMemoryBarrier();

            return tlas;
        }
    }

    public VRef<VDescriptorSet> getGeometrySet() {
        return buildDataManager.geometryBufferDescSet.addRef();
    }

    public VRef<VDescriptorSetLayout> getGeometryLayout() {
        return buildDataManager.geometryBufferSetLayout.addRef();
    }

    private static final class TlasPointerArena {
        private final BitSet vacant;
        public int maxIndex = 0;

        private TlasPointerArena(int size) {
            size *= 3;
            vacant = new BitSet(size);
            vacant.set(0, size);
        }

        public int allocate(int count) {
            int pos = vacant.nextSetBit(0);
            outer:
            while (pos != -1) {
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

        public void free(int pos, int count) {
            vacant.set(pos, pos + count);

            maxIndex = vacant.previousClearBit(maxIndex) + 1;
        }
    }

    // Manages entries in the VkAccelerationStructureInstanceKHR buffer, ment to
    // reuse as much as possible and be very efficient
    private class TLASGeometryManager {
        // Have a global buffer for VkAccelerationStructureInstanceKHR, then use
        // VkAccelerationStructureGeometryInstancesDataKHR.arrayOfPointers
        // Use LibCString.memmove to ensure streaming data is compact
        // Stream this to the gpu per frame (not ideal tbh, could implement a cache of
        // some kind)

        // Needs a gpu buffer for the instance data, this can be reused
        // private VkAccelerationStructureInstanceKHR.Buffer buffer;

        private final IntArrayFIFOQueue freeIds = new IntArrayFIFOQueue();
        private int maxInstances = 0;
        private VkAccelerationStructureInstanceKHR.Buffer instances = null;
        private int count = 0;
        // This maps each location in the instance buffer to an id
        private int[] loc2id = new int[maxInstances];
        // This maps each id to a location in the instance buffer
        private int[] id2loc = new int[maxInstances];

        public TLASGeometryManager() {
            resize(32768);
        }

        public void resize(int newSize) {
            if (newSize > maxInstances) {
                newSize = roundUpPow2(newSize);

                // Resize the instance buffer
                VkAccelerationStructureInstanceKHR.Buffer newBuffer = VkAccelerationStructureInstanceKHR
                        .calloc(newSize);
                if (instances != null) {
                    newBuffer.put(instances);
                    newBuffer.rewind();
                    instances.free();
                }
                instances = newBuffer;

                // Add new ids to the free list
                for (int i = maxInstances; i < newSize; i++) {
                    freeIds.enqueue(i);
                }

                // Resize the id mapping arrays
                int[] newLoc2Id = new int[newSize];
                int[] newId2Loc = new int[newSize];
                System.arraycopy(loc2id, 0, newLoc2Id, 0, count);
                System.arraycopy(id2loc, 0, newId2Loc, 0, count);
                loc2id = newLoc2Id;
                id2loc = newId2Loc;

                maxInstances = newSize;
            }
        }

        protected int alloc(VkAccelerationStructureInstanceKHR instance) {
            // Increment the count
            count++;
            resize(count);

            // Get a free id
            int id = freeIds.dequeueInt();

            // The instances buffer is dense
            // Allocation always append to the end
            loc2id[count - 1] = id;
            id2loc[id] = count - 1;

            // Copy the instance to the buffer
            MemoryUtil.memCopy(instance.address(), instances.address(count - 1), VkAccelerationStructureInstanceKHR.SIZEOF);

            return id;
        }

        protected void free(int id) {
            if (id < 0) {
                throw new IllegalArgumentException("Invalid id");
            }

            freeIds.enqueue(id);

            int loc = id2loc[id];
            id2loc[id] = -1;
            loc2id[loc] = -1;

            count--;
            if (loc != count) {
                // Move the last element to the hole
                int lastId = loc2id[count];
                loc2id[count] = -1;
                loc2id[loc] = lastId;
                id2loc[lastId] = loc;
                MemoryUtil.memCopy(instances.address(count), instances.address(loc),
                        VkAccelerationStructureInstanceKHR.SIZEOF);
            }
        }

        private final List<VkAccelerationStructureInstanceKHR> ephemeralInstances = new ArrayList<>();

        public void addEphemeralInstance(VkAccelerationStructureInstanceKHR asi) {
            var newASI = VkAccelerationStructureInstanceKHR.calloc();
            newASI.set(asi);
            ephemeralInstances.add(newASI);
        }

        public Pair<VRef<VBuffer>, Integer> getInstanceBuffer() {
            int count = this.count + ephemeralInstances.size();

            long size = VkAccelerationStructureInstanceKHR.SIZEOF * (long) count;
            if (size == 0) {
                size = VkAccelerationStructureInstanceKHR.SIZEOF;
            }
            var data = context.memory.createBuffer(size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            data.get().setDebugUtilsObjectName("TLAS Instance Buffer");

            long persistentSize = VkAccelerationStructureInstanceKHR.SIZEOF * (long) this.count;
            long ptr = data.get().map();
            if (persistentSize > 0) {
                MemoryUtil.memCopy(this.instances.address(0), ptr, persistentSize);
            }

            ptr = ptr + persistentSize;
            for (var asi : ephemeralInstances) {
                MemoryUtil.memCopy(asi.address(), ptr, VkAccelerationStructureInstanceKHR.SIZEOF);
                ptr += VkAccelerationStructureInstanceKHR.SIZEOF;
                asi.free();
            }
            ephemeralInstances.clear();

            data.get().unmap();
            data.get().flush();

            return new Pair<>(data, count);
        }
    }

    private final class TLASSectionManager extends TLASGeometryManager {
        private final TlasPointerArena arena = new TlasPointerArena(30000);
        private final ConcurrentLinkedDeque<AccelerationBlasBuilder.BLASBuildResult> sectionUpdates = new ConcurrentLinkedDeque<>();
        private final ConcurrentLinkedDeque<RenderSection> sectionRemovals = new ConcurrentLinkedDeque<>();
        private final Map<ChunkSectionPos, VRef<Holder>> activeSections = new HashMap<>();
        private final ArrayList<DescriptorUpdateJob> descriptorUpdateJobs = new ArrayList<>();
        private VRef<VDescriptorSetLayout> geometryBufferSetLayout;
        private VRef<VDescriptorSet> geometryBufferDescSet = null;
        private int setCapacity = 0;

        public TLASSectionManager() {
            super();
        }

        public void resizeBindlessSet(int newSize) {
            if (geometryBufferSetLayout == null) {
                var layoutBuilder = new DescriptorSetLayoutBuilder(
                        VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT);
                layoutBuilder.binding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 65536, VK_SHADER_STAGE_ALL);
                layoutBuilder.setBindingFlags(0,
                        VK_DESCRIPTOR_BINDING_VARIABLE_DESCRIPTOR_COUNT_BIT
                                | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT
                                | VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT);
                geometryBufferSetLayout = layoutBuilder.build(context);
            }

            if (newSize > setCapacity) {
                int newCapacity = roundUpPow2(Math.max(newSize, 32));
                var geometryBufferDescPool = VDescriptorPool.create(context, geometryBufferSetLayout, VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT, newCapacity);
                var newGeometryBufferDescSet = geometryBufferDescPool.get().allocateSet(newCapacity);

                System.out.println("New geometry desc set: " + Long.toHexString(newGeometryBufferDescSet.get().set)
                        + " with capacity " + newCapacity);

                if (geometryBufferDescSet != null) {
                    newGeometryBufferDescSet.get().copyFrom(context, geometryBufferDescSet, setCapacity);
                    geometryBufferDescSet.close();
                }

                geometryBufferDescSet = newGeometryBufferDescSet;
                setCapacity = newCapacity;
            }

        }

        @Override
        public Pair<VRef<VBuffer>, Integer> getInstanceBuffer() {
            HashSet<RenderSection> removals = new HashSet<>();
            {
                RenderSection section;
                while ((section = sectionRemovals.poll()) != null) {
                    removals.add(section);
                }
            }

            // Filter updates to only the latest
            HashMap<ChunkSectionPos, AccelerationBlasBuilder.BLASBuildResult> updates = new HashMap<>();
            {
                AccelerationBlasBuilder.BLASBuildResult result;
                while ((result = sectionUpdates.poll()) != null) {
                    var data = result.data();
                    var section = data.section();
                    if (removals.contains(section)) {
                        // Already removed, close the buffers and continue
                        result.structure().close();
                        data.geometryBuffer().close();
                    } else {
                        // We process the updates sequentially
                        // Older updates are overwritten
                        var key = section.getPosition();
                        if (updates.containsKey(key)) {
                            var prev = updates.get(key);
                            prev.structure().close();
                            prev.data().geometryBuffer().close();
                        }
                        updates.put(key, result);
                    }
                }
            }

            // Process removals
            for (var section : removals) {
                var prev = activeSections.remove(section.getPosition());
                if (prev != null) {
                    free(prev.get().id);
                    prev.close();
                }
            }

            int newGeoms = 0;
            for (var entry : updates.entrySet()) {
                newGeoms += entry.getValue().data().bufferOffsets().size();
            }
            resizeBindlessSet(Integer.max(arena.maxIndex + newGeoms, 1024));

            // Process updates
            if (!updates.isEmpty() || !descriptorUpdateJobs.isEmpty()) {
                var dub = new DescriptorUpdateBuilder(context, updates.size() + descriptorUpdateJobs.size());
                dub.set(geometryBufferDescSet);

                for (var entry : updates.entrySet()) {
                    var result = entry.getValue();
                    var data = result.data();
                    var section = data.section();
                    var posKey = section.getPosition();

                    var prevHolder = activeSections.remove(posKey);
                    if (prevHolder != null) {
                        free(prevHolder.get().id);
                        prevHolder.close();
                    }

                    int numGeometriesInInstance = data.bufferOffsets().size();
                    int geometryIndex = arena.allocate(numGeometriesInInstance);

                    // Add the geometry buffer to the descriptor set (the set retains another reference)
                    dub.buffer(0, geometryIndex, data.geometryBuffer(), data.bufferOffsets());
                    data.geometryBuffer().close();

                    int id;
                    try (var stack = stackPush()) {
                        var asi = VkAccelerationStructureInstanceKHR.calloc(stack)
                                .mask(~0)
                                .instanceCustomIndex(geometryIndex)
                                .accelerationStructureReference(result.structure().get().deviceAddress);
                        asi.transform()
                                .matrix(new Matrix4x3f()
                                        .translate(section.getOriginX(), section.getOriginY(),
                                                section.getOriginZ())
                                        .getTransposed(stack.mallocFloat(12)));

                        id = alloc(asi);
                    }

                    // Ownership of result.structure() is transferred to the holder
                    var holder = Holder.create(id, geometryIndex, numGeometriesInInstance, result.structure(), this);
                    activeSections.put(section.getPosition(), holder);
                }

                for (var job : descriptorUpdateJobs) {
                    dub.buffer(0, job.element, job.geometryBuffer, job.bufferOffsets);
                    job.geometryBuffer.close();
                }
                descriptorUpdateJobs.clear();

                dub.apply();
            }

            return super.getInstanceBuffer();
        }

        private void arenaFree(int index, int count) {
            arena.free(index, count);
            for (int i = 0; i < count; i++) {
                geometryBufferDescSet.get().removeRef(index + i);
            }
        }

        public void update(AccelerationBlasBuilder.BLASBuildResult result) {
            sectionUpdates.add(result);
        }

        public void remove(RenderSection section) {
            sectionRemovals.add(section);
        }

        public void addEphemeralInstance(VCmdBuff cmd, VkAccelerationStructureInstanceKHR asi, final VRef<VAccelerationStructure> structure, final VRef<VBuffer> geometryBuffer, List<Long> bufferOffsets) {
            if (bufferOffsets.isEmpty()) {
                return;
            }

            int numGeometries = bufferOffsets.size();
            int geometryIndex = arena.allocate(bufferOffsets.size());

            asi.accelerationStructureReference(structure.get().deviceAddress);
            asi.instanceCustomIndex(geometryIndex);

            addEphemeralInstance(asi);

            var holder = Holder.create(-1, geometryIndex, numGeometries, structure.addRef(), this);
            cmd.moveRefGeneric(holder.addRefGeneric());
            holder.close();

            descriptorUpdateJobs.add(new DescriptorUpdateJob(geometryIndex, geometryBuffer.addRef(), bufferOffsets));
        }

        public record DescriptorUpdateJob(int element, VRef<VBuffer> geometryBuffer, List<Long> bufferOffsets) {
        }

        // TODO: mixinto RenderSection and add a reference to a holder for us, its much
        // faster than a hashmap
        private static final class Holder extends VObject {
            // A holder holds (duh) a section and its associated data
            // The data might currently be in use by the gpu

            final int id;
            final TLASSectionManager manager;
            final int geometryIndex;
            final int numGeometries;
            final VRef<VAccelerationStructure> structure;

            private Holder(int id, int geometryIndex, int numGeometries, VRef<VAccelerationStructure> structure, TLASSectionManager manager) {
                this.id = id;
                this.geometryIndex = geometryIndex;
                this.numGeometries = numGeometries;
                this.structure = structure;
                this.manager = manager;
            }

            public static VRef<Holder> create(int id, int geometryIndex, int numGeometries, VRef<VAccelerationStructure> structure, TLASSectionManager manager) {
                return new VRef<>(new Holder(id, geometryIndex, numGeometries, structure, manager));
            }

            @Override
            protected void free() {
                structure.close();
                // This removes it from the geometry buffer & descriptor set
                manager.arenaFree(geometryIndex, numGeometries);
            }
        }
    }
}
