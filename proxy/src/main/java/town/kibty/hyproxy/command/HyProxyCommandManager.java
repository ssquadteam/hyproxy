package town.kibty.hyproxy.command;

import lombok.Getter;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.jspecify.annotations.Nullable;
import town.kibty.hyproxy.HyProxy;
import town.kibty.hyproxy.command.cloud.HyProxyCloudCommandManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HyProxyCommandManager {
    @Getter
    private final HyProxyCloudCommandManager<CommandSender> cloudCommandManager;
    @Getter
    private final AnnotationParser<CommandSender> cloudAnnotationParser;
    private final Map<String, HyProxyCommand> commandsByName = new ConcurrentHashMap<>();

    public HyProxyCommandManager(HyProxy proxy) {
        this.cloudCommandManager = new HyProxyCloudCommandManager<>(proxy, ExecutionCoordinator.asyncCoordinator(), SenderMapper.identity());
        this.cloudAnnotationParser = new AnnotationParser<>(this.cloudCommandManager, CommandSender.class);
    }

    public boolean performCommand(CommandSender sender, String input) {
        List<String> args = new ArrayList<>(Arrays.asList(input.split(" ")));
        String commandName = args.removeFirst();

        HyProxyCommand command = this.getCommandByName(commandName);
        if (command == null) {
            return false;
        }

        command.handle(sender, args.toArray(new String[0]));
        return true;
    }

    /**
     * registers an incendo cloud framework annotation command from a user-provided class instance
     * @param command the command class
     */
    public void registerCloudAnnotationCommand(Object command) {
        cloudAnnotationParser.parse(command);
    }

    /**
     * registers a proxy command
     * @param command the command to register
     */
    public void registerCommand(HyProxyCommand command) {
        this.commandsByName.put(command.getInfo().name().toLowerCase(Locale.ROOT), command);
    }

    /**
     * unregisters a proxy command
     * @param command the command to unregister
     * @return if the command was unregistered or not
     */
    public boolean unregisterCommand(HyProxyCommand command) {
        return this.commandsByName.remove(command.getInfo().name().toLowerCase(Locale.ROOT)) != null;
    }

    public @Nullable HyProxyCommand getCommandByName(String name) {
        return this.commandsByName.get(name.toLowerCase(Locale.ROOT));
    }
}
