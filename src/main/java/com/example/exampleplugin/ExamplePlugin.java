package com.example.exampleplugin;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

public class ExamplePlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private ComponentType<ChunkStore, BedSuppressionLink> bedSuppressionLinkType;

    public ExamplePlugin(JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Hello from %s version %s", this.getName(), this.getManifest().getVersion().toString());
    }

    @Override
    protected void setup() {
        super.setup();

        // Commands
        this.getCommandRegistry().registerCommand(new RespawnBlockCommand());
        this.getCommandRegistry().registerCommand(new SpawnSuppressionDebugCommand());

        BedSuppressionLink.TYPE =
                ChunkStore.REGISTRY.registerComponent(
                        BedSuppressionLink.class,
                        "ExamplePluginBedSuppressionLink",
                        BedSuppressionLink.CODEC
                );
    }

    @Override
    protected void start() {
        // Register system after all plugins have setup()’d
        ChunkStore.REGISTRY.registerSystem(new BedAuditTickSystem(BedSuppressionLink.TYPE));
    }
}
