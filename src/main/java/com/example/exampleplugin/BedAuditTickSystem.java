package com.example.exampleplugin;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.SpawningPlugin;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
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

    // Pick a suppression that exists on vanilla servers
    private static final String SUPPRESSION_ID = "Spawn_Camp";
    private static final String TAG_PREFIX = "ExamplePluginBedSuppressor:";

    private static final ComponentType<ChunkStore, WorldChunk> WORLD_CHUNK_TYPE = WorldChunk.getComponentType();
    private static final ComponentType<ChunkStore, BlockModule.BlockStateInfo> BSI_TYPE =
            BlockModule.BlockStateInfo.getComponentType();

    private static final ComponentType<EntityStore, UUIDComponent> UUID_TYPE = UUIDComponent.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM_TYPE = TransformComponent.getComponentType();

    private final ComponentType<ChunkStore, BedSuppressionLink> linkType;

    // Throttle per chunk (ms) keyed by chunkRefIndex
    private final ConcurrentHashMap<Long, Long> nextScanTimeMsByChunk = new ConcurrentHashMap<>();

    // chunkRefIndex -> set of bedRefIndex seen last scan
    private final ConcurrentHashMap<Long, LongOpenHashSet> lastBedsByChunk = new ConcurrentHashMap<>();

    // bedRefIndex -> snapshot (while loaded)
    private final ConcurrentHashMap<Long, BedSnapshot> lastSeenByBedRefIndex = new ConcurrentHashMap<>();

    // Deferred work (cross-store safe)
    private final ConcurrentLinkedQueue<CreateRequest> pendingCreates = new ConcurrentLinkedQueue<>();
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

    private static final class CreateRequest {
        final Ref<ChunkStore> bedRef;
        final UUID owner;
        final long chunkRefIndex;

        CreateRequest(Ref<ChunkStore> bedRef, UUID owner, long chunkRefIndex) {
            this.bedRef = bedRef;
            this.owner = owner;
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

        // RespawnBlock type (avoid startup nulls)
        final ComponentType<ChunkStore, RespawnBlock> RESPAWN_TYPE;
        try {
            RESPAWN_TYPE = RespawnBlock.getComponentType();
            if (RESPAWN_TYPE == null) return;
        } catch (Throwable t) {
            return;
        }

        final LongOpenHashSet seenThisPass = new LongOpenHashSet();

        // Scan all RespawnBlocks, filter to current chunkRef
        store.forEachChunk(RESPAWN_TYPE, (bedsChunk, bedsCb) -> {
            for (int iBed = 0; iBed < bedsChunk.size(); iBed++) {
                RespawnBlock rb = bedsChunk.getComponent(iBed, RESPAWN_TYPE);
                if (rb == null) continue;

                Ref<ChunkStore> bedRef = bedsChunk.getReferenceTo(iBed);
                long bedKey = bedRef.getIndex();

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
                    lastSeenByBedRefIndex.put(bedKey, new BedSnapshot(owner, suppressor, chunkKey));
                    if (owner != null && suppressor == null) {
                        LOGGER.at(Level.INFO).log(
                                "CLAIMED bed (first seen) owner=%s bedRefIndex=%d chunk=%d",
                                owner, bedKey, chunkKey
                        );
                        // schedule creation (first seen claimed)
                        pendingCreates.add(new CreateRequest(bedRef, owner, chunkKey));
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
                        if (suppressor == null) {
                            LOGGER.at(Level.WARNING).log(
                                    "CLAIMED bed has NO suppressor link yet owner=%s bedRefIndex=%d chunk=%d",
                                    owner, bedKey, chunkKey
                            );
                            pendingCreates.add(new CreateRequest(bedRef, owner, chunkKey));
                        }
                    } else if (prev.owner != null && owner == null) {
                        LOGGER.at(Level.INFO).log(
                                "BED OWNER CLEARED oldOwner=%s bedRefIndex=%d chunk=%d",
                                prev.owner, bedKey, chunkKey
                        );
                        // if it had a suppressor, queue removal
                        if (prev.suppressor != null) {
                            pendingSuppressorRemovals.add(prev.suppressor);
                        }
                        // also clear link component (tidy)
                        cb.tryRemoveComponent(bedRef, linkType);
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

                // Keep snapshot current
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

        // Flush deferred work safely (not inside ChunkStore processing)
        if ((!pendingCreates.isEmpty() || !pendingSuppressorRemovals.isEmpty()) && flushQueued.compareAndSet(false, true)) {
            World world = ((ChunkStore) store.getExternalData()).getWorld();
            if (world != null) {
                world.execute(() -> {
                    try {
                        flushDeferred(world);
                    } finally {
                        flushQueued.set(false);
                    }
                });
            } else {
                flushQueued.set(false);
            }
        }
    }

    private void flushDeferred(@Nonnull World world) {
        flushCreates(world);
        flushSuppressorRemovals(world);
    }

    private void flushCreates(@Nonnull World world) {
        if (SpawningPlugin.get() == null) {
            // Spawning system not ready; leave creates queued for next flush
            return;
        }

        Store<ChunkStore> cs = world.getChunkStore().getStore();
        Store<EntityStore> es = world.getEntityStore().getStore();

        final ComponentType<ChunkStore, RespawnBlock> RESPAWN_TYPE;
        try {
            RESPAWN_TYPE = RespawnBlock.getComponentType();
            if (RESPAWN_TYPE == null) return;
        } catch (Throwable t) {
            return;
        }

        int createdCount = 0;

        CreateRequest req;
        while ((req = pendingCreates.poll()) != null) {
            if (req.bedRef == null || !req.bedRef.isValid()) continue;

            RespawnBlock rb = cs.getComponent(req.bedRef, RESPAWN_TYPE);
            if (rb == null) continue;

            UUID ownerNow = rb.getOwnerUUID();
            if (ownerNow == null || !ownerNow.equals(req.owner)) continue;

            BedSuppressionLink link = cs.getComponent(req.bedRef, linkType);
            UUID existingSup = (link != null) ? link.getSuppressorUuid() : null;

            // If already linked and entity exists (loaded), skip
            if (existingSup != null) {
                Ref<EntityStore> r = findEntityByUuid(es, existingSup);
                if (r != null && r.isValid()) continue;
            }

            Vector3d pos = getBedWorldCenter(cs, req.bedRef);
            if (pos == null) continue;

            UUID supUuid = spawnSuppressor(es, pos, ownerNow);
            if (supUuid == null) continue;

            cs.addComponent(req.bedRef, linkType, new BedSuppressionLink(ownerNow, supUuid));
            createdCount++;

            LOGGER.at(Level.INFO).log("Created suppressor uuid=%s for bedRef=%s owner=%s pos=%s",
                    supUuid, req.bedRef, ownerNow, pos);
        }

        if (createdCount > 0) {
            LOGGER.at(Level.INFO).log("Created %d suppressor(s) from deferred queue.", createdCount);
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
            LOGGER.at(Level.INFO).log("Removed %d suppressor entity(s) (loaded) from deferred queue.", removed);
        }
    }

    private static @Nullable Vector3d getBedWorldCenter(@Nonnull Store<ChunkStore> cs, @Nonnull Ref<ChunkStore> bedRef) {
        BlockModule.BlockStateInfo bsi = cs.getComponent(bedRef, BSI_TYPE);
        if (bsi == null) return null;

        Ref<ChunkStore> chunkRef = bsi.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) return null;

        WorldChunk wc = cs.getComponent(chunkRef, WORLD_CHUNK_TYPE);
        if (wc == null) return null;

        int idx = bsi.getIndex();
        int lx = ChunkUtil.xFromBlockInColumn(idx);
        int ly = ChunkUtil.yFromBlockInColumn(idx);
        int lz = ChunkUtil.zFromBlockInColumn(idx);

        // WorldChunk uses 32-wide coords (see decompiled BlockState: chunkX<<5 | localX)
        int wx = (wc.getX() << 5) | (lx & 31);
        int wz = (wc.getZ() << 5) | (lz & 31);

        return new Vector3d(wx + 0.5, ly + 0.5, wz + 0.5);
    }

    private static @Nullable UUID spawnSuppressor(@Nonnull Store<EntityStore> es, @Nonnull Vector3d pos, @Nonnull UUID ownerUuid) {
        var spawnSuppType = SpawningPlugin.get().getSpawnSuppressorComponentType();

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.putComponent(spawnSuppType, new SpawnSuppressionComponent(SUPPRESSION_ID));
        holder.putComponent(Nameplate.getComponentType(), new Nameplate(TAG_PREFIX + ownerUuid));
        holder.putComponent(TRANSFORM_TYPE, new TransformComponent(pos, new Vector3f(0f, 0f, 0f)));
        holder.ensureComponent(UUID_TYPE);

        Ref<EntityStore> created = es.addEntity(holder, AddReason.SPAWN);
        if (created == null) return null;

        UUIDComponent u = es.getComponent(created, UUID_TYPE);
        return (u != null) ? u.getUuid() : null;
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
