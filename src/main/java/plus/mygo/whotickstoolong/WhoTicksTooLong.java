package plus.mygo.whotickstoolong;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import plus.mygo.whotickstoolong.command.WttlCommand;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.deep.DeepProfiler;

/**
 * Entry point. Everything this mod does is opt-in at runtime: loading it registers a command
 * and two lifecycle listeners, and no profiling machinery starts, allocates, or instruments
 * anything until an operator switches a level of monitoring on.
 */
public final class WhoTicksTooLong implements ModInitializer {
	public static final String MOD_ID = "whotickstoolong";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> WttlCommand.register(dispatcher));

		// Publishes which level is about to tick — once per level per tick, not per object —
		// and drives deep-inspection expiry, which must happen on the server thread.
		ServerTickEvents.START_LEVEL_TICK.register(level -> {
			ChunkProfiler chunks = ChunkProfiler.get();
			DeepProfiler deep = DeepProfiler.get();
			if (!chunks.isEnabled() && !deep.isRunning()) {
				return;
			}
			int dimensionId = chunks.dimensions().idOf(level.dimension());
			chunks.onLevelTickStart(dimensionId);
			deep.onLevelTickStart(dimensionId);
		});

		// A reloading integrated server must never leave a sampler thread or recording behind.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			DeepProfiler.get().shutdown();
			ChunkProfiler.get().shutdown();
		});

		LOGGER.info("Who Ticks Too Long is loaded and idle; no tick instrumentation is active.");
	}
}
