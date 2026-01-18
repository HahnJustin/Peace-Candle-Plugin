package com.example.exampleplugin;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.SpawningPlugin;
import com.hypixel.hytale.server.spawning.suppression.component.SpawnSuppressionComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.exampleplugin.Utils.getPlayerUuid;

/**
 * Debug command:
 *   /suppression spawn
 *   /suppression remove
 *   /suppression clear   (delete all loaded plugin suppressors)
 *   /suppression nuke    (delete all loaded plugin suppressors + clear all loaded BedSuppressionLinks)
 *   /suppression here
 */
public class SpawnSuppressionDebugCommand extends AbstractCommandCollection {

    // Optional convenience: last suppressor UUID per player UUID (in-memory only)
    private static final Map<UUID, UUID> LAST_SUPPRESSOR_BY_PLAYER = new ConcurrentHashMap<>();

    private static final String DEBUG_SUPPRESSOR_TAG = "ExamplePluginSuppressor";

    /**
     * IMPORTANT:
     * Use a built-in suppression ID that is guaranteed to exist on vanilla servers.
     * Do NOT use custom IDs until the server stops hard-failing on missing assets.
     */
    private static final String SUPPRESSION_ID = "Spawn_Camp";

    public SpawnSuppressionDebugCommand() {
        super("suppression", "Spawn/remove a spawn suppression entity for debugging.");
        this.addSubCommand(new Spawn());
        this.addSubCommand(new Remove());
        this.addSubCommand(new Clear());
        this.addSubCommand(new Nuke());
        this.addSubCommand(new Here());
    }

    // ---------------- helpers ----------------

    private static boolean isPluginSuppressor(@Nullable Nameplate np) {
        if (np == null) return false;
        String t = np.getText();
        return t != null && t.equals(DEBUG_SUPPRESSOR_TAG);
    }

    private static @Nullable Ref<EntityStore> findEntityByUuid(@Nonnull Store<EntityStore> store, @Nonnull UUID uuid) {
        final Ref<EntityStore>[] found = new Ref[]{null};

        store.forEachChunk(UUIDComponent.getComponentType(), (archetypeChunk, commandBuffer) -> {
            for (int i = 0; i < archetypeChunk.size(); i++) {
                UUIDComponent u = archetypeChunk.getComponent(i, UUIDComponent.getComponentType());
                if (u != null && uuid.equals(u.getUuid())) {
                    found[0] = archetypeChunk.getReferenceTo(i);
                    return;
                }
            }
        });

        return found[0];
    }

    private static @Nullable Ref<ChunkStore> findRespawnRef(@Nonnull World world, @Nonnull Ref<EntityStore> playerRef) {
        return Utils.findRespawnBlockRefInPlayerChunk(world, playerRef);
    }

    private static @Nullable BedSuppressionLink getLink(@Nonnull Ref<ChunkStore> respawnRef) {
        Store<ChunkStore> cs = respawnRef.getStore();
        return cs.getComponent(respawnRef, BedSuppressionLink.TYPE);
    }

    private static void setLink(@Nonnull Ref<ChunkStore> respawnRef, @Nonnull BedSuppressionLink link) {
        Store<ChunkStore> cs = respawnRef.getStore();
        cs.addComponent(respawnRef, BedSuppressionLink.TYPE, link);
    }

    private static void clearLink(@Nonnull Ref<ChunkStore> respawnRef) {
        Store<ChunkStore> cs = respawnRef.getStore();
        cs.tryRemoveComponent(respawnRef, BedSuppressionLink.TYPE);
    }

    private static @Nullable Ref<EntityStore> spawnSuppressor(
            @Nonnull Store<EntityStore> store,
            @Nonnull Vector3d nearPos
    ) {
        if (SpawningPlugin.get() == null) return null;

        var spawnSuppType = SpawningPlugin.get().getSpawnSuppressorComponentType();

        Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();

        holder.putComponent(spawnSuppType, new SpawnSuppressionComponent(SUPPRESSION_ID));

        // Tag so /suppression clear/nuke can identify plugin-made suppressors
        holder.putComponent(Nameplate.getComponentType(), new Nameplate(DEBUG_SUPPRESSOR_TAG));

        Vector3d spawnPos = new Vector3d(nearPos.x + 0.5, nearPos.y, nearPos.z + 0.5);

        holder.putComponent(
                TransformComponent.getComponentType(),
                new TransformComponent(spawnPos, new Vector3f(0f, 0f, 0f))
        );

        holder.ensureComponent(UUIDComponent.getComponentType());

        return store.addEntity(holder, AddReason.SPAWN);
    }

    // ---------------- subcommands ----------------

    public static class Spawn extends InitializableAbstractCommand {

        public Spawn() {
            super("spawn", "Spawn a SpawnSuppression entity near you and link it to the bed in your chunk.");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            if (!Initialize(ctx)) return null;

            world.execute(() -> {
                if (SpawningPlugin.get() == null) {
                    ctx.sendMessage(Message.raw("SpawningPlugin.get() is null. Spawning may be disabled or load order is wrong."));
                    return;
                }

                Store<EntityStore> es = world.getEntityStore().getStore();

                UUID ownerUuid = getPlayerUuid(es, playerRef);
                if (ownerUuid == null) {
                    ctx.sendMessage(Message.raw("Player UUIDComponent missing; cannot link suppressor."));
                    return;
                }

                Ref<ChunkStore> respawnRef = findRespawnRef(world, playerRef);
                if (respawnRef == null) {
                    ctx.sendMessage(Message.raw("No respawn block found in your current chunk."));
                    return;
                }

                BedSuppressionLink existing = getLink(respawnRef);
                if (existing != null && existing.getSuppressorUuid() != null) {
                    ctx.sendMessage(Message.raw(
                            "Bed already has a linked suppressor UUID=" + existing.getSuppressorUuid() +
                                    " (use /suppression remove or /suppression clear/nuke)."
                    ));
                    return;
                }

                TransformComponent pt = es.getComponent(playerRef, TransformComponent.getComponentType());
                if (pt == null) {
                    ctx.sendMessage(Message.raw("Could not read player TransformComponent."));
                    return;
                }

                Ref<EntityStore> created = spawnSuppressor(es, pt.getPosition());
                if (created == null) {
                    ctx.sendMessage(Message.raw("Failed to spawn suppressor (SpawningPlugin missing or addEntity returned null)."));
                    return;
                }

                UUIDComponent supUuidComp = es.getComponent(created, UUIDComponent.getComponentType());
                UUID supUuid = (supUuidComp != null) ? supUuidComp.getUuid() : null;

                if (supUuid == null) {
                    ctx.sendMessage(Message.raw("Spawned suppressor " + created + " but UUIDComponent missing."));
                    return;
                }

                setLink(respawnRef, new BedSuppressionLink(ownerUuid, supUuid));
                LAST_SUPPRESSOR_BY_PLAYER.put(ownerUuid, supUuid);

                ctx.sendMessage(Message.raw("Spawned suppressor uuid=" + supUuid + " suppressionId=" + SUPPRESSION_ID + " and linked to bed."));
            });

            return null;
        }
    }

    public static class Remove extends InitializableAbstractCommand {

        public Remove() {
            super("remove", "Remove the suppressor linked to the bed in your current chunk (must be loaded).");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            if (!Initialize(ctx)) return null;

            world.execute(() -> {
                try {
                    Store<EntityStore> es = world.getEntityStore().getStore();

                    UUID playerUuid = getPlayerUuid(es, playerRef);
                    if (playerUuid == null) {
                        ctx.sendMessage(Message.raw("Player UUIDComponent missing; cannot remove suppressor."));
                        return;
                    }

                    Ref<ChunkStore> respawnRef = findRespawnRef(world, playerRef);
                    if (respawnRef == null) {
                        ctx.sendMessage(Message.raw("No respawn block found in your current chunk."));
                        return;
                    }

                    BedSuppressionLink link = getLink(respawnRef);
                    if (link == null || link.getSuppressorUuid() == null) {
                        ctx.sendMessage(Message.raw("This bed has no linked suppressor."));
                        return;
                    }

                    UUID owner = link.getOwnerUuid();
                    if (owner != null && !owner.equals(playerUuid)) {
                        ctx.sendMessage(Message.raw("Bed is owned by a different player; refusing to remove."));
                        return;
                    }

                    UUID supUuid = link.getSuppressorUuid();

                    Ref<EntityStore> supRef = findEntityByUuid(es, supUuid);

                    if ((supRef == null || !supRef.isValid())) {
                        UUID last = LAST_SUPPRESSOR_BY_PLAYER.get(playerUuid);
                        if (last != null && !last.equals(supUuid)) {
                            supRef = findEntityByUuid(es, last);
                        }
                    }

                    if (supRef == null || !supRef.isValid()) {
                        ctx.sendMessage(Message.raw(
                                "Linked suppressor uuid=" + supUuid + " is not currently loaded. " +
                                        "Go near it and retry, or use /suppression clear/nuke."
                        ));
                        return;
                    }

                    es.removeEntity(supRef, RemoveReason.REMOVE);
                    clearLink(respawnRef);
                    LAST_SUPPRESSOR_BY_PLAYER.remove(playerUuid);

                    ctx.sendMessage(Message.raw("Removed suppressor uuid=" + supUuid + " and cleared BedSuppressionLink."));
                } catch (Exception e) {
                    ctx.sendMessage(Message.raw("Error in remove: " + e.getMessage()));
                    e.printStackTrace();
                }
            });

            return null;
        }
    }

    /**
     * Clear = delete all LOADED plugin suppressors (identified by DEBUG_SUPPRESSOR_TAG).
     * (Does not touch bed links.)
     */
    public static class Clear extends InitializableAbstractCommand {

        public Clear() {
            super("clear", "Delete all LOADED plugin suppressors (does not touch bed links).");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            if (!Initialize(ctx)) return null;

            world.execute(() -> {
                if (SpawningPlugin.get() == null) {
                    ctx.sendMessage(Message.raw("SpawningPlugin.get() is null; cannot query suppression component type."));
                    return;
                }

                Store<EntityStore> es = world.getEntityStore().getStore();
                var spawnSuppType = SpawningPlugin.get().getSpawnSuppressorComponentType();

                AtomicInteger removed = new AtomicInteger();

                es.forEachChunk(spawnSuppType, (archetypeChunk, commandBuffer) -> {
                    for (int i = 0; i < archetypeChunk.size(); i++) {
                        Ref<EntityStore> r = archetypeChunk.getReferenceTo(i);

                        Nameplate np = commandBuffer.getComponent(r, Nameplate.getComponentType());
                        if (!isPluginSuppressor(np)) continue;

                        es.removeEntity(r, RemoveReason.REMOVE);
                        removed.incrementAndGet();
                    }
                });

                ctx.sendMessage(Message.raw("Removed " + removed.get() + " plugin suppressors (loaded)."));
            });

            return null;
        }
    }

    /**
     * Nuke = delete all LOADED plugin suppressors + clear all LOADED BedSuppressionLinks.
     * This is the “just make my test world sane again” button.
     */
    public static class Nuke extends InitializableAbstractCommand {

        public Nuke() {
            super("nuke", "Delete ALL loaded spawn suppressor entities (ignores tags/ownership).");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            if (!Initialize(ctx)) return null;

            world.execute(() -> {
                if (SpawningPlugin.get() == null) {
                    ctx.sendMessage(Message.raw("SpawningPlugin.get() is null; cannot query suppression component type."));
                    return;
                }

                Store<EntityStore> es = world.getEntityStore().getStore();
                var spawnSuppType = SpawningPlugin.get().getSpawnSuppressorComponentType();

                AtomicInteger removed = new AtomicInteger();

                es.forEachChunk(spawnSuppType, (archetypeChunk, commandBuffer) -> {
                    for (int i = 0; i < archetypeChunk.size(); i++) {
                        // Only nuke entities that actually have the component in this archetype chunk
                        SpawnSuppressionComponent s = archetypeChunk.getComponent(i, spawnSuppType);
                        if (s == null) continue;

                        Ref<EntityStore> r = archetypeChunk.getReferenceTo(i);

                        // IMPORTANT: mutate via commandBuffer inside forEachChunk
                        commandBuffer.removeEntity(r, RemoveReason.REMOVE);
                        removed.incrementAndGet();
                    }
                });

                ctx.sendMessage(Message.raw("NUKE removed " + removed.get() + " suppressors (loaded only)."));
            });

            return null;
        }
    }

    public static class Here extends InitializableAbstractCommand {

        public Here() {
            super("here", "List spawn suppressors in your current chunk.");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext ctx) {
            if (!Initialize(ctx)) return null;

            world.execute(() -> {
                if (SpawningPlugin.get() == null) {
                    ctx.sendMessage(Message.raw("SpawningPlugin.get() is null; cannot query suppression component type."));
                    return;
                }

                Store<EntityStore> store = world.getEntityStore().getStore();

                TransformComponent pt = store.getComponent(playerRef, TransformComponent.getComponentType());
                if (pt == null) {
                    ctx.sendMessage(Message.raw("Could not read player TransformComponent."));
                    return;
                }

                Vector3i p = pt.getPosition().toVector3i();
                long playerChunkIndex = ChunkUtil.indexChunkFromBlock(p.x, p.z);

                var spawnSuppType = SpawningPlugin.get().getSpawnSuppressorComponentType();

                AtomicInteger totalInChunk = new AtomicInteger();
                AtomicInteger samplesPrinted = new AtomicInteger();

                store.forEachChunk(spawnSuppType, (archetypeChunk, commandBuffer) -> {
                    for (int i = 0; i < archetypeChunk.size(); i++) {
                        SpawnSuppressionComponent s = archetypeChunk.getComponent(i, spawnSuppType);
                        if (s == null) continue;

                        Ref<EntityStore> r = archetypeChunk.getReferenceTo(i);
                        TransformComponent t = commandBuffer.getComponent(r, TransformComponent.getComponentType());
                        if (t == null) continue;

                        Vector3i sp = t.getPosition().toVector3i();
                        long ci = ChunkUtil.indexChunkFromBlock(sp.x, sp.z);
                        if (ci != playerChunkIndex) continue;

                        totalInChunk.incrementAndGet();

                        if (samplesPrinted.get() < 8) {
                            UUIDComponent u = commandBuffer.getComponent(r, UUIDComponent.getComponentType());
                            UUID id = (u != null) ? u.getUuid() : null;

                            Nameplate np = commandBuffer.getComponent(r, Nameplate.getComponentType());
                            boolean ours = isPluginSuppressor(np);

                            ctx.sendMessage(Message.raw(" - uuid=" + id + " suppression=" + s.getSpawnSuppression() + " pos=" + sp + (ours ? " [PLUGIN]" : "")));
                            samplesPrinted.incrementAndGet();
                        }
                    }
                });

                ctx.sendMessage(Message.raw("Suppressors in your chunk: " + totalInChunk.get()));
            });

            return null;
        }
    }
}
