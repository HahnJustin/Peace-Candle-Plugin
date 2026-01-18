package com.example.exampleplugin;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.jspecify.annotations.Nullable;

import javax.annotation.Nonnull;

public abstract class InitializableAbstractCommand extends AbstractCommand {

    protected Player player;
    protected World world;
    protected Ref<EntityStore> playerRef;

    protected InitializableAbstractCommand(@Nullable String name, @Nullable String description, boolean requiresConfirmation) {
        super(name, description, requiresConfirmation);
    }

    protected InitializableAbstractCommand(@Nullable String name, @Nullable String description) {
        super(name, description);
    }

    protected InitializableAbstractCommand(@Nullable String description) {
        super(description);
    }

    protected boolean Initialize(CommandContext ctx){
        if (!ensurePlayer(ctx)) return false;

        player = ctx.senderAs(Player.class);
        playerRef = ctx.senderAsPlayerRef();
        if (playerRef == null) {
            ctx.sendMessage(Message.raw("Could not get player reference."));
            return false;
        }

        world = player.getWorld();
        if (world == null) {
            ctx.sendMessage(Message.raw("Could not get world."));
            return false;
        }
        return true;
    }

    private static boolean ensurePlayer(@Nonnull CommandContext ctx) {
        if (!ctx.isPlayer()) {
            ctx.sendMessage(Message.raw("This command must be run by a player."));
            return false;
        }
        return true;
    }
}
