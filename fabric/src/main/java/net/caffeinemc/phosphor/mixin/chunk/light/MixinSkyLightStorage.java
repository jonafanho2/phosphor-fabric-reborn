package net.caffeinemc.phosphor.mixin.chunk.light;

import it.unimi.dsi.fastutil.ints.IntIterable;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.caffeinemc.phosphor.common.chunk.light.IReadonly;
import net.caffeinemc.phosphor.common.chunk.light.LevelPropagatorAccess;
import net.caffeinemc.phosphor.common.chunk.light.SkyLightStorageAccess;
import net.caffeinemc.phosphor.common.chunk.light.SkyLightStorageDataAccess;
import net.caffeinemc.phosphor.common.util.chunk.light.EmptyChunkNibbleArray;
import net.caffeinemc.phosphor.common.util.chunk.light.SkyLightChunkNibbleArray;
import net.caffeinemc.phosphor.common.util.math.ChunkSectionPosHelper;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightSectionStorage;
import org.apache.commons.lang3.ArrayUtils;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.Arrays;
import java.util.concurrent.locks.StampedLock;

@Mixin(SkyLightSectionStorage.class)
public abstract class MixinSkyLightStorage extends MixinLightStorage implements SkyLightStorageAccess {
    /**
     * An optimized implementation which avoids constantly unpacking and repacking integer coordinates.
     *
     * @reason Use faster implementation
     * @author JellySquid
     */
    @Overwrite
    public int getLightValue(long pos) {
        int posX = BlockPos.getX(pos);
        int posYOrig = BlockPos.getY(pos);
        int posZ = BlockPos.getZ(pos);

        int chunkX = SectionPos.blockToSectionCoord(posX);
        int chunkYOrig = SectionPos.blockToSectionCoord(posYOrig);
        int chunkZ = SectionPos.blockToSectionCoord(posZ);

        long chunkOrig = SectionPos.asLong(chunkX, chunkYOrig, chunkZ);

        StampedLock lock = this.uncachedLightArraysLock;
        long stamp;

        DataLayer array;

        optimisticRead:
        while (true) {
            stamp = lock.tryOptimisticRead();

            int posY = posYOrig;
            int chunkY = chunkYOrig;
            long chunk = chunkOrig;

            DataLayerStorageMap<?> data = this.visibleSectionData;
            SkyLightStorageDataAccess sdata = (SkyLightStorageDataAccess) data;

            int height = sdata.getHeight(SectionPos.getZeroNode(chunk));

            if (height == sdata.getDefaultHeight() || chunkY >= height) {
                if (lock.validate(stamp)) {
                    return 15;
                } else {
                    continue;
                }
            }

            array = data.getLayer(chunk);

            while (array == null) {
                ++chunkY;

                if (chunkY >= height) {
                    if (lock.validate(stamp)) {
                        return 15;
                    } else {
                        continue optimisticRead;
                    }
                }

                chunk = ChunkSectionPosHelper.updateYLong(chunk, chunkY);
                array = data.getLayer(chunk);

                posY = chunkY << 4;
            }

            if (lock.validate(stamp)) {
                return array.get(
                    SectionPos.sectionRelative(posX),
                    SectionPos.sectionRelative(posY),
                    SectionPos.sectionRelative(posZ)
                );
            }
        }
    }

    @Shadow
    protected abstract boolean lightOnInSection(long sectionPos);

    @Override
    public int getLightWithoutLightmap(final long blockPos) {
        final long sectionPos = SectionPos.blockToSection(blockPos);
        final DataLayer lightmap = this.getLightmapAbove(sectionPos);

        if (lightmap == null) {
            return this.lightOnInSection(sectionPos) ? 15 : 0;
        }

        return lightmap.get(
            SectionPos.sectionRelative(BlockPos.getX(blockPos)),
            0,
            SectionPos.sectionRelative(BlockPos.getZ(blockPos))
        );
    }

    @Redirect(
        method = "createDataLayer(J)Lnet/minecraft/world/level/chunk/DataLayer;",
        at = @At(
            value = "NEW",
            target = "()Lnet/minecraft/world/level/chunk/DataLayer;"
        )
    )
    private DataLayer initializeLightmap(final long pos) {
        if (this.lightOnInSection(pos)) {
            return new DataLayer(15);
        }

        return new DataLayer();
    }

    @Override
    protected void beforeChunkEnabled(final long chunkPos) {
        // Initialize height data
        int minHeight = Integer.MAX_VALUE;
        int height = Integer.MIN_VALUE;

        for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
            final int y = it.nextInt();
            final long sectionPos = ChunkSectionPosHelper.updateYLong(chunkPos, y);

            if (y < minHeight && (this.nonOptimizableSections.contains(sectionPos) || this.getDataLayer(sectionPos, true) != null)) {
                minHeight = y;
            }

            if (y > height && this.readySections.contains(sectionPos)) {
                height = y;
            }
        }

        this.updateMinHeight(minHeight);

        if (height != Integer.MIN_VALUE) {
            this.setHeight(chunkPos, height);
        }

        // Remove lightmaps above the topmost non-empty section
        for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
            final int y = it.nextInt();

            if (y <= height) {
                continue;
            }

            final long sectionPos = ChunkSectionPosHelper.updateYLong(chunkPos, y);

            if (this.updatingSectionData.removeLayer(sectionPos) != null) {
                this.untrackSection(chunkPos, y);
                this.dirtySections.add(sectionPos);
            }
        }

        this.updatingSectionData.clearCache();

        // Initialize Vanilla lightmap complexities
        for (final IntIterator it = this.getTrackedSections(chunkPos); it.hasNext(); ) {
            final long sectionPos = ChunkSectionPosHelper.updateYLong(chunkPos, it.nextInt());
            final DataLayer lightmap = this.getDataLayer(sectionPos, true);

            if (lightmap != null) {
                this.initializeVanillaLightmapComplexity(sectionPos, lightmap);
            }
        }
    }

    @Override
    protected void afterChunkDisabled(final long chunkPos, final IntIterable removedLightmaps) {
        for (IntIterator it = removedLightmaps.iterator(); it.hasNext(); ) {
            this.vanillaLightmapComplexities.remove(ChunkSectionPosHelper.updateYLong(chunkPos, it.nextInt()));
        }

        ((SkyLightStorageDataAccess) this.updatingSectionData).setHeight(chunkPos, this.getMinHeight());
        this.scheduledHeightChecks.remove(chunkPos);
        this.scheduledHeightIncreases.remove(chunkPos);
    }

    @Unique
    private final LongSet initSkylightChunks = new LongOpenHashSet();

    @Shadow
    @Final
    private LongSet columnsWithSkySources;

    @Shadow
    @Final
    private LongSet sectionsWithSources;

    @Unique
    private final Long2IntMap vanillaLightmapComplexities = new Long2IntOpenHashMap();

    @Unique
    private final LongSet scheduledHeightChecks = new LongOpenHashSet();

    @Unique
    private final LongSet scheduledHeightIncreases = new LongOpenHashSet();

    @Unique
    private static final Direction[] HORIZONTAL_DIRECTIONS = new Direction[]{
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    @Unique
    private DataLayer getLightmapAbove(final long sectionPos) {
        final int y = SectionPos.y(sectionPos);
        final long chunkPos = SectionPos.getZeroNode(sectionPos);
        final int height = ((SkyLightStorageDataAccess) this.updatingSectionData).getHeight(chunkPos);

        for (int curY = y + 1; curY < height; ++curY) {
            final DataLayer lightmap = this.getDataLayer(ChunkSectionPosHelper.updateYLong(chunkPos, curY), true);

            if (lightmap != null) {
                return lightmap;
            }
        }

        return null;
    }

    @Unique
    private void setHeight(final long chunkPos, final int height) {
        final SkyLightStorageDataAccess data = (SkyLightStorageDataAccess) this.updatingSectionData;
        final int oldHeight = data.getHeight(chunkPos);

        data.setHeight(chunkPos, height);

        if (height > oldHeight) {
            this.scheduledHeightIncreases.add(chunkPos);
            this.scheduledHeightChecks.remove(chunkPos);
        } else if (height < oldHeight) {
            this.scheduledHeightChecks.add(chunkPos);
            this.scheduledHeightIncreases.remove(chunkPos);
        }
    }

    @Unique
    private int getMinHeight() {
        return ((SkyLightStorageDataAccess) this.updatingSectionData).getDefaultHeight();
    }

    @Unique
    private void updateMinHeight(final int minHeight) {
        final SkyLightStorageDataAccess data = (SkyLightStorageDataAccess) this.updatingSectionData;
        final int oldMinHeight = data.getDefaultHeight();

        if (minHeight < oldMinHeight) {
data.updateMinHeight(minHeight);
}
}
@Unique
private void initializeVanillaLightmapComplexity(final long sectionPos, final DataLayer lightmap) {
if (lightmap.isEmpty()) {
return;
}
int complexity = 0;
for (int y = 0; y < 16; ++y) {
for (int z = 0; z < 16; ++z) {
for (int x = 0; x < 16; ++x) {
complexity += lightmap.get(x, y, z);
}
}
}
if (complexity > 0) {
this.vanillaLightmapComplexities.put(sectionPos, complexity);
}
}
@Override
protected int getLightmapComplexityChange(final long blockPos, final int oldVal, final int newVal, final DataLayer lightmap) {
final long sectionPos = SectionPos.blockToSection(blockPos);
if (!this.vanillaLightmapComplexities.containsKey(sectionPos)) {
return 0;
}
final int oldComplexity = this.vanillaLightmapComplexities.get(sectionPos);
final int newComplexity = oldComplexity + (newVal - oldVal);
if (newComplexity <= 0) {
this.vanillaLightmapComplexities.remove(sectionPos);
return -1;
} else {
this.vanillaLightmapComplexities.put(sectionPos, newComplexity);
return 0;
}
}
@Override
protected int getInitialLightmapComplexity(final long sectionPos, final DataLayer lightmap) {
return this.vanillaLightmapComplexities.containsKey(sectionPos) ? 1 : 0;
}
@Override
public void setSkylightEnabled(final long chunkPos, final boolean enabled) {
if (enabled) {
this.initSkylightChunks.add(chunkPos);
} else {
this.initSkylightChunks.remove(chunkPos);
this.columnsWithSkySources.remove(chunkPos);
}
}
@Override
public boolean isSkylightEnabled(final long sectionPos) {
return this.columnsWithSkySources.contains(SectionPos.getZeroNode(sectionPos));
}
@Invoker("hasSourceInSection")
protected abstract boolean invokeHasSourceInSection(long sectionPos);
}
