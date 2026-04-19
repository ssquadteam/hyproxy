package ac.eva.hyproxy.command.cloud;

import lombok.RequiredArgsConstructor;
import org.incendo.cloud.execution.preprocessor.CommandPreprocessingContext;
import org.incendo.cloud.execution.preprocessor.CommandPreprocessor;

@RequiredArgsConstructor
public class HyProxyCloudCommandPreprocessor<C> implements CommandPreprocessor<C> {
    private final HyProxyCloudCommandManager<C> manager;

    @Override
    public void accept(@org.jspecify.annotations.NonNull CommandPreprocessingContext<C> context) {
        context.commandContext().store(HyProxyCloudCommandManager.PROXY_KEY, this.manager.getProxy());
    }
}
