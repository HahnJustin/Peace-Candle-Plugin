package com.example.exampleplugin;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public class BedSpawnSuppressionSystem extends RefChangeSystem<ChunkStore, RespawnBlock> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String SUPPRESSION_ID = "Spawn_Camp";
    private static final String TAG_PREFIX = "ExamplePluginBedSuppressor:";

    private final ComponentType<ChunkStore, BedSuppressionLink> linkType;

    // Common chunk/block info type for extra debug context (may be null in some builds)
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockStateInfoType =
            BlockModule.BlockStateInfo.getComponentType();

    public BedSpawnSuppressionSystem(ComponentType<ChunkStore, BedSuppressionLink> linkType) {
        this.linkType = Objects.requireNonNull(linkType, "BedSuppressionLink ComponentType is null");
    }

    /**
     * IMPORTANT:
     * Query MUST match beds even before our link exists.
     * If you query on linkType, you will never see newly placed/claimed beds.
     */
    @Override
    public Query<ChunkStore> getQuery() {
        return RespawnBlock.getComponentType();
    }

    @Nonnull
    @Override
    public ComponentType<ChunkStore, RespawnBlock> componentType() {
        return RespawnBlock.getComponentType();
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<ChunkStore> ref,
            @Nonnull RespawnBlock component,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> cb
    ) {
        logEvent("RespawnBlock ADDED", ref, null, component, store, cb);
        reconcile(ref, null, component, store, cb);
    }

    @Override
    public void onComponentSet(
            @Nonnull Ref<ChunkStore> ref,
            @Nullable RespawnBlock oldComponent,
            @Nonnull RespawnBlock newComponent,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> cb
    ) {
        logEvent("RespawnBlock SET", ref, oldComponent, newComponent, store, cb);
        reconcile(ref, oldComponent, newComponent, store, cb);
    }

    @Override
    public void onComponentRemoved(
            @Nonnull Ref<ChunkStore> ref,
            @Nonnull RespawnBlock component,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> cb
    ) {
        logEvent("RespawnBlock REMOVED", ref, component, null, store, cb);

        // Bed is going away: attempt suppressor cleanup if we had a link.
        BedSuppressionLink link = store.getComponent(ref, this.linkType);
        if (link != null) {
            UUID sup = link.getSuppressorUuid();
            LOGGER.at(Level.INFO).log("Bed removed. Linked suppressor uuid=%s (will attempt removal)", sup);
            removeSuppressorByUuid(store, sup);
        }
    }

    // ---------------- logging helpers ----------------

    private void logEvent(
            @Nonnull String label,
            @Nonnull Ref<ChunkStore> ref,
            @Nullable RespawnBlock oldRb,
            @Nullable RespawnBlock newRb,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> cb
    ) {
        try {
            UUID oldOwner = oldRb != null ? oldRb.getOwnerUUID() : null;
            UUID newOwner = newRb != null ? newRb.getOwnerUUID() : null;

            BedSuppressionLink link = store.getComponent(ref, this.linkType);
            UUID linkOwner = link != null ? link.getOwnerUuid() : null;
            UUID linkSup = link != null ? link.getSuppressorUuid() : null;

            // If BlockStateInfo exists, log chunk index + block index for sanity
            BlockModule.BlockStateInfo bsi = cb.getComponent(ref, blockStateInfoType);
            String loc;
            if (bsi != null && bsi.getChunkRef() != null) {
                long chunkIndex = bsi.getChunkRef().getIndex(); // if your Ref API differs, change this
                int blockIndex = bsi.getIndex();
                int lx = ChunkUtil.xFromBlockInColumn(blockIndex);
                int ly = ChunkUtil.yFromBlockInColumn(blockIndex);
                int lz = ChunkUtil.zFromBlockInColumn(blockIndex);
                loc = "chunkIndex=" + chunkIndex + " local=(" + lx + "," + ly + "," + lz + ")";
            } else {
                loc = "location=<no BlockStateInfo>";
            }

            LOGGER.at(Level.INFO).log(
                    "%s ref=%s %s owner:%s -> %s linkOwner=%s linkSup=%s",
                    label, ref, loc, oldOwner, newOwner, linkOwner, linkSup
            );
        } catch (Throwable t) {
            LOGGER.at(Level.WARNING).log("Failed to log bed event (%s): %s", label, t.toString());
        }
    }

    // ---------------- your existing logic (unchanged) ----------------

    private void reconcile(
            @Nonnull Ref<ChunkStore> respawnRef,
            @Nullable RespawnBlock oldRb,
            @Nonnull RespawnBlock newRb,
            @Nonnull Store<ChunkStore> chunkStore,
            @Nonnull CommandBuffer<ChunkStore> cb
    ) {
        UUID oldOwner = (oldRb != null) ? oldRb.getOwnerUUID() : null;
        UUID newOwner = newRb.getOwnerUUID();

        BedSuppressionLink link = chunkStore.getComponent(respawnRef, this.linkType);

        if (newOwner == null) {
            if (link != null) {
                removeSuppressorByUuid(chunkStore, link.getSuppressorUuid());
                tryRemoveChunkComponent(cb, respawnRef, this.linkType);
            }
            return;
        }

        boolean ownerChanged = (oldOwner == null) || !oldOwner.equals(newOwner);

        if (link != null && !ownerChanged) {
            if (!suppressorExists(chunkStore, link.getSuppressorUuid())) {
                UUID sup = spawnSuppressorAtRespawnBlock(chunkStore, respawnRef, newOwner);
                if (sup != null) {
                    cb.putComponent(respawnRef, this.linkType, new BedSuppressionLink(newOwner, sup));
                }
            }
            return;
        }

        if (link != null) {
            removeSuppressorByUuid(chunkStore, link.getSuppressorUuid());
        }

        UUID sup = spawnSuppressorAtRespawnBlock(chunkStore, respawnRef, newOwner);
        if (sup != null) {
            cb.putComponent(respawnRef, this.linkType, new BedSuppressionLink(newOwner, sup));
        }
    }

    private boolean suppressorExists(@Nonnull Store<ChunkStore> chunkStore, @Nullable UUID suppressorUuid) {
        // keep your existing implementation
        return suppressorUuid != null; // placeholder if you paste back your real one
    }

    private void removeSuppressorByUuid(@Nonnull Store<ChunkStore> chunkStore, @Nullable UUID suppressorUuid) {
        // keep your existing implementation
    }

    private @Nullable UUID spawnSuppressorAtRespawnBlock(
            @Nonnull Store<ChunkStore> chunkStore,
            @Nonnull Ref<ChunkStore> respawnRef,
            @Nonnull UUID ownerUuid
    ) {
        // keep your existing implementation
        return null;
    }

    private static <T extends Component<ChunkStore>> void tryRemoveChunkComponent(
            @Nonnull CommandBuffer<ChunkStore> cb,
            @Nonnull Ref<ChunkStore> ref,
            @Nonnull ComponentType<ChunkStore, T> type
    ) {
        cb.tryRemoveComponent(ref, type);
    }
}
