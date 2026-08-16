package plus.mygo.whotickstoolong.util;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and prints durations the way vanilla's {@code /time} argument does: a number with a
 * unit suffix, where a bare number means ticks.
 *
 * <p>Vanilla offers {@code t}, {@code s} and {@code d}. This adds {@code m} and {@code h},
 * which a profiling window needs, and leaves out {@code d} because no sample buffer retains a
 * day of history.
 *
 * <p>Deliberately not a Brigadier {@code ArgumentType}: a custom argument type has to be
 * registered so it can be serialised to clients, and an unregistered one stops a vanilla
 * client from joining. A server-side profiler must not cost anyone their vanilla client, so
 * the argument stays a plain string and the parsing happens here.
 */
public final class DurationSyntax {

	private static final long MILLIS_PER_TICK = 50L;
	private static final long MILLIS_PER_SECOND = 1000L;
	private static final int SECONDS_PER_MINUTE = 60;
	private static final int SECONDS_PER_HOUR = 3600;

	private static final Pattern SYNTAX = Pattern.compile("^(\\d+(?:\\.\\d+)?)([a-zA-Z]*)$");
	private static final Pattern LEADING_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

	/** Offered as completions; any other value the syntax accepts still works. */
	public static final List<String> COMMON_WINDOWS = List.of("10s", "30s", "1m", "5m");

	public static final List<String> UNITS = List.of("t", "s", "m", "h");

	private DurationSyntax() {
	}

	/**
	 * @param text a number with an optional unit, such as {@code 30s}, {@code 5m} or
	 *             {@code 200} (ticks, as in vanilla)
	 * @throws IllegalArgumentException with a message meant to be shown to the player
	 */
	public static Duration parse(String text) {
		Matcher matcher = SYNTAX.matcher(text.trim());
		if (!matcher.matches()) {
			throw new IllegalArgumentException(
					"'" + text + "' is not a duration. Write a number and a unit, such as 30s, 5m or 1h.");
		}

		double value = Double.parseDouble(matcher.group(1));
		String unit = matcher.group(2).toLowerCase(Locale.ROOT);

		Duration duration = switch (unit) {
			// A bare number means ticks, matching vanilla's /time argument.
			case "", "t" -> Duration.ofMillis(Math.round(value * MILLIS_PER_TICK));
			case "s" -> Duration.ofMillis(Math.round(value * 1000.0));
			case "m" -> Duration.ofMillis(Math.round(value * 1000.0 * SECONDS_PER_MINUTE));
			case "h" -> Duration.ofMillis(Math.round(value * 1000.0 * SECONDS_PER_HOUR));
			default -> throw new IllegalArgumentException(
					"'" + unit + "' is not a unit. Use t for ticks, s, m or h.");
		};

		if (duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException("A window has to be longer than zero.");
		}
		return duration;
	}

	/**
	 * Length of the leading number in a partly typed argument, or 0 if it does not start with
	 * one. Used to offer unit completions after the number, the way vanilla's {@code /time}
	 * argument does.
	 */
	public static int leadingNumberLength(String partial) {
		Matcher matcher = LEADING_NUMBER.matcher(partial);
		return matcher.lookingAt() ? matcher.end() : 0;
	}

	/**
	 * Prints a duration back in the same syntax, largest unit first.
	 *
	 * <p>Units are tried from the top down, because a duration divisible by a minute can also
	 * be divisible by an hour and the hour is the one worth showing: 5400 seconds reads far
	 * better as {@code 1h30m} than as {@code 90m}.
	 *
	 * <p>Sub-second precision is shown while it is the dominant part of the value and dropped
	 * once minutes are involved, where a few hundred milliseconds are noise.
	 */
	public static String format(Duration duration) {
		long millis = duration.toMillis();
		if (millis < MILLIS_PER_SECOND) {
			return millis + "ms";
		}

		long seconds = millis / MILLIS_PER_SECOND;
		if (seconds < SECONDS_PER_MINUTE) {
			return millis % MILLIS_PER_SECOND == 0L
					? seconds + "s"
					: String.format(Locale.ROOT, "%.1fs", millis / (double) MILLIS_PER_SECOND);
		}

		long hours = seconds / SECONDS_PER_HOUR;
		long minutes = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE;
		long remainingSeconds = seconds % SECONDS_PER_MINUTE;

		StringBuilder out = new StringBuilder(8);
		if (hours > 0L) {
			out.append(hours).append('h');
		}
		if (minutes > 0L || (hours > 0L && remainingSeconds > 0L)) {
			out.append(minutes).append('m');
		}
		if (remainingSeconds > 0L) {
			out.append(remainingSeconds).append('s');
		}
		return out.toString();
	}
}
