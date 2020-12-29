package me.jellysquid.mods.phosphor.common.util.chunk.light;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;

public class WorldHandleProvider {
    private static final int SECTION_INDEX_BITS = 20;
    private static final int WORLD_HANDLE_BITS = 10;

    private static final int SECTION_INDEX_SIZE = (1 << SECTION_INDEX_BITS) - 1;
    private static final int WORLD_HANDLE_MASK = (1 << WORLD_HANDLE_BITS) - 1;

    private static final Direction[] DECODE_DIRECTIONS = Arrays.copyOf(Direction.values(), 7);
    private static final int[] DIRECTION_OFFSETS;
    private static final int[] DIRECTION_OPPOSITE_INDEXES;
    private static final Direction[] DECODE_OPPOSITE_DIRECTIONS;
    private static final int LOCAL_MASK = (15 << 12) | (15 << 6) | 15;
    private static final int OVERFLOW_MASK = (16 << 12) | (16 << 6) | 16;

    private static final long[] SECTION_PROPAGATOR_MASKS;

    static {
        DIRECTION_OFFSETS = new int[6];

        for (final Direction dir : Direction.values()) {
            DIRECTION_OFFSETS[dir.ordinal()] = ((dir.getOffsetX() & 31) << 12) | ((dir.getOffsetY() & 31) << 6) | (dir.getOffsetZ() & 31);
        }

        DECODE_OPPOSITE_DIRECTIONS = new Direction[7];
        DIRECTION_OPPOSITE_INDEXES = new int[7];

        for (final Direction dir : Direction.values()) {
            DECODE_OPPOSITE_DIRECTIONS[dir.ordinal()] = dir.getOpposite();
            DIRECTION_OPPOSITE_INDEXES[dir.ordinal()] = dir.getOpposite().ordinal();
        }

        DIRECTION_OPPOSITE_INDEXES[6] = 6;

        SECTION_PROPAGATOR_MASKS = new long[6];

        for (final Direction dir : Direction.values()) {
            SECTION_PROPAGATOR_MASKS[dir.ordinal()] =
                ((dir.getOffsetX() != 0 ? 15L : 0L) << 30) |
                ((dir.getOffsetY() != 0 ? 15L : 0L) << 24) |
                ((dir.getOffsetZ() != 0 ? 15L : 0L) << 18) |
                (((dir.getOffsetX() >> 2) & 15) << 12) |
                (((dir.getOffsetY() >> 2) & 15) << 6) |
                ((dir.getOffsetZ() >> 2) & 15);
        }
    }

    public final int expectedChunkCount;
    public final int expectedSectionCount;
    public final int expectedHandleCount;

    private final Long2IntMap chunkPosToIndexMap;
    private long[] chunkPositions;
    private int chunkCount;

    private final Long2IntMap sectionPosToIndexMap;
    private long[] sectionPositions;
    private int[] sectionLinks;
    private int sectionCount;

    private int[] localWorldHandlePositions;
    private int[] revokedHandles;
    private int handleCount;
    private int revokedCount;
    private int nextIndex;

    public WorldHandleProvider(final int expectedChunkCount, final int expectdeSectionCount, final int expectedHandleCount) {
        this.expectedChunkCount = expectedChunkCount;
        this.expectedSectionCount = expectdeSectionCount;
        this.expectedHandleCount = expectedHandleCount;

        this.chunkPosToIndexMap = new Long2IntOpenHashMap(expectedChunkCount);
        this.sectionPosToIndexMap = new Long2IntOpenHashMap(expectdeSectionCount);

        this.resetCache();
    }

    protected void increaseChunkSize() {
        this.chunkPositions = Arrays.copyOf(this.chunkPositions, this.chunkPositions.length << 2);
    }

    protected void increaseSectionSize() {
        this.sectionPositions = Arrays.copyOf(this.sectionPositions, this.sectionPositions.length << 2);
        this.sectionLinks = Arrays.copyOf(this.sectionLinks, this.sectionLinks.length << 2);
    }

    protected void increaseWorldHandlesSize() {
        if (this.revokedHandles.length >= WORLD_HANDLE_BITS) {
            throw new IllegalStateException("Too many open world handles");
        }

        final int handlesSize = Math.min(this.revokedHandles.length << 2, WORLD_HANDLE_BITS);

        this.localWorldHandlePositions = Arrays.copyOf(this.localWorldHandlePositions, handlesSize);
        this.revokedHandles = Arrays.copyOf(this.revokedHandles, handlesSize);
    }

    public void resetCache() {
        if (this.chunkPositions == null || this.chunkPositions.length > this.expectedChunkCount) {
            this.chunkPositions = new long[this.expectedChunkCount];
        }

        this.chunkCount = 0;

        if (this.sectionPositions == null || this.sectionPositions.length > this.expectedSectionCount) {
            this.sectionPositions = new long[this.expectedSectionCount];
            this.sectionLinks = new int[this.expectedSectionCount << 3];
        }

        this.sectionCount = 0;

        if (this.revokedHandles == null || this.revokedHandles.length > this.expectedHandleCount) {
            this.localWorldHandlePositions = new int[this.expectedHandleCount];
            this.revokedHandles = new int[this.expectedHandleCount];
        }

        this.clearWorldHandles();
    }

    protected void prepareCacheForChunk(final int chunkIndex, final long chunkId) {}

    protected void prepareCacheForSection(final int sectionIndex, final long sectionId) {}

    protected void prepareCacheForWorldHandle(final int index, final long worldHandle) {}

    public int getChunkIndexFromId(final long chunkId) {
        return (int) chunkId;
    }

    public int getSectionIndexFromId(final long sectionId) {
        return (int) sectionId;
    }

    public int getIndexFromWorldHandle(final long worldHandle) {
        return ((int) worldHandle) & WORLD_HANDLE_MASK;
    }

    protected int getChunkIndexFromSectionIndex(final int sectionIndex) {
        return this.sectionLinks[sectionIndex << 3];
    }

    public int getChunkIndexFromSectionId(final long sectionId) {
        return this.getChunkIndexFromSectionIndex(this.getSectionIndexFromId(sectionId));
    }

    public long getChunkIdFromSectionId(final long sectionId) {
        return this.getChunkIndexFromSectionId(sectionId);
    }

    public int getChunkIndexFromWorldHandle(final long worldHandle) {
        return this.getChunkIndexFromSectionIndex(this.getSectionIndexFromWorldHandle(worldHandle));
    }

    public long getChunkIdFromWorldHandle(final long worldHandle) {
        return this.getChunkIdFromSectionId(this.getSectionIdFromWorldHandle(worldHandle));
    }

    public int getSectionIndexFromWorldHandle(final long worldHandle) {
        return ((int) worldHandle) >>> WORLD_HANDLE_BITS;
    }

    public int getSectionIdFromWorldHandle(final long worldHandle) {
        return this.getSectionIndexFromWorldHandle(worldHandle);
    }

    protected long getChunkPosFromIndex(final int chunkIndex) {
        return this.chunkPositions[chunkIndex];
    }

    public long getChunkPosFromId(final long chunkId) {
        return this.getChunkPosFromIndex(this.getChunkIndexFromId(chunkId));
    }

    protected long getSectionPosFromIndex(final int sectionIndex) {
        return this.sectionPositions[sectionIndex];
    }

    public long getSectionPosFromId(final long sectionId) {
        return this.getSectionPosFromIndex(this.getSectionIndexFromId(sectionId));
    }

    public long getIdFromWorldHandle(final long worldHandle) {
        return ((long) this.getSectionIndexFromWorldHandle(worldHandle) << 18) | this.getLocalPosMaskFromWorldHandle(worldHandle);
    }

    protected int getLocalPosMaskFromIndex(final int index) {
        return this.localWorldHandlePositions[index];
    }

    public int getLocalPosMaskFromWorldHandle(final long worldHandle) {
        return this.getLocalPosMaskFromIndex(this.getIndexFromWorldHandle(worldHandle));
    }

    public int getLocalXFromMask(final int localMask) {
        return localMask >>> 12;
    }

    public int getLocalYFromMask(final int localMask) {
        return (localMask >>> 6) & 15;
    }

    public int getLocalZFromMask(final int localMask) {
        return localMask & 15;
    }

    public int getLocalXFromWorldHandle(final long worldHandle) {
        return this.getLocalXFromMask(this.getLocalPosMaskFromWorldHandle(worldHandle));
    }

    public int getLocalYFromWorldHandle(final long worldHandle) {
        return this.getLocalYFromMask(this.getLocalPosMaskFromWorldHandle(worldHandle));
    }

    public int getLocalZFromWorldHandle(final long worldHandle) {
        return this.getLocalZFromMask(this.getLocalPosMaskFromWorldHandle(worldHandle));
    }

    public BlockPos getBlockPosFromWorldHandle(final long worldHandle) {
        final long sectionPos = this.getSectionPosFromIndex(this.getSectionIndexFromWorldHandle(worldHandle));
        final int localMask = this.getLocalPosMaskFromWorldHandle(worldHandle);

        final int sectionX = ChunkSectionPos.getBlockCoord(ChunkSectionPos.unpackX(sectionPos));
        final int sectionY = ChunkSectionPos.getBlockCoord(ChunkSectionPos.unpackY(sectionPos));
        final int sectionZ = ChunkSectionPos.getBlockCoord(ChunkSectionPos.unpackZ(sectionPos));

        final int localX = this.getLocalXFromMask(localMask);
        final int localY = this.getLocalYFromMask(localMask);
        final int localZ = this.getLocalZFromMask(localMask);

        return new BlockPos(sectionX + localX, sectionY + localY, sectionZ + localZ);
    }

    public long getPackedPosFromWorldHandle(final long worldHandle) {
        return this.getBlockPosFromWorldHandle(worldHandle).asLong();
    }

    public BlockPos fillBlockPosFromWorldHandle(final long worldHandle, final BlockPos.Mutable pos) {
        return pos.set(this.getBlockPosFromWorldHandle(worldHandle));
    }

    protected int getExistingChunkIndex(final long chunkPos) {
        return this.chunkPosToIndexMap.getOrDefault(chunkPos, -1);
    }

    protected long createChunkId(final long chunkPos) {
        final int index = this.chunkCount++;

        if (index == this.chunkPositions.length) {
            this.increaseChunkSize();
        }

        this.chunkPosToIndexMap.put(chunkPos, index);
        this.chunkPositions[index] = chunkPos;
        this.prepareCacheForChunk(index, index);

        return index;
    }

    protected int getOrCreateChunkIndex(final long chunkPos) {
        int chunkIndex = this.getExistingChunkIndex(chunkPos);

        if (chunkIndex == -1) {
            chunkIndex = this.getChunkIndexFromId(this.createChunkId(chunkPos));
        }

        return chunkIndex;
    }

    public long getExistingChunkId(final long chunkPos) {
        return this.getExistingChunkIndex(chunkPos);
    }

    public long getOrCreateChunkId(final long chunkPos) {
        return this.getOrCreateChunkIndex(chunkPos);
    }

    protected int getExistingSectionIndex(final long sectionPos) {
        return this.sectionPosToIndexMap.getOrDefault(sectionPos, -1);
    }

    protected long createSectionId(final long sectionPos) {
        final int index = this.sectionCount++;

        if (index == this.sectionPositions.length) {
            this.increaseSectionSize();
        }

        this.sectionPosToIndexMap.put(sectionPos, index);
        this.sectionPositions[index] = sectionPos;
        this.sectionLinks[index << 3] = this.getOrCreateChunkIndex(ChunkSectionPos.withZeroY(sectionPos));

        Arrays.fill(this.sectionLinks, (index << 3) + 1, (index << 3) + 8, -1);

        this.prepareCacheForSection(index, index);

        return index;
    }

    protected int getOrCreateSectionIndex(final long sectionPos) {
        int sectionIndex = this.getExistingSectionIndex(sectionPos);

        if (sectionPos == -1) {
            sectionIndex = this.getSectionIndexFromId(this.createSectionId(sectionPos));
        }

        return sectionIndex;
    }

    public long getExistingSectionId(final long sectionPos) {
        return this.getExistingSectionIndex(sectionPos);
    }

    public long getOrCreateSectionId(final long sectionPos) {
        return this.getOrCreateSectionIndex(sectionPos);
    }

    public long createWorldHandleForBlockPos(final long blockpos) {
        final int localMask = this.getLocalMask(BlockPos.unpackLongX(blockpos), BlockPos.unpackLongY(blockpos), BlockPos.unpackLongZ(blockpos));
        final int sectionIndex = this.getOrCreateSectionIndex(ChunkSectionPos.fromBlockPos(blockpos));
        return this.createWorldHandle(sectionIndex, localMask);
    }

    public long createWorldHandleForId(final long worldId) {
        return this.createWorldHandle((int) (worldId >>> 18), ((int) worldId) & ((1 << 18) - 1));
    }

    protected int getLocalMask(final int x, final int y, final int z) {
        return ((x & 15) << 12) | ((y & 15) << 6) | (z & 15);
    }

    protected int getExistingNeighborSectionIndex(final int sectionIndex, final int dirIndex) {
        return this.sectionLinks[(sectionIndex << 3) + 1 + dirIndex];
    }

    public long getExistingNeighborSectionId(final long sectionId, final Direction dir) {
        return this.getExistingNeighborSectionIndex(this.getSectionIndexFromId(sectionId), dir.ordinal());
    }

    protected int createNeighborSectionIndex(final int sectionIndex, final int dirIndex, final Direction dir) {
        final int neighborIndex = this.getOrCreateSectionIndex(ChunkSectionPos.offset(this.getSectionPosFromIndex(sectionIndex), dir));
        this.sectionLinks[(sectionIndex << 3) + 1 + dirIndex] = neighborIndex;

        return neighborIndex;
    }

    protected int getOrCreateNeighborSectionIndex(final int sectionIndex, final Direction dir) {
        final int dirIndex = dir.ordinal();
        int neighborIndex = this.getExistingNeighborSectionIndex(sectionIndex, dirIndex);

        if (neighborIndex == -1) {
            neighborIndex = this.createNeighborSectionIndex(sectionIndex, dirIndex, dir);
        }

        return neighborIndex;
    }

    public long getOrCreateNeighborSectionId(final long sectionId, final Direction dir) {
        return this.getOrCreateNeighborSectionIndex(this.getSectionIndexFromId(sectionId), dir);
    }

    private int createWorldHandleIndex() {
        int revokedCount = this.revokedCount;
        final boolean revokedAvailable = revokedCount > 0;

        final int newIndex = this.handleCount;
        this.handleCount += revokedAvailable ? 0 : 1;

        revokedCount -= revokedAvailable ? 1 : 0;
        this.revokedCount = revokedCount;
        final int revokedIndex = this.revokedHandles[revokedCount];

        final int index = this.nextIndex;
        this.nextIndex = revokedAvailable ? revokedIndex : newIndex;

        if (index == this.revokedHandles.length) {
            this.increaseWorldHandlesSize();
        }

        return index;
    }

    protected void prepareWorldHandle(final int index, final int localPos, final long handle) {
        this.localWorldHandlePositions[index] = localPos;
        this.prepareCacheForWorldHandle(index, handle);
    }

    protected int createUninitializedWorldHandle(final int sectionIndex, final int localPos) {
        final int index = this.createWorldHandleIndex();
        this.localWorldHandlePositions[index] = localPos;
        return (sectionIndex << WORLD_HANDLE_BITS) | index;
    }

    protected long createWorldHandle(final int sectionIndex, final int localPos) {
        final int index = this.createWorldHandleIndex();
        final long handle = (sectionIndex << WORLD_HANDLE_BITS) | index;
        this.prepareWorldHandle(index, localPos, handle);

        return handle;
    }

    public void releaseWorldHandle(final long worldHandle) {
        final int index = this.getIndexFromWorldHandle(worldHandle);
        this.nextIndex = index;
        this.revokedHandles[this.revokedCount++] = index;
    }

    public void clearWorldHandles() {
        this.nextIndex = 0;
        this.revokedCount = 0;
        this.handleCount = 0;
    }

    protected long createNeighborWorldHandle(final long worldHandle, final int dirIndex, final Direction dir) {
        final int sectionIndex = this.getSectionIndexFromWorldHandle(worldHandle);
        final int localPos = this.getLocalPosMaskFromWorldHandle(worldHandle);
        final int neighborSectionIndex = this.getExistingNeighborSectionIndex(sectionIndex, dirIndex);

        final int neighborMask = localPos + DIRECTION_OFFSETS[dirIndex];
        final boolean overflow = (neighborMask & OVERFLOW_MASK) != 0;
        final int neighborPos = neighborMask & LOCAL_MASK;

        int resSectionIndex = overflow ? neighborSectionIndex : sectionIndex;

        if (resSectionIndex == -1) {
            resSectionIndex = this.createNeighborSectionIndex(sectionIndex, dirIndex, dir);
        }

        return this.createWorldHandle(resSectionIndex, neighborPos);
    }

    public long createNeighborWorldHandle(final long worldHandle, final Direction dir) {
        return this.createNeighborWorldHandle(worldHandle, dir.ordinal(), dir);
    }

    public long getSourceHandle(final long propagationHandle) {
        return (propagationHandle >>> 3) & ((1 << 30) - 1);
    }

    public long getTargetHandle(final long propagationHandle) {
        return propagationHandle >>> 33;
    }

    protected int getDirIndex(final long propagationHandle) {
        return ((int) propagationHandle) & 7;
    }

    public Direction getDirection(final long propagationHandle) {
        return DECODE_DIRECTIONS[this.getDirIndex(propagationHandle)];
    }

    protected int getOppDirIndex(final long propagationHandle) {
        return DIRECTION_OPPOSITE_INDEXES[this.getDirIndex(propagationHandle)];
    }

    public Direction getOppDirection(final long propagationHandle) {
        return DECODE_OPPOSITE_DIRECTIONS[this.getDirIndex(propagationHandle)];
    }

    public long createOutgoingPropagationHandle(final long worldHandle, final Direction dir) {
        final int dirIndex = dir.ordinal();
        return ((this.createNeighborWorldHandle(worldHandle, dirIndex, dir)) << 33) | (worldHandle << 3) | dirIndex;
    }

    public long createIncomingPropagationHandle(final long worldHandle, final Direction dir) {
        return (worldHandle << 33) | (this.createNeighborWorldHandle(worldHandle, dir) << 3) | dir.getOpposite().ordinal();
    }

    public long createSelfPropagationHandle(final long worldHandle) {
        return (worldHandle << 33) | (worldHandle << 3) | 6;
    }

    public void releasePropagationHandle(final long propagationHandle) {}

    public void releaseOutgoingPropagationHandle(final long propagationHandle) {
        this.releaseWorldHandle(this.getTargetHandle(propagationHandle));
        this.releasePropagationHandle(propagationHandle);
    }

    public void releaseIncomingPropagationHandle(final long propagationHandle) {
        this.releaseWorldHandle(this.getSourceHandle(propagationHandle));
        this.releasePropagationHandle(propagationHandle);
    }

    public long getMarkerChunkId() {
        return Long.MIN_VALUE;
    }

    public long getMarkerSectionId() {
        return Long.MIN_VALUE;
    }

    public long getMarkerWorldHandle() {
        return Long.MIN_VALUE;
    }

    public long getMarkerPropagationHandle() {
        return Long.MIN_VALUE;
    }

    /**
     * Propagation handles derived from this are only valid until the next call to {@link #getCachedPropagationHandleFromSectionPropagator} and must not be released manually
     */
    public long createCachedSectionPropagator(final long sectionId, final Direction dir) {
        final int sectionIndex = this.getSectionIndexFromId(sectionId);
        final int neighborSectionIndex = this.getOrCreateNeighborSectionIndex(sectionIndex, dir);
        final int dirIndex = dir.ordinal();

        final int targetLocalPos = (int) SECTION_PROPAGATOR_MASKS[dirIndex] & LOCAL_MASK;
        final int srcLocalPos = targetLocalPos ^ (int) (SECTION_PROPAGATOR_MASKS[dirIndex] >>> 18);

        final int srcHandle = this.createUninitializedWorldHandle(sectionIndex, srcLocalPos);
        final int targetHandle = this.createUninitializedWorldHandle(neighborSectionIndex, targetLocalPos);

        return (((long) targetHandle) << 33) | (((long) srcHandle) << 3) | dirIndex;
    }

    public void releaseSectionPropagator(final long sectionPropagator) {
        this.releaseWorldHandle(this.getSourceHandle(sectionPropagator));
        this.releaseWorldHandle(this.getTargetHandle(sectionPropagator));
        this.releasePropagationHandle(sectionPropagator);
    }

    public long getCachedPropagationHandleFromSectionPropagator(final long sectionPropagator, final long iterator) {
        final long srcHandle = this.getSourceHandle(sectionPropagator);
        final long targetHandle = this.getTargetHandle(sectionPropagator);

        final int srcIndex = this.getIndexFromWorldHandle(srcHandle);
        final int targetIndex = this.getIndexFromWorldHandle(targetHandle);

        final int localMask = (int) (SECTION_PROPAGATOR_MASKS[this.getDirIndex(sectionPropagator)] >>> 18);
        final int localPos = ((int) iterator) & LOCAL_MASK & ~localMask;

        this.prepareWorldHandle(srcIndex, (this.localWorldHandlePositions[srcIndex] & localMask) | localPos, srcHandle);
        this.prepareWorldHandle(targetIndex, (this.localWorldHandlePositions[targetIndex] & localMask) | localPos, targetHandle);

        return sectionPropagator;
    }

    protected boolean hasAbstractIteratorFinished(final long iterator) {
        return (iterator & OVERFLOW_MASK) != 0;
    }

    protected long incrementAbstractIterator(final long iterator) {
        return (iterator + ((iterator >>> 18) & ((1 << 18) - 1))) & (iterator >> 36);
    }

    public long createSectionFaceIterator(final long sectionPropagator) {

    }

    public interface Callback {
        default void prepareCacheForChunk(int chunkIndex, long chunkId) {}

        default void prepareCacheForSection(int sectionIndex, long sectionId) {}

        default void prepareCacheForWorldHandle(int index, long worldHandle) {}

        default void releaseWorldHandle(long worldHandle) {}

        default void clearWorldHandles(int handleCount) {}

        default void increaseChunkSize(int chunkSize) {}

        default void increaseSectionSize(int sectionSize) {}

        default void increaseWorldHandlesSize(int handleSize) {}

        default void resetChunkCache(int chunkCount, int expectedChunkCount) {}

        default void resetSectionCache(int sectionCount, int expectedSectionCount) {}

        default void resetHandleCache(int expectedHandleCount) {}
    }
}
