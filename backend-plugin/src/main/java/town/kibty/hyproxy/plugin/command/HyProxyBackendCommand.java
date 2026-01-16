package town.kibty.hyproxy.plugin.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandUtil;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.common.util.ProxyCommunicationUtil;
import town.kibty.hyproxy.plugin.HyProxyBackendPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class HyProxyBackendCommand extends AbstractCommandCollection {
    private final HyProxyBackendPlugin plugin;

    public HyProxyBackendCommand(HyProxyBackendPlugin plugin) {
        super("hyproxy-backend", "Backend commands for hyproxy");
        this.plugin = plugin;

        addSubCommand(new SendCommand(this.plugin));
    }

    public class SendCommand extends AbstractCommand {
        private final HyProxyBackendPlugin plugin;
        private final RequiredArg<UUID> playerArg = this.withRequiredArg("player", "The player to send to the specified backend", ArgTypes.PLAYER_UUID);
        private final RequiredArg<String> backendIdArg = this.withRequiredArg("backendId", "The backend to send the player to", ArgTypes.STRING);

        public SendCommand(HyProxyBackendPlugin plugin) {
            super("send", "Send a player to a backend");
            this.plugin = plugin;
        }

        @Override
        protected @Nullable CompletableFuture<Void> execute(@NonNull CommandContext context) {
            UUID senderId = new UUID(0, 0); // console
            if (context.isPlayer()) {
                //noinspection removal - sorta have to here tbh
                senderId = context.senderAs(Player.class).getPlayerRef().getPacketHandler().getAuth().getUuid();
            }

            CommandUtil.requirePermission(context.sender(), "hyproxy.command.send");
            UUID targetId = context.get(this.playerArg);
            String backendId = context.get(this.backendIdArg);

            if (Universe.get().getPlayers().isEmpty()) {
                context.sendMessage(Message.raw("There is no players on the server to be able to relay the proxy communication message over."));
                return CompletableFuture.completedFuture(null);
            }
            plugin.sendProxyMessage(new ProxyCommunicationUtil.ProxyCommunicationMessage.SendToBackend(
                    backendId,
                    targetId,
                    senderId
            ));

            return CompletableFuture.completedFuture(null);
        }
    }
}
