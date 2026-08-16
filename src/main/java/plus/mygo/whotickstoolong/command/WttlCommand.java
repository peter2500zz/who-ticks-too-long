package plus.mygo.whotickstoolong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
import plus.mygo.whotickstoolong.i18n.Messages;
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
			new DynamicCommandExceptionType(reason -> (Component) reason);

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

	private static LiteralArgumentBuilder<CommandSourceStack> chunkBranch() {
		return Commands.literal("chunk")
				.then(Commands.literal("enable").executes(context -> setChunkMonitoring(context, true)))
				.then(Commands.literal("disable").executes(context -> setChunkMonitoring(context, false)));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> topBranch() {
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

	private static LiteralArgumentBuilder<CommandSourceStack> objectBranch() {
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

	private static LiteralArgumentBuilder<CommandSourceStack> autoBranch() {
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

	/**
	 * Accepts vanilla {@code /time} syntax extended with minutes and hours: 200, 200t, 30s,
	 * 5m, 1h. A bare number means ticks, as it does in vanilla.
	 */
	private static Duration window(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		String raw = StringArgumentType.getString(context, "window");
		try {
			return DurationSyntax.parse(raw);
		} catch (DurationSyntax.InvalidDuration e) {
			throw ERROR_BAD_WINDOW.create(Messages.of(context.getSource(), e.key(), e.args()));
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

	private static void fail(CommandSourceStack source, String key, Object... args) {
		source.sendFailure(Messages.of(source, key, args));
	}

	private static String percent(double fraction) {
		return WttlOutput.formatPercent(fraction * 100.0);
	}

	// ------------------------------------------------------------ chunk monitoring

	private static int setChunkMonitoring(CommandContext<CommandSourceStack> context, boolean enable) {
		CommandSourceStack source = context.getSource();
		ChunkProfiler profiler = ChunkProfiler.get();
		boolean changed = enable ? profiler.enable() : profiler.disable();

		ChatReport out = new ChatReport(source);
		if (!changed) {
			fail(source, "wttl.heat.already",
					Messages.of(source, enable ? "wttl.status.heat.on" : "wttl.status.heat.off"));
			return 0;
		}

		out.styled(enable ? ChatFormatting.GREEN : ChatFormatting.YELLOW,
				enable ? "wttl.heat.enabled" : "wttl.heat.disabled");
		out.broadcast();
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		SamplerStats stats = ChunkProfiler.get().stats();
		ChatReport out = new ChatReport(source);

		out.raw(WttlOutput.header(out.text("wttl.title")));

		// The number an operator needs before choosing an automatic capture threshold.
		double msptNow = source.getServer().getAverageTickTimeNanos() / 1e6;
		out.raw(WttlOutput.field(out.text("wttl.status.server"),
				out.text("wttl.status.server.value", String.format(Locale.ROOT, "%.2f", msptNow))
						.withStyle(ChatFormatting.WHITE)));

		if (stats == null) {
			out.raw(WttlOutput.field(out.text("wttl.status.heat"),
					out.text("wttl.status.heat.off").withStyle(ChatFormatting.GRAY)));
			out.raw(WttlOutput.detail(out.text("wttl.status.heat.idle")));
		} else {
			out.raw(WttlOutput.field(out.text("wttl.status.heat"),
					out.text("wttl.status.heat.on", String.valueOf(stats.requestedRateHz()),
							String.format(Locale.ROOT, "%.0f", stats.achievedRateHz()))
							.withStyle(ChatFormatting.GREEN)));
			out.raw(WttlOutput.detail(out.text("wttl.status.samples",
					String.format(Locale.ROOT, "%,d", stats.samplesTaken()),
					String.format(Locale.ROOT, "%,d", stats.samplesDiscarded()),
					percent(stats.discardRate()))));
			out.raw(WttlOutput.detail(out.text("wttl.status.buffer",
					String.format(Locale.ROOT, "%,d", stats.ringBytes() / 1024L))));
			out.raw(WttlOutput.field(out.text("wttl.status.sampler"),
					stats.samplerPercentOfOneCore() < 0.0
							? out.text("wttl.status.sampler.unavailable")
							: out.text("wttl.status.sampler.value",
									String.format(Locale.ROOT, "%.2f", stats.samplerCpuNanos() / 1e9),
									WttlOutput.formatPercent(stats.samplerPercentOfOneCore()))));
			out.raw(WttlOutput.field(out.text("wttl.status.hot_path"),
					out.text("wttl.status.hot_path.value",
							WttlOutput.formatPercent(stats.hotPathPercentOfWall()))
							.withStyle(ChatFormatting.GREEN)));
			out.raw(WttlOutput.detail(out.text("wttl.status.hot_path.detail",
					String.format(Locale.ROOT, "%,d", stats.instrumentedObjects()),
					String.format(Locale.ROOT, "%.1fus", stats.estimatedHotPathNanos() / 1e3))));
		}

		out.raw(WttlOutput.field(out.text("wttl.status.deep"), out.text(
				DeepProfiler.get().isRunning() ? "wttl.status.deep.running" : "wttl.status.deep.idle")));

		AutoDrillDown auto = AutoDrillDown.get();
		out.raw(WttlOutput.field(out.text("wttl.status.auto"), auto.isEnabled()
				? out.text("wttl.status.auto.on",
						String.format(Locale.ROOT, "%.0f", auto.msptThresholdMs()),
						percent(auto.shareThreshold()),
						String.valueOf(auto.captures().size())).withStyle(ChatFormatting.GREEN)
				: out.text("wttl.status.auto.off").withStyle(ChatFormatting.GRAY)));
		out.raw(WttlOutput.field(out.text("wttl.status.reports"),
				out.text("wttl.status.reports.value", ReportStore.DIRECTORY_NAME)));

		out.send();
		return 1;
	}

	private static int top(CommandContext<CommandSourceStack> context, int count, Duration window, int page) {
		CommandSourceStack source = context.getSource();
		int offset = (page - 1) * count;
		HeatReport report = ChunkProfiler.get().report(window, offset + count);
		String label = DurationSyntax.format(window);

		if (report == null) {
			fail(source, "wttl.heat.off");
			return 0;
		}
		if (report.totalSamples() == 0) {
			fail(source, "wttl.heat.no_samples", label);
			return 0;
		}

		List<HeatReport.ChunkHeat> chunks = report.chunks();
		if (chunks.isEmpty()) {
			fail(source, "wttl.heat.all_idle", label, String.format(Locale.ROOT, "%,d", report.totalSamples()));
			return 0;
		}
		if (offset >= chunks.size()) {
			fail(source, "wttl.heat.page_past_end", String.valueOf(page), String.valueOf(chunks.size()));
			return 0;
		}

		List<HeatReport.ChunkHeat> pageChunks = chunks.subList(offset, Math.min(offset + count, chunks.size()));
		ChatReport out = new ChatReport(source);
		WttlOutput.appendHeat(out, report, pageChunks, offset + 1);
		out.send();
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
			source.sendFailure(Component.literal(String.valueOf(e.getMessage())));
			return 0;
		}

		ChatReport out = new ChatReport(source);
		out.raw(out.text("wttl.object.started", chunkX, chunkZ,
				duration == null
						? out.text("wttl.object.started.until_stopped")
						: out.text("wttl.object.started.for", String.valueOf(duration.toSeconds())))
				.withStyle(ChatFormatting.GREEN));
		out.raw(WttlOutput.detail(out.text("wttl.object.started.location",
				SectionPos.sectionToBlockCoord(chunkX), SectionPos.sectionToBlockCoord(chunkZ),
				SectionPos.sectionToBlockCoord(chunkX + 1) - 1, SectionPos.sectionToBlockCoord(chunkZ + 1) - 1,
				level.dimension().identifier().toString())));
		if (flavour != null) {
			out.raw(WttlOutput.detail(out.text("wttl.object.started.sampling",
					out.text(flavour.translationKey()))));
		}
		out.raw(WttlOutput.detail(out.text(flavour == null
				? "wttl.object.started.hint"
				: "wttl.object.started.hint_methods")));
		out.broadcast();
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
				WhoTicksTooLong.LOGGER.info(Messages.plain("wttl.auto.offline"));
				return;
			}

			ChatReport out = new ChatReport(target);
			out.styled(ChatFormatting.GREEN, "wttl.object.finished");
			WttlOutput.appendObjects(out, objects, false);
			if (methods != null) {
				WttlOutput.appendMethods(out, methods);
			}
			out.send();
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
		CommandSourceStack source = context.getSource();
		ObjectBreakdown breakdown = DeepProfiler.get().stop();
		if (breakdown == null) {
			fail(source, "wttl.object.none_running");
			return 0;
		}
		new ChatReport(source).styled(ChatFormatting.YELLOW, "wttl.object.stopped",
				String.format(Locale.ROOT, "%,d", breakdown.observedEvents())).broadcast();
		return 1;
	}

	private static int deepReport(CommandContext<CommandSourceStack> context, int count) {
		CommandSourceStack source = context.getSource();
		ObjectBreakdown breakdown = DeepProfiler.get().breakdown(count, count);

		if (breakdown == null) {
			fail(source, "wttl.object.nothing_yet");
			return 0;
		}
		if (breakdown.observedEvents() == 0L) {
			int chunkX = ChunkPos.getX(breakdown.chunkKey());
			int chunkZ = ChunkPos.getZ(breakdown.chunkKey());
			fail(source, "wttl.object.no_ticks", chunkX, chunkZ,
					SectionPos.sectionToBlockCoord(chunkX), SectionPos.sectionToBlockCoord(chunkZ));
			return 0;
		}

		ChatReport out = new ChatReport(source);
		WttlOutput.appendObjects(out, breakdown, DeepProfiler.get().isRunning());
		out.send();
		return 1;
	}

	private static int methodReport(CommandContext<CommandSourceStack> context, int count) {
		CommandSourceStack source = context.getSource();
		MethodBreakdown breakdown = DeepProfiler.get().methodBreakdown(count);

		if (breakdown == null) {
			fail(source, "wttl.method.not_sampled");
			return 0;
		}
		if (breakdown.samplesInChunk() == 0) {
			fail(source, "wttl.method.none_inside",
					String.format(Locale.ROOT, "%,d", breakdown.samplesOnThread()));
			return 0;
		}

		ChatReport out = new ChatReport(source);
		WttlOutput.appendMethods(out, breakdown);
		out.send();
		return 1;
	}

	private static int saveReport(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ObjectBreakdown objects = DeepProfiler.get().breakdown(MAX_SAVED_ROWS, MAX_SAVED_ROWS);
		if (objects == null) {
			fail(source, "wttl.object.nothing_saved");
			return 0;
		}

		MethodBreakdown methods = DeepProfiler.get().methodBreakdown(MAX_SAVED_ROWS);
		Path file = ReportStore.writeAsync(
				TextReport.render(Messages.plain("wttl.report.file_heading.manual"), objects, methods),
				"manual", objects.dimensionId(), objects.chunkKey());

		new ChatReport(source).styled(ChatFormatting.GREEN, "wttl.object.saving",
				ReportStore.DIRECTORY_NAME + "/" + file.getFileName()).broadcast();
		return 1;
	}

	// ------------------------------------------------------------------ automatic

	private static int enableAuto(CommandContext<CommandSourceStack> context, double msptMs, double share) {
		CommandSourceStack source = context.getSource();
		AutoDrillDown.get().enable(msptMs, share);

		ChatReport out = new ChatReport(source);
		out.styled(ChatFormatting.GREEN, "wttl.auto.enabled",
				String.format(Locale.ROOT, "%.0f", msptMs), percent(share));
		if (!ChunkProfiler.get().isEnabled()) {
			out.raw(Component.literal("   ").append(out.text("wttl.auto.needs_heat"))
					.withStyle(ChatFormatting.YELLOW));
		}
		out.broadcast();
		return 1;
	}

	private static int disableAuto(CommandContext<CommandSourceStack> context) {
		AutoDrillDown.get().disable();
		new ChatReport(context.getSource())
				.styled(ChatFormatting.YELLOW, "wttl.auto.disabled").broadcast();
		return 1;
	}

	private static int listCaptures(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<AutoCapture> captures = AutoDrillDown.get().captures();
		if (captures.isEmpty()) {
			fail(source, "wttl.auto.empty", ReportStore.DIRECTORY_NAME);
			return 0;
		}

		ChatReport out = new ChatReport(source);
		WttlOutput.appendCaptureList(out, captures);
		out.send();
		return captures.size();
	}

	private static int showCapture(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<AutoCapture> captures = AutoDrillDown.get().captures();
		int index = IntegerArgumentType.getInteger(context, "index");

		if (index > captures.size()) {
			fail(source, "wttl.auto.out_of_range", String.valueOf(captures.size()));
			return 0;
		}

		AutoCapture capture = captures.get(index - 1);
		ChatReport out = new ChatReport(source);
		out.styled(ChatFormatting.GRAY, "wttl.auto.show_header", capture.formattedTime(),
				String.format(Locale.ROOT, "%.1f", capture.msptAtTrigger()));
		WttlOutput.appendObjects(out, capture.objects(), false);
		if (capture.methods() != null) {
			WttlOutput.appendMethods(out, capture.methods());
		}
		out.send();
		return 1;
	}
}
