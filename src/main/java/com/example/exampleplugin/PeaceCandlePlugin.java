package com.example.exampleplugin;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventBus;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.StateData;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class PeaceCandlePlugin extends JavaPlugin {

    private static final String SUPPRESSION_ASSET_ID = "Peace_Candle";
    private static final long SCAN_INTERVAL_SECONDS = 60L;
    private static final String PEACE_CANDLE_BLOCK_ID = "Peace_Candle";

    // Minimal logging switches
    private static final boolean LOG_CANDLE_FOUND = true;      // logs each candle block found (can still be a lot)
    private static final boolean LOG_SUPPRESSOR_CREATE = true; // logs when we actually spawn a suppressor
    private static final boolean LOG_SUPPRESSOR_REMOVE = true; // logs when we remove a suppressor
    private static final boolean LOG_NULL_HOLDERS_WARN = false;// optional: warns when takeEntityHolders() is null

    private ComponentType<ChunkStore, PeaceCandleComponent> peaceCandleComponentType;

    private ScheduledFuture<?> scanningTask;

    public PeaceCandlePlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        EventBus eventBus = HytaleServer.get().getEventBus();

        // Chunk lifecycle hook
        eventBus.registerGlobal(ChunkPreLoadProcessEvent.class, event -> {
            WorldChunk chunk = event.getChunk();
            World world = (chunk != null) ? chunk.getWorld() : null;
            runOnWorldThread(world, () -> setupChunk(chunk));
        });

        // Periodic scan
        scanningTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                this::scanWorlds,
                0L,
                SCAN_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        this.peaceCandleComponentType = ChunkStore.REGISTRY.registerComponent(PeaceCandleComponent.class, "PeaceCandleComponent", PeaceCandleComponent.CODEC);
        PeaceCandleComponent.SetComponentType(peaceCandleComponentType);
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

    private void setupChunk(WorldChunk chunk) {
        if (chunk == null) return;

        // remove stale suppression entities in this chunk + keep valid ones
        cleanChunk(chunk, true);

        // find candles and ensure they have a suppression entity (only if ON)
        discoverPeaceCandles(chunk);
    }

    private void cleanChunk(WorldChunk chunk, boolean keepAlreadyLoaded) {
        EntityChunk entityChunk = chunk.getEntityChunk();
        if (entityChunk == null) return;

        Holder[] holders = entityChunk.takeEntityHolders();
        if (holders == null) {
            if (LOG_NULL_HOLDERS_WARN) {
                getLogger().atWarning().log("PeaceCandle cleanChunk: takeEntityHolders() returned null for chunk (%d,%d)",
                        chunk.getX(), chunk.getZ());
            }
            return;
        }

        ComponentType<EntityStore, SpawnSuppressionComponent> suppressionType =
                SpawnSuppressionComponent.getComponentType();

        for (Holder<EntityStore> entityHolder : holders) {
            SpawnSuppressionComponent suppression =
                    (SpawnSuppressionComponent) entityHolder.getComponent(suppressionType);

            if (suppression != null && SUPPRESSION_ASSET_ID.equals(suppression.getSpawnSuppression())) {
                TransformComponent transform =
                        (TransformComponent) entityHolder.getComponent(TransformComponent.getComponentType());
                UUIDComponent uuidComponent =
                        (UUIDComponent) entityHolder.getComponent(UUIDComponent.getComponentType());

                if (transform != null) {
                    Vector3i blockPos = toBlockPosition(transform);

                    // If it exists without UUID, recreate it (mirrors their logic)
                    if (uuidComponent == null) {
                        createSuppression(chunk, blockPos);
                        continue;
                    }

                    // Keep if the candle is still ON at that location; else drop (delete)
                    if (keepAlreadyLoaded && isPeaceCandleOn(chunk, blockPos)) {
                        entityChunk.storeEntityHolder(entityHolder);
                    } else {
                        if (LOG_SUPPRESSOR_REMOVE) {
                            getLogger().atInfo().log("PeaceCandle REMOVE suppressor at (%d,%d,%d)",
                                    blockPos.getX(), blockPos.getY(), blockPos.getZ());
                        }
                        // drop it (deletes)
                    }
                }
                // else: drop it (matches your current behavior)
            } else {
                // keep everything else
                entityChunk.storeEntityHolder(entityHolder);
            }
        }

        entityChunk.markNeedsSaving();
    }

    private void discoverChunkCandles(WorldChunk chunk) {
        if (chunk.getBlockChunk() == null) return;

        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                for (int y = 0; y < 320; y++) {
                    BlockType bt = chunk.getBlockType(lx, y, lz);
                    if (bt == null || bt.isUnknown()) continue;

                    if (!bt.getId().contains(PEACE_CANDLE_BLOCK_ID)) continue;

                    int worldX = (chunk.getX() << 5) + lx;
                    int worldZ = (chunk.getZ() << 5) + lz;
                    Vector3i pos = new Vector3i(worldX, y, worldZ);

                    if (LOG_CANDLE_FOUND) {
                        getLogger().atInfo().log("PeaceCandle FOUND block=%s at (%d,%d,%d)",
                                bt.getId(), pos.getX(), pos.getY(), pos.getZ());
                    }

                    createSuppression(chunk, pos);
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
        for (ObjectIterator<Int2ObjectMap.Entry<Holder<ChunkStore>>> objectIterator = holders.int2ObjectEntrySet().iterator(); objectIterator.hasNext(); ) {
            Int2ObjectMap.Entry<Holder<ChunkStore>> entry = objectIterator.next();
            Holder<ChunkStore> holder = (Holder<ChunkStore>)entry.getValue();
            if (holder == null)
                continue;
            PeaceCandleComponent peaceCandleComponent = holder.getComponent(peaceCandleComponentType);
            if (peaceCandleComponent == null)
                continue;
            int blockIndex = entry.getIntKey();
            int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
            int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
            int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
            int worldX = ChunkUtil.worldCoordFromLocalCoord(chunk.getX(), localX);
            int worldZ = ChunkUtil.worldCoordFromLocalCoord(chunk.getZ(), localZ);
            BlockType blockType = world.getBlockType(worldX, localY, worldZ);
            if (!isPeaceCandleBlock(blockType) && isPeaceCandleOn(chunk, new Vector3i(worldX, localY, worldZ)))
                continue;
            if (LOG_CANDLE_FOUND) {
                getLogger().atInfo().log("PeaceCandle FOUND block=%s at (%d,%d,%d)",
                        blockType.getId(), worldX, localY, worldZ);
            }
            createSuppression(chunk, new Vector3i(worldX, localY, worldZ));
        }
    }

    private void createSuppression(WorldChunk chunk, Vector3i blockPos) {
        // Only create if candle is ON
        if (!isPeaceCandleOn(chunk, blockPos)) return;

        EntityStore entityStore = chunk.getWorld().getEntityStore();
        Store<EntityStore> store = entityStore.getStore();
        if (store == null) return;

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
        holder.addComponent(
                SpawnSuppressionComponent.getComponentType(),
                new SpawnSuppressionComponent(SUPPRESSION_ASSET_ID)
        );
        holder.addComponent(
                TransformComponent.getComponentType(),
                new TransformComponent(new Vector3d(blockPos).add(0.5D), Vector3f.ZERO)
        );
        holder.addComponent(UUIDComponent.getComponentType(), UUIDComponent.randomUUID());

        store.addEntity(holder, AddReason.SPAWN);

        if (LOG_SUPPRESSOR_CREATE) {
            getLogger().atInfo().log("PeaceCandle CREATE suppressor at (%d,%d,%d)",
                    blockPos.getX(), blockPos.getY(), blockPos.getZ());
        }
    }

    /**
     * Determines ON/OFF by comparing the placed block's numeric ID to the numeric ID
     * for the "Off" state variant key from StateData.
     *
     * This matches the behavior you observed:
     * - ON block id: "Peace_Candle"  (curIdx=3947)
     * - OFF block id: "*Peace_Candle_State_Definitions_Off" (curIdx=3950)
     */
    private boolean isPeaceCandleOn(WorldChunk chunk, Vector3i worldPos) {
        if (chunk == null || worldPos == null) return false;

        int wx = worldPos.getX();
        int wy = worldPos.getY();
        int wz = worldPos.getZ();

        int lx = wx & 31;
        int lz = wz & 31;

        BlockType bt = chunk.getBlockType(lx, wy, lz);
        if (!isPeaceCandleBlock(bt)) return false;

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
        for (Iterator<World> it = Universe.get().getWorlds().values().iterator(); it.hasNext(); ) {
            World world = it.next();
            runOnWorldThread(world, () -> {
                LongIterator longIterator = world.getChunkStore().getChunkIndexes().iterator();
                while (longIterator.hasNext()) {
                    long chunkIndex = longIterator.nextLong();
                    WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
                    if (chunk == null) continue;
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
                    cleanChunk(chunk, false);
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
}
