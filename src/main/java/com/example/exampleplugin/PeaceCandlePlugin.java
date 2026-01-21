package com.example.exampleplugin;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.builtin.beds.interactions.BedInteraction;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PeaceCandlePlugin extends JavaPlugin {

    private static final String SUPPRESSION_ASSET_ID = "Spawn_Camp";
    private static final long SCAN_INTERVAL_SECONDS = 5L;
    private static final String PEACE_CANDLE_BLOCK_ID = "Peace_Candle";
    private static final String SUPPRESSION_NAME_PLATE = "Peace_Candle_Suppressor";

    // Minimal logging switches
    private static final boolean LOG_CANDLE_FOUND = true;      // logs each candle block found (can still be a lot)
    private static final boolean LOG_SUPPRESSOR_CREATE = true; // logs when we actually spawn a suppressor
    private static final boolean LOG_SUPPRESSOR_REMOVE = true; // logs when we remove a suppressor
    private static final boolean LOG_SUPPRESSOR_FOUND = false; // logs when we actually spawn a suppressor
    private static final boolean LOG_NULL_REFS_WARN = false;// optional: warns when takeEntityHolders() is null

    private ComponentType<ChunkStore, PeaceCandleComponent> peaceCandleComponentType;

    private ScheduledFuture<?> scanningTask;

    public PeaceCandlePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        this.peaceCandleComponentType = ChunkStore.REGISTRY.registerComponent(PeaceCandleComponent.class, "PeaceCandleComponent", PeaceCandleComponent.CODEC);
        PeaceCandleComponent.SetComponentType(peaceCandleComponentType);

        EventBus eventBus = HytaleServer.get().getEventBus();

        // Chunk lifecycle hook
        eventBus.registerGlobal(ChunkPreLoadProcessEvent.class, event -> {
            WorldChunk chunk = event.getChunk();
            World world = (chunk != null) ? chunk.getWorld() : null;
            runOnWorldThread(world, () -> setupChunk(chunk));
        });
        this.scanningTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::scanWorlds, 0L, 5L, TimeUnit.SECONDS);
        registerInteraction();
    }

    @Override
    protected void shutdown() {
        if (scanningTask != null) {
            scanningTask.cancel(false);
            scanningTask = null;
        }
        cleanWorlds();
        super.shutdown();
    }


    private void registerInteraction() {
        try {
            AssetRegistry.getAssetStore(Interaction.class)
                    .loadAssets(getName(), List.of(PeaceCandleInteraction.INSTANCE));
            getCodecRegistry(Interaction.CODEC)
                    .register(PEACE_CANDLE_BLOCK_ID, PeaceCandleInteraction.class, PeaceCandleInteraction.CODEC);
        } catch (Exception e) {
            ((HytaleLogger.Api)getLogger().atWarning()).log("[Gravestones] Interaction registration failed: " + e.getMessage());
        }
    }

    private void setupChunk(WorldChunk chunk) {
        if (chunk == null) return;

        // remove stale suppression entities in this chunk + keep valid ones
        cleanChunk(chunk, false);

        // find candles and ensure they have a suppression entity (only if ON)
        discoverPeaceCandles(chunk);
    }

    private void cleanChunk(WorldChunk chunk, boolean removeAll) {
        EntityChunk entityChunk = chunk.getEntityChunk();
        if (entityChunk == null) return;

        Ref<EntityStore>[] refs = entityChunk.takeEntityReferences();

        Holder<EntityStore>[] holders = entityChunk.takeEntityHolders();

        World world = chunk.getWorld();
        BlockComponentChunk blockComponentChunk = chunk.getBlockComponentChunk();

        if (refs != null) {
            for (Ref<EntityStore> entityRef : refs) {
                Store<EntityStore> store = entityRef.getStore();
                SpawnSuppressionComponent suppression =
                        store.getComponent(entityRef, SpawnSuppressionComponent.getComponentType());
                Nameplate nameplate = store.getComponent(entityRef, Nameplate.getComponentType());
                if (suppression != null && nameplate != null && SUPPRESSION_NAME_PLATE.equals(nameplate.getText())) {

                    TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
                    getLogger().atInfo().log("PeaceCandle FOUND Ref suppressor");

                    if(removeAll) {
                        if (transform != null) {
                            Vector3i pos = toBlockPosition(transform);
                            removeSuppressor(pos, blockComponentChunk);
                        }
                        despawnSafely(entityRef);
                        getLogger().atInfo().log("PeaceCandle REMOVE ALL Ref suppressor");
                        continue;
                    }

                    if (transform != null) {
                        Vector3i pos = toBlockPosition(transform);

                        getLogger().atInfo().log("PeaceCandle FOUND Ref suppressor at (%d,%d,%d)",
                                pos.getX(), pos.getY(), pos.getZ());

                        BlockType blockType = world.getBlockType(pos);

                        if (!isPeaceCandleOn(blockType, chunk, pos)) {
                            removeSuppressor(pos, blockComponentChunk);
                            despawnSafely(entityRef);
                            if (LOG_SUPPRESSOR_REMOVE) {
                                getLogger().atInfo().log("PeaceCandle REMOVE Ref suppressor at (%d,%d,%d)",
                                        pos.getX(), pos.getY(), pos.getZ());
                            }
                        } else {
                            entityChunk.addEntityReference(entityRef);
                        }
                    } else {
                        despawnSafely(entityRef);
                        if (LOG_SUPPRESSOR_REMOVE) {
                            getLogger().atInfo().log("PeaceCandle REMOVE Ref suppressor at unknown pos, couldn't reset candle");
                        }
                    }
                } else {
                    entityChunk.addEntityReference(entityRef);
                }
            }
        }

        if (holders != null) {
            for (Holder<EntityStore> holder : holders) {
                SpawnSuppressionComponent suppression =
                        holder.getComponent(SpawnSuppressionComponent.getComponentType());
                Nameplate nameplate = holder.getComponent(Nameplate.getComponentType());
                if (suppression != null && nameplate != null && SUPPRESSION_NAME_PLATE.equals(nameplate.getText())) {

                    TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
                    getLogger().atInfo().log("PeaceCandle FOUND Holder suppressor");

                    if(removeAll) {
                        if (transform != null) {
                            Vector3i pos = toBlockPosition(transform);
                            removeSuppressor(pos, blockComponentChunk);
                        }
                        getLogger().atInfo().log("PeaceCandle REMOVE ALL Holder suppressor");
                        despawnHolderSafely(chunk, holder);
                        continue;
                    }

                    if (transform != null) {
                        Vector3i pos = toBlockPosition(transform);

                        getLogger().atInfo().log("PeaceCandle FOUND Holder suppressor at (%d,%d,%d)",
                                pos.getX(), pos.getY(), pos.getZ());

                        BlockType blockType = world.getBlockType(pos);

                        if (!isPeaceCandleOn(blockType, chunk, pos)) {
                            removeSuppressor(pos, blockComponentChunk);
                            despawnHolderSafely(chunk, holder);
                            if (LOG_SUPPRESSOR_REMOVE) {
                                getLogger().atInfo().log("PeaceCandle REMOVE Holder suppressor at (%d,%d,%d)",
                                        pos.getX(), pos.getY(), pos.getZ());
                            }
                        } else {
                            entityChunk.storeEntityHolder(holder);
                        }
                    } else {
                        despawnHolderSafely(chunk, holder);
                        if (LOG_SUPPRESSOR_REMOVE) {
                            getLogger().atInfo().log("PeaceCandle REMOVE Ref suppressor at unknown pos, couldn't reset candle");
                        }
                    }
                }
                else{
                    entityChunk.storeEntityHolder(holder);
                }
            }
        }

        if (refs != null || holders != null) entityChunk.markNeedsSaving();
    }

    private void removeSuppressor( Vector3i pos, BlockComponentChunk blockComponentChunk){
        int index = toBlockIndex(pos);
        if(blockComponentChunk != null) {
            Ref<ChunkStore> ref = blockComponentChunk.getEntityReference(index);
            if (ref != null) {
                PeaceCandleComponent pc = ref.getStore().getComponent(ref, peaceCandleComponentType);
                if (pc != null) {
                    pc.removeSuppressorUuid();
                }
            }
            Holder<ChunkStore> holder = blockComponentChunk.getEntityHolder(index);
            if (holder != null) {
                PeaceCandleComponent pc = holder.getComponent(peaceCandleComponentType);
                if (pc != null) {
                    pc.removeSuppressorUuid();
                }
            }
        }
    }

    private void discoverPeaceCandles(WorldChunk chunk) {
        World world = chunk.getWorld();
        BlockComponentChunk blockComponentChunk = chunk.getBlockComponentChunk();
        if (blockComponentChunk == null)
            return;

        Int2ObjectMap<Holder<ChunkStore>> holders = blockComponentChunk.getEntityHolders();

        if (holders != null && !holders.isEmpty()) {

            // snapshot map (not just keys)
            Int2ObjectMap<Holder<ChunkStore>> snap = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(holders);
            int[] keys = snap.keySet().toIntArray();

            for (int blockIndex : keys) {
                Holder<ChunkStore> holder = snap.get(blockIndex);
                PeaceCandleComponent pc = holder.getComponent(peaceCandleComponentType);
                if (pc == null ) continue;

                if (pc.getSuppressorUuid() != null){
                    if (LOG_SUPPRESSOR_FOUND) getLogger().atInfo().log("PeaceCandle  FOUND with suppression: %d", pc.getSuppressorUuid().toString());
                    continue;
                }

                int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
                int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
                int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
                int worldX = ChunkUtil.worldCoordFromLocalCoord(chunk.getX(), localX);
                int worldZ = ChunkUtil.worldCoordFromLocalCoord(chunk.getZ(), localZ);

                BlockType blockType = world.getBlockType(worldX, localY, worldZ);
                Vector3i pos = new Vector3i(worldX, localY, worldZ);

                if (!isPeaceCandleOn(blockType, chunk, pos)) continue;
                if (LOG_CANDLE_FOUND) {
                    getLogger().atInfo().log("PeaceCandle HOLDER FOUND block=%s at (%d,%d,%d)",
                            blockType.getId(), worldX, localY, worldZ);
                }
                createSuppression(chunk, new Vector3i(worldX, localY, worldZ), pc);
            }
        }

        Int2ObjectMap<Ref<ChunkStore>> references = blockComponentChunk.getEntityReferences();
        if (references != null && !references.isEmpty()) {

            for (int blockIndex : references.keySet()) {

                Ref<ChunkStore> ref = references.get(blockIndex);
                PeaceCandleComponent pc =  ref.getStore().getComponent(ref, peaceCandleComponentType);
                if (pc == null) continue;

                if (pc.getSuppressorUuid() != null){
                    if (LOG_SUPPRESSOR_FOUND) getLogger().atInfo().log("PeaceCandle  FOUND with suppression: %d", pc.getSuppressorUuid().toString());
                    continue;
                }

                int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
                int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
                int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
                int worldX = ChunkUtil.worldCoordFromLocalCoord(chunk.getX(), localX);
                int worldZ = ChunkUtil.worldCoordFromLocalCoord(chunk.getZ(), localZ);

                BlockType blockType = world.getBlockType(worldX, localY, worldZ);
                Vector3i pos = new Vector3i(worldX, localY, worldZ);

                if (!isPeaceCandleOn(blockType, chunk, pos)) continue;
                if (LOG_CANDLE_FOUND) {
                    getLogger().atInfo().log("PeaceCandle REF FOUND block=%s at (%d,%d,%d)",
                            blockType.getId(), worldX, localY, worldZ);
                }
                createSuppression(chunk, new Vector3i(worldX, localY, worldZ), pc);
            }
        }

    }

    private UUID createSuppression(WorldChunk chunk, Vector3i blockPos, PeaceCandleComponent pc) {
        if ( pc == null ) return null;

        EntityStore entityStore = chunk.getWorld().getEntityStore();
        Store<EntityStore> store = entityStore.getStore();
        if (store == null) return null;

        UUIDComponent suppressionUUID = UUIDComponent.randomUUID();
        pc.setSuppressorUuid(suppressionUUID.getUuid());
        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(SpawnSuppressionComponent.getComponentType(), new SpawnSuppressionComponent(SUPPRESSION_ASSET_ID));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(blockPos).add(0.5D), Vector3f.ZERO));
        holder.addComponent(UUIDComponent.getComponentType(), suppressionUUID);
        holder.addComponent(Nameplate.getComponentType(), new Nameplate(SUPPRESSION_NAME_PLATE));

        store.addEntity(holder, AddReason.SPAWN);

        if (LOG_SUPPRESSOR_CREATE) {
            getLogger().atInfo().log("PeaceCandle CREATE suppressor at (%d,%d,%d)",
                    blockPos.getX(), blockPos.getY(), blockPos.getZ());
        }
        return suppressionUUID.getUuid();
    }

    /**
     * Determines ON/OFF by comparing the placed block's numeric ID to the numeric ID
     * for the "Off" state variant key from StateData.
     *
     * This matches the behavior you observed:
     * - ON block id: "Peace_Candle"  (curIdx=3947)
     * - OFF block id: "*Peace_Candle_State_Definitions_Off" (curIdx=3950)
     */
    private boolean isPeaceCandleOn(BlockType bt, WorldChunk chunk, Vector3i worldPos) {
        if (chunk == null || worldPos == null) return false;

        int wx = worldPos.getX();
        int wy = worldPos.getY();
        int wz = worldPos.getZ();

        int lx = wx & 31;
        int lz = wz & 31;

        StateData sd = bt.getState();
        if (sd == null) return false;

        String offKey = sd.getBlockForState("Off");
        if (offKey == null) return false;

        int curIdx = chunk.getBlock(lx, wy, lz);
        int offIdx = BlockType.getAssetMap().getIndex(offKey);

        return curIdx != offIdx;
    }

    private boolean isPeaceCandleBlock(BlockType bt) {
        if (bt == null) return false;
        String id = bt.getId();
        return id != null && id.contains(PEACE_CANDLE_BLOCK_ID);
    }

    private void scanWorlds() {
        for (Iterator<World> iterator = Universe.get().getWorlds().values().iterator(); iterator.hasNext(); ) {
            World world = iterator.next();
            runOnWorldThread(world, () -> {
                LongIterator longIterator = world.getChunkStore().getChunkIndexes().iterator();
                while (longIterator.hasNext()) {
                    Long chunkIndex = longIterator.next();
                    WorldChunk chunk = world.getChunkIfLoaded(chunkIndex.longValue());
                    if (chunk == null)
                        continue;
                    setupChunk(chunk);
                }
            });
        }
    }

    private void cleanWorlds() {
        for (Iterator<World> it = Universe.get().getWorlds().values().iterator(); it.hasNext(); ) {
            World world = it.next();
            runOnWorldThread(world, () -> {
                LongIterator longIterator = world.getChunkStore().getChunkIndexes().iterator();
                while (longIterator.hasNext()) {
                    long chunkIndex = longIterator.nextLong();
                    WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
                    if (chunk == null) continue;
                    cleanChunk(chunk, true);
                }
            });
        }
    }

    private void runOnWorldThread(World world, Runnable action) {
        if (world == null || action == null) return;
        if (world.isInThread()) { action.run(); return; }
        world.execute(action);
    }

    private Vector3i toBlockPosition(TransformComponent transform) {
        Vector3d p = transform.getPosition();
        return new Vector3i(
                (int) Math.floor(p.getX()),
                (int) Math.floor(p.getY()),
                (int) Math.floor(p.getZ())
        );
    }

    private int toBlockIndex(Vector3i pos){
        int lx = pos.getX() & 31;
        int lz = pos.getZ() & 31;
        int index = ChunkUtil.indexBlockInColumn(lx, pos.getY(), lz);
        return index;
    }

    private void despawnSafely(Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return;

        Store<EntityStore> store = ref.getStore();
        store.removeEntity(ref, RemoveReason.REMOVE );
    }

    private void despawnHolderSafely(WorldChunk chunk, Holder<EntityStore> holder) {
        if (chunk == null || holder == null) return;

        Store<EntityStore> es = chunk.getWorld().getEntityStore().getStore();
        if (es == null) return;

        // Add -> get a Ref -> remove, so all normal cleanup runs (incl. suppression controller)
        Ref<EntityStore> ref = es.addEntity(holder, AddReason.LOAD);
        if (ref != null) es.removeEntity(ref, RemoveReason.REMOVE);
        else{
            getLogger().atWarning().log("despawnHolderSafely -- created ref is null?");
        }
    }
}
