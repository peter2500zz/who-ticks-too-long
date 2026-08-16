package plus.mygo.whotickstoolong.report;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;

/**
 * Renders a finished inspection as plain text for a file on disk.
 *
 * <p>Kept separate from the chat rendering on purpose: chat output is paged, coloured and
 * abbreviated to fit a few lines, whereas a file is read after the fact by someone who was
 * not there and wants everything, including the parts that would be noise in chat.
 */
public final class TextReport {

	private static final String RULE = "-".repeat(78);

	private TextReport() {
	}

	public static String render(
			String heading,
			String dimensionName,
			ObjectBreakdown objects,
			@Nullable MethodBreakdown methods) {

		StringBuilder out = new StringBuilder(4096);
		out.append("Who Ticks Too Long").append(System.lineSeparator());
		out.append(heading).append(System.lineSeparator());
		out.append(RULE).append(System.lineSeparator());

		int chunkX = ChunkPos.getX(objects.chunkKey());
		int chunkZ = ChunkPos.getZ(objects.chunkKey());
		int originX = SectionPos.sectionToBlockCoord(chunkX);
		int originZ = SectionPos.sectionToBlockCoord(chunkZ);

		out.append(String.format(Locale.ROOT, "Chunk [%d, %d] in %s%n", chunkX, chunkZ, dimensionName));
		// Chunk coordinates are not somewhere a player can walk to; block coordinates are.
		out.append(String.format(Locale.ROOT, "  Blocks X %d..%d, Z %d..%d - origin %d, %d%n",
				originX, SectionPos.sectionToBlockCoord(chunkX + 1) - 1,
				originZ, SectionPos.sectionToBlockCoord(chunkZ + 1) - 1,
				originX, originZ));
		out.append(String.format(Locale.ROOT,
				"  %.3f ms per tick over %,d ticks - %,d object ticks in %.1f s%n%n",
				objects.millisPerTick(), objects.ticksObserved(), objects.observedEvents(),
				objects.sessionNanos() / 1e9));

		out.append("By type").append(System.lineSeparator());
		int rank = 1;
		for (ObjectBreakdown.TypeRow row : objects.types()) {
			out.append(String.format(Locale.ROOT,
					"  %3d. %5.1f%%  %-34s %8.3f ms/tick  avg %8.1f us  worst %9.1f us  [%s]%n",
					rank++, row.shareOf(objects) * 100.0, row.type(), row.millisPerTick(objects),
					row.averageMicros(), row.maxNanos() / 1e3, row.kind()));
		}

		out.append(System.lineSeparator()).append("Individual objects").append(System.lineSeparator());
		rank = 1;
		for (ObjectBreakdown.InstanceRow row : objects.instances()) {
			out.append(String.format(Locale.ROOT, "  %3d. %5.1f%%  %-34s %s[%d, %d, %d]  %,d ticks%n",
					rank++, row.shareOf(objects) * 100.0, row.type(),
					row.isMovable() ? "last at " : "", row.x(), row.y(), row.z(), row.ticks()));
		}
		if (objects.instanceCapReached()) {
			out.append("  Distinct-object tracking hit its bound; type totals above are still complete.")
					.append(System.lineSeparator());
		}

		out.append(System.lineSeparator());
		appendMethods(out, methods);
		return out.toString();
	}

	private static void appendMethods(StringBuilder out, @Nullable MethodBreakdown methods) {
		if (methods == null) {
			out.append("Methods").append(System.lineSeparator());
			out.append("  Not sampled for this inspection.").append(System.lineSeparator());
			return;
		}

		out.append(String.format(Locale.ROOT,
				"Methods  (%s; %,d of %,d server-thread samples fell inside the chunk)%n",
				methods.sampler().displayName(), methods.samplesInChunk(), methods.samplesOnThread()));
		if (methods.biased()) {
			out.append("  NOTE: this sampler lands on safepoints, so hot spots are indicative, not exact.")
					.append(System.lineSeparator());
		}
		if (methods.lostSamples() > 0) {
			out.append(String.format(Locale.ROOT, "  NOTE: the JVM dropped %,d samples.%n",
					methods.lostSamples()));
		}
		if (methods.bufferCapReached()) {
			out.append("  NOTE: sample buffers filled; this covers only the earlier part of the inspection.")
					.append(System.lineSeparator());
		}

		int rank = 1;
		for (MethodBreakdown.MethodRow row : methods.methods()) {
			out.append(String.format(Locale.ROOT, "  %3d. %5.1f%%  %-52s during %s%n",
					rank++, row.shareOf(methods) * 100.0, row.method(), row.dominantType()));
			List<String> stack = row.representativeStack();
			if (stack.size() > 1) {
				out.append("            via ")
						.append(String.join(" <- ", stack.subList(1, stack.size())))
						.append(System.lineSeparator());
			}
		}
	}
}
