package net.caffeinemc.phosphor.mixin.world;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ThreadedAnvilChunkStorageAccess {
    @Invoker("releaseLightTicket")
    void invokeReleaseLightTicket(ChunkPos pos);
}
