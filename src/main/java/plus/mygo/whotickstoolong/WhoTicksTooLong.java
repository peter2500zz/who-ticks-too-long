package plus.mygo.whotickstoolong;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Everything this mod does is opt-in at runtime: loading it costs
 * nothing beyond registering the command, and no profiling machinery starts
 * until an operator switches a granularity level on.
 */
public final class WhoTicksTooLong implements ModInitializer {
	public static final String MOD_ID = "whotickstoolong";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Who Ticks Too Long is loaded and idle; no tick instrumentation is active.");
	}
}
