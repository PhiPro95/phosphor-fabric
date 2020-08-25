package me.jellysquid.mods.phosphor.common.chunk.light;

import me.jellysquid.mods.phosphor.common.util.collections.DoubleBufferedLong2ObjectHashMap;
import net.minecraft.world.chunk.ChunkNibbleArray;

public interface SharedNibbleArrayMap {
    /**
     * Make this instance a copy of another, copying the object references from another instance into this one.
     * The shared copy cannot be directly written into.
     *
     * @param queue The queue of changed lightmaps
     */
    void makeSharedCopy(DoubleBufferedLong2ObjectHashMap<ChunkNibbleArray> queue);
}
