package ac.eva.hyproxy.command.cloud;

import io.leangen.geantyref.TypeToken;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.SenderMapperHolder;
import org.incendo.cloud.caption.Caption;
import org.incendo.cloud.caption.CaptionProvider;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.key.CloudKey;
import ac.eva.hyproxy.HyProxy;
import ac.eva.hyproxy.command.CommandSender;
import ac.eva.hyproxy.command.cloud.parser.CloudBackendParser;
import ac.eva.hyproxy.command.cloud.parser.CloudPlayerParser;
import ac.eva.hyproxy.message.HyProxyColors;
import ac.eva.hyproxy.message.Message;

@Slf4j
public class HyProxyCloudCommandManager<C> extends CommandManager<C> implements SenderMapperHolder<CommandSender, C> {
    public static final CloudKey<HyProxy> PROXY_KEY = CloudKey.of(
            "Proxy",
            TypeToken.get(HyProxy.class)
    );

    public static final Caption ARGUMENT_PARSE_FAILURE_PLAYER = Caption.of("argument.parse.failure.player");
    public static final Caption ARGUMENT_PARSE_FAILURE_BACKEND = Caption.of("argument.parse.failure.backend");

    @Getter
    private final HyProxy proxy;
    private final SenderMapper<CommandSender, C> senderMapper;

    public HyProxyCloudCommandManager(
            HyProxy proxy,
            ExecutionCoordinator<C> executionCoordinator,
            SenderMapper<CommandSender, C> senderMapper
    ) {
        super(executionCoordinator, new HyProxyCloudCommandRegistrationHandler<>());
        ((HyProxyCloudCommandRegistrationHandler<C>) this.commandRegistrationHandler()).initialize(this);

        this.proxy = proxy;
        this.senderMapper = senderMapper;

        this.registerCommandPreProcessor(new HyProxyCloudCommandPreprocessor<>(this));

        this.parserRegistry()
                .registerParser(CloudPlayerParser.playerParser())
                .registerParser(CloudBackendParser.backendParser());

        this.captionRegistry()
                .registerProvider(CaptionProvider.<C>constantProvider()
                        .putCaption(ARGUMENT_PARSE_FAILURE_PLAYER, "'<input>' is not a valid player")
                        .putCaption(ARGUMENT_PARSE_FAILURE_BACKEND, "'<input>' is not a valid backend")
                        .build()
                );

        this.registerDefaultExceptionHandlers();
    }

    @Override
    public boolean hasPermission(@org.jspecify.annotations.NonNull C sender, @NonNull String permission) {
        if (permission.isEmpty()) {
            return true;
        }
        return this.senderMapper.reverse(sender).hasPermission(permission);
    }

    @Override
    public @NonNull SenderMapper<CommandSender, C> senderMapper() {
        return this.senderMapper;
    }


    private void registerDefaultExceptionHandlers() {
        this.registerDefaultExceptionHandlers(
                triplet -> {
                    final CommandSender commandSender = this.senderMapper.reverse(triplet.first().sender());
                    final String message = triplet.first().formatCaption(triplet.second(), triplet.third());
                    commandSender.sendMessage(Message.raw(message).color(HyProxyColors.ERROR_COLOR));
                },
                pair -> log.error(pair.first(), pair.second())
        );
    }
}
