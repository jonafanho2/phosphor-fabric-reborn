package net.caffeinemc.phosphor.mixin.chunk.light;

import net.caffeinemc.phosphor.common.chunk.light.SharedBlockLightData;
import net.caffeinemc.phosphor.common.util.collections.DoubleBufferedLong2ObjectHashMap;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(BlockLightSectionStorage.BlockDataLayerStorageMap.class)
public abstract class MixinBlockLightStorageData extends MixinChunkToNibbleArrayMap implements SharedBlockLightData {
    @Override
    public void makeSharedCopy(final DoubleBufferedLong2ObjectHashMap<DataLayer> queue) {
        super.makeSharedCopy(queue);
    }

    /**
     * @reason Use double-buffering to avoid copying
     * @author JellySquid
     */
    @SuppressWarnings("ConstantConditions")
    @Overwrite
    public BlockLightSectionStorage.BlockDataLayerStorageMap copy() {
        // This will be called immediately by LightStorage in the constructor
        // We can take advantage of this fact to initialize our extra properties here without additional hacks
        if (!this.isInitialized()) {
            this.init();
        }

        BlockLightSectionStorage.BlockDataLayerStorageMap data = new BlockLightSectionStorage.BlockDataLayerStorageMap(this.map);
        ((SharedBlockLightData) (Object) data).makeSharedCopy(this.getUpdateQueue());

        return data;
    }
}
