package plus.mygo.whotickstoolong.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.HeatReport;
import plus.mygo.whotickstoolong.profile.HeatWindow;
import plus.mygo.whotickstoolong.profile.SamplerStats;

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
