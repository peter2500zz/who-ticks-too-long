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
 * Turns profiler results into chat lines, in the language of whoever asked.
 *
 * <p>The chat box is narrow. A row that reads well in a terminal wraps into an unreadable
 * block in game, so every row is broken into a short headline and indented detail beneath it,
 * and colour carries the emphasis that alignment cannot: gray for labels, white for values,
 * and a warm scale for shares that deserve attention.
 *
 * <p>Numbers are formatted here and passed as strings. Vanilla's translation templates
 * understand {@code %s} and nothing else, so a numeric format cannot survive the language
 * table or the wire.
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

	static MutableComponent header(Component title) {
		return Component.literal("-- ").withStyle(ChatFormatting.AQUA)
				.append(title.copy().withStyle(ChatFormatting.AQUA))
				.append(Component.literal(" --").withStyle(ChatFormatting.AQUA));
	}

	/** A top level {@code label: value} row. */
	static MutableComponent field(Component name, Component value) {
		return Component.literal(" ").append(name.copy().withStyle(LABEL))
				.append(Component.literal(": ").withStyle(LABEL))
				.append(value);
	}

	/** An indented continuation beneath a field or a ranked row. */
	static MutableComponent detail(Component text) {
		return Component.literal("   ").withStyle(DETAIL).append(text.copy().withStyle(DETAIL));
	}

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

	private static String decimal(double value, int places) {
		return String.format(Locale.ROOT, "%." + places + "f", value);
	}

	private static String count(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	/** Shares are of a single chunk's cost, so a few percent already means a dominant item. */
	private static ChatFormatting severity(double share) {
		if (share >= 0.20) {
			return ChatFormatting.RED;
		}
		return share >= 0.05 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
	}

	/** The headline of a ranked row: its position, its share, and what it is. */
	private static MutableComponent rankedRow(ChatReport out, int rank, double share, String name) {
		return Component.literal(String.format(Locale.ROOT, "%2d. ", rank)).withStyle(RANK)
				.append(Component.literal(String.format(Locale.ROOT, "%5s", formatPercent(share * 100.0)))
						.withStyle(severity(share)))
				.append(Component.literal("  ").append(out.label(name)).withStyle(VALUE));
	}

	private static String chunkLabel(long chunkKey) {
		int chunkX = ChunkPos.getX(chunkKey);
		int chunkZ = ChunkPos.getZ(chunkKey);
		return String.format(Locale.ROOT, "[%d, %d] @ %d,%d", chunkX, chunkZ,
				SectionPos.sectionToBlockCoord(chunkX), SectionPos.sectionToBlockCoord(chunkZ));
	}

	/** Vanilla dimensions read better without the namespace that every one of them shares. */
	static String dimensionLabel(int dimensionId) {
		String name = ChunkProfiler.get().dimensions().nameOf(dimensionId);
		return name.startsWith(VANILLA_NAMESPACE) ? name.substring(VANILLA_NAMESPACE.length()) : name;
	}

	// ------------------------------------------------------------- chunk ranking

	static void appendHeat(ChatReport out, HeatReport report,
			List<HeatReport.ChunkHeat> page, int firstRank) {
		out.raw(header(out.text("wttl.heat.header", DurationSyntax.format(report.requested()))));
		out.raw(detail(out.text("wttl.heat.summary", count(report.totalSamples()),
				formatPercent(report.busyShare() * 100.0))));

		if (report.truncated()) {
			// Asking for more history than the ring holds is not an error, but silently
			// answering a shorter window than was asked for would be misleading.
			out.raw(Component.literal("   ").append(out.text("wttl.heat.truncated",
					DurationSyntax.format(Duration.ofNanos(report.spanNanos()))))
					.withStyle(ChatFormatting.YELLOW));
		}

		int rank = firstRank;
		for (HeatReport.ChunkHeat chunk : page) {
			out.raw(rankedRow(out, rank++, chunk.shareOfBusy(report), chunkLabel(chunk.chunkKey())));
			out.raw(detail(out.text("wttl.heat.row_detail", dimensionLabel(chunk.dimensionId()),
					out.text(chunk.dominantPhase().translationKey()))));
		}
	}

	// ---------------------------------------------------------- object breakdown

	static void appendObjects(ChatReport out, ObjectBreakdown breakdown, boolean stillRunning) {
		int chunkX = ChunkPos.getX(breakdown.chunkKey());
		int chunkZ = ChunkPos.getZ(breakdown.chunkKey());

		out.raw(header(out.text("wttl.object.header", chunkX, chunkZ,
				SectionPos.sectionToBlockCoord(chunkX), SectionPos.sectionToBlockCoord(chunkZ))));
		out.raw(field(out.text("wttl.object.cost"),
				out.text("wttl.object.cost.value", decimal(breakdown.millisPerTick(), 3))
						.withStyle(ChatFormatting.GOLD)));
		out.raw(detail(out.text("wttl.object.cost.detail",
				dimensionLabel(breakdown.dimensionId()), count(breakdown.ticksObserved()),
				count(breakdown.observedEvents()), decimal(breakdown.sessionNanos() / 1e9, 1))
				.append(stillRunning ? out.text("wttl.object.still_running") : Component.empty())));

		out.raw(Component.literal(" ").append(out.text("wttl.object.by_type")).withStyle(LABEL));
		int rank = 1;
		for (ObjectBreakdown.TypeRow row : breakdown.types()) {
			out.raw(rankedRow(out, rank++, row.shareOf(breakdown), row.type()));
			out.raw(detail(out.text("wttl.object.type_detail", out.text(row.kind()),
					decimal(row.millisPerTick(breakdown), 3),
					formatMicros(row.averageMicros()), formatMicros(row.maxNanos() / 1e3))));
		}

		if (!breakdown.instances().isEmpty()) {
			out.raw(Component.literal(" ").append(out.text("wttl.object.worst")).withStyle(LABEL));
			rank = 1;
			for (ObjectBreakdown.InstanceRow row : breakdown.instances()) {
				out.raw(rankedRow(out, rank++, row.shareOf(breakdown), row.type()));
				out.raw(detail(out.text("wttl.object.instance_detail",
						out.text(row.isMovable() ? "wttl.object.instance.now_at" : "wttl.object.instance.at"),
						row.x(), row.y(), row.z(), count(row.ticks()))));
			}
		}

		if (breakdown.instanceCapReached()) {
			out.raw(detail(out.text("wttl.object.instance_cap")));
		}
	}

	// ---------------------------------------------------------- method breakdown

	static void appendMethods(ChatReport out, MethodBreakdown breakdown) {
		out.raw(header(out.text("wttl.method.header")));
		out.raw(detail(out.text("wttl.method.summary", count(breakdown.samplesInChunk()),
				count(breakdown.samplesOnThread()),
				formatPercent(breakdown.chunkShareOfThread() * 100.0))));
		out.raw(detail(out.text("wttl.method.sampler", out.text(breakdown.sampler().translationKey()))));

		if (breakdown.biased()) {
			out.raw(Component.literal("   ").append(out.text("wttl.method.biased"))
					.withStyle(ChatFormatting.YELLOW));
		}
		if (breakdown.lostSamples() > 0) {
			out.raw(Component.literal("   ").append(out.text("wttl.method.lost",
					count(breakdown.lostSamples()))).withStyle(ChatFormatting.YELLOW));
		}
		if (breakdown.bufferCapReached()) {
			out.raw(Component.literal("   ").append(out.text("wttl.method.buffer_full"))
					.withStyle(ChatFormatting.YELLOW));
		}

		int rank = 1;
		for (MethodBreakdown.MethodRow row : breakdown.methods()) {
			out.raw(rankedRow(out, rank++, row.shareOf(breakdown), row.method()));
			out.raw(detail(out.text("wttl.method.during", row.dominantType())));

			List<String> stack = row.representativeStack();
			// The innermost frame is the method itself, already shown above.
			int shown = Math.min(stack.size(), STACK_LINES + 1);
			for (int i = 1; i < shown; i++) {
				out.raw(i == 1
						? detail(out.text("wttl.method.from", stack.get(i)))
						: Component.literal("        " + stack.get(i)).withStyle(DETAIL));
			}
		}
	}

	// ------------------------------------------------------------ auto captures

	static void appendCaptureList(ChatReport out, List<AutoCapture> captures) {
		out.raw(header(out.text("wttl.auto.list_header", String.valueOf(captures.size()))));
		int index = 1;
		for (AutoCapture capture : captures) {
			out.raw(Component.literal(String.format(Locale.ROOT, "%2d. ", index++)).withStyle(RANK)
					.append(Component.literal(capture.formattedTime()).withStyle(VALUE)));
			out.raw(detail(chunkLabel(capture.chunkKey()) + " · " + dimensionLabel(capture.dimensionId())));
			out.raw(detail(out.text("wttl.auto.list_detail", decimal(capture.msptAtTrigger(), 1),
					formatPercent(capture.shareAtTrigger() * 100.0))));
			if (capture.file() != null) {
				out.raw(detail(capture.file().getFileName().toString()));
			}
		}
	}
}
