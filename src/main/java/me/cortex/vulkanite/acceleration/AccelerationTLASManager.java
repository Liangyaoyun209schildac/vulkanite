package me.cortex.vulkanite.acceleration;

//TLAS manager, ingests blas build requests and manages builds and syncs the tlas

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.EntityRenderer;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkSection;
import org.joml.Matrix4x3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

public class AccelerationTLASManager {
    private final TLASSectionManager buildDataManager = new TLASSectionManager();
    private final VContext context;
    private final int queue;
    private final VCommandPool singleUsePool;

    private final List<VAccelerationStructure> structuresToRelease = new ArrayList<>();

    private VAccelerationStructure currentTLAS;

    public AccelerationTLASManager(VContext context, int queue) {
        this.context = context;
        this.queue = queue;
        this.singleUsePool = context.cmd.createSingleUsePool();
    }

    //Returns a sync semaphore to chain in the next command submit
    public void updateSections(List<AccelerationBlasBuilder.BLASBuildResult> results) {
        for (var result : results) {

            //boolean canAcceptResult = (!result.section().isDisposed()) && result.time() >= result.section().lastAcceptedBuildTime;

            buildDataManager.update(result);
        }
    }

    public void removeSection(RenderSection section) {
        buildDataManager.remove(section);
    }


    //THIS IS TEMPORARILY GOING HERE, TODO: FIXME
    private final EntityRenderer entityRenderer = new EntityRenderer();


    //TODO: cleanup, this is very messy
    //FIXME: in the case of no geometry create an empty tlas or something???
    public void buildTLAS(VSemaphore semIn, VSemaphore semOut, VSemaphore[] blocking) {
        RenderSystem.assertOnRenderThread();

        singleUsePool.doReleases();

        if (buildDataManager.sectionCount() == 0) {
            if (blocking.length != 0) {
                //This case can happen when reloading or some other weird cases, only occurse when the world _becomes_ empty for some reason, so just clear all the semaphores
                //TODO: move to a destroy method or something in AccelerationManager instead of here
                for (var semaphore : blocking) {
                    semaphore.free();
                }
            }
            return;
        }

        //NOTE: renderLink is required to ensure that we are not overriding memory that is actively being used for frames
        // should have a VK_PIPELINE_STAGE_TRANSFER_BIT blocking bit
        try (var stack = stackPush()) {
            //The way the tlas build works is that terrain data is split up into regions, each region is its own geometry input
            // this is done for performance reasons when updating (adding/removing) sections

            //This would also be where other geometries (such as entities) get added to the tlas TODO: implement entities

            //TODO: look into doing an update instead of a full tlas rebuild so instead just update the tlas to a new
            // acceleration structure!!!


            //The reason its done like this is so that entities and stuff can be easily added to the tlas manager



            var cmd = singleUsePool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT );
            VFence fence = context.sync.createFence();

            //Build the entities here cause it means that we can check if the result is null or not, can also just pass in the fence and cmd buffer
            var res = entityRenderer.render(0.001f, context, cmd, fence);

            int buildGeometryCount = res == null?1:2;

            VkAccelerationStructureGeometryKHR.Buffer geometries = VkAccelerationStructureGeometryKHR.calloc(buildGeometryCount, stack);
            int[] instanceCounts = new int[buildGeometryCount];




            {
                //TODO: need to sync with respect to updates from gpu memory updates from TLASBuildDataManager
                // OR SOMETHING CAUSE WITH MULTIPLE FRAMES GOING AT ONCE the gpu state of TLASBuildDataManager needs to be synced with
                // the current build phase, and the gpu side needs to be updated accoringly and synced correctly

                vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                        VkMemoryBarrier.calloc(1, stack)
                                .sType$Default()
                                .srcAccessMask(0)
                                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT),
                        null, null);

                buildDataManager.setGeometryUpdateMemory(cmd, fence, geometries.get(0));
                instanceCounts[0] = buildDataManager.sectionCount();

                vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0,
                        VkMemoryBarrier.calloc(1, stack)
                                .sType$Default()
                                .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                                .dstAccessMask(VK_ACCESS_SHADER_READ_BIT),
                        null, null);
            }

            {
                var struct = geometries.get(1);
                if (res != null) {
                    var asi = VkAccelerationStructureInstanceKHR.calloc(stack)
                            .mask(~0)
                            .instanceCustomIndex(0)
                            .accelerationStructureReference(res.getLeft().structure);
                    asi.transform()
                            .matrix(new Matrix4x3f()
                                    .translate(0,0,0)
                                    .getTransposed(stack.mallocFloat(12)));

                    VBuffer data = context.memory.createBuffer(VkAccelerationStructureInstanceKHR.SIZEOF,
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                    | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                    | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                            VK_MEMORY_HEAP_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                            0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
                    long ptr = data.map();
                    MemoryUtil.memCopy(asi.address(), ptr, VkAccelerationStructureInstanceKHR.SIZEOF);
                    data.unmap();
                    data.flush();

                    context.sync.addCallback(fence, () -> {
                        data.free();
                        res.getRight().free();
                        res.getLeft().free();
                    });


                    struct.sType$Default()
                            .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                            .flags(0);

                    struct.geometry()
                            .instances()
                            .sType$Default()
                            .arrayOfPointers(false);

                    struct.geometry()
                            .instances()
                            .data()
                            .deviceAddress(data.deviceAddress());
                }
                instanceCounts[1] = 1;
            }



            var buildInfo = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                    .sType$Default()
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)//TODO: explore using VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR to speedup build times
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .pGeometries(geometries)
                    .geometryCount(geometries.capacity());

            VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                    .calloc(stack)
                    .sType$Default();

            vkGetAccelerationStructureBuildSizesKHR(
                    context.device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo.get(0),//The reason its a buffer is cause of pain and that vkCmdBuildAccelerationStructuresKHR requires a buffer of VkAccelerationStructureBuildGeometryInfoKHR
                    stack.ints(instanceCounts),
                    buildSizesInfo);

            VAccelerationStructure tlas = context.memory.createAcceleration(buildSizesInfo.accelerationStructureSize(), 256,
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);

            //TODO: instead of making a new scratch buffer, try to reuse
            // ACTUALLY wait since we doing the on fence free thing, we dont have to worry about that and it should
            // get automatically freed since we using vma dont have to worry about performance _too_ much i think
            VBuffer scratchBuffer = context.memory.createBuffer(buildSizesInfo.buildScratchSize(),
                    VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);

            buildInfo.dstAccelerationStructure(tlas.structure)
                    .scratchData(VkDeviceOrHostAddressKHR.calloc(stack)
                            .deviceAddress(scratchBuffer.deviceAddress()));

            var buildRanges = VkAccelerationStructureBuildRangeInfoKHR.calloc(instanceCounts.length, stack);
            for (int count : instanceCounts) {
                buildRanges.get().primitiveCount(count);
            }
            buildRanges.rewind();

            vkCmdBuildAccelerationStructuresKHR(cmd.buffer,
                    buildInfo,
                    stack.pointers(buildRanges));

            cmd.end();

            int[] waitingStage = new int[blocking.length + 1];
            VSemaphore[] allBlocking = new VSemaphore[waitingStage.length];
            System.arraycopy(blocking, 0, allBlocking,0, blocking.length);

            allBlocking[waitingStage.length-1] = semIn;

            Arrays.fill(waitingStage, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_TRANSFER_BIT);
            context.cmd.submit(queue, new VCmdBuff[]{cmd}, allBlocking, waitingStage, new VSemaphore[]{semOut}, fence);

            VAccelerationStructure oldTLAS = currentTLAS;
            currentTLAS = tlas;

            List<VAccelerationStructure> capturedList = new ArrayList<>(structuresToRelease);
            structuresToRelease.clear();
            context.sync.addCallback(fence, () -> {
                scratchBuffer.free();
                if (oldTLAS != null) {
                    oldTLAS.free();
                }
                fence.free();
                cmd.enqueueFree();

                for (var as : capturedList) {
                    as.free();
                }

                //Release all the semaphores from the blas build system
                for (var sem : blocking) {
                    sem.free();
                }
            });
        }
    }

    public VAccelerationStructure getTlas() {
        return currentTLAS;
    }

    //Manages entries in the VkAccelerationStructureInstanceKHR buffer, ment to reuse as much as possible and be very efficient
    private class TLASGeometryManager {
        //Have a global buffer for VkAccelerationStructureInstanceKHR, then use
        // VkAccelerationStructureGeometryInstancesDataKHR.arrayOfPointers
        //Use LibCString.memmove to ensure streaming data is compact
        //  Stream this to the gpu per frame (not ideal tbh, could implement a cache of some kind)

        //Needs a gpu buffer for the instance data, this can be reused
        //private VkAccelerationStructureInstanceKHR.Buffer buffer;

        private VkAccelerationStructureInstanceKHR.Buffer instances = VkAccelerationStructureInstanceKHR.calloc(30000);
        private int[] instance2pointer = new int[30000];
        private int[] pointer2instance = new int[30000];
        private BitSet free = new BitSet(30000);//The reason this is needed is to give non used instance ids
        private int count;

        public TLASGeometryManager() {
            free.set(0, instance2pointer.length);
        }



        //TODO: make the instances buffer, gpu permenent then stream updates instead of uploading per frame
        public void setGeometryUpdateMemory(VCmdBuff cmd, VFence fence, VkAccelerationStructureGeometryKHR struct) {
            long size = (long) VkAccelerationStructureInstanceKHR.SIZEOF * count;
            VBuffer data = context.memory.createBuffer(size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            long ptr = data.map();
            MemoryUtil.memCopy(this.instances.address(0), ptr, size);
            data.unmap();
            data.flush();

            context.sync.addCallback(fence, () -> {
                data.free();
            });

            struct.sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                    .flags(0);

            struct.geometry()
                    .instances()
                    .sType$Default()
                    .arrayOfPointers(false);

            struct.geometry()
                    .instances()
                    .data()
                    .deviceAddress(data.deviceAddress());
        }

        public int sectionCount() {
            return count;
        }

        protected int alloc() {
            int id = free.nextSetBit(0);

            free.clear(id);

            //Update the map
            instance2pointer[id] = count;
            pointer2instance[count] = id;

            //Increment the count
            count++;

            return id;
        }

        protected void free(int id) {
            free.set(id);

            count--;
            if (instance2pointer[id] == count) {
                //We are at the end of the pointer list, so just decrement and be done
                instance2pointer[id] = -1;
                pointer2instance[count] = -1;
            } else {
                //TODO: CHECK THIS IS CORRECT

                //We need to remove the pointer, and fill it in with the last element in the pointer array, updating the mapping of the moved
                int ptrId = instance2pointer[id];
                instance2pointer[id] = -1;

                //I feel like this should be pointer2instance = pointer2instance
                pointer2instance[ptrId] = pointer2instance[count];

                //move over the ending data to the missing hole point
                MemoryUtil.memCopy(instances.address(count), instances.address(ptrId), VkAccelerationStructureInstanceKHR.SIZEOF);

                instance2pointer[pointer2instance[count]] = ptrId;
            }
        }

        protected void update(int id, VkAccelerationStructureInstanceKHR data) {
            MemoryUtil.memCopy(data.address(), instances.address(instance2pointer[id]), VkAccelerationStructureInstanceKHR.SIZEOF);
        }
    }

    private final class TLASSectionManager extends TLASGeometryManager {
        private final TlasPointerArena arena = new TlasPointerArena(30000);
        private final long arrayRef = MemoryUtil.nmemCalloc(30000 * 3, 8);
        public VBuffer geometryReferenceBuffer;

        @Override
        public void setGeometryUpdateMemory(VCmdBuff cmd, VFence fence, VkAccelerationStructureGeometryKHR struct) {
            super.setGeometryUpdateMemory(cmd, fence, struct);
            var ref = geometryReferenceBuffer;
            if (ref != null) {
                context.sync.addCallback(fence, ref::free);
            }
            geometryReferenceBuffer = context.memory.createBuffer(8L * arena.maxIndex,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                    0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            long ptr = geometryReferenceBuffer.map();
            MemoryUtil.memCopy(arrayRef, ptr, 8L * arena.maxIndex);
            geometryReferenceBuffer.unmap();
        }

        //TODO: mixinto RenderSection and add a reference to a holder for us, its much faster than a hashmap
        private static final class Holder {
            final int id;
            int geometryIndex = -1;
            List<VBuffer> geometryBuffers;

            final RenderSection section;
            VAccelerationStructure structure;

            private Holder(int id, RenderSection section) {
                this.id = id;
                this.section = section;
            }
        }

        Map<ChunkSectionPos, Holder> tmp = new HashMap<>();

        public void update(AccelerationBlasBuilder.BLASBuildResult result) {
            var data = result.data();
            var holder = tmp.computeIfAbsent(data.section().getPosition(), a -> new Holder(alloc(), data.section()));
            if (holder.structure != null) {
                structuresToRelease.add(holder.structure);
            }
            holder.structure = result.structure();

            if (holder.geometryIndex != -1) {
                arena.free(holder.geometryIndex, holder.geometryBuffers.size());
                holder.geometryBuffers.forEach(buffer -> Vulkanite.INSTANCE.addSyncedCallback(buffer::free));
            }
            holder.geometryBuffers = data.geometryBuffers();
            holder.geometryIndex = arena.allocate(holder.geometryBuffers.size());

            for (int i = 0; i < holder.geometryBuffers.size(); i++) {
                MemoryUtil.memPutAddress(arrayRef + 8L*(holder.geometryIndex+i), holder.geometryBuffers.get(i).deviceAddress());
            }


            try (var stack = stackPush()) {
                var asi = VkAccelerationStructureInstanceKHR.calloc(stack)
                        .mask(~0)
                        .instanceCustomIndex(holder.geometryIndex)
                        .accelerationStructureReference(holder.structure.deviceAddress);
                asi.transform()
                        .matrix(new Matrix4x3f()
                                .translate(holder.section.getOriginX(), holder.section.getOriginY(), holder.section.getOriginZ())
                                .getTransposed(stack.mallocFloat(12)));
                update(holder.id, asi);
            }
        }

        public void remove(RenderSection section) {
            var holder = tmp.remove(section.getPosition());
            if (holder == null)
                return;

            structuresToRelease.add(holder.structure);

            free(holder.id);

            if (holder.geometryIndex != -1) {
                arena.free(holder.geometryIndex, holder.geometryBuffers.size());
            }
            holder.geometryBuffers.forEach(buffer -> Vulkanite.INSTANCE.addSyncedCallback(buffer::free));
        }
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
            vacant.clear(pos, pos+count);
            maxIndex = Math.max(maxIndex, pos + count);
            return pos;
        }

        public void free(int pos, int count) {
            vacant.set(pos, pos+count);

            maxIndex = vacant.previousClearBit(maxIndex) + 1;
        }
    }

    public VBuffer getReferenceBuffer() {
        return buildDataManager.geometryReferenceBuffer;
    }

    //Called for cleaning up any remaining loose resources
    void cleanupTick() {
        singleUsePool.doReleases();
        structuresToRelease.forEach(VAccelerationStructure::free);
        structuresToRelease.clear();
        if (currentTLAS != null) {
            currentTLAS.free();
            currentTLAS = null;
        }
        if (buildDataManager.sectionCount() != 0) {
            throw new IllegalStateException("Sections are not empty on cleanup");
        }
    }
}
