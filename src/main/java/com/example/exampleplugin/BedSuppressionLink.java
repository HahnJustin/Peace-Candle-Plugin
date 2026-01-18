package com.example.exampleplugin;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nullable;
import java.util.UUID;

public class BedSuppressionLink implements Component<ChunkStore> {

    public static final BuilderCodec<BedSuppressionLink> CODEC =
            BuilderCodec.builder(BedSuppressionLink.class, BedSuppressionLink::new)
                    .append(new KeyedCodec<>("OwnerUUID", Codec.UUID_BINARY),
                            (c, v) -> c.ownerUuid = v,
                            c -> c.ownerUuid)
                    .add()
                    .append(new KeyedCodec<>("SuppressorUUID", Codec.UUID_BINARY),
                            (c, v) -> c.suppressorUuid = v,
                            c -> c.suppressorUuid)
                    .add()
                    .build();

    private UUID ownerUuid;
    private UUID suppressorUuid;

    public BedSuppressionLink() {}

    public BedSuppressionLink(UUID ownerUuid, UUID suppressorUuid) {
        this.ownerUuid = ownerUuid;
        this.suppressorUuid = suppressorUuid;
    }

    public UUID getOwnerUuid() { return ownerUuid; }
    public UUID getSuppressorUuid() { return suppressorUuid; }

    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }
    public void setSuppressorUuid(UUID suppressorUuid) { this.suppressorUuid = suppressorUuid; }

    @Nullable
    @Override
    public Component<ChunkStore> clone() {
        return new BedSuppressionLink(ownerUuid, suppressorUuid);
    }

    public static ComponentType<ChunkStore, BedSuppressionLink> TYPE;
}
