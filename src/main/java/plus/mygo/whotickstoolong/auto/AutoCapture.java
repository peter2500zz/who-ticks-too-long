package plus.mygo.whotickstoolong.auto;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;

/**
 * A lag incident the profiler caught on its own, kept so it can be read long after it passed.
 *
 * @param triggeredAt  when the threshold was crossed
 * @param msptAtTrigger the server's average tick time at that moment, in milliseconds
 * @param shareAtTrigger how much of chunk tick time the culprit held, in the range 0..1
 * @param file          where the full report was written, or null if the write was not started
 */
public record AutoCapture(
		LocalDateTime triggeredAt,
		double msptAtTrigger,
		double shareAtTrigger,
		int dimensionId,
		long chunkKey,
		ObjectBreakdown objects,
		@Nullable MethodBreakdown methods,
		@Nullable Path file
) {
	/** Readable to a human reading a log at breakfast, unlike the raw ISO form. */
	public static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

	public String formattedTime() {
		return this.triggeredAt.format(TIMESTAMP);
	}
}
