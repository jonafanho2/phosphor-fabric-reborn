package net.caffeinemc.phosphor.mixin.chunk;

import net.caffeinemc.phosphor.common.chunk.light.IReadonly;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * An optimized implementation of DataLayer which uses bit-banging instead of a conditional to select
 * the right bit index of a nibble.
 */
@Mixin(DataLayer.class)
public abstract class MixinChunkNibbleArray implements IReadonly {
    @Shadow
    protected byte[] data;

    @Shadow
    private int defaultValue;

    @Shadow
    private static byte pack(int value) {
        throw new AssertionError();
    }

    /**
     * @reason Avoid an additional branch.
     * @author JellySquid
     */
    @Overwrite
    public int get(int idx) {
        byte[] arr = this.data;

        if (arr == null) {
            return this.defaultValue;
        }

        int byteIdx = idx >> 1;
        int shift = (idx & 1) << 2;

        return (arr[byteIdx] >>> shift) & 15;
    }

    /**
     * @reason Avoid an additional branch.
     * @author JellySquid
     */
    @Overwrite
    private void set(int idx, int value) {
        if (this.isReadonly()) {
            throw new UnsupportedOperationException("Cannot modify readonly DataLayer");
        }

        byte[] arr = this.data;

        if (arr == null) {
            this.data = (arr = new byte[2048]);
            if (this.defaultValue != 0) {
                java.util.Arrays.fill(arr, pack(this.defaultValue));
            }
        }

        int byteIdx = idx >> 1;
        int shift = (idx & 1) << 2;

        arr[byteIdx] = (byte) ((arr[byteIdx] & ~(15 << shift))
                | ((value & 15) << shift));
    }

    @Override
    public boolean isReadonly() {
        return false;
    }
}
