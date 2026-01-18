package com.example.exampleplugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

public final class Utils {
    private Utils() {}

    /**
     * Finds ANY RespawnBlock in the player's current chunk and returns its ChunkStore ref.
     */
    @Nullable
    public static Ref<ChunkStore> findRespawnBlockRefInPlayerChunk(World world, Ref<EntityStore> playerRef) {
        Store<EntityStore> es = world.getEntityStore().getStore();

        TransformComponent transform = es.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) return null;

        Vector3i pos = transform.getPosition().toVector3i();
        long chunkIndex = ChunkUtil.indexChunkFromBlock(pos.x, pos.z);

        Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(chunkIndex);
        if (chunkRef == null) return null;

        Store<ChunkStore> cs = chunkRef.getStore();

        final Ref<ChunkStore>[] found = new Ref[]{ null };

        cs.forEachChunk(RespawnBlock.getComponentType(), (archetypeChunk, commandBuffer) -> {
            for (int i = 0; i < archetypeChunk.size(); i++) {
                RespawnBlock rb = archetypeChunk.getComponent(i, RespawnBlock.getComponentType());
                if (rb == null) continue;

                Ref<ChunkStore> rbRef = archetypeChunk.getReferenceTo(i);

                BlockModule.BlockStateInfo blockInfo = commandBuffer.getComponent(rbRef, BlockModule.BlockStateInfo.getComponentType());
                if (blockInfo == null) continue;

                // Ensure it's actually in *this* chunk
                Ref<ChunkStore> blockChunkRef = blockInfo.getChunkRef();
                if (chunkRef.equals(blockChunkRef)) {
                    found[0] = rbRef;
                    return;
                }
            }
        });

        return found[0];
    }

    public static @Nullable UUID getPlayerUuid(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
        UUIDComponent u = store.getComponent(playerRef, UUIDComponent.getComponentType());
        return (u != null) ? u.getUuid() : null;
    }
}
