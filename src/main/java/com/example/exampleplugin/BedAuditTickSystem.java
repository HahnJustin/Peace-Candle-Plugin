package com.example.exampleplugin;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class BedAuditTickSystem extends EntityTickingSystem<ChunkStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final ComponentType<ChunkStore, WorldChunk> WORLD_CHUNK_TYPE = WorldChunk.getComponentType();
    private static final ComponentType<ChunkStore, BlockModule.BlockStateInfo> BSI_TYPE =
            BlockModule.BlockStateInfo.getComponentType();

    private static final ComponentType<EntityStore, UUIDComponent> UUID_TYPE = UUIDComponent.getComponentType();

    private final ComponentType<ChunkStore, BedSuppressionLink> linkType;

    // Throttle per chunk (ms) - keyed by chunkRef.getIndex() (long)
    private final ConcurrentHashMap<Long, Long> nextScanTimeMsByChunk = new ConcurrentHashMap<>();

    // last scan per chunk (chunkRefIndex -> set of bedRefIndex)
    private final ConcurrentHashMap<Long, LongOpenHashSet> lastBedsByChunk = new ConcurrentHashMap<>();

    // Edge-trigger memory per bed ref index (NOT UUID; the block entity ref index is stable while loaded)
    private final ConcurrentHashMap<Long, BedSnapshot> lastSeenByBedRefIndex = new ConcurrentHashMap<>();

    // Cross-store removals must be deferred (EntityStore writes cannot happen while ChunkStore is processing)
    private final ConcurrentLinkedQueue<UUID> pendingSuppressorRemovals = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean(false);

    private static final class BedSnapshot {
        final UUID owner;
        final UUID suppressor;
        final long chunkRefIndex;

        BedSnapshot(UUID owner, UUID suppressor, long chunkRefIndex) {
            this.owner = owner;
            this.suppressor = suppressor;
            this.chunkRefIndex = chunkRefIndex;
        }
    }

    public BedAuditTickSystem(ComponentType<ChunkStore, BedSuppressionLink> linkType) {
        this.linkType = java.util.Objects.requireNonNull(linkType, "BedSuppressionLink ComponentType is null");
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return WORLD_CHUNK_TYPE;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> cb
    ) {
        final Ref<ChunkStore> chunkRef = archetypeChunk.getReferenceTo(index);
        final long chunkKey = chunkRef.getIndex();

        // Per-chunk throttle
        final long now = System.currentTimeMillis();
        final long nextAllowed = nextScanTimeMsByChunk.getOrDefault(chunkKey, 0L);
        if (now < nextAllowed) return;
        nextScanTimeMsByChunk.put(chunkKey, now + 1000L);

        // Lazily resolve respawn component type (avoid setup-time nulls)
        final ComponentType<ChunkStore, RespawnBlock> RESPAWN_TYPE;
        try {
            RESPAWN_TYPE = RespawnBlock.getComponentType();
            if (RESPAWN_TYPE == null) return;
        } catch (Throwable t) {
            return;
        }

        final LongOpenHashSet seenThisPass = new LongOpenHashSet();

        // Scan all RespawnBlocks, filter down to beds in this specific chunkRef
        store.forEachChunk(RESPAWN_TYPE, (bedsChunk, bedsCb) -> {
            for (int iBed = 0; iBed < bedsChunk.size(); iBed++) {
                RespawnBlock rb = bedsChunk.getComponent(iBed, RESPAWN_TYPE);
                if (rb == null) continue;

                Ref<ChunkStore> bedRef = bedsChunk.getReferenceTo(iBed);
                long bedKey = bedRef.getIndex();

                // Filter to current chunk
                BlockModule.BlockStateInfo bsi = bedsCb.getComponent(bedRef, BSI_TYPE);
                if (bsi == null) continue;

                Ref<ChunkStore> bedChunkRef = bsi.getChunkRef();
                if (bedChunkRef == null || !bedChunkRef.isValid()) continue;
                if (!bedChunkRef.equals(chunkRef)) continue;

                seenThisPass.add(bedKey);

                UUID owner = rb.getOwnerUUID();
                BedSuppressionLink link = bedsCb.getComponent(bedRef, linkType);
                UUID suppressor = (link != null) ? link.getSuppressorUuid() : null;

                BedSnapshot prev = lastSeenByBedRefIndex.get(bedKey);
                if (prev == null) {
                    // First time we’ve seen it while loaded
                    lastSeenByBedRefIndex.put(bedKey, new BedSnapshot(owner, suppressor, chunkKey));

                    if (owner != null && suppressor == null) {
                        LOGGER.at(Level.INFO).log(
                                "CLAIMED bed (first seen) owner=%s bedRefIndex=%d chunk=%d",
                                owner, bedKey, chunkKey
                        );
                    }
                    continue;
                }

                boolean ownerChanged = !Objects.equals(prev.owner, owner);
                boolean suppressorChanged = !Objects.equals(prev.suppressor, suppressor);

                if (ownerChanged) {
                    if (prev.owner == null && owner != null) {
                        LOGGER.at(Level.INFO).log(
                                "BED CLAIMED owner=%s bedRefIndex=%d chunk=%d",
                                owner, bedKey, chunkKey
                        );
                    } else if (prev.owner != null && owner == null) {
                        LOGGER.at(Level.INFO).log(
                                "BED OWNER CLEARED oldOwner=%s bedRefIndex=%d chunk=%d",
                                prev.owner, bedKey, chunkKey
                        );
                    } else {
                        LOGGER.at(Level.INFO).log(
                                "BED OWNER CHANGED %s -> %s bedRefIndex=%d chunk=%d",
                                prev.owner, owner, bedKey, chunkKey
                        );
                    }
                }

                if (suppressorChanged) {
                    if (prev.suppressor == null && suppressor != null) {
                        LOGGER.at(Level.INFO).log(
                                "BED LINKED suppressor=%s bedRefIndex=%d chunk=%d",
                                suppressor, bedKey, chunkKey
                        );
                    } else if (prev.suppressor != null && suppressor == null) {
                        LOGGER.at(Level.INFO).log(
                                "BED UNLINKED oldSuppressor=%s bedRefIndex=%d chunk=%d",
                                prev.suppressor, bedKey, chunkKey
                        );
                    } else {
                        LOGGER.at(Level.INFO).log(
                                "BED LINK CHANGED %s -> %s bedRefIndex=%d chunk=%d",
                                prev.suppressor, suppressor, bedKey, chunkKey
                        );
                    }
                }

                // Warn only on the claim edge (prevents spam)
                if (owner != null && suppressor == null) {
                    if (ownerChanged && prev.owner == null) {
                        LOGGER.at(Level.WARNING).log(
                                "CLAIMED bed has NO suppressor link yet owner=%s bedRefIndex=%d chunk=%d",
                                owner, bedKey, chunkKey
                        );
                    }
                }

                if (ownerChanged || suppressorChanged) {
                    lastSeenByBedRefIndex.put(bedKey, new BedSnapshot(owner, suppressor, chunkKey));
                }
            }
        });

        // ---- Removal detection (bed broken while chunk stays loaded) ----
        LongOpenHashSet prevSeen = lastBedsByChunk.put(chunkKey, seenThisPass);
        if (prevSeen != null) {
            for (LongIterator it = prevSeen.iterator(); it.hasNext(); ) {
                long bedKey = it.nextLong();
                if (seenThisPass.contains(bedKey)) continue;

                BedSnapshot snap = lastSeenByBedRefIndex.remove(bedKey);
                if (snap == null) continue;

                LOGGER.at(Level.INFO).log(
                        "BED REMOVED bedRefIndex=%d chunk=%d owner=%s suppressor=%s",
                        bedKey, chunkKey, snap.owner, snap.suppressor
                );

                if (snap.suppressor != null) {
                    pendingSuppressorRemovals.add(snap.suppressor);
                }
            }
        }

        // Defer suppressor removals to a world task (safe: not inside ChunkStore processing)
        if (!pendingSuppressorRemovals.isEmpty()) {
            World world = ((ChunkStore) store.getExternalData()).getWorld();
            if (world != null && flushQueued.compareAndSet(false, true)) {
                world.execute(() -> {
                    try {
                        flushSuppressorRemovals(world);
                    } finally {
                        flushQueued.set(false);
                    }
                });
            }
        }
    }

    private void flushSuppressorRemovals(@Nonnull World world) {
        Store<EntityStore> es = world.getEntityStore().getStore();

        int removed = 0;
        UUID uuid;
        while ((uuid = pendingSuppressorRemovals.poll()) != null) {
            Ref<EntityStore> r = findEntityByUuid(es, uuid);
            if (r != null && r.isValid()) {
                es.removeEntity(r, RemoveReason.REMOVE);
                removed++;
            }
        }

        if (removed > 0) {
            LOGGER.at(Level.INFO).log("Removed %d suppressor entities (loaded) from deferred queue.", removed);
        }
    }

    private static @Nullable Ref<EntityStore> findEntityByUuid(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        final Ref<EntityStore>[] found = new Ref[]{ null };

        store.forEachChunk(UUID_TYPE, (archetypeChunk, commandBuffer) -> {
            for (int i = 0; i < archetypeChunk.size(); i++) {
                UUIDComponent u = archetypeChunk.getComponent(i, UUID_TYPE);
                if (u != null && uuid.equals(u.getUuid())) {
                    found[0] = archetypeChunk.getReferenceTo(i);
                    return;
                }
            }
        });

        return found[0];
    }
}
