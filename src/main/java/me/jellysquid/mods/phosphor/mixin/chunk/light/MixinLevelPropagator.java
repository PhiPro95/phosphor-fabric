package me.jellysquid.mods.phosphor.mixin.chunk.light;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongList;
import me.jellysquid.mods.phosphor.common.chunk.level.LevelUpdateListener;
import me.jellysquid.mods.phosphor.common.chunk.light.LevelPropagatorAccess;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.light.LevelPropagator;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.function.LongPredicate;

@Mixin(LevelPropagator.class)
public abstract class MixinLevelPropagator implements LevelUpdateListener, LevelPropagatorAccess {
    @Shadow
    @Final
    private Long2ByteMap pendingUpdates;

    @Shadow
    protected abstract int getLevel(long id);

    @Shadow
    @Final
    private int levelCount;

    @Shadow
    protected abstract int getPropagatedLevel(long sourceId, long targetId, int level);

    @Shadow
    protected abstract void updateLevel(long sourceId, long id, int level, boolean decrease);

    @Shadow
    protected abstract void updateLevel(long sourceId, long id, int level, int currentLevel, int pendingLevel, boolean decrease);

    @Override
    @Invoker("propagateLevel")
    public abstract void invokePropagateLevel(long sourceId, long targetId, int level, boolean decrease);

    @Unique
    protected int getPropagatedLevel(final long propagationHandle, final int level) {
        throw new UnsupportedOperationException();
    }

    @Unique
    protected long getTargetHandle(final long propagationHandle) {
        throw new UnsupportedOperationException();
    }

    @Unique
    protected long getIdFromHandle(final long worldHandle) {
        return worldHandle;
    }

    @Unique
    protected int getPendingLevel(final long worldHandle) {
        return this.pendingUpdates.get(this.getIdFromHandle(worldHandle));
    }

    @Unique
    protected long createMarkerPropagationHandleForResetLevel(final long worldHandle) {
        return worldHandle;
    }

    @Unique
    protected long createHandleForId(final long worldId) {
        return worldId;
    }

    @Unique
    protected void releaseHandle(final long worldHandle) {}

    @Unique
    protected void clearHandles() {}

    // [VanillaCopy] LevelPropagator#propagateLevel(long, long, int, boolean)
    @Unique
    protected void propagateLevel(final long propagationHandle, final int level, final boolean decrease) {
        final long targetHandle = this.getTargetHandle(propagationHandle);

        final int pendingLevel = this.getPendingLevel(targetHandle) & 0xFF;

        final int propagatedLevel = this.getPropagatedLevel(propagationHandle, level);
        final int clampedLevel = MathHelper.clamp(propagatedLevel, 0, this.levelCount - 1);

        if (decrease) {
            this.updateLevel(propagationHandle, targetHandle, clampedLevel, this.getLevel(targetHandle), pendingLevel, true);
        } else {
            final boolean flag;
            final int resultLevel;

            if (pendingLevel == 0xFF) {
                flag = true;
                resultLevel = MathHelper.clamp(this.getLevel(targetHandle), 0, this.levelCount - 1);
            } else {
                flag = false;
                resultLevel = pendingLevel;
            }

            if (clampedLevel == resultLevel) {
                this.updateLevel(propagationHandle, targetHandle, this.levelCount - 1, flag ? resultLevel : this.getLevel(targetHandle), pendingLevel, false);
            }
        }
    }

    @Redirect(
        method = {
            "updateLevel(JJIZ)V",
            "removePendingUpdate(J)V"
        },
        slice = @Slice(
            from = @At(
                value = "FIELD",
                opcode = Opcodes.GETFIELD,
                target = "Lnet/minecraft/world/chunk/light/LevelPropagator;pendingUpdates:Lit/unimi/dsi/fastutil/longs/Long2ByteMap;"
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ByteMap;get(J)B",
            ordinal = 0
        )
    )
    private byte getPendingLevelFromHandle(final Long2ByteMap pendingUpdates, final long worldHandle) {
        return (byte) this.getPendingLevel(worldHandle);
    }

    @ModifyArg(
        method = "resetLevel(J)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/chunk/light/LevelPropagator;updateLevel(JJIZ)V"
        ),
        index = 0
    )
    private long useMarkerPropagationHandleForResetLevel(final long worldHandle) {
        return this.createMarkerPropagationHandleForResetLevel(worldHandle);
    }

    @ModifyVariable(
        method = {
            "addPendingUpdate(JII)V",
            "removePendingUpdate(JIIZ)V"
        },
        at = @At(
            value = "FIELD",
            opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/world/chunk/light/LevelPropagator;pendingIdUpdatesByLevel:[Lit/unimi/dsi/fastutil/longs/LongLinkedOpenHashSet;",
            ordinal = 0
        ),
        index = 1
    )
    private long useIdForUpdateQueue(final long worldHandle) {
        return this.getIdFromHandle(worldHandle);
    }

    @Shadow
    protected abstract void removePendingUpdate(long id);

    @Shadow
    protected abstract void propagateLevel(long sourceId, long targetId, int level, boolean decrease);

    /**
     * @author PhiPro
     * @reason
     */
    @Overwrite
    public void removePendingUpdateIf(final LongPredicate predicate) {
        final LongList longList = new LongArrayList();

        this.pendingUpdates.keySet().forEach((long id) -> {
            final long handle = this.createHandleForId(id);
            if (predicate.test(handle)) {
                longList.add(id);
            }
            this.releaseHandle(handle);
        });

        longList.forEach((long id) -> {
            final long handle = this.createHandleForId(id);
            this.removePendingUpdate(handle);
            this.releaseHandle(handle);
        });
    }

    @Redirect(
        method = "applyPendingUpdates(I)I",
        slice = @Slice(
            from = @At(
                value = "FIELD",
                opcode = Opcodes.GETFIELD,
                target = "Lnet/minecraft/world/chunk/light/LevelPropagator;pendingIdUpdatesByLevel:[Lit/unimi/dsi/fastutil/longs/LongLinkedOpenHashSet;"
            )
        ),
        at = @At(
            value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongLinkedOpenHashSet;removeFirstLong()J",
            ordinal = 0
        )
    )
    private long createHandleForUpdate(final LongLinkedOpenHashSet pendingUpdates) {
        this.clearHandles();
        return this.createHandleForId(pendingUpdates.removeFirstLong());
    }

    @Redirect(method = { "removePendingUpdate(JIIZ)V", "applyPendingUpdates" }, at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ByteMap;remove(J)B", remap = false))
    private byte redirectRemovePendingUpdate(Long2ByteMap map, long handle) {
        byte ret = map.remove(this.getIdFromHandle(handle));

        if (ret != map.defaultReturnValue()) {
            this.onPendingUpdateRemoved(handle);
        }

        return ret;
    }

    @Redirect(method = "addPendingUpdate", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ByteMap;put(JB)B", remap = false))
    private byte redirectAddPendingUpdate(Long2ByteMap map, long handle, byte value) {
        byte ret = map.put(this.getIdFromHandle(handle), value);

        if (ret == map.defaultReturnValue()) {
            this.onPendingUpdateAdded(handle);
        }

        return ret;
    }

    @Override
    public void onPendingUpdateAdded(long key) {
        // NO-OP
    }

    @Override
    public void onPendingUpdateRemoved(long key) {
        // NO-OP
    }
}
