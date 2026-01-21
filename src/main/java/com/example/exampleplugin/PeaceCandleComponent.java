package com.example.exampleplugin;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.meta.state.RespawnBlock;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nullable;
import java.util.UUID;

public class PeaceCandleComponent implements Component<ChunkStore> {

    private static ComponentType<ChunkStore, PeaceCandleComponent> componentType;

    public static final BuilderCodec<PeaceCandleComponent> CODEC =
            BuilderCodec.builder(PeaceCandleComponent.class, PeaceCandleComponent::new)
                    .append(new KeyedCodec<>("OwnerUUID", Codec.UUID_BINARY),
                            (c, v) -> c.ownerUuid = v,
                            c -> c.ownerUuid)
                    .add()
                    .append(new KeyedCodec<>("SuppressorUUID", Codec.UUID_BINARY),
                            (c, v) -> c.suppressorUuid = v,
                            c -> c.suppressorUuid)
                    .add()
                    .build();


    public static ComponentType<ChunkStore, PeaceCandleComponent> getComponentType() {
        return componentType;
    }

    public static void SetComponentType(ComponentType<ChunkStore, PeaceCandleComponent> componentType){
        PeaceCandleComponent.componentType = componentType;
    }

    private UUID ownerUuid;
    private UUID suppressorUuid;

    public PeaceCandleComponent() {}

    public PeaceCandleComponent(UUID ownerUuid, UUID suppressorUuid) {
        this.ownerUuid = ownerUuid;
        this.suppressorUuid = suppressorUuid;
    }

    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getSuppressorUuid() { return suppressorUuid; }

    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }
    public void setSuppressorUuid(UUID suppressorUuid) { this.suppressorUuid = suppressorUuid; }

    public void removeSuppressorUuid(){this.suppressorUuid = null; }

    @Nullable
    @Override
    public Component<ChunkStore> clone() {
        return new PeaceCandleComponent(ownerUuid, suppressorUuid);
    }

}
