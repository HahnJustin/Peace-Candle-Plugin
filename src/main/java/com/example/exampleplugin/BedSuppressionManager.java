package com.example.exampleplugin;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.SpawningPlugin;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

public final class BedSuppressionManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Use a suppression id that actually exists on the server.
     * If you haven’t shipped your own asset pack yet, keep using Spawn_Camp.
     */
    public static final String SUPPRESSION_ID = "Spawn_Camp";

    /**
     * If you want “only ours”, add a Nameplate tag like:
     *   holder.putComponent(Nameplate.getComponentType(), new Nameplate("ExamplePluginBedSuppressor"));
     *
     * For now you said “nuke all” during testing, but for production you want *some* identifier.
     */
    // public static final String NAMEPLATE_TAG = "ExamplePluginBedSuppressor";

    private static final ComponentType<EntityStore, SpawnSuppressionComponent> SUPPRESSION_TYPE =
            SpawnSuppressionComponent.getComponentType();

    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM_TYPE =
            TransformComponent.getComponentType();

    private static final ComponentType<EntityStore, UUIDComponent> UUID_TYPE =
            UUIDComponent.getComponentType();

    private static final ComponentType<ChunkStore, RespawnBlock> RESPAWN_TYPE =
            RespawnBlock.getComponentType();

    private BedSuppressionManager() {}

    /** Called on chunk load and during periodic scan. */
    public static void setupChunk(WorldChunk chunk, boolean keepAlreadyLoaded) {
        if (chunk == null) return;
        World world = chunk.getWorld();
        if (world == null) return;
        if (!world.isInThread()) {
            world.execute(() -> setupChunk(chunk, keepAlreadyLoaded));
            return;
        }

        // if spawning plugin not loaded yet, just skip (will be fixed next scan)
        if (SpawningPlugin.get() == null) return;

        cleanChunk(chunk, keepAlreadyLoaded);
        discoverChunkClaimedBeds(chunk);
    }

    /**
     * Remove suppressors we own that no longer correspond to claimed beds.
     * Keeps valid ones (if keepAlreadyLoaded = true).
     */
    private static void cleanChunk(WorldChunk chunk, boolean keepAlreadyLoaded) {
        EntityChunk entityChunk = chunk.getEntityChunk();
        if (entityChunk == null) return;

        World world = chunk.getWorld();
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) return;

        Holder<EntityStore>[] holders = entityChunk.takeEntityHolders();
        if (holders == null) return;

        int kept = 0, removed = 0, recreated = 0;

        for (Holder<EntityStore> h : holders) {
            if (h == null) continue;

            SpawnSuppressionComponent s = (SpawnSuppressionComponent) h.getComponent(SUPPRESSION_TYPE);

            // Keep non-suppression entities unchanged
            if (s == null) {
                entityChunk.storeEntityHolder(h);
                kept++;
                continue;
            }

            // If you later tag only your suppressors, filter here:
            // if (!SUPPRESSION_ID.equals(s.getSpawnSuppression())) { keep... }
            // For now: treat ALL suppressors with SUPPRESSION_ID as ours
            if (!SUPPRESSION_ID.equals(s.getSpawnSuppression())) {
                entityChunk.storeEntityHolder(h);
                kept++;
                continue;
            }

            TransformComponent t = (TransformComponent) h.getComponent(TRANSFORM_TYPE);
            if (t == null) {
                // no transform? drop it
                removed++;
                continue;
            }

            Vector3i blockPos = toBlockPosition(t);
            boolean stillValid = isClaimedBed(world, blockPos);

            if (!stillValid) {
                // bed gone / unclaimed -> remove suppressor
                removed++;
                continue;
            }

            if (!keepAlreadyLoaded) {
                // Used for shutdown cleanup: delete all our suppressors in loaded chunks
                removed++;
                continue;
            }

            // Ensure UUID exists; if not, recreate suppressor cleanly
            UUIDComponent uuid = (UUIDComponent) h.getComponent(UUID_TYPE);
            if (uuid == null) {
                // Recreate it (don’t re-store the old holder)
                createSuppression(world, blockPos);
                recreated++;
            } else {
                // Keep it
                entityChunk.storeEntityHolder(h);
                kept++;
            }
        }

        entityChunk.markNeedsSaving();

        if (removed > 0 || recreated > 0) {
            LOGGER.at(Level.INFO).log(
                    "BedSuppression cleanChunk (%d,%d): kept=%d removed=%d recreated=%d",
                    chunk.getX(), chunk.getZ(), kept, removed, recreated
            );
        }
    }

    /**
     * Find claimed beds in this chunk and ensure a suppression entity exists for each.
     * Avoid duplicates by building a set of existing suppression block positions first.
     */
    private static void discoverChunkClaimedBeds(WorldChunk chunk) {
        World world = chunk.getWorld();

        BlockComponentChunk bcc = chunk.getBlockComponentChunk();
        if (bcc == null) return;

        // 1) Build set of suppressor positions already present in this chunk
        Set<Long> suppressorPosKeys = getSuppressorPositionsInChunk(chunk);

        // 2) Iterate block component holders and create missing suppressors
        Int2ObjectMap<Holder<ChunkStore>> holders = bcc.getEntityHolders();
        if (holders == null) return;

        int created = 0;

        for (ObjectIterator<Int2ObjectMap.Entry<Holder<ChunkStore>>> it = holders.int2ObjectEntrySet().iterator(); it.hasNext();) {
            Int2ObjectMap.Entry<Holder<ChunkStore>> entry = it.next();
            Holder<ChunkStore> h = entry.getValue();
            if (h == null) continue;

            RespawnBlock rb = (RespawnBlock) h.getComponent(RESPAWN_TYPE);
            if (rb == null || rb.getOwnerUUID() == null) continue;

            int blockIndex = entry.getIntKey();
            int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
            int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
            int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);

            int worldX = ChunkUtil.worldCoordFromLocalCoord(chunk.getX(), localX);
            int worldZ = ChunkUtil.worldCoordFromLocalCoord(chunk.getZ(), localZ);

            BlockType bt = world.getBlockType(worldX, localY, worldZ);
            if (!isBed(bt)) continue;

            Vector3i bedPos = new Vector3i(worldX, localY, worldZ);
            long posKey = posKey(bedPos);

            if (suppressorPosKeys.contains(posKey)) continue;

            createSuppression(world, bedPos);
            suppressorPosKeys.add(posKey);
            created++;
        }

        if (created > 0) {
            LOGGER.at(Level.INFO).log("BedSuppression discoverChunkClaimedBeds (%d,%d): created=%d",
                    chunk.getX(), chunk.getZ(), created);
        }
    }

    private static Set<Long> getSuppressorPositionsInChunk(WorldChunk chunk) {
        EntityChunk entityChunk = chunk.getEntityChunk();
        if (entityChunk == null) return Set.of();

        Holder<EntityStore>[] holders = entityChunk.takeEntityHolders();
        if (holders == null) return Set.of();

        // IMPORTANT: we must put them back after inspection
        Set<Long> keys = new HashSet<>();
        for (Holder<EntityStore> h : holders) {
            if (h == null) continue;

            SpawnSuppressionComponent s = (SpawnSuppressionComponent) h.getComponent(SUPPRESSION_TYPE);
            if (s != null && SUPPRESSION_ID.equals(s.getSpawnSuppression())) {
                TransformComponent t = (TransformComponent) h.getComponent(TRANSFORM_TYPE);
                if (t != null) {
                    keys.add(posKey(toBlockPosition(t)));
                }
            }

            entityChunk.storeEntityHolder(h);
        }
        return keys;
    }

    private static void createSuppression(World world, Vector3i bedPos) {
        if (!isClaimedBed(world, bedPos)) return;

        Store<EntityStore> store = world.getEntityStore().getStore();
        if (store == null) return;

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();

        var spawnSuppType = SpawningPlugin.get().getSpawnSuppressorComponentType();
        holder.addComponent(spawnSuppType, new SpawnSuppressionComponent(SUPPRESSION_ID));

        holder.addComponent(TRANSFORM_TYPE,
                 new TransformComponent(new Vector3d(bedPos).add(0.5D), Vector3f.ZERO));

        // UUID required for later commands / cleanup
        holder.addComponent(UUID_TYPE, UUIDComponent.randomUUID());

        store.addEntity(holder, AddReason.SPAWN);
    }

    private static boolean isClaimedBed(World world, Vector3i pos) {
        if (world == null || pos == null) return false;

        BlockType bt = world.getBlockType(pos.getX(), pos.getY(), pos.getZ());
        if (!isBed(bt)) return false;

        Holder<ChunkStore> holder = world.getBlockComponentHolder(pos.getX(), pos.getY(), pos.getZ());
        if (holder == null) return false;

        RespawnBlock rb = (RespawnBlock) holder.getComponent(RESPAWN_TYPE);
        return rb != null && rb.getOwnerUUID() != null;
    }

    private static boolean isBed(@Nullable BlockType blockType) {
        return blockType != null && blockType.getBeds() != null;
    }

    private static Vector3i toBlockPosition(TransformComponent transform) {
        Vector3d p = transform.getPosition();
        return new Vector3i((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
    }

    private static long posKey(Vector3i p) {
        // good enough for a per-chunk set; collisions extremely unlikely
        return (((long) p.getX()) & 0x3FFFFFFL) << 38
                | (((long) p.getY()) & 0xFFFL) << 26
                | (((long) p.getZ()) & 0x3FFFFFFL);
    }
}
