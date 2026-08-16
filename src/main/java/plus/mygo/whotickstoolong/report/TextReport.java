package plus.mygo.whotickstoolong.report;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.i18n.Messages;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;

/**
 * Renders a finished inspection as plain text for a file on disk.
 *
 * <p>Written in the authoring language. A file has no reader to ask at the moment it is
 * created, and an automatic capture is written when nobody is present at all.
 *
 * <p>Kept separate from the chat rendering on purpose: chat output is paged, coloured and
 * abbreviated to fit a few lines, whereas a file is read after the fact by someone who was
 * not there and wants everything, including the parts that would be noise in chat.
 */
public final class TextReport {

	private static final String RULE = "-".repeat(78);

	private TextReport() {
	}

	public static String render(String heading, ObjectBreakdown objects,
			@Nullable MethodBreakdown methods) {

		StringBuilder out = new StringBuilder(4096);
		out.append(Messages.plain("wttl.title")).append(System.lineSeparator());
		out.append(heading).append(System.lineSeparator());
		out.append(RULE).append(System.lineSeparator());

		int chunkX = ChunkPos.getX(objects.chunkKey());
		int chunkZ = ChunkPos.getZ(objects.chunkKey());
		int originX = SectionPos.sectionToBlockCoord(chunkX);
		int originZ = SectionPos.sectionToBlockCoord(chunkZ);

		out.append(Messages.plain("wttl.report.chunk", chunkX, chunkZ,
				ChunkProfiler.get().dimensions().nameOf(objects.dimensionId())))
				.append(System.lineSeparator());
		// Chunk coordinates are not somewhere a player can walk to; block coordinates are.
		out.append("  ").append(Messages.plain("wttl.report.blocks",
				originX, SectionPos.sectionToBlockCoord(chunkX + 1) - 1,
				originZ, SectionPos.sectionToBlockCoord(chunkZ + 1) - 1,
				originX, originZ)).append(System.lineSeparator());
		out.append("  ").append(Messages.plain("wttl.report.summary",
				decimal(objects.millisPerTick(), 3), count(objects.ticksObserved()),
				count(objects.observedEvents()), decimal(objects.sessionNanos() / 1e9, 1)))
				.append(System.lineSeparator()).append(System.lineSeparator());

		out.append(Messages.plain("wttl.report.by_type")).append(System.lineSeparator());
		int rank = 1;
		for (ObjectBreakdown.TypeRow row : objects.types()) {
			out.append(String.format(Locale.ROOT, "  %3d. %6s  %-34s %s%n",
					rank++, percent(row.shareOf(objects)), Messages.plainLabel(row.type()),
					Messages.plain("wttl.object.type_detail", Messages.plain(row.kind()),
							decimal(row.millisPerTick(objects), 3),
							micros(row.averageMicros()), micros(row.maxNanos() / 1e3))));
		}

		out.append(System.lineSeparator()).append(Messages.plain("wttl.report.instances"))
				.append(System.lineSeparator());
		rank = 1;
		for (ObjectBreakdown.InstanceRow row : objects.instances()) {
			out.append(String.format(Locale.ROOT, "  %3d. %6s  %-34s %s%n",
					rank++, percent(row.shareOf(objects)), Messages.plainLabel(row.type()),
					Messages.plain("wttl.object.instance_detail",
							Messages.plain(row.isMovable()
									? "wttl.object.instance.now_at" : "wttl.object.instance.at"),
							row.x(), row.y(), row.z(), count(row.ticks()))));
		}
		if (objects.instanceCapReached()) {
			out.append("  ").append(Messages.plain("wttl.report.note.instance_cap"))
					.append(System.lineSeparator());
		}

		out.append(System.lineSeparator());
		appendMethods(out, methods);
		return out.toString();
	}

	private static void appendMethods(StringBuilder out, @Nullable MethodBreakdown methods) {
		out.append(Messages.plain("wttl.report.methods")).append(System.lineSeparator());
		if (methods == null) {
			out.append("  ").append(Messages.plain("wttl.report.methods.none"))
					.append(System.lineSeparator());
			return;
		}

		out.append("  ").append(Messages.plain("wttl.report.methods.summary",
				count(methods.samplesInChunk()), count(methods.samplesOnThread())))
				.append(" - ").append(Messages.plain(methods.sampler().translationKey()))
				.append(System.lineSeparator());

		if (methods.biased()) {
			out.append("  ").append(Messages.plain("wttl.report.note.biased")).append(System.lineSeparator());
		}
		if (methods.lostSamples() > 0) {
			out.append("  ").append(Messages.plain("wttl.report.note.lost", count(methods.lostSamples())))
					.append(System.lineSeparator());
		}
		if (methods.bufferCapReached()) {
			out.append("  ").append(Messages.plain("wttl.report.note.buffer_full"))
					.append(System.lineSeparator());
		}

		int rank = 1;
		for (MethodBreakdown.MethodRow row : methods.methods()) {
			out.append(String.format(Locale.ROOT, "  %3d. %6s  %-52s %s%n",
					rank++, percent(row.shareOf(methods)), row.method(),
					Messages.plain("wttl.method.during", row.dominantType())));
			List<String> stack = row.representativeStack();
			if (stack.size() > 1) {
				out.append("            ")
						.append(Messages.plain("wttl.method.from", String.join(" <- ",
								stack.subList(1, stack.size()))))
						.append(System.lineSeparator());
			}
		}
	}

	private static String percent(double fraction) {
		double value = fraction * 100.0;
		return value < 1.0
				? String.format(Locale.ROOT, "%.3f%%", value)
				: String.format(Locale.ROOT, "%.1f%%", value);
	}

	private static String decimal(double value, int places) {
		return String.format(Locale.ROOT, "%." + places + "f", value);
	}

	private static String count(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static String micros(double value) {
		return value >= 1000.0
				? String.format(Locale.ROOT, "%.2fms", value / 1000.0)
				: String.format(Locale.ROOT, "%.1fus", value);
	}
}
