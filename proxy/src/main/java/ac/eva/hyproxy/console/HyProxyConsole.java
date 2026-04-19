package ac.eva.hyproxy.console;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecrell.terminalconsole.SimpleTerminalConsole;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.command.CommandSender;
import ac.eva.hyproxy.message.HyProxyColors;
import ac.eva.hyproxy.message.Message;
import ac.eva.hyproxy.util.MessageUtil;

@Slf4j
@RequiredArgsConstructor
public class HyProxyConsole extends SimpleTerminalConsole implements CommandSender {
    @Getter
    private final HyProxy proxy;

    @Override
    protected boolean isRunning() {
        return true;
    }

    @Override
    protected void runCommand(String s) {
        String input = s;
        if (input.startsWith("/")) {
            input = input.substring(1);
        }

        if (!proxy.getCommandManager().performCommand(this, input)) {
            sendMessage(Message.raw("Unknown command specified").color(HyProxyColors.ERROR_COLOR));
        }
    }

    @Override
    protected void shutdown() {
        proxy.shutdown();
    }

    @Override
    public void sendMessage(Message message) {
        log.info(MessageUtil.toAnsiString(message).toAnsi());
    }

    @Override
    public boolean performCommand(String command) {
        return proxy.getCommandManager().performCommand(this, command);
    }

    @Override
    public boolean hasPermission(String permission) {
        return true;
    }
}
