package town.kibty.hyproxy.command;

import town.kibty.hyproxy.message.Message;

public interface CommandSender {
    void sendMessage(Message message);
    boolean performCommand(String command);
    boolean hasPermission(String permission);
}
