package town.kibty.hyproxy.command;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public abstract class HyProxyCommand {
    @Getter
    private final CommandInfo info;

    public abstract void handle(CommandSender sender, String[] args);
}
