package town.kibty.hyproxy.command.provided;

import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import town.kibty.hyproxy.command.CommandSender;
import town.kibty.hyproxy.console.HyProxyConsole;
import town.kibty.hyproxy.message.HyProxyColors;
import town.kibty.hyproxy.message.Message;

public class ShutdownCommand {
    @Command("hyproxy shutdown")
    @CommandDescription("Shuts down the entire proxy")
    @Permission("hyproxy.command.shutdown")
    public void shutdown(CommandSender sender) {
        if (!(sender instanceof HyProxyConsole console)) {
            sender.sendMessage(Message.raw("You can only shut down the proxy via the console!").color(HyProxyColors.ERROR_COLOR));
            return;
        }
        console.getProxy().shutdown();
    }
}
