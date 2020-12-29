package me.jellysquid.mods.phosphor.mixin.chunk.light;

import me.jellysquid.mods.phosphor.common.chunk.light.BlockLightStorageAccess;
import me.jellysquid.mods.phosphor.common.util.LightUtil;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.light.BlockLightStorage;
import net.minecraft.world.chunk.light.ChunkBlockLightProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkBlockLightProvider.class)
public abstract class MixinChunkBlockLightProvider extends MixinChunkLightProvider<BlockLightStorage.Data, BlockLightStorage> {
    @Shadow
    protected abstract int getLightSourceLuminance(long blockPos);

    @Shadow
    @Final
    private static Direction[] DIRECTIONS;

    /**
     * This breaks up the call to method_20479 into smaller parts so we do not have to pass a mutable heap object
     * to the method in order to extract the light result. This has a few other advantages, allowing us to:
     * - Avoid the de-optimization that occurs from allocating and passing a heap object
     * - Avoid unpacking coordinates twice for both the call to method_20479 and method_20710.
     * - Avoid the the specific usage of AtomicInteger, which has additional overhead for the atomic get/set operations.
     * - Avoid checking if the checked block is opaque twice.
     * - Avoid a redundant block state lookup by re-using {@param fromState}
     * <p>
     * The rest of the implementation has been otherwise copied from vanilla, but is optimized to avoid constantly
     * (un)packing coordinates and to use an optimized direction lookup function.
     *
     * @param fromState The re-usable block state at position {@param fromId}
     * @author JellySquid
     */
    public int getPropagatedLevel(final long propagationHandle, final int level) {
        final long targetHandle = this.handleProvider.getTargetHandle(propagationHandle);

        if (targetHandle == this.handleProvider.getMarkerWorldHandle()) {
            return 15;
        }

        final Direction dir = this.handleProvider.getDirection(propagationHandle);

        if (dir == null) {
            if (((BlockLightStorageAccess) this.lightStorage).isLightEnabled(targetHandle)) {
                // Disable blocklight sources before initial lighting
                return level + 15 - this.getLightSourceLuminance(this.handleProvider.getPackedPosFromWorldHandle(targetHandle));
            } else {
                return level;
            }
        }

        if (level >= 15) {
            return level;
        }

        final BlockState targetState = this.getCachedBlockState(targetHandle);

        if (targetState == null) {
            return 15;
        }

        int newLevel = this.getSubtractedLight(targetState, targetHandle);

        if (newLevel >= 15) {
            return 15;
        }

        final long sourceHandle = this.handleProvider.getSourceHandle(propagationHandle);
        final BlockState sourceState = this.getCachedBlockState(sourceHandle);

        final VoxelShape aShape = this.getOpaqueShape(sourceState, sourceHandle, dir);
        final VoxelShape bShape = this.getOpaqueShape(targetState, targetHandle, dir.getOpposite());

        if (!LightUtil.unionCoversFullCube(aShape, bShape)) {
            return level + Math.max(1, newLevel);
        }

        return 15;
    }

    @Override
    public void propagateLevel(final long worldHandle, final int level, final boolean increaseLight) {
        for (Direction dir : DIRECTIONS) {
            final long propagationHandle = this.handleProvider.createOutgoingPropagationHandle(worldHandle, dir);

            if (this.lightStorage.hasSection(this.handleProvider.getTargetHandle(propagationHandle))) {
                this.propagateLevel(propagationHandle, level, increaseLight);
            }

            this.handleProvider.releaseOutgoingPropagationHandle(propagationHandle);
        }
    }

    /**
     * @author PhiPro
     * @reason
     */
    @Overwrite
    protected int recalculateLevel(final long worldHandle, final long providedPropagation, final int providedLevel) {
        int level = providedLevel;

        final boolean isMarkerPropagation = providedPropagation == this.handleProvider.getMarkerPropagationHandle();
        final Direction providedDir = isMarkerPropagation ? null : this.handleProvider.getOppDirection(providedPropagation);

        if (providedDir != null || isMarkerPropagation) {
            final long propagationHandle = this.handleProvider.createSelfPropagationHandle(worldHandle);
            final int propagatedLevel = this.getPropagatedLevel(propagationHandle, 0);

            if (level > propagatedLevel) {
                level = propagatedLevel;
            }

            this.handleProvider.releasePropagationHandle(propagationHandle);

            if (level == 0) {
                return level;
            }
        }

        for(final Direction dir : DIRECTIONS) {
            if (dir != providedDir) {
                final long propagationHandle = this.handleProvider.createIncomingPropagationHandle(worldHandle, dir);
                final int propagatedLevel = this.getPropagatedLevel(propagationHandle, this.getLevel(this.handleProvider.getSourceHandle(propagationHandle)));

                if (level > propagatedLevel) {
                    level = propagatedLevel;
                }

                this.handleProvider.releaseIncomingPropagationHandle(propagationHandle);

                if (level == 0) {
                    return level;
                }
            }
        }

        return level;
    }

    @Redirect(
        method = "addLightSource(Lnet/minecraft/util/math/BlockPos;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/light/ChunkBlockLightProvider;updateLevel(JJIZ)V"
        )
    )
    private void passPropagationHandleToUpdateLevel(ChunkBlockLightProvider lightProvider, long excluded, long blockpos, int level, boolean decrease) {
        final long worldHandle = this.handleProvider.createWorldHandleForBlockPos(blockpos);
        this.updateLevel(this.handleProvider.createSelfPropagationHandle(worldHandle), worldHandle, level, decrease);
    }
}
