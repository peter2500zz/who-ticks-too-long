package plus.mygo.whotickstoolong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.WhoTicksTooLong;
import plus.mygo.whotickstoolong.auto.AutoCapture;
import plus.mygo.whotickstoolong.auto.AutoDrillDown;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.HeatReport;
import plus.mygo.whotickstoolong.profile.SamplerStats;
import plus.mygo.whotickstoolong.profile.deep.DeepCompletion;
import plus.mygo.whotickstoolong.profile.deep.DeepProfiler;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;
import plus.mygo.whotickstoolong.profile.deep.SamplerFlavour;
import plus.mygo.whotickstoolong.report.ReportStore;
import plus.mygo.whotickstoolong.report.TextReport;
import plus.mygo.whotickstoolong.util.DurationSyntax;

/**
 * The {@code /wttl} command tree: switch a level of monitoring on or off, ask what the
 * hottest chunks are, drill into one, and ask what the profiler itself is costing.
 *
 * <p>Everything is read-only with respect to the world. No command here loads a chunk,
 * touches an entity, or writes anything to a save; reports go to their own folder beside the
 * game's logs.
 */
public final class WttlCommand {

	private static final int DEFAULT_COUNT = 10;
	private static final int MAX_COUNT = 50;
	private static final int MAX_SAVED_ROWS = 100;
	private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

	private static final DynamicCommandExceptionType ERROR_BAD_WINDOW =
			new DynamicCommandExceptionType(reason -> Component.literal(String.valueOf(reason)));

	private WttlCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("wttl")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("status").executes(WttlCommand::status))
				.then(chunkBranch())
				.then(topBranch())
				.then(objectBranch())
				.then(autoBranch()));
	}

	// ------------------------------------------------------------------ branches

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> chunkBranch() {
		return Commands.literal("chunk")
				.then(Commands.literal("enable").executes(context -> setChunkMonitoring(context, true)))
				.then(Commands.literal("disable").executes(context -> setChunkMonitoring(context, false)));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> topBranch() {
		return Commands.literal("top")
				.executes(context -> top(context, DEFAULT_COUNT, DEFAULT_WINDOW, 1))
				.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
						.executes(context -> top(context, count(context), DEFAULT_WINDOW, 1))
						.then(Commands.argument("window", StringArgumentType.word())
								.suggests(WttlCommand::suggestWindows)
								.executes(context -> top(context, count(context), window(context), 1))
								.then(Commands.argument("page", IntegerArgumentType.integer(1))
										.executes(context -> top(context, count(context), window(context),
												IntegerArgumentType.getInteger(context, "page"))))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> objectBranch() {
		return Commands.literal("object")
				.then(Commands.literal("enable")
						.then(Commands.argument("chunkX", IntegerArgumentType.integer())
								.then(Commands.argument("chunkZ", IntegerArgumentType.integer())
										.executes(context -> startDeep(context, DeepProfiler.DEFAULT_DURATION, null, false))
										.then(Commands.literal("until-stopped")
												.executes(context -> startDeep(context, null, null, false))
												.then(Commands.literal("methods")
														.executes(context -> startDeep(context, null, null, true))))
										.then(Commands.argument("seconds",
														IntegerArgumentType.integer(1, DeepProfiler.MAX_DURATION_SECONDS))
												.executes(context -> startDeep(context, seconds(context), null, false))
												.then(Commands.literal("methods")
														.executes(context -> startDeep(context, seconds(context), null, true)))
												.then(Commands.argument("dimension", DimensionArgument.dimension())
														.executes(context -> startDeep(context, seconds(context), dimension(context), false))
														.then(Commands.literal("methods")
																.executes(context -> startDeep(context, seconds(context),
																		dimension(context), true))))))))
				.then(Commands.literal("disable").executes(WttlCommand::stopDeep))
				.then(Commands.literal("report")
						.executes(context -> deepReport(context, DEFAULT_COUNT))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
								.executes(context -> deepReport(context, count(context)))))
				.then(Commands.literal("methods")
						.executes(context -> methodReport(context, DEFAULT_COUNT))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
								.executes(context -> methodReport(context, count(context)))))
				.then(Commands.literal("save").executes(WttlCommand::saveReport));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> autoBranch() {
		return Commands.literal("auto")
				.then(Commands.literal("enable")
						.executes(context -> enableAuto(context,
								AutoDrillDown.DEFAULT_MSPT_THRESHOLD_MS, AutoDrillDown.DEFAULT_SHARE_THRESHOLD))
						.then(Commands.argument("mspt", IntegerArgumentType.integer(1, 10_000))
								.executes(context -> enableAuto(context, mspt(context),
										AutoDrillDown.DEFAULT_SHARE_THRESHOLD))
								.then(Commands.argument("sharePercent", IntegerArgumentType.integer(1, 100))
										.executes(context -> enableAuto(context, mspt(context),
												IntegerArgumentType.getInteger(context, "sharePercent") / 100.0)))))
				.then(Commands.literal("disable").executes(WttlCommand::disableAuto))
				.then(Commands.literal("list").executes(WttlCommand::listCaptures))
				.then(Commands.literal("show")
						.then(Commands.argument("index", IntegerArgumentType.integer(1))
								.executes(WttlCommand::showCapture)));
	}

	// ----------------------------------------------------------------- arguments

	private static int count(CommandContext<CommandSourceStack> context) {
		return IntegerArgumentType.getInteger(context, "count");
	}

	/**
	 * Accepts vanilla {@code /time} syntax extended with minutes and hours: 200, 200t, 30s,
	 * 5m, 1h. A bare number means ticks, as it does in vanilla.
	 */
	private static Duration window(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		String raw = StringArgumentType.getString(context, "window");
		try {
			return DurationSyntax.parse(raw);
		} catch (IllegalArgumentException e) {
			throw ERROR_BAD_WINDOW.create(e.getMessage());
		}
	}

	/** Mirrors vanilla's {@code /time}: once a number is typed, offer the units after it. */
	private static CompletableFuture<Suggestions> suggestWindows(
			CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
		int typedNumber = DurationSyntax.leadingNumberLength(builder.getRemaining());
		if (typedNumber > 0) {
			return SharedSuggestionProvider.suggest(DurationSyntax.UNITS,
					builder.createOffset(builder.getStart() + typedNumber));
		}
		return SharedSuggestionProvider.suggest(DurationSyntax.COMMON_WINDOWS, builder);
	}

	private static Duration seconds(CommandContext<CommandSourceStack> context) {
		return Duration.ofSeconds(IntegerArgumentType.getInteger(context, "seconds"));
	}

	private static double mspt(CommandContext<CommandSourceStack> context) {
		return IntegerArgumentType.getInteger(context, "mspt");
	}

	private static ServerLevel dimension(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		return DimensionArgument.getDimension(context, "dimension");
	}

	// ------------------------------------------------------------ chunk monitoring

	private static int setChunkMonitoring(CommandContext<CommandSourceStack> context, boolean enable) {
		ChunkProfiler profiler = ChunkProfiler.get();
		boolean changed = enable ? profiler.enable() : profiler.disable();

		if (!changed) {
			context.getSource().sendFailure(Component.literal(
					"Chunk heat monitoring is already " + (enable ? "on" : "off") + "."));
			return 0;
		}

		context.getSource().sendSuccess(() -> Component.literal(
				enable
						? "Chunk heat monitoring on. Ranking becomes meaningful after a few seconds of samples."
						: "Chunk heat monitoring off. Sample buffer released.")
				.withStyle(enable ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		SamplerStats stats = ChunkProfiler.get().stats();

		ChatReport out = new ChatReport();
		out.line(WttlOutput.header("Who Ticks Too Long"));

		// The number an operator needs before choosing an automatic drill-down threshold.
		double msptNow = source.getServer().getAverageTickTimeNanos() / 1e6;
		out.line(WttlOutput.field("server", Component.literal(String.format(Locale.ROOT,
				"%.2f ms/tick averaged over the last 100 ticks", msptNow))));

		if (stats == null) {
			out.line(WttlOutput.field("chunk heat",
					Component.literal("off").withStyle(ChatFormatting.GRAY)));
			out.line(Component.literal("  Nothing is instrumented and no memory is held.")
					.withStyle(ChatFormatting.DARK_GRAY));
		} else {
			out.line(WttlOutput.field("chunk heat", Component.literal(String.format(Locale.ROOT,
					"on - %d Hz requested, %.0f Hz achieved", stats.requestedRateHz(), stats.achievedRateHz()))
					.withStyle(ChatFormatting.GREEN)));
			out.line(WttlOutput.detail(String.format(Locale.ROOT, "%,d samples · %,d discarded · %s",
					stats.samplesTaken(), stats.samplesDiscarded(),
					WttlOutput.formatPercent(stats.discardRate() * 100.0))));
			out.line(WttlOutput.detail(String.format(Locale.ROOT, "buffer %,d KiB",
					stats.ringBytes() / 1024L)));
			out.line(WttlOutput.field("sampler thread", stats.samplerPercentOfOneCore() < 0.0
					? "CPU time unavailable on this JVM"
					: String.format(Locale.ROOT, "%.2fs CPU · %s of one core",
							stats.samplerCpuNanos() / 1e9,
							WttlOutput.formatPercent(stats.samplerPercentOfOneCore()))));
			out.line(WttlOutput.field("hot path", Component.literal(
					WttlOutput.formatPercent(stats.hotPathPercentOfWall()) + " of wall time")
					.withStyle(ChatFormatting.GREEN)));
			out.line(WttlOutput.detail(String.format(Locale.ROOT,
					"%,d objects instrumented · about %.1fus total · estimated",
					stats.instrumentedObjects(), stats.estimatedHotPathNanos() / 1e3)));
		}

		out.line(WttlOutput.field("deep inspection", Component.literal(
				DeepProfiler.get().isRunning() ? "running" : "idle")));

		AutoDrillDown auto = AutoDrillDown.get();
		out.line(WttlOutput.field("automatic drill-down", Component.literal(auto.isEnabled()
				? String.format(Locale.ROOT, "on - triggers above %.0f ms/tick with a chunk over %.0f%%; %d captured",
						auto.msptThresholdMs(), auto.shareThreshold() * 100.0, auto.captures().size())
				: "off").withStyle(auto.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
		out.line(WttlOutput.field("reports",
				Component.literal(ReportStore.DIRECTORY_NAME + "/ in the game directory")));

		out.send(source);
		return 1;
	}

	private static int top(CommandContext<CommandSourceStack> context, int count, Duration window, int page) {
		CommandSourceStack source = context.getSource();
		int offset = (page - 1) * count;
		HeatReport report = ChunkProfiler.get().report(window, offset + count);
		String label = DurationSyntax.format(window);

		if (report == null) {
			source.sendFailure(Component.literal(
					"Chunk heat monitoring is off. Switch it on with /wttl chunk enable."));
			return 0;
		}
		if (report.totalSamples() == 0) {
			source.sendFailure(Component.literal("No samples in the last " + label + " yet."));
			return 0;
		}

		List<HeatReport.ChunkHeat> chunks = report.chunks();
		if (chunks.isEmpty()) {
			source.sendFailure(Component.literal(String.format(Locale.ROOT,
					"No chunk work in the last %s - all %,d samples caught the server thread idle.",
					label, report.totalSamples())));
			return 0;
		}
		if (offset >= chunks.size()) {
			source.sendFailure(Component.literal(
					"Page " + page + " is past the end; only " + chunks.size() + " chunks were sampled."));
			return 0;
		}

		List<HeatReport.ChunkHeat> pageChunks = chunks.subList(offset, Math.min(offset + count, chunks.size()));
		ChatReport out = new ChatReport();
		WttlOutput.appendHeat(out, report, pageChunks, offset + 1);
		out.send(source);
		return pageChunks.size();
	}

	// -------------------------------------------------------------- deep inspection

	private static int startDeep(CommandContext<CommandSourceStack> context,
			@Nullable Duration duration, @Nullable ServerLevel explicitLevel, boolean withMethods) {
		CommandSourceStack source = context.getSource();
		ServerLevel level = explicitLevel != null ? explicitLevel : source.getLevel();
		int chunkX = IntegerArgumentType.getInteger(context, "chunkX");
		int chunkZ = IntegerArgumentType.getInteger(context, "chunkZ");
		long chunkKey = ChunkPos.pack(chunkX, chunkZ);
		int dimensionId = ChunkProfiler.get().dimensions().idOf(level.dimension());

		SamplerFlavour flavour;
		try {
			flavour = DeepProfiler.get().start(dimensionId, chunkKey, duration, withMethods,
					reportBackTo(source));
		} catch (IllegalStateException | UnsupportedOperationException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}

		ChatReport out = new ChatReport();
		out.line(Component.literal(String.format(Locale.ROOT,
				"Inspecting chunk [%d, %d] %s", chunkX, chunkZ,
				duration == null ? "until stopped" : "for " + duration.toSeconds() + "s"))
				.withStyle(ChatFormatting.GREEN));
		out.line(WttlOutput.detail(String.format(Locale.ROOT, "blocks %d,%d to %d,%d in %s",
				SectionPos.sectionToBlockCoord(chunkX), SectionPos.sectionToBlockCoord(chunkZ),
				SectionPos.sectionToBlockCoord(chunkX + 1) - 1, SectionPos.sectionToBlockCoord(chunkZ + 1) - 1,
				level.dimension().identifier())));
		if (flavour != null) {
			out.line(WttlOutput.detail("sampling methods with " + flavour.displayName()));
		}
		out.line(WttlOutput.detail("read it with /wttl object report"
				+ (flavour == null ? "" : " and /wttl object methods")));
		out.broadcast(source);
		return 1;
	}

	/**
	 * Sends the results back to whoever started the inspection, once it ends.
	 *
	 * <p>A timed inspection outlives the command that began it, and the player may well have
	 * left in the meantime, so the target is resolved by id at the moment it finishes rather
	 * than held onto. Holding the original command source would also keep a level and an
	 * entity reachable for the whole run, which a profiler has no business doing.
	 */
	private static DeepCompletion reportBackTo(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		ServerPlayer starter = source.getPlayer();
		UUID starterId = starter == null ? null : starter.getUUID();

		return (objects, methods) -> {
			CommandSourceStack target = resolveReportTarget(server, starterId);
			if (target == null) {
				WhoTicksTooLong.LOGGER.info(
						"Inspection finished but whoever started it has left; it is still readable "
								+ "with /wttl object report.");
				return;
			}

			ChatReport out = new ChatReport();
			out.line(Component.literal("Inspection finished.").withStyle(ChatFormatting.GREEN));
			WttlOutput.appendObjects(out, objects, "");
			if (methods != null) {
				WttlOutput.appendMethods(out, methods);
			}
			out.send(target);
		};
	}

	/** @return null when the player who started the inspection is no longer online */
	private static @Nullable CommandSourceStack resolveReportTarget(
			MinecraftServer server, @Nullable UUID starterId) {
		if (starterId == null) {
			return server.createCommandSourceStack();
		}
		ServerPlayer player = server.getPlayerList().getPlayer(starterId);
		return player == null ? null : player.createCommandSourceStack();
	}

	private static int stopDeep(CommandContext<CommandSourceStack> context) {
		ObjectBreakdown breakdown = DeepProfiler.get().stop();
		if (breakdown == null) {
			context.getSource().sendFailure(Component.literal("No inspection is running."));
			return 0;
		}
		context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"Inspection stopped after %,d object ticks. Read it with /wttl object report.",
				breakdown.observedEvents())).withStyle(ChatFormatting.YELLOW), true);
		return 1;
	}

	private static int deepReport(CommandContext<CommandSourceStack> context, int count) {
		CommandSourceStack source = context.getSource();
		ObjectBreakdown breakdown = DeepProfiler.get().breakdown(count, count);

		if (breakdown == null) {
			source.sendFailure(Component.literal(
					"Nothing to report. Start one with /wttl object enable <chunkX> <chunkZ>."));
			return 0;
		}
		if (breakdown.observedEvents() == 0L) {
			source.sendFailure(Component.literal(String.format(Locale.ROOT,
					"No object ticks recorded in chunk [%d, %d] at blocks %d,%d yet. Nothing there is ticking.",
					ChunkPos.getX(breakdown.chunkKey()), ChunkPos.getZ(breakdown.chunkKey()),
					SectionPos.sectionToBlockCoord(ChunkPos.getX(breakdown.chunkKey())),
					SectionPos.sectionToBlockCoord(ChunkPos.getZ(breakdown.chunkKey())))));
			return 0;
		}

		ChatReport out = new ChatReport();
		WttlOutput.appendObjects(out, breakdown, DeepProfiler.get().isRunning() ? ", still running" : "");
		out.send(source);
		return 1;
	}

	private static int methodReport(CommandContext<CommandSourceStack> context, int count) {
		CommandSourceStack source = context.getSource();
		MethodBreakdown breakdown = DeepProfiler.get().methodBreakdown(count);

		if (breakdown == null) {
			source.sendFailure(Component.literal("No method samples. Method sampling needs the object "
					+ "tick windows, so start it with /wttl object enable <x> <z> <seconds> methods."));
			return 0;
		}
		if (breakdown.samplesInChunk() == 0) {
			source.sendFailure(Component.literal(String.format(Locale.ROOT,
					"No stack samples landed inside the chunk. %,d were taken on the server thread. "
							+ "Either the chunk is a very small slice of the tick, or sampling produced nothing.",
					breakdown.samplesOnThread())));
			return 0;
		}

		ChatReport out = new ChatReport();
		WttlOutput.appendMethods(out, breakdown);
		out.send(source);
		return 1;
	}

	private static int saveReport(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ObjectBreakdown objects = DeepProfiler.get().breakdown(MAX_SAVED_ROWS, MAX_SAVED_ROWS);
		if (objects == null) {
			source.sendFailure(Component.literal("Nothing to save; no inspection has run."));
			return 0;
		}

		MethodBreakdown methods = DeepProfiler.get().methodBreakdown(MAX_SAVED_ROWS);
		String dimensionName = ChunkProfiler.get().dimensions().nameOf(objects.dimensionId());
		Path file = ReportStore.writeAsync(
				TextReport.render("Manual inspection", dimensionName, objects, methods),
				"manual", objects.dimensionId(), objects.chunkKey());

		source.sendSuccess(() -> Component.literal("Writing report to " + ReportStore.DIRECTORY_NAME
				+ "/" + file.getFileName()).withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	// ------------------------------------------------------------------ automatic

	private static int enableAuto(CommandContext<CommandSourceStack> context, double msptMs, double share) {
		AutoDrillDown.get().enable(msptMs, share);
		CommandSourceStack source = context.getSource();

		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"Automatic drill-down on: captures when the server averages over %.0f ms/tick "
						+ "and one chunk holds over %.0f%% of chunk tick time.", msptMs, share * 100.0))
				.withStyle(ChatFormatting.GREEN), true);

		if (!ChunkProfiler.get().isEnabled()) {
			source.sendSuccess(() -> Component.literal(
					"  It has nothing to watch until chunk heat monitoring is on: /wttl chunk enable")
					.withStyle(ChatFormatting.YELLOW), false);
		}
		return 1;
	}

	private static int disableAuto(CommandContext<CommandSourceStack> context) {
		AutoDrillDown.get().disable();
		context.getSource().sendSuccess(() -> Component.literal("Automatic drill-down off.")
				.withStyle(ChatFormatting.YELLOW), true);
		return 1;
	}

	private static int listCaptures(CommandContext<CommandSourceStack> context) {
		List<AutoCapture> captures = AutoDrillDown.get().captures();
		if (captures.isEmpty()) {
			context.getSource().sendFailure(Component.literal(
					"Nothing captured yet. Reports also land in " + ReportStore.DIRECTORY_NAME + "/."));
			return 0;
		}
		ChatReport out = new ChatReport();
		WttlOutput.appendCaptureList(out, captures);
		out.send(context.getSource());
		return captures.size();
	}

	private static int showCapture(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<AutoCapture> captures = AutoDrillDown.get().captures();
		int index = IntegerArgumentType.getInteger(context, "index");

		if (index > captures.size()) {
			source.sendFailure(Component.literal(
					"There are only " + captures.size() + " captures. List them with /wttl auto list."));
			return 0;
		}

		AutoCapture capture = captures.get(index - 1);
		ChatReport out = new ChatReport();
		out.line(Component.literal(String.format(Locale.ROOT,
				"Captured automatically at %s · server averaging %.1f ms/tick",
				capture.formattedTime(), capture.msptAtTrigger())).withStyle(ChatFormatting.GRAY));
		WttlOutput.appendObjects(out, capture.objects(), "");
		if (capture.methods() != null) {
			WttlOutput.appendMethods(out, capture.methods());
		}
		out.send(source);
		return 1;
	}
}
