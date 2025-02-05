package net.caffeinemc.sodium.render.chunk.draw;

import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongList;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import net.caffeinemc.gfx.api.buffer.Buffer;
import net.caffeinemc.gfx.api.buffer.MappedBufferFlags;
import net.caffeinemc.gfx.api.device.RenderDevice;
import net.caffeinemc.gfx.api.device.commands.RenderCommandList;
import net.caffeinemc.gfx.api.pipeline.PipelineState;
import net.caffeinemc.gfx.api.pipeline.RenderPipeline;
import net.caffeinemc.gfx.api.types.ElementFormat;
import net.caffeinemc.gfx.api.types.PrimitiveType;
import net.caffeinemc.gfx.util.buffer.streaming.DualStreamingBuffer;
import net.caffeinemc.gfx.util.buffer.streaming.StreamingBuffer;
import net.caffeinemc.sodium.SodiumClientMod;
import net.caffeinemc.sodium.render.buffer.arena.BufferSegment;
import net.caffeinemc.sodium.render.chunk.passes.ChunkRenderPass;
import net.caffeinemc.sodium.render.chunk.passes.ChunkRenderPassManager;
import net.caffeinemc.sodium.render.chunk.region.RenderRegion;
import net.caffeinemc.sodium.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.sodium.render.shader.ShaderConstants;
import net.caffeinemc.sodium.render.terrain.format.TerrainVertexType;
import net.caffeinemc.gfx.util.misc.MathUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.lwjgl.system.MemoryUtil;

public class MdiChunkRenderer extends AbstractMdChunkRenderer<MdiChunkRenderer.MdiChunkRenderBatch> {
    public static final int COMMAND_STRUCT_STRIDE = 5 * Integer.BYTES;

    protected final StreamingBuffer commandBuffer;

    public MdiChunkRenderer(
            RenderDevice device,
            ChunkCameraContext camera,
            ChunkRenderPassManager renderPassManager,
            TerrainVertexType vertexType
    ) {
        super(device, camera, renderPassManager, vertexType);

        int maxInFlightFrames = SodiumClientMod.options().advanced.cpuRenderAheadLimit + 1;
        
        this.commandBuffer = new DualStreamingBuffer(
                device,
                1,
                1048576, // start with 1 MiB and expand from there if needed
                maxInFlightFrames,
                EnumSet.of(MappedBufferFlags.EXPLICIT_FLUSH)
        );
    }
    
    @Override
    protected ShaderConstants.Builder addAdditionalShaderConstants(ShaderConstants.Builder constants) {
        constants.add("BASE_INSTANCE_INDEX");
        constants.add("MAX_BATCH_SIZE", String.valueOf(RenderRegion.REGION_SIZE));
        return constants;
    }

    @Override
    public int getDeviceBufferObjects() {
        return super.getDeviceBufferObjects() + 1;
    }

    @Override
    public long getDeviceUsedMemory() {
        return super.getDeviceUsedMemory() +
               this.commandBuffer.getDeviceUsedMemory();
    }

    @Override
    public long getDeviceAllocatedMemory() {
        return super.getDeviceAllocatedMemory() +
               this.commandBuffer.getDeviceAllocatedMemory();
    }

    @Override
    public void createRenderLists(SortedTerrainLists lists, int frameIndex) {
        if (lists.isEmpty()) {
            this.renderLists = null;
            return;
        }
    
        BlockPos cameraBlockPos = this.camera.getBlockPos();
        float cameraDeltaX = this.camera.getDeltaX();
        float cameraDeltaY = this.camera.getDeltaY();
        float cameraDeltaZ = this.camera.getDeltaZ();

        ChunkRenderPass[] chunkRenderPasses = this.renderPassManager.getAllRenderPasses();
        int totalPasses = chunkRenderPasses.length;

        // setup buffers, resizing as needed
        int commandsRequiredSize = commandsRequiredSize(this.commandBuffer.getAlignment(), lists);
        StreamingBuffer.WritableSection commandBufferSection = this.commandBuffer.getSection(
                frameIndex,
                commandsRequiredSize,
                false
        );
        ByteBuffer commandBufferSectionView = commandBufferSection.getView();
        long commandBufferSectionAddress = MemoryUtil.memAddress0(commandBufferSectionView);

        int transformsRequiredSize = indexedTransformsRequiredSize(this.uniformBufferChunkTransforms.getAlignment(), lists);
        StreamingBuffer.WritableSection transformBufferSection = this.uniformBufferChunkTransforms.getSection(
                frameIndex,
                transformsRequiredSize,
                false
        );
        ByteBuffer transformBufferSectionView = transformBufferSection.getView();
        long transformBufferSectionAddress = MemoryUtil.memAddress0(transformBufferSectionView);
    
        int largestVertexIndex = 0;
        int commandBufferPosition = commandBufferSectionView.position();
        int transformBufferPosition = transformBufferSectionView.position();
    
        @SuppressWarnings("unchecked")
        Collection<MdiChunkRenderBatch>[] renderLists = new Collection[totalPasses];
    
        for (int passId = 0; passId < chunkRenderPasses.length; passId++) {
            ChunkRenderPass renderPass = chunkRenderPasses[passId];
            Deque<MdiChunkRenderBatch> renderList = new ArrayDeque<>(128); // just an estimate, should be plenty

            var pass = lists.builtPasses[passId];
            IntList passRegionIndices = pass.regionIndices;
            int passRegionCount = passRegionIndices.size();
        
            boolean reverseOrder = renderPass.isTranslucent();
        
            int regionIdx = reverseOrder ? passRegionCount - 1 : 0;
            while (reverseOrder ? (regionIdx >= 0) : (regionIdx < passRegionCount)) {
                var builtRegion = pass.builtRegions.get(regionIdx);
                IntList regionPassModelPartCounts = builtRegion.modelPartCounts;
                LongList regionPassModelPartSegments = builtRegion.modelPartSegments;
                IntList regionPassSectionIndices = builtRegion.sectionIndices;
            
                int fullRegionIdx = passRegionIndices.getInt(regionIdx);
                RenderRegion region = lists.regions.get(fullRegionIdx);
                IntList regionSectionCoords = lists.sectionCoords.get(fullRegionIdx);
                LongList regionUploadedSegments = lists.uploadedSegments.get(fullRegionIdx);
            
                int regionPassSectionCount = regionPassSectionIndices.size();
            
                // don't use regionIdx or fullRegionIdx past here
                if (reverseOrder) {
                    regionIdx--;
                } else {
                    regionIdx++;
                }
            
                int regionPassModelPartIdx = reverseOrder ? regionPassModelPartSegments.size() - 1 : 0;
                int regionPassModelPartCount = 0;
                int regionPassTransformCount = 0;
                int sectionIdx = reverseOrder ? regionPassSectionCount - 1 : 0;
                while (reverseOrder ? (sectionIdx >= 0) : (sectionIdx < regionPassSectionCount)) {
                    int sectionModelPartCount = regionPassModelPartCounts.getInt(sectionIdx);
                
                    int fullSectionIdx = regionPassSectionIndices.getInt(sectionIdx);
                    long sectionUploadedSegment = regionUploadedSegments.getLong(fullSectionIdx);
                
                    int sectionCoordsIdx = fullSectionIdx * 3;
                    int sectionCoordX = regionSectionCoords.getInt(sectionCoordsIdx);
                    int sectionCoordY = regionSectionCoords.getInt(sectionCoordsIdx + 1);
                    int sectionCoordZ = regionSectionCoords.getInt(sectionCoordsIdx + 2);
                    // don't use fullSectionIdx or sectionIdx past here
                    if (reverseOrder) {
                        sectionIdx--;
                    } else {
                        sectionIdx++;
                    }
                
                    // this works because the segment is in units of vertices
                    int baseVertex = BufferSegment.getOffset(sectionUploadedSegment);
                
                    for (int i = 0; i < sectionModelPartCount; i++) {
                        long modelPartSegment = regionPassModelPartSegments.getLong(regionPassModelPartIdx);
                    
                        // don't use regionPassModelPartIdx past here (in this loop)
                        if (reverseOrder) {
                            regionPassModelPartIdx--;
                        } else {
                            regionPassModelPartIdx++;
                        }
                    
                        long ptr = commandBufferSectionAddress + commandBufferPosition;
                        MemoryUtil.memPutInt(ptr, 6 * (BufferSegment.getLength(modelPartSegment) >> 2)); // go from vertex count -> index count
                        MemoryUtil.memPutInt(ptr + 4, 1); // instance count
                        MemoryUtil.memPutInt(ptr + 8, 0); // first index
                        MemoryUtil.memPutInt(ptr + 12, baseVertex + BufferSegment.getOffset(modelPartSegment)); // baseVertex
                        MemoryUtil.memPutInt(ptr + 16, regionPassTransformCount); // baseInstance
                        commandBufferPosition += COMMAND_STRUCT_STRIDE;
                    }
    
                    regionPassModelPartCount += sectionModelPartCount;
    
                    float x = getCameraTranslation(
                            ChunkSectionPos.getBlockCoord(sectionCoordX),
                            cameraBlockPos.getX(),
                            cameraDeltaX
                    );
                    float y = getCameraTranslation(
                            ChunkSectionPos.getBlockCoord(sectionCoordY),
                            cameraBlockPos.getY(),
                            cameraDeltaY
                    );
                    float z = getCameraTranslation(
                            ChunkSectionPos.getBlockCoord(sectionCoordZ),
                            cameraBlockPos.getZ(),
                            cameraDeltaZ
                    );
    
                    long ptr = transformBufferSectionAddress + transformBufferPosition;
                    MemoryUtil.memPutFloat(ptr, x);
                    MemoryUtil.memPutFloat(ptr + 4, y);
                    MemoryUtil.memPutFloat(ptr + 8, z);
                    transformBufferPosition += TRANSFORM_STRUCT_STRIDE;
                    regionPassTransformCount++;
                
                    largestVertexIndex = Math.max(largestVertexIndex, BufferSegment.getLength(sectionUploadedSegment));
                }
    
                int commandSubsectionLength = regionPassModelPartCount * COMMAND_STRUCT_STRIDE;
                long commandSubsectionStart = commandBufferSection.getDeviceOffset()
                                              + commandBufferPosition - commandSubsectionLength;
                commandBufferPosition = MathUtil.align(
                        commandBufferPosition,
                        this.commandBuffer.getAlignment()
                );
    
                int transformSubsectionLength = regionPassTransformCount * TRANSFORM_STRUCT_STRIDE;
                long transformSubsectionStart = transformBufferSection.getDeviceOffset()
                                                + transformBufferPosition - transformSubsectionLength;
                transformBufferPosition = MathUtil.align(
                        transformBufferPosition,
                        this.uniformBufferChunkTransforms.getAlignment()
                );
            
                renderList.add(new MdiChunkRenderBatch(
                        region.getVertexBuffer().getBufferObject(),
                        region.getVertexBuffer().getStride(),
                        regionPassModelPartCount,
                        transformSubsectionStart,
                        commandSubsectionStart
                ));
            }
        
            renderLists[passId] = renderList;
        }
        
        commandBufferSectionView.position(commandBufferPosition);
        transformBufferSectionView.position(transformBufferPosition);

        commandBufferSection.flushPartial();
        transformBufferSection.flushPartial();
        
        this.indexBuffer.ensureCapacity(largestVertexIndex);
        
        this.renderLists = renderLists;
    }

    @Override
    public void delete() {
        super.delete();
        this.commandBuffer.delete();
    }

    protected static class MdiChunkRenderBatch extends MdChunkRenderBatch {
        protected final long commandBufferOffset;

        public MdiChunkRenderBatch(
                Buffer vertexBuffer,
                int vertexStride,
                int commandCount,
                long transformBufferOffset,
                long commandBufferOffset
        ) {
            super(vertexBuffer, vertexStride, commandCount, transformBufferOffset);
            this.commandBufferOffset = commandBufferOffset;
        }

        public long getCommandBufferOffset() {
            return this.commandBufferOffset;
        }
    }

    protected static int commandsRequiredSize(int alignment, SortedTerrainLists lists) {
        int size = 0;

        for (var pass : lists.builtPasses) {
            for (var region : pass.builtRegions) {
                size = MathUtil.align(size + (region.modelPartSegments.size() * COMMAND_STRUCT_STRIDE), alignment);
            }
        }

        return size;
    }
    
    protected static int indexedTransformsRequiredSize(int alignment, SortedTerrainLists lists) {
        int size = 0;
        
        for (var pass : lists.builtPasses) {
            for (var region : pass.builtRegions) {
                size = MathUtil.align(size + (region.sectionIndices.size() * TRANSFORM_STRUCT_STRIDE), alignment);
            }
        }
        
        return size;
    }
    
    @Override
    protected void setupPerRenderList(
            ChunkRenderPass renderPass,
            ChunkRenderMatrices matrices,
            int frameIndex,
            RenderPipeline<ChunkShaderInterface, BufferTarget> renderPipeline,
            RenderCommandList<BufferTarget> commandList,
            ChunkShaderInterface programInterface,
            PipelineState pipelineState
    ) {
        super.setupPerRenderList(
                renderPass,
                matrices,
                frameIndex,
                renderPipeline,
                commandList,
                programInterface,
                pipelineState
        );
        
        commandList.bindCommandBuffer(this.commandBuffer.getBufferObject());
    }
    
    @Override
    protected void setupPerBatch(
            ChunkRenderPass renderPass,
            ChunkRenderMatrices matrices,
            int frameIndex,
            RenderPipeline<ChunkShaderInterface, BufferTarget> renderPipeline,
            RenderCommandList<BufferTarget> commandList,
            ChunkShaderInterface programInterface,
            PipelineState pipelineState,
            MdiChunkRenderBatch batch
    ) {
        super.setupPerBatch(
                renderPass,
                matrices,
                frameIndex,
                renderPipeline,
                commandList,
                programInterface,
                pipelineState,
                batch
        );
    
        pipelineState.bindBufferBlock(
                programInterface.uniformChunkTransforms,
                this.uniformBufferChunkTransforms.getBufferObject(),
                batch.getTransformsBufferOffset(),
                RenderRegion.REGION_SIZE * TRANSFORM_STRUCT_STRIDE // the spec requires that the entire part of the UBO is filled completely, so lets just make the range the right size
        );
    }
    
    @Override
    protected void issueDraw(
            ChunkRenderPass renderPass,
            ChunkRenderMatrices matrices,
            int frameIndex,
            RenderPipeline<ChunkShaderInterface, BufferTarget> renderPipeline,
            RenderCommandList<BufferTarget> commandList,
            ChunkShaderInterface programInterface,
            PipelineState pipelineState,
            MdiChunkRenderBatch batch
    ) {
        commandList.multiDrawElementsIndirect(
                PrimitiveType.TRIANGLES,
                ElementFormat.UNSIGNED_INT,
                batch.getCommandBufferOffset(),
                batch.getCommandCount(),
                0
        );
    }
    
    @Override
    public String getDebugName() {
        return "MDI";
    }
}
