package ac.eva.hyproxy.command.provided;

import jdk.jfr.Description;
import lombok.RequiredArgsConstructor;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.Permission;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.command.CommandSender;
import ac.eva.hyproxy.message.HyProxyColors;
import ac.eva.hyproxy.message.Message;

@RequiredArgsConstructor
public class ReloadCommand {
    private final HyProxy proxy;

    @Command("hyproxy reload")
    @Description("Reloads the configuration for the proxy")
    @Permission("hyproxy.command.reload")
    public void reloadConfig(CommandSender sender) {
        if (!proxy.reloadConfig()) {
            sender.sendMessage(Message.raw("An error occurred while reloading the proxy configuration, please check your console for more details").color(HyProxyColors.ERROR_COLOR));
            return;
        }

        sender.sendMessage(Message.raw("Successfully reloaded the proxy configuration").color(HyProxyColors.PRIMARY_COLOR));
    }
}
