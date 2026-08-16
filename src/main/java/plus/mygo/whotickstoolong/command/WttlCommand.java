package plus.mygo.whotickstoolong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.HeatReport;
import plus.mygo.whotickstoolong.profile.HeatWindow;
import plus.mygo.whotickstoolong.profile.SamplerStats;
import plus.mygo.whotickstoolong.profile.deep.DeepProfiler;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;
import plus.mygo.whotickstoolong.profile.deep.SamplerFlavour;

/**
 * The {@code /wttl} command tree: switch a level of monitoring on or off, ask what the
 * hottest chunks are, and ask what the profiler itself is costing.
 *
 * <p>Everything is read-only with respect to the world. No command here loads a chunk,
 * touches an entity, or writes anything to the save.
 */
public final class WttlCommand {

	private static final int DEFAULT_COUNT = 10;
	private static final int MAX_COUNT = 50;
	private static final HeatWindow DEFAULT_WINDOW = HeatWindow.MEDIUM;

	private static final List<String> WINDOW_NAMES =
			Arrays.stream(HeatWindow.values()).map(HeatWindow::label).toList();

	private WttlCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("wttl")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("status")
						.executes(WttlCommand::status))
				.then(Commands.literal("chunk")
						.then(Commands.literal("enable").executes(context -> setChunkMonitoring(context, true)))
						.then(Commands.literal("disable").executes(context -> setChunkMonitoring(context, false))))
				.then(Commands.literal("object")
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
						.then(Commands.literal("disable")
								.executes(WttlCommand::stopDeep))
						.then(Commands.literal("report")
								.executes(context -> deepReport(context, DEFAULT_COUNT))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
										.executes(context -> deepReport(context, count(context)))))
						.then(Commands.literal("methods")
								.executes(context -> methodReport(context, DEFAULT_COUNT))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
										.executes(context -> methodReport(context, count(context))))))
				.then(Commands.literal("top")
						.executes(context -> top(context, DEFAULT_COUNT, DEFAULT_WINDOW, 1))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_COUNT))
								.executes(context -> top(context, count(context), DEFAULT_WINDOW, 1))
								.then(Commands.argument("window", StringArgumentType.word())
										.suggests((context, builder) -> SharedSuggestionProvider.suggest(WINDOW_NAMES, builder))
										.executes(context -> top(context, count(context), window(context), 1))
										.then(Commands.argument("page", IntegerArgumentType.integer(1))
												.executes(context -> top(context, count(context), window(context),
														IntegerArgumentType.getInteger(context, "page"))))))));
	}

	private static int count(CommandContext<CommandSourceStack> context) {
		return IntegerArgumentType.getInteger(context, "count");
	}

	private static HeatWindow window(CommandContext<CommandSourceStack> context) {
		return HeatWindow.byName(StringArgumentType.getString(context, "window"));
	}

	private static Duration seconds(CommandContext<CommandSourceStack> context) {
		return Duration.ofSeconds(IntegerArgumentType.getInteger(context, "seconds"));
	}

	// -------------------------------------------------------------- deep inspection

	private static ServerLevel dimension(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		return DimensionArgument.getDimension(context, "dimension");
	}

	/** @param duration null means run until stopped by hand */
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

		source.sendSuccess(() -> header("Methods inside the inspected chunk"), false);
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"  %s — %,d of %,d server-thread samples fell inside the chunk (%s)",
				breakdown.sampler().displayName(), breakdown.samplesInChunk(), breakdown.samplesOnThread(),
				formatPercent(breakdown.chunkShareOfThread() * 100.0)))
				.withStyle(ChatFormatting.DARK_GRAY), false);

		if (breakdown.biased()) {
			source.sendSuccess(() -> Component.literal(
					"  This sampler lands on safepoints, so treat hot spots as indicative, not exact.")
					.withStyle(ChatFormatting.YELLOW), false);
		}
		if (breakdown.lostSamples() > 0) {
			source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
					"  The JVM dropped %,d samples.", breakdown.lostSamples()))
					.withStyle(ChatFormatting.YELLOW), false);
		}
		if (breakdown.bufferCapReached()) {
			source.sendSuccess(() -> Component.literal(
					"  Sample buffers filled, so this covers only the earlier part of the inspection.")
					.withStyle(ChatFormatting.YELLOW), false);
		}

		int rank = 1;
		for (MethodBreakdown.MethodRow row : breakdown.methods()) {
			int shown = rank++;
			source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  %2d. %5.1f%%  ",
					shown, row.shareOf(breakdown) * 100.0))
					.withStyle(ChatFormatting.GRAY)
					.append(Component.literal(row.method()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  during " + row.dominantType())
							.withStyle(ChatFormatting.DARK_GRAY)), false);

			String path = callPath(row.representativeStack());
			if (!path.isEmpty()) {
				source.sendSuccess(() -> Component.literal("        via " + path)
						.withStyle(ChatFormatting.DARK_GRAY), false);
			}
		}
		return 1;
	}

	/** Renders the callers of a sampled method, nearest first, short enough for one chat line. */
	private static String callPath(List<String> stack) {
		int depth = Math.min(stack.size(), 5);
		return depth <= 1 ? "" : String.join(" <- ", stack.subList(1, depth));
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
					"No object ticks recorded in chunk [%d, %d] yet — nothing there is ticking.",
					ChunkPos.getX(breakdown.chunkKey()), ChunkPos.getZ(breakdown.chunkKey()))));
			return 0;
		}

		String dimension = ChunkProfiler.get().dimensions().nameOf(breakdown.dimensionId());
		source.sendSuccess(() -> header(String.format(Locale.ROOT, "Chunk [%d, %d] in %s",
				ChunkPos.getX(breakdown.chunkKey()), ChunkPos.getZ(breakdown.chunkKey()), dimension)), false);
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"  %.3f ms per tick over %,d ticks — %,d object ticks in %.1fs%s",
				breakdown.millisPerTick(), breakdown.ticksObserved(), breakdown.observedEvents(),
				breakdown.sessionNanos() / 1e9,
				DeepProfiler.get().isRunning() ? ", still running" : ""))
				.withStyle(ChatFormatting.DARK_GRAY), false);

		source.sendSuccess(() -> Component.literal("  by type:").withStyle(ChatFormatting.GRAY), false);
		int typeRank = 1;
		for (ObjectBreakdown.TypeRow row : breakdown.types()) {
			int shown = typeRank++;
			source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
					"   %2d. %5.1f%%  %-28s %8.3f ms/tick  avg %6.1f us  worst %6.1f us  [%s]",
					shown, row.shareOf(breakdown) * 100.0, row.type(), row.millisPerTick(breakdown),
					row.averageMicros(), row.maxNanos() / 1e3, row.kind())), false);
		}

		if (!breakdown.instances().isEmpty()) {
			source.sendSuccess(() -> Component.literal("  worst individual objects:")
					.withStyle(ChatFormatting.GRAY), false);
			int instanceRank = 1;
			for (ObjectBreakdown.InstanceRow row : breakdown.instances()) {
				int shown = instanceRank++;
				source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
						"   %2d. %5.1f%%  %-28s %s[%d, %d, %d]  %,d ticks",
						shown, row.shareOf(breakdown) * 100.0, row.type(),
						row.isMovable() ? "now at " : "", row.x(), row.y(), row.z(), row.ticks())), false);
			}
		}

		if (breakdown.instanceCapReached()) {
			source.sendSuccess(() -> Component.literal(
					"  (too many distinct objects to track them all; type totals are still complete)")
					.withStyle(ChatFormatting.DARK_GRAY), false);
		}
		return 1;
	}

	// ------------------------------------------------------------------ actions

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
		ChunkProfiler profiler = ChunkProfiler.get();
		SamplerStats stats = profiler.stats();

		List<Component> lines = new ArrayList<>();
		lines.add(header("Who Ticks Too Long"));

		if (stats == null) {
			lines.add(field("chunk heat", Component.literal("off").withStyle(ChatFormatting.GRAY)));
			lines.add(Component.literal("  Nothing is instrumented and no memory is held.")
					.withStyle(ChatFormatting.DARK_GRAY));
		} else {
			lines.add(field("chunk heat", Component.literal(String.format(Locale.ROOT,
					"on — %d Hz requested, %.0f Hz achieved", stats.requestedRateHz(), stats.achievedRateHz()))
					.withStyle(ChatFormatting.GREEN)));
			lines.add(field("samples", Component.literal(String.format(Locale.ROOT,
					"%,d taken, %,d discarded (%.2f%%)",
					stats.samplesTaken(), stats.samplesDiscarded(), stats.discardRate() * 100.0))));
			lines.add(field("sample buffer", Component.literal(
					String.format(Locale.ROOT, "%,d KiB", stats.ringBytes() / 1024L))));
			lines.add(field("sampler thread", Component.literal(stats.samplerPercentOfOneCore() < 0.0
					? "CPU time unavailable on this JVM"
					: String.format(Locale.ROOT, "%.2f s CPU — %s of one core",
							stats.samplerCpuNanos() / 1e9, formatPercent(stats.samplerPercentOfOneCore())))));
			lines.add(field("hot path", Component.literal(String.format(Locale.ROOT,
					"%,d objects instrumented — about %.1f us total, %s of server wall time (estimate)",
					stats.instrumentedObjects(), stats.estimatedHotPathNanos() / 1e3,
					formatPercent(stats.hotPathPercentOfWall())))));
		}

		lines.forEach(line -> context.getSource().sendSuccess(() -> line, false));
		return 1;
	}

	private static int top(CommandContext<CommandSourceStack> context, int count, HeatWindow window, int page) {
		ChunkProfiler profiler = ChunkProfiler.get();
		int offset = (page - 1) * count;
		HeatReport report = profiler.report(window, offset + count);

		if (report == null) {
			context.getSource().sendFailure(Component.literal(
					"Chunk heat monitoring is off. Switch it on with /wttl chunk enable."));
			return 0;
		}
		if (report.totalSamples() == 0) {
			context.getSource().sendFailure(Component.literal(
					"No samples in the last " + window.label() + " yet."));
			return 0;
		}

		List<HeatReport.ChunkHeat> chunks = report.chunks();
		if (chunks.isEmpty()) {
			// Samples exist but every one of them was idle: the server thread genuinely did
			// no chunk work, which is what an empty or paused server looks like.
			context.getSource().sendFailure(Component.literal(String.format(Locale.ROOT,
					"No chunk work in the last %s — all %,d samples caught the server thread idle.",
					window.label(), report.totalSamples())));
			return 0;
		}
		if (offset >= chunks.size()) {
			context.getSource().sendFailure(Component.literal(
					"Page " + page + " is past the end; only " + chunks.size() + " chunks were sampled."));
			return 0;
		}
		List<HeatReport.ChunkHeat> pageChunks = chunks.subList(offset, Math.min(offset + count, chunks.size()));

		context.getSource().sendSuccess(() -> header(String.format(Locale.ROOT,
				"Hottest chunks — %s window, page %d", window.label(), page)), false);
		context.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
				"  %,d samples, %.1f%% of wall time spent ticking chunks",
				report.totalSamples(), report.busyShare() * 100.0)).withStyle(ChatFormatting.DARK_GRAY), false);

		int rank = offset + 1;
		for (HeatReport.ChunkHeat chunk : pageChunks) {
			int shown = rank++;
			context.getSource().sendSuccess(() -> line(shown, chunk, report), false);
		}
		return pageChunks.size();
	}

	// ---------------------------------------------------------------- formatting

	private static Component line(int rank, HeatReport.ChunkHeat chunk, HeatReport report) {
		String dimension = ChunkProfiler.get().dimensions().nameOf(chunk.dimensionId());
		int chunkX = ChunkPos.getX(chunk.chunkKey());
		int chunkZ = ChunkPos.getZ(chunk.chunkKey());

		return Component.literal(String.format(Locale.ROOT, "%2d. ", rank))
				.withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(String.format(Locale.ROOT, "%5.1f%%", chunk.shareOfBusy(report) * 100.0))
						.withStyle(severity(chunk.shareOfBusy(report))))
				.append(Component.literal(String.format(Locale.ROOT, "  [%d, %d] ", chunkX, chunkZ))
						.withStyle(ChatFormatting.WHITE))
				.append(Component.literal(dimension).withStyle(ChatFormatting.GRAY))
				.append(Component.literal("  mostly " + chunk.dominantPhase().displayName())
						.withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Shares are of chunk-ticking time, so a handful of percent already means a dominant chunk. */
	private static ChatFormatting severity(double shareOfBusy) {
		if (shareOfBusy >= 0.20) {
			return ChatFormatting.RED;
		}
		return shareOfBusy >= 0.05 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
	}

	/** Keeps very small shares readable instead of rounding a real cost down to "0.00%". */
	private static String formatPercent(double percent) {
		if (percent <= 0.0) {
			return "0%";
		}
		if (percent < 0.001) {
			return "<0.001%";
		}
		return percent < 1.0
				? String.format(Locale.ROOT, "%.3f%%", percent)
				: String.format(Locale.ROOT, "%.1f%%", percent);
	}

	private static MutableComponent header(String title) {
		return Component.literal("── " + title + " ──").withStyle(ChatFormatting.AQUA);
	}

	private static MutableComponent field(String name, Component value) {
		return Component.literal("  " + name + ": ").withStyle(ChatFormatting.GRAY).append(value);
	}
}
