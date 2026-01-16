package town.kibty.hyproxy.command.cloud.parser;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.caption.CaptionVariable;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.exception.parsing.ParserException;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import town.kibty.hyproxy.command.cloud.HyProxyCloudCommandManager;
import town.kibty.hyproxy.player.HyProxyPlayer;

public class CloudPlayerParser<C> implements ArgumentParser<C, HyProxyPlayer> {
    public static <C> @NonNull ParserDescriptor<C, HyProxyPlayer> playerParser() {
        return ParserDescriptor.of(new CloudPlayerParser<>(), HyProxyPlayer.class);
    }

    public static <C> CommandComponent.@NonNull Builder<C, HyProxyPlayer> playerComponent() {
        return CommandComponent.<C, HyProxyPlayer>builder().parser(playerParser());
    }

    @Override
    public @NonNull ArgumentParseResult<HyProxyPlayer> parse(@NonNull CommandContext<C> context, @NonNull CommandInput commandInput) {
        String input = commandInput.readString();
        HyProxyPlayer player = context.get(HyProxyCloudCommandManager.PROXY_KEY).getPlayerByUsername(input);

        if (player == null) {
            return ArgumentParseResult.failure(new PlayerParseException(
                    input,
                    context
            ));
        }

        return ArgumentParseResult.success(player);
    }

    public static final class PlayerParseException extends ParserException {
        PlayerParseException(
                String input,
                CommandContext<?> context
        ) {
            super(
                    CloudPlayerParser.class,
                    context,
                    HyProxyCloudCommandManager.ARGUMENT_PARSE_FAILURE_PLAYER,
                    CaptionVariable.of("input", input)
            );
        }
    }
}
