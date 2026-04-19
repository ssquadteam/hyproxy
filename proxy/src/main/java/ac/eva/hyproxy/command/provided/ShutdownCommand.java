package ac.eva.hyproxy.command.provided;

import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import ac.eva.hyproxy.command.CommandSender;
import ac.eva.hyproxy.console.HyProxyConsole;
import ac.eva.hyproxy.message.HyProxyColors;
import ac.eva.hyproxy.message.Message;

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
