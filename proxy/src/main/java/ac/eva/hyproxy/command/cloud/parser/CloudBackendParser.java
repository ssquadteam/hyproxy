package ac.eva.hyproxy.command.cloud.parser;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.caption.CaptionVariable;
import org.incendo.cloud.component.CommandComponent;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.exception.parsing.ParserException;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;
import ac.eva.hyproxy.backend.HyProxyBackend;
import ac.eva.hyproxy.command.cloud.HyProxyCloudCommandManager;

public class CloudBackendParser<C> implements ArgumentParser<C, HyProxyBackend> {
    public static <C> @NonNull ParserDescriptor<C, HyProxyBackend> backendParser() {
        return ParserDescriptor.of(new CloudBackendParser<>(), HyProxyBackend.class);
    }

    public static <C> CommandComponent.@NonNull Builder<C, HyProxyBackend> backendComponent() {
        return CommandComponent.<C, HyProxyBackend>builder().parser(backendParser());
    }

    @Override
    public @NonNull ArgumentParseResult<HyProxyBackend> parse(@NonNull CommandContext<C> context, @NonNull CommandInput commandInput) {
        String input = commandInput.readString();
        HyProxyBackend backend = context.get(HyProxyCloudCommandManager.PROXY_KEY).getBackendById(input);

        if (backend == null) {
            return ArgumentParseResult.failure(new BackendParseException(
                    input,
                    context
            ));
        }

        return ArgumentParseResult.success(backend);
    }

    public static final class BackendParseException extends ParserException {
        BackendParseException(
                String input,
                CommandContext<?> context
        ) {
            super(
                    CloudBackendParser.class,
                    context,
                    HyProxyCloudCommandManager.ARGUMENT_PARSE_FAILURE_BACKEND,
                    CaptionVariable.of("input", input)
            );
        }
    }
}
