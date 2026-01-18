package com.example.exampleplugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class RespawnBlockCommand extends CommandBase {

    public RespawnBlockCommand() {
        super("respawnBlock", "Checks for respawn blocks in the current chunk");
        this.setPermissionGroup(GameMode.Adventure);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("This command must be run by a player"));
            return;
        }

        Player player = ctx.senderAs(Player.class);

        Ref<EntityStore> playerRef = ctx.senderAsPlayerRef();
        if (playerRef == null) {
            ctx.sendMessage(Message.raw("Could not get player reference"));
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("Could not get world"));
            return;
        }

        world.execute(() -> {
            try {
                Store<EntityStore> entityStore = world.getEntityStore().getStore();

                Vector3i playerBlockPos = getPlayerBlockPos(entityStore, playerRef);
                if (playerBlockPos == null) {
                    ctx.sendMessage(Message.raw("Could not get player position"));
                    return;
                }

                Ref<ChunkStore> chunkRef = getPlayerChunkRef(world, playerBlockPos);
                if (chunkRef == null) {
                    ctx.sendMessage(Message.raw("Chunk not loaded"));
                    return;
                }

                Store<ChunkStore> chunkStore = chunkRef.getStore();

                RespawnBlockCounts counts = countRespawnBlocksInChunk(chunkStore, chunkRef);

                ctx.sendMessage(Message.raw(
                        "Chunk respawn blocks: " + counts.total +
                                " (owned: " + counts.owned + ")"
                ));
            } catch (Exception e) {
                ctx.sendMessage(Message.raw("Error: " + e.getMessage()));
                e.printStackTrace();
            }
        });
    }

    // ---------------- helpers ----------------

    private static @Nullable Vector3i getPlayerBlockPos(
            @Nonnull Store<EntityStore> entityStore,
            @Nonnull Ref<EntityStore> playerRef
    ) {
        TransformComponent transform = entityStore.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) return null;
        return transform.getPosition().toVector3i();
    }

    private static @Nullable Ref<ChunkStore> getPlayerChunkRef(
            @Nonnull World world,
            @Nonnull Vector3i playerBlockPos
    ) {
        long playerChunkIndex = ChunkUtil.indexChunkFromBlock(playerBlockPos.x, playerBlockPos.z);
        Ref<ChunkStore> chunkRef = world.getChunkStore().getChunkReference(playerChunkIndex);
        return (chunkRef != null && chunkRef.isValid()) ? chunkRef : null;
    }

    private static @Nonnull RespawnBlockCounts countRespawnBlocksInChunk(
            @Nonnull Store<ChunkStore> chunkStore,
            @Nonnull Ref<ChunkStore> chunkRef
    ) {
        final int[] total = {0};
        final int[] owned = {0};

        chunkStore.forEachChunk(RespawnBlock.getComponentType(), (archetypeChunk, commandBuffer) -> {
            for (int i = 0; i < archetypeChunk.size(); i++) {
                RespawnBlock respawnBlock = archetypeChunk.getComponent(i, RespawnBlock.getComponentType());
                if (respawnBlock == null) continue;

                Ref<ChunkStore> blockRef = archetypeChunk.getReferenceTo(i);

                BlockModule.BlockStateInfo blockInfo = commandBuffer.getComponent(
                        blockRef,
                        BlockModule.BlockStateInfo.getComponentType()
                );
                if (blockInfo == null) continue;

                Ref<ChunkStore> blockChunkRef = blockInfo.getChunkRef();
                if (blockChunkRef == null) continue;

                if (!blockChunkRef.equals(chunkRef)) continue;

                total[0]++;
                if (respawnBlock.getOwnerUUID() != null) owned[0]++;
            }
        });

        return new RespawnBlockCounts(total[0], owned[0]);
    }

    private static final class RespawnBlockCounts {
        final int total;
        final int owned;

        private RespawnBlockCounts(int total, int owned) {
            this.total = total;
            this.owned = owned;
        }
    }
}
