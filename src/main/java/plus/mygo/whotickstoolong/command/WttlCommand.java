package plus.mygo.whotickstoolong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.auto.AutoCapture;
import plus.mygo.whotickstoolong.auto.AutoDrillDown;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.HeatReport;
import plus.mygo.whotickstoolong.profile.HeatWindow;
import plus.mygo.whotickstoolong.profile.SamplerStats;
import plus.mygo.whotickstoolong.profile.deep.DeepProfiler;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;
import plus.mygo.whotickstoolong.profile.deep.SamplerFlavour;
import plus.mygo.whotickstoolong.report.ReportStore;
import plus.mygo.whotickstoolong.report.TextReport;

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
	private static final HeatWindow DEFAULT_WINDOW = HeatWindow.MEDIUM;

	private static final List<String> WINDOW_NAMES =
			Arrays.stream(HeatWindow.values()).map(HeatWindow::label).toList();

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
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(WINDOW_NAMES, builder))
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

	private static HeatWindow window(CommandContext<CommandSourceStack> context) {
		return HeatWindow.byName(StringArgumentType.getString(context, "window"));
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

		source.sendSuccess(() -> WttlOutput.header("Who Ticks Too Long"), false);

		// The number an operator needs before choosing an automatic drill-down threshold.
		double msptNow = source.getServer().getAverageTickTimeNanos() / 1e6;
		source.sendSuccess(() -> WttlOutput.field("server", Component.literal(String.format(Locale.ROOT,
				"%.2f ms/tick averaged over the last 100 ticks", msptNow))), false);

		if (stats == null) {
			source.sendSuccess(() -> WttlOutput.field("chunk heat",
					Component.literal("off").withStyle(ChatFormatting.GRAY)), false);
			source.sendSuccess(() -> Component.literal("  Nothing is instrumented and no memory is held.")
					.withStyle(ChatFormatting.DARK_GRAY), false);
		} else {
			source.sendSuccess(() -> WttlOutput.field("chunk heat", Component.literal(String.format(Locale.ROOT,
					"on - %d Hz requested, %.0f Hz achieved", stats.requestedRateHz(), stats.achievedRateHz()))
					.withStyle(ChatFormatting.GREEN)), false);
			source.sendSuccess(() -> WttlOutput.field("samples", Component.literal(String.format(Locale.ROOT,
					"%,d taken, %,d discarded (%.2f%%)",
					stats.samplesTaken(), stats.samplesDiscarded(), stats.discardRate() * 100.0))), false);
			source.sendSuccess(() -> WttlOutput.field("sample buffer", Component.literal(
					String.format(Locale.ROOT, "%,d KiB", stats.ringBytes() / 1024L))), false);
			source.sendSuccess(() -> WttlOutput.field("sampler thread", Component.literal(
					stats.samplerPercentOfOneCore() < 0.0
							? "CPU time unavailable on this JVM"
							: String.format(Locale.ROOT, "%.2f s CPU - %s of one core",
									stats.samplerCpuNanos() / 1e9,
									WttlOutput.formatPercent(stats.samplerPercentOfOneCore())))), false);
			source.sendSuccess(() -> WttlOutput.field("hot path", Component.literal(String.format(Locale.ROOT,
					"%,d objects instrumented - about %.1f us total, %s of server wall time (estimate)",
					stats.instrumentedObjects(), stats.estimatedHotPathNanos() / 1e3,
					WttlOutput.formatPercent(stats.hotPathPercentOfWall())))), false);
		}

		source.sendSuccess(() -> WttlOutput.field("deep inspection", Component.literal(
				DeepProfiler.get().isRunning() ? "running" : "idle")), false);

		AutoDrillDown auto = AutoDrillDown.get();
		source.sendSuccess(() -> WttlOutput.field("automatic drill-down", Component.literal(auto.isEnabled()
				? String.format(Locale.ROOT, "on - triggers above %.0f ms/tick with a chunk over %.0f%%; %d captured",
						auto.msptThresholdMs(), auto.shareThreshold() * 100.0, auto.captures().size())
				: "off").withStyle(auto.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY)), false);
		source.sendSuccess(() -> WttlOutput.field("reports",
				Component.literal(ReportStore.DIRECTORY_NAME + "/ in the game directory")), false);
		return 1;
	}

	private static int top(CommandContext<CommandSourceStack> context, int count, HeatWindow window, int page) {
		CommandSourceStack source = context.getSource();
		int offset = (page - 1) * count;
		HeatReport report = ChunkProfiler.get().report(window, offset + count);

		if (report == null) {
			source.sendFailure(Component.literal(
					"Chunk heat monitoring is off. Switch it on with /wttl chunk enable."));
			return 0;
		}
		if (report.totalSamples() == 0) {
			source.sendFailure(Component.literal("No samples in the last " + window.label() + " yet."));
			return 0;
		}

		List<HeatReport.ChunkHeat> chunks = report.chunks();
		if (chunks.isEmpty()) {
			source.sendFailure(Component.literal(String.format(Locale.ROOT,
					"No chunk work in the last %s - all %,d samples caught the server thread idle.",
					window.label(), report.totalSamples())));
			return 0;
		}
		if (offset >= chunks.size()) {
			source.sendFailure(Component.literal(
					"Page " + page + " is past the end; only " + chunks.size() + " chunks were sampled."));
			return 0;
		}

		List<HeatReport.ChunkHeat> pageChunks = chunks.subList(offset, Math.min(offset + count, chunks.size()));
		WttlOutput.sendHeat(source, report, pageChunks, offset + 1);
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
			flavour = DeepProfiler.get().start(dimensionId, chunkKey, duration, withMethods);
		} catch (IllegalStateException | UnsupportedOperationException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		}

		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"Inspecting chunk [%d, %d] in %s %s. Read it with /wttl object report.",
				chunkX, chunkZ, level.dimension().identifier(),
				duration == null ? "until stopped" : "for " + duration.toSeconds() + "s"))
				.withStyle(ChatFormatting.GREEN), true);

		if (flavour != null) {
			source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
					"  Sampling methods with %s. Read them with /wttl object methods.",
					flavour.displayName())).withStyle(ChatFormatting.DARK_GRAY), false);
		}
		return 1;
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
					"No object ticks recorded in chunk [%d, %d] yet - nothing there is ticking.",
					ChunkPos.getX(breakdown.chunkKey()), ChunkPos.getZ(breakdown.chunkKey()))));
			return 0;
		}

		WttlOutput.sendObjects(source, breakdown, DeepProfiler.get().isRunning() ? ", still running" : "");
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
					"No stack samples landed inside the chunk (%,d were taken on the server thread). "
							+ "Either the chunk is a very small slice of the tick, or sampling produced nothing.",
					breakdown.samplesOnThread())));
			return 0;
		}

		WttlOutput.sendMethods(source, breakdown);
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
		WttlOutput.sendCaptureList(context.getSource(), captures);
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
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"Captured automatically at %s, server averaging %.1f ms/tick",
				capture.formattedTime(), capture.msptAtTrigger())).withStyle(ChatFormatting.GRAY), false);
		WttlOutput.sendObjects(source, capture.objects(), "");
		if (capture.methods() != null) {
			WttlOutput.sendMethods(source, capture.methods());
		}
		return 1;
	}
}
