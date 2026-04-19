package ac.eva.hyproxy.command.cloud;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.Command;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import ac.eva.hyproxy.command.HyProxyCommand;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HyProxyCloudCommandRegistrationHandler<C> implements CommandRegistrationHandler<C> {
    private HyProxyCloudCommandManager<C> manager;
    // todo: does this need to be concurrent?
    private final Map<CommandComponent<C>, HyProxyCommand> registeredCommands = new ConcurrentHashMap<>();

    HyProxyCloudCommandRegistrationHandler() {}

    void initialize(HyProxyCloudCommandManager<C> manager) {
        this.manager = manager;
    }

    @Override
    public boolean registerCommand(@NonNull Command<C> cloudCommand) {
        CommandComponent<C> component = cloudCommand.rootComponent();
        if (this.registeredCommands.containsKey(component)) {
            return false;
        }

        CloudHyProxyCommand<C> command = new CloudHyProxyCommand<>(
                component,
                this.manager
        );

        this.registeredCommands.put(component, command);
        this.manager.getProxy().getCommandManager().registerCommand(command);

        return true;
    }
}
