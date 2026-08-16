package plus.mygo.whotickstoolong.profile.deep;

import java.time.Duration;
import java.util.Locale;
import jdk.jfr.EventSettings;
import jdk.jfr.FlightRecorder;

/**
 * Which JFR event supplies the stack samples that get attributed to a chunk.
 *
 * <p>{@code jdk.CPUTimeSample} (JEP 509) samples at fixed intervals of CPU time using a
 * POSIX timer, so it is free of the safepoint bias that skews {@code jdk.ExecutionSample}
 * towards wherever the JVM happens to be able to stop a thread. It is the better answer
 * where it works, and Linux is where servers actually run.
 *
 * <p>The trap: the {@code jdk.CPUTimeSample} event <em>type is registered on every
 * platform</em>, including Windows, where the sampler behind it does not exist and no
 * samples are ever produced. Asking Flight Recorder whether the event type is present is
 * therefore not enough to decide — the platform has to be checked too.
 */
public enum SamplerFlavour {

	/** Unbiased, Linux only, still an experimental JDK feature. */
	CPU_TIME("jdk.CPUTimeSample", "wttl.sampler.cpu_time"),

	/** Works everywhere, but samples land on safepoints rather than where time is truly spent. */
	EXECUTION("jdk.ExecutionSample", "wttl.sampler.execution");

	private final String eventName;
	private final String translationKey;

	SamplerFlavour(String eventName, String translationKey) {
		this.eventName = eventName;
		this.translationKey = translationKey;
	}

	public String eventName() {
		return this.eventName;
	}

	public String translationKey() {
		return this.translationKey;
	}

	public boolean isBiased() {
		return this == EXECUTION;
	}

	/** Picks the most accurate sampler this JVM can actually deliver. */
	public static SamplerFlavour detect() {
		return isLinux() && isRegistered(CPU_TIME.eventName) ? CPU_TIME : EXECUTION;
	}

	private static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	}

	private static boolean isRegistered(String eventName) {
		try {
			return FlightRecorder.getFlightRecorder().getEventTypes().stream()
					.anyMatch(type -> type.getName().equals(eventName));
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Applies the requested rate. The two events are configured differently: execution
	 * sampling takes a period between samples, CPU time sampling takes a throttle expressed
	 * as a ceiling on samples per second.
	 */
	public void configureRate(EventSettings settings, int samplesPerSecond) {
		if (this == CPU_TIME) {
			settings.with("throttle", samplesPerSecond + "/s");
		} else {
			settings.withPeriod(Duration.ofNanos(1_000_000_000L / samplesPerSecond));
		}
	}
}
