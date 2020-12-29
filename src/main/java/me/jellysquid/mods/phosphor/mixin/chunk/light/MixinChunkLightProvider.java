package me.jellysquid.mods.phosphor.mixin.chunk.light;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.jellysquid.mods.phosphor.common.block.BlockStateLightInfo;
import me.jellysquid.mods.phosphor.common.block.BlockStateLightInfoAccess;
import me.jellysquid.mods.phosphor.common.chunk.level.LevelUpdateListener;
import me.jellysquid.mods.phosphor.common.chunk.light.*;
import me.jellysquid.mods.phosphor.common.util.chunk.light.LightHandleProvider;
import me.jellysquid.mods.phosphor.common.util.chunk.light.WorldHandleProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.chunk.*;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.world.chunk.light.LightStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.BitSet;

@Mixin(ChunkLightProvider.class)
public abstract class MixinChunkLightProvider<M extends ChunkToNibbleArrayMap<M>, S extends LightStorage<M>>
        extends MixinLevelPropagator
        implements LightProviderUpdateTracker, LightInitializer, LevelUpdateListener, InitialLightingAccess, WorldHandleProvider.Callback {
    @Unique
    private static final BlockState DEFAULT_STATE = Blocks.AIR.getDefaultState();

    @Mutable
    @Final
    protected WorldHandleProvider handleProvider;

    @Unique
    private BlockView[] blockViews;
    @Unique
    private ChunkSection[] sections;
    @Unique
    private BlockState[] states;

    @Shadow
    @Final
    protected BlockPos.Mutable reusableBlockPos;

    @Shadow
    @Final
    protected ChunkProvider chunkProvider;

    private final Long2ObjectOpenHashMap<BitSet> buckets = new Long2ObjectOpenHashMap<>();

    private long prevChunkBucketKey = ChunkPos.MARKER;
    private BitSet prevChunkBucketSet;

    @Inject(
        method = "<init>",
        at = @At("TAIL")
    )
    private void setHandleProvider(final CallbackInfo ci) {
        this.handleProvider = this.createHandleProvider();
    }

    @Override
    protected long getTargetHandle(long propagationHandle) {
        return this.handleProvider.getTargetHandle(propagationHandle);
    }

    @Override
    protected long getIdFromHandle(long worldHandle) {
        return this.handleProvider.getIdFromWorldHandle(worldHandle);
    }

    @Override
    protected long createMarkerPropagationHandleForResetLevel(long worldHandle) {
        return this.handleProvider.getMarkerPropagationHandle();
    }

    @Override
    protected long createHandleForId(long worldId) {
        return this.handleProvider.createWorldHandleForId(worldId);
    }

    @Override
    protected void releaseHandle(long worldHandle) {
        this.handleProvider.releaseWorldHandle(worldHandle);
    }

    @Override
    protected void clearHandles() {
        this.handleProvider.clearWorldHandles();
    }

    /**
     * @author PhiPro
     * @reason
     */
    @Overwrite
    protected boolean isMarker(long worldHandle) {
        return worldHandle == this.handleProvider.getMarkerWorldHandle();
    }

    @Redirect(
        method = "resetLevel(J)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/math/ChunkSectionPos;fromBlockPos(J)J"
        )
    )
    private long useSectionId(final long worldHandle) {
        return this.handleProvider.getSectionIdFromWorldHandle(worldHandle);
    }

    @ModifyArg(
        method = "checkBlock(Lnet/minecraft/util/math/BlockPos;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/light/ChunkLightProvider;resetLevel(J)V"
        ),
        index = 0
    )
    private long useWorldHandleForResetLevel(final long blockpos) {
        return this.handleProvider.createWorldHandleForBlockPos(blockpos);
    }

    @Override
    public void increaseChunkSize(final int chunkSize) {
        this.blockViews = Arrays.copyOf(this.blockViews, chunkSize);
    }

    @Override
    public void increaseSectionSize(final int sectionSize) {
        this.sections = Arrays.copyOf(this.sections, sectionSize);
    }

    @Override
    public void increaseWorldHandlesSize(final int handleSize) {
        this.states = Arrays.copyOf(this.states, handleSize);
    }

    @Override
    public void prepareCacheForChunk(final int chunkIndex, final long chunkId) {
        final long chunkPos = this.handleProvider.getChunkPosFromId(chunkId);
        BlockView blockView = this.chunkProvider.getChunk(ChunkSectionPos.unpackX(chunkPos), ChunkSectionPos.unpackZ(chunkPos));

        if (blockView == null) {
            blockView = EmptyBlockView.INSTANCE;
        }

        this.blockViews[chunkIndex] = blockView;
    }

    @Override
    public void prepareCacheForSection(final int sectionIndex, final long sectionId) {
        final BlockView blockView = this.getCachedBlockView(this.handleProvider.getChunkIndexFromSectionId(sectionId));
        ChunkSection section = null;

        if (blockView != EmptyBlockView.INSTANCE) {
            final int y = ChunkSectionPos.unpackY(this.handleProvider.getSectionPosFromId(sectionId));
            final ChunkSection[] sections = ((Chunk) blockView).getSectionArray();

            if (y >= 0 && y < sections.length) {
                section = sections[y];
            }
        }

        this.sections[sectionIndex] = section;
    }

    @Override
    public void prepareCacheForWorldHandle(final int index, final long worldHandle) {
        this.states[index] = null;
    }

    @Override
    public void resetChunkCache(int chunkCount, int expectedChunkCount) {
        if (this.blockViews == null || this.blockViews.length > expectedChunkCount) {
            this.blockViews = new BlockView[expectedChunkCount];
        } else {
            Arrays.fill(this.blockViews, 0, chunkCount, null);
        }
    }

    @Override
    public void resetSectionCache(int sectionCount, int expectedSectionCount) {
        if (this.sections == null || this.sections.length > expectedSectionCount) {
            this.sections = new ChunkSection[expectedSectionCount];
        } else {
            Arrays.fill(this.sections, 0, sectionCount, null);
        }
    }

    @Unique
    protected BlockView getCachedBlockView(final int chunkIndex) {
        return this.blockViews[chunkIndex];
    }

    @Unique
    protected ChunkSection getCachedChunkSection(final int sectionIndex) {
        return this.sections[sectionIndex];
    }

    /**
     * Returns the BlockState which represents the block at the specified coordinates in the world. This may return
     * a different BlockState than what actually exists at the coordinates (such as if it is out of bounds), but will
     * always represent a state with valid light properties for that coordinate.
     */
    @Unique
    protected BlockState getCachedBlockState(final long worldHandle) {
        final int index = this.handleProvider.getIndexFromWorldHandle(worldHandle);
        BlockState state = this.states[index];

        if (state == null) {
            state = this.getUncachedBlockState(worldHandle);
            this.states[index] = state;
        }

        return state;
    }

    protected BlockState getUncachedBlockState(final long worldHandle) {
        final ChunkSection section = this.getCachedChunkSection(this.handleProvider.getSectionIndexFromWorldHandle(worldHandle));

        if (ChunkSection.isEmpty(section)) {
            return DEFAULT_STATE;
        } else {
            final int localMask = this.handleProvider.getLocalPosMaskFromWorldHandle(worldHandle);
            final int x = this.handleProvider.getLocalXFromMask(localMask);
            final int y = this.handleProvider.getLocalYFromMask(localMask);
            final int z = this.handleProvider.getLocalZFromMask(localMask);

            return section.getBlockState(x, y, z);
        }
    }

    // [VanillaCopy] method_20479
    /**
     * Returns the amount of light which is blocked at the specified coordinates by the BlockState.
     */
    @Unique
    protected int getSubtractedLight(final BlockState state, final long worldHandle) {
        BlockStateLightInfo info = ((BlockStateLightInfoAccess) state).getLightInfo();

        if (info != null) {
            return info.getLightSubtracted();
        } else {
            return this.getSubtractedLightFallback(state, worldHandle);
        }
    }

    @Unique
    private int getSubtractedLightFallback(final BlockState state, final long worldHandle) {
        return state.getBlock().getOpacity(
            state,
            this.chunkProvider.getWorld(),
            this.handleProvider.fillBlockPosFromWorldHandle(worldHandle, this.reusableBlockPos)
        );
    }

    // [VanillaCopy] method_20479
    /**
     * Returns the VoxelShape of a block for lighting without making a second call to
     * {@link LightProviderBlockAccess#getBlockStateForLighting(int, int, int)}.
     */
    @Unique
    public VoxelShape getOpaqueShape(final BlockState state, final long worldHandle, final Direction dir) {
        if (state != null && state.hasSidedTransparency()) {
            BlockStateLightInfo info = ((BlockStateLightInfoAccess) state).getLightInfo();

            if (info != null) {
                VoxelShape[] extrudedFaces = info.getExtrudedFaces();

                if (extrudedFaces != null) {
                    return extrudedFaces[dir.ordinal()];
                }
            } else {
                return this.getOpaqueShapeFallback(state, worldHandle, dir);
            }
        }

        return VoxelShapes.empty();
    }

    @Unique
    private VoxelShape getOpaqueShapeFallback(final BlockState state, final long worldHandle, final Direction dir) {
        return VoxelShapes.extrudeFace(
            state.getCullingShape(
                this.chunkProvider.getWorld(),
                this.handleProvider.fillBlockPosFromWorldHandle(worldHandle, this.reusableBlockPos)
            ),
            dir
        );
    }

    @Override
    public void spreadLightInto(long a, long b) {
        this.propagateLevel(a, b, this.getLevel(a), false);
        this.propagateLevel(b, a, this.getLevel(b), false);
    }

    /**
     * The vanilla implementation for removing pending light updates requires iterating over either every queued light
     * update (<8K checks) or every block position within a sub-chunk (16^3 checks). This is painfully slow and results
     * in a tremendous amount of CPU time being spent here when chunks are unloaded on the client and server.
     *
     * To work around this, we maintain a bit-field of queued updates by chunk position so we can simply select every
     * light update within a section without excessive iteration. The bit-field only requires 64 bytes of memory per
     * section with queued updates, and does not require expensive hashing in order to track updates within it. In order
     * to avoid as much overhead as possible when looking up a bit-field for a given chunk section, the previous lookup
     * is cached and used where possible. The integer key for each bucket can be computed by performing a simple bit
     * mask over the already-encoded block position value.
     */
    @Override
    public void cancelUpdatesForChunk(long sectionPos) {
        long key = getBucketKeyForSection(sectionPos);
        BitSet bits = this.removeChunkBucket(key);

        if (bits != null && !bits.isEmpty()) {
            int startX = ChunkSectionPos.unpackX(sectionPos) << 4;
            int startY = ChunkSectionPos.unpackY(sectionPos) << 4;
            int startZ = ChunkSectionPos.unpackZ(sectionPos) << 4;

            for (int i = bits.nextSetBit(0); i != -1; i = bits.nextSetBit(i + 1)) {
                int x = (i >> 8) & 15;
                int y = (i >> 4) & 15;
                int z = i & 15;

                this.removePendingUpdate(BlockPos.asLong(startX + x, startY + y, startZ + z));
            }
        }
    }

    @Override
    public void onPendingUpdateRemoved(long blockPos) {
        long key = getBucketKeyForBlock(blockPos);

        BitSet bits;

        if (this.prevChunkBucketKey == key) {
            bits = this.prevChunkBucketSet;
        } else {
            bits = this.buckets.get(key);

            if (bits == null) {
                return;
            }
        }

        bits.clear(getLocalIndex(blockPos));

        if (bits.isEmpty()) {
            this.removeChunkBucket(key);
        }
    }

    @Override
    public void onPendingUpdateAdded(long blockPos) {
        long key = getBucketKeyForBlock(blockPos);

        BitSet bits;

        if (this.prevChunkBucketKey == key) {
            bits = this.prevChunkBucketSet;
        } else {
            bits = this.buckets.get(key);

            if (bits == null) {
                this.buckets.put(key, bits = new BitSet(16 * 16 * 16));
            }

            this.prevChunkBucketKey = key;
            this.prevChunkBucketSet = bits;
        }

        bits.set(getLocalIndex(blockPos));
    }

    // Used to mask a long-encoded block position into a bucket key by dropping the first 4 bits of each component
    private static final long BLOCK_TO_BUCKET_KEY_MASK = ~BlockPos.asLong(15, 15, 15);

    private long getBucketKeyForBlock(long blockPos) {
        return blockPos & BLOCK_TO_BUCKET_KEY_MASK;
    }

    private long getBucketKeyForSection(long sectionPos) {
        return BlockPos.asLong(ChunkSectionPos.unpackX(sectionPos) << 4, ChunkSectionPos.unpackY(sectionPos) << 4, ChunkSectionPos.unpackZ(sectionPos) << 4);
    }

    private BitSet removeChunkBucket(long key) {
        BitSet set = this.buckets.remove(key);

        if (this.prevChunkBucketSet == set) {
            this.prevChunkBucketKey = ChunkPos.MARKER;
            this.prevChunkBucketSet = null;
        }

        return set;
    }

    // Finds the bit-flag index of a local position within a chunk section
    private static int getLocalIndex(long blockPos) {
        int x = BlockPos.unpackLongX(blockPos) & 15;
        int y = BlockPos.unpackLongY(blockPos) & 15;
        int z = BlockPos.unpackLongZ(blockPos) & 15;

        return (x << 8) | (y << 4) | z;
    }

    @Shadow
    @Final
    protected S lightStorage;

    @Shadow
    protected abstract int getCurrentLevelFromSection(ChunkNibbleArray section, long blockPos);

    @Shadow
    protected void resetLevel(long id) {}

    /**
     * @author PhiPro
     * @reason Re-implement completely. Change specification of the method.
     * Now controls both source light and light updates. Disabling now additionally removes all data associated to the chunk.
     */
    @Overwrite
    public void setColumnEnabled(final ChunkPos pos, final boolean enabled) {
        final long chunkPos = ChunkSectionPos.withZeroY(ChunkSectionPos.asLong(pos.x, 0, pos.z));
        final LightStorageAccess lightStorage = (LightStorageAccess) this.lightStorage;

        if (enabled) {
            lightStorage.invokeSetColumnEnabled(chunkPos, true);
            lightStorage.setLightUpdatesEnabled(chunkPos, true);
        } else {
            lightStorage.setLightUpdatesEnabled(chunkPos, false);
        }
    }

    @Override
    public void enableSourceLight(final long chunkPos) {
        ((LightStorageAccess) this.lightStorage).invokeSetColumnEnabled(chunkPos, true);
    }

    @Override
    public void enableLightUpdates(final long chunkPos) {
        ((LightStorageAccess) this.lightStorage).setLightUpdatesEnabled(chunkPos, true);
    }
}
