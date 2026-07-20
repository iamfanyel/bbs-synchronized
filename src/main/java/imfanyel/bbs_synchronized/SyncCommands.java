package imfanyel.bbs_synchronized;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import imfanyel.bbs_synchronized.network.SyncPackets;
import imfanyel.bbs_synchronized.server.ServerModelSync;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Registers the {@code /bbs model ...} subcommands. Brigadier merges the
 * {@code bbs} root literal with the one registered by BBS FS itself, so the
 * commands show up under the familiar {@code /bbs} tree.
 */
public class SyncCommands
{
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment)
    {
        LiteralArgumentBuilder<ServerCommandSource> bbs = CommandManager.literal("bbs").requires((source) -> true);

        bbs.then(CommandManager.literal("model")
            .then(CommandManager.literal("download").executes(SyncCommands::download))
            .then(CommandManager.literal("upload")
                .executes((ctx) -> upload(ctx, false))
                .then(CommandManager.literal("--force").executes((ctx) -> upload(ctx, true)))
            )
        );

        dispatcher.register(bbs);
    }

    private static ServerPlayerEntity requireSyncedPlayer(CommandContext<ServerCommandSource> ctx)
    {
        ServerPlayerEntity player = ctx.getSource().getPlayer();

        if (player == null)
        {
            ctx.getSource().sendError(Text.translatable("bbs_synchronized.command.players_only"));

            return null;
        }

        if (!ServerModelSync.isClientSynchronized(player))
        {
            ctx.getSource().sendError(Text.translatable("bbs_synchronized.command.not_installed"));

            return null;
        }

        return player;
    }

    private static int download(CommandContext<ServerCommandSource> ctx)
    {
        ServerPlayerEntity player = requireSyncedPlayer(ctx);

        if (player == null)
        {
            return 0;
        }

        ctx.getSource().sendFeedback(() -> prefixed("bbs_synchronized.command.checking"), false);
        ServerModelSync.sendManifest(player, SyncPackets.REASON_RELOAD);

        return 1;
    }

    private static int upload(CommandContext<ServerCommandSource> ctx, boolean force)
    {
        ServerPlayerEntity player = requireSyncedPlayer(ctx);

        if (player == null)
        {
            return 0;
        }

        ctx.getSource().sendFeedback(() -> prefixed(force
            ? "bbs_synchronized.command.uploading_all"
            : "bbs_synchronized.command.uploading"), false);
        ServerModelSync.requestUpload(player, force);

        return 1;
    }

    private static Text prefixed(String key)
    {
        return Text.translatable("bbs_synchronized.prefix").formatted(Formatting.AQUA)
            .append(Text.translatable(key).formatted(Formatting.GRAY));
    }
}
