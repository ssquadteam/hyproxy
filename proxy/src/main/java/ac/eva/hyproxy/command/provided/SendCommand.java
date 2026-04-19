package ac.eva.hyproxy.command.provided;

import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.command.CommandSender;
import ac.eva.hyproxy.message.HyProxyColors;
import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.player.HyProxyPlayer;

public class SendCommand {
    @Command("hyproxy send <player> <backend>")
    @Permission("hyproxy.command.send")
    @CommandDescription("Send a player to a specific backend")
    public void sendPlayerToBackend(CommandSender sender, @Argument("player") HyProxyPlayer player, @Argument("backend") HyProxyBackend backend) {
        player.sendPlayerToBackend(backend);
        sender.sendMessage(Message.empty()
                .insert(Message.raw("Successfully sent ").color(HyProxyColors.SECONDARY_COLOR))
                .insert(Message.raw(player.getUsername()).color(HyProxyColors.PRIMARY_COLOR))
                .insert(Message.raw(" to backend ").color(HyProxyColors.SECONDARY_COLOR))
                .insert(Message.raw(backend.getInfo().id()).color(HyProxyColors.PRIMARY_COLOR))
        );
    }
}
