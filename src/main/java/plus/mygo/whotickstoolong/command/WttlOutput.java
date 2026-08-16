package plus.mygo.whotickstoolong.command;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import plus.mygo.whotickstoolong.auto.AutoCapture;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.HeatReport;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;

/**
 * Turns profiler results into chat lines.
 *
 * <p>Separate from the command tree so the same rendering serves a live inspection and a
 * capture the profiler took by itself hours earlier.
 */
final class WttlOutput {

	private WttlOutput() {
	}

	// ------------------------------------------------------------------ pieces

	static MutableComponent header(String title) {
		return Component.literal("-- " + title + " --").withStyle(ChatFormatting.AQUA);
	}

	static MutableComponent field(String name, Component value) {
		return Component.literal("  " + name + ": ").withStyle(ChatFormatting.GRAY).append(value);
	}

	/** Keeps very small shares readable instead of rounding a real cost down to "0.00%". */
	static String formatPercent(double percent) {
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

	private static void line(CommandSourceStack source, Component component) {
		source.sendSuccess(() -> component, false);
	}

	private static String chunkLabel(int dimensionId, long chunkKey) {
		return String.format(Locale.ROOT, "[%d, %d] %s",
				ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey),
				ChunkProfiler.get().dimensions().nameOf(dimensionId));
	}

	// ------------------------------------------------------------- chunk ranking

	/** Shares are of chunk-ticking time, so a few percent already means a dominant chunk. */
	private static ChatFormatting severity(double shareOfBusy) {
		if (shareOfBusy >= 0.20) {
			return ChatFormatting.RED;
		}
		return shareOfBusy >= 0.05 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
	}

	static void sendHeat(CommandSourceStack source, HeatReport report,
			List<HeatReport.ChunkHeat> page, int firstRank) {
		line(source, header(String.format(Locale.ROOT, "Hottest chunks - %s window", report.window().label())));
		line(source, Component.literal(String.format(Locale.ROOT,
				"  %,d samples, %.1f%% of wall time spent ticking chunks",
				report.totalSamples(), report.busyShare() * 100.0)).withStyle(ChatFormatting.DARK_GRAY));

		int rank = firstRank;
		for (HeatReport.ChunkHeat chunk : page) {
			double share = chunk.shareOfBusy(report);
			line(source, Component.literal(String.format(Locale.ROOT, "%3d. ", rank++))
					.withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(String.format(Locale.ROOT, "%5.1f%%", share * 100.0))
							.withStyle(severity(share)))
					.append(Component.literal("  " + chunkLabel(chunk.dimensionId(), chunk.chunkKey()))
							.withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  mostly " + chunk.dominantPhase().displayName())
							.withStyle(ChatFormatting.DARK_GRAY)));
		}
	}

	// ---------------------------------------------------------- object breakdown

	static void sendObjects(CommandSourceStack source, ObjectBreakdown breakdown, String suffix) {
		line(source, header("Chunk " + chunkLabel(breakdown.dimensionId(), breakdown.chunkKey())));
		line(source, Component.literal(String.format(Locale.ROOT,
				"  %.3f ms per tick over %,d ticks - %,d object ticks in %.1fs%s",
				breakdown.millisPerTick(), breakdown.ticksObserved(), breakdown.observedEvents(),
				breakdown.sessionNanos() / 1e9, suffix)).withStyle(ChatFormatting.DARK_GRAY));

		line(source, Component.literal("  by type:").withStyle(ChatFormatting.GRAY));
		int rank = 1;
		for (ObjectBreakdown.TypeRow row : breakdown.types()) {
			line(source, Component.literal(String.format(Locale.ROOT,
					"   %2d. %5.1f%%  %-28s %8.3f ms/tick  avg %6.1f us  worst %6.1f us  [%s]",
					rank++, row.shareOf(breakdown) * 100.0, row.type(), row.millisPerTick(breakdown),
					row.averageMicros(), row.maxNanos() / 1e3, row.kind())));
		}

		if (!breakdown.instances().isEmpty()) {
			line(source, Component.literal("  worst individual objects:").withStyle(ChatFormatting.GRAY));
			rank = 1;
			for (ObjectBreakdown.InstanceRow row : breakdown.instances()) {
				line(source, Component.literal(String.format(Locale.ROOT,
						"   %2d. %5.1f%%  %-28s %s[%d, %d, %d]  %,d ticks",
						rank++, row.shareOf(breakdown) * 100.0, row.type(),
						row.isMovable() ? "now at " : "", row.x(), row.y(), row.z(), row.ticks())));
			}
		}

		if (breakdown.instanceCapReached()) {
			line(source, Component.literal(
					"  (too many distinct objects to track them all; type totals are still complete)")
					.withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	// ---------------------------------------------------------- method breakdown

	static void sendMethods(CommandSourceStack source, MethodBreakdown breakdown) {
		line(source, header("Methods inside the inspected chunk"));
		line(source, Component.literal(String.format(Locale.ROOT,
				"  %s - %,d of %,d server-thread samples fell inside the chunk (%s)",
				breakdown.sampler().displayName(), breakdown.samplesInChunk(), breakdown.samplesOnThread(),
				formatPercent(breakdown.chunkShareOfThread() * 100.0))).withStyle(ChatFormatting.DARK_GRAY));

		if (breakdown.biased()) {
			line(source, Component.literal(
					"  This sampler lands on safepoints, so treat hot spots as indicative, not exact.")
					.withStyle(ChatFormatting.YELLOW));
		}
		if (breakdown.lostSamples() > 0) {
			line(source, Component.literal(String.format(Locale.ROOT,
					"  The JVM dropped %,d samples.", breakdown.lostSamples()))
					.withStyle(ChatFormatting.YELLOW));
		}
		if (breakdown.bufferCapReached()) {
			line(source, Component.literal(
					"  Sample buffers filled, so this covers only the earlier part of the inspection.")
					.withStyle(ChatFormatting.YELLOW));
		}

		int rank = 1;
		for (MethodBreakdown.MethodRow row : breakdown.methods()) {
			line(source, Component.literal(String.format(Locale.ROOT, "  %2d. %5.1f%%  ",
					rank++, row.shareOf(breakdown) * 100.0)).withStyle(ChatFormatting.GRAY)
					.append(Component.literal(row.method()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("  during " + row.dominantType())
							.withStyle(ChatFormatting.DARK_GRAY)));

			List<String> stack = row.representativeStack();
			int depth = Math.min(stack.size(), 5);
			if (depth > 1) {
				line(source, Component.literal("        via " + String.join(" <- ", stack.subList(1, depth)))
						.withStyle(ChatFormatting.DARK_GRAY));
			}
		}
	}

	// ------------------------------------------------------------ auto captures

	static void sendCaptureList(CommandSourceStack source, List<AutoCapture> captures) {
		line(source, header("Automatic captures (" + captures.size() + ")"));
		int index = 1;
		for (AutoCapture capture : captures) {
			int shown = index++;
			line(source, Component.literal(String.format(Locale.ROOT, "%3d. ", shown))
					.withStyle(ChatFormatting.DARK_GRAY)
					.append(Component.literal(capture.formattedTime()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(String.format(Locale.ROOT, "  %s  %.1f ms/tick server, %.0f%% chunk",
							chunkLabel(capture.dimensionId(), capture.chunkKey()),
							capture.msptAtTrigger(), capture.shareAtTrigger() * 100.0))
							.withStyle(ChatFormatting.GRAY)));
			if (capture.file() != null) {
				line(source, Component.literal("      " + capture.file().getFileName())
						.withStyle(ChatFormatting.DARK_GRAY));
			}
		}
	}
}
