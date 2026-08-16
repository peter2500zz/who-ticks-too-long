package plus.mygo.whotickstoolong.command;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ChunkPos;
import plus.mygo.whotickstoolong.auto.AutoCapture;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.HeatReport;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;
import plus.mygo.whotickstoolong.util.DurationSyntax;

/**
 * Turns profiler results into chat lines.
 *
 * <p>The chat box is narrow. A row that reads well in a terminal wraps into an unreadable
 * block in game, so every row is broken into a short headline and indented detail beneath it,
 * and colour carries the meaning that alignment cannot: gray for labels, white for values,
 * and a warm scale for shares that deserve attention.
 *
 * <p>Everything appends into a {@link ChatReport} rather than sending directly, so a caller
 * can stitch several sections together and deliver them as one message.
 */
final class WttlOutput {

	private static final String VANILLA_NAMESPACE = "minecraft:";

	/** Longest call path shown beneath a sampled method. */
	private static final int STACK_LINES = 4;

	private static final ChatFormatting LABEL = ChatFormatting.GRAY;
	private static final ChatFormatting VALUE = ChatFormatting.WHITE;
	private static final ChatFormatting DETAIL = ChatFormatting.DARK_GRAY;
	private static final ChatFormatting RANK = ChatFormatting.DARK_GRAY;

	private WttlOutput() {
	}

	// ------------------------------------------------------------------ pieces

	static MutableComponent header(String title) {
		return Component.literal("-- " + title + " --").withStyle(ChatFormatting.AQUA);
	}

	/** A top level {@code label: value} row. */
	static MutableComponent field(String name, Component value) {
		return Component.literal(" " + name + ": ").withStyle(LABEL).append(value);
	}

	static MutableComponent field(String name, String value) {
		return field(name, Component.literal(value).withStyle(VALUE));
	}

	/** An indented continuation beneath a field or a ranked row. */
	static MutableComponent detail(String text) {
		return Component.literal("   " + text).withStyle(DETAIL);
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

	/** Microseconds get unwieldy past a millisecond, so the unit follows the magnitude. */
	private static String formatMicros(double micros) {
		return micros >= 1000.0
				? String.format(Locale.ROOT, "%.2fms", micros / 1000.0)
				: String.format(Locale.ROOT, "%.1fus", micros);
	}

	/** Shares are of a single chunk's cost, so a few percent already means a dominant item. */
	private static ChatFormatting severity(double share) {
		if (share >= 0.20) {
			return ChatFormatting.RED;
		}
		return share >= 0.05 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
	}

	/** The headline of a ranked row: its position, its share, and what it is. */
	private static MutableComponent rankedRow(int rank, double share, String name) {
		return Component.literal(String.format(Locale.ROOT, "%2d. ", rank)).withStyle(RANK)
				.append(Component.literal(String.format(Locale.ROOT, "%5s", formatPercent(share * 100.0)))
						.withStyle(severity(share)))
				.append(Component.literal("  " + name).withStyle(VALUE));
	}

	/**
	 * Chunk coordinates alone are not something a player can walk to, so the chunk's origin
	 * block goes with them. That is the corner to type into a teleport.
	 */
	private static String chunkLabel(int dimensionId, long chunkKey) {
		int chunkX = ChunkPos.getX(chunkKey);
		int chunkZ = ChunkPos.getZ(chunkKey);
		return String.format(Locale.ROOT, "[%d, %d] @ %d,%d",
				chunkX, chunkZ,
				SectionPos.sectionToBlockCoord(chunkX), SectionPos.sectionToBlockCoord(chunkZ));
	}

	static String dimensionLabel(int dimensionId) {
		String name = ChunkProfiler.get().dimensions().nameOf(dimensionId);
		return name.startsWith(VANILLA_NAMESPACE) ? name.substring(VANILLA_NAMESPACE.length()) : name;
	}

	// ------------------------------------------------------------- chunk ranking

	static void appendHeat(ChatReport out, HeatReport report,
			List<HeatReport.ChunkHeat> page, int firstRank) {
		out.line(header("Hottest chunks · " + DurationSyntax.format(report.requested())));
		out.line(detail(String.format(Locale.ROOT, "%,d samples · %.1f%% of wall time ticking chunks",
				report.totalSamples(), report.busyShare() * 100.0)));

		if (report.truncated()) {
			// Asking for more history than the ring holds is not an error, but silently
			// answering a shorter window than was asked for would be misleading.
			out.line(Component.literal("   only " + DurationSyntax.format(Duration.ofNanos(report.spanNanos()))
					+ " of samples retained").withStyle(ChatFormatting.YELLOW));
		}

		int rank = firstRank;
		for (HeatReport.ChunkHeat chunk : page) {
			out.line(rankedRow(rank++, chunk.shareOfBusy(report),
					chunkLabel(chunk.dimensionId(), chunk.chunkKey())));
			out.line(detail(dimensionLabel(chunk.dimensionId()) + " · mostly "
					+ chunk.dominantPhase().displayName()));
		}
	}

	// ---------------------------------------------------------- object breakdown

	static void appendObjects(ChatReport out, ObjectBreakdown breakdown, String suffix) {
		out.line(header("Chunk " + chunkLabel(breakdown.dimensionId(), breakdown.chunkKey())));
		out.line(field("cost", Component.literal(String.format(Locale.ROOT, "%.3f ms per tick",
				breakdown.millisPerTick())).withStyle(ChatFormatting.GOLD)));
		out.line(detail(String.format(Locale.ROOT, "%s · %,d ticks · %,d object ticks · %.1fs%s",
				dimensionLabel(breakdown.dimensionId()), breakdown.ticksObserved(),
				breakdown.observedEvents(), breakdown.sessionNanos() / 1e9, suffix)));

		out.line(Component.literal(" by type").withStyle(LABEL));
		int rank = 1;
		for (ObjectBreakdown.TypeRow row : breakdown.types()) {
			out.line(rankedRow(rank++, row.shareOf(breakdown), row.type()));
			out.line(detail(String.format(Locale.ROOT, "%s · %.3f ms/tick · avg %s · worst %s",
					row.kind(), row.millisPerTick(breakdown),
					formatMicros(row.averageMicros()), formatMicros(row.maxNanos() / 1e3))));
		}

		if (!breakdown.instances().isEmpty()) {
			out.line(Component.literal(" worst individual objects").withStyle(LABEL));
			rank = 1;
			for (ObjectBreakdown.InstanceRow row : breakdown.instances()) {
				out.line(rankedRow(rank++, row.shareOf(breakdown), row.type()));
				out.line(detail(String.format(Locale.ROOT, "%s%d, %d, %d · %,d ticks",
						row.isMovable() ? "now at " : "at ", row.x(), row.y(), row.z(), row.ticks())));
			}
		}

		if (breakdown.instanceCapReached()) {
			out.line(detail("too many distinct objects to track them all; type totals are complete"));
		}
	}

	// ---------------------------------------------------------- method breakdown

	static void appendMethods(ChatReport out, MethodBreakdown breakdown) {
		out.line(header("Methods in the inspected chunk"));
		out.line(detail(String.format(Locale.ROOT, "%,d of %,d samples fell inside · %s",
				breakdown.samplesInChunk(), breakdown.samplesOnThread(),
				formatPercent(breakdown.chunkShareOfThread() * 100.0))));
		out.line(detail("sampler: " + breakdown.sampler().displayName()));

		if (breakdown.biased()) {
			out.line(Component.literal("   safepoint biased, treat hot spots as indicative")
					.withStyle(ChatFormatting.YELLOW));
		}
		if (breakdown.lostSamples() > 0) {
			out.line(Component.literal(String.format(Locale.ROOT, "   the JVM dropped %,d samples",
					breakdown.lostSamples())).withStyle(ChatFormatting.YELLOW));
		}
		if (breakdown.bufferCapReached()) {
			out.line(Component.literal("   sample buffers filled, covers only the earlier part")
					.withStyle(ChatFormatting.YELLOW));
		}

		int rank = 1;
		for (MethodBreakdown.MethodRow row : breakdown.methods()) {
			out.line(rankedRow(rank++, row.shareOf(breakdown), row.method()));
			out.line(detail("during " + row.dominantType()));

			List<String> stack = row.representativeStack();
			// The innermost frame is the method itself, already shown above.
			int shown = Math.min(stack.size(), STACK_LINES + 1);
			for (int i = 1; i < shown; i++) {
				out.line(Component.literal((i == 1 ? "   from " : "        ") + stack.get(i))
						.withStyle(DETAIL));
			}
		}
	}

	// ------------------------------------------------------------ auto captures

	static void appendCaptureList(ChatReport out, List<AutoCapture> captures) {
		out.line(header("Automatic captures · " + captures.size()));
		int index = 1;
		for (AutoCapture capture : captures) {
			out.line(Component.literal(String.format(Locale.ROOT, "%2d. ", index++)).withStyle(RANK)
					.append(Component.literal(capture.formattedTime()).withStyle(VALUE)));
			out.line(detail(chunkLabel(capture.dimensionId(), capture.chunkKey()) + " · "
					+ dimensionLabel(capture.dimensionId())));
			out.line(detail(String.format(Locale.ROOT, "server %.1f ms/tick · chunk held %.0f%%",
					capture.msptAtTrigger(), capture.shareAtTrigger() * 100.0)));
			if (capture.file() != null) {
				out.line(detail(capture.file().getFileName().toString()));
			}
		}
	}
}
