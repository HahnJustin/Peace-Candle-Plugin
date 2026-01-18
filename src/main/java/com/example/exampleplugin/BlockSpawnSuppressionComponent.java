package com.example.exampleplugin;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nullable;

public class BlockSpawnSuppressionComponent implements Component<ChunkStore> {

    public static final BuilderCodec<BlockSpawnSuppressionComponent> CODEC =
            BuilderCodec.builder(BlockSpawnSuppressionComponent.class, BlockSpawnSuppressionComponent::new)
                    .append(new KeyedCodec<>("SpawnSuppression", Codec.STRING),
                            (c, v) -> c.spawnSuppression = v,
                            c -> c.spawnSuppression)
                    .add()
                    .build();

    private String spawnSuppression;

    public BlockSpawnSuppressionComponent() {}
    public BlockSpawnSuppressionComponent(String spawnSuppression) { this.spawnSuppression = spawnSuppression; }

    public String getSpawnSuppression() { return spawnSuppression; }
    public void setSpawnSuppression(String spawnSuppression) { this.spawnSuppression = spawnSuppression; }

    @Nullable
    @Override
    public Component<ChunkStore> clone() {
        return new BlockSpawnSuppressionComponent(spawnSuppression);
    }

    public static ComponentType<ChunkStore, BlockSpawnSuppressionComponent> TYPE;
}
