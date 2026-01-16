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

    public void registerCloudAnnotationCommand(Object command) {
        cloudAnnotationParser.parse(command);
    }

    public void registerCommand(HyProxyCommand command) {
        this.commandsByName.put(command.getInfo().name().toLowerCase(Locale.ROOT), command);
    }

    public void unregisterCommand(HyProxyCommand command) {
        this.commandsByName.remove(command.getInfo().name().toLowerCase(Locale.ROOT));
    }

    public @Nullable HyProxyCommand getCommandByName(String name) {
        return this.commandsByName.get(name.toLowerCase(Locale.ROOT));
    }
}
