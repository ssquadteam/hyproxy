package ac.eva.hyproxy.command;

import ac.eva.hyproxy.message.Message;

public interface CommandSender {
    /**
     * sends a message to the sender
     * @param message the message to send
     */
    void sendMessage(Message message);

    /**
     * performs a proxy command as the sender
     * <br /><br />
     * note: this will <b>not</b> execute backend commands
     * @param command the command to perform
     * @return if the command was found (on the proxy) or not
     */
    boolean performCommand(String command);
    /**
     * checks if the sender has a permission
     * @param permission the permission to check for
     * @return if the player has the permission or not
     */
    boolean hasPermission(String permission);
}
