package town.kibty.hyproxy.command.cloud;

import lombok.extern.slf4j.Slf4j;
import org.incendo.cloud.component.CommandComponent;
import town.kibty.hyproxy.command.HyProxyCommand;
import town.kibty.hyproxy.command.CommandInfo;
import town.kibty.hyproxy.command.CommandSender;

@Slf4j
public class CloudHyProxyCommand<C> extends HyProxyCommand {
    private final HyProxyCloudCommandManager<C> manager;
    private final CommandComponent<C> command;

    CloudHyProxyCommand(
            CommandComponent<C> command,
            HyProxyCloudCommandManager<C> manager
    ) {
        super(new CommandInfo(
                command.name(),
                command.description().isEmpty() ? null : command.description().textDescription()
        ));
        this.command = command;
        this.manager = manager;
    }

    @Override
    public void handle(CommandSender commandSender, String[] args) {
        StringBuilder builder = new StringBuilder(this.command.name());
        for (String string : args) {
            builder.append(" ").append(string);
        }

        C sender = this.manager.senderMapper().map(commandSender);
        this.manager.commandExecutor().executeCommand(sender, builder.toString());
    }
}
