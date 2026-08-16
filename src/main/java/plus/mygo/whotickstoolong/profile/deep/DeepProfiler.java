package plus.mygo.whotickstoolong.profile.deep;

import java.time.Duration;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.WhoTicksTooLong;

/**
 * Owns the object-breakdown level of monitoring: at most one chunk under inspection at a
 * time, for a bounded stretch of time by default.
 *
 * <p>Inspections are time limited so that an operator who forgets to switch one off does not
 * leave the server paying for events indefinitely. When the clock runs out the hot path is
 * silenced immediately, but the recording stays open for a short grace period so events
 * already in flight are still counted, and the finished breakdown is kept so it can be read
 * after the fact.
 */
public final class DeepProfiler {

	public static final Duration DEFAULT_DURATION = Duration.ofSeconds(30);
	public static final int MAX_DURATION_SECONDS = 600;

	/**
	 * Requested stack samples per second. Both samplers treat this as a ceiling and the CPU
	 * time one in particular delivers well under it, so reports quote counts, never a rate.
	 */
	public static final int METHOD_SAMPLE_RATE_HZ = 1000;

	/** Long enough for JFR to hand over events emitted just before the probe was disarmed. */
	private static final long DRAIN_NANOS = Duration.ofSeconds(3).toNanos();

	private static final DeepProfiler INSTANCE = new DeepProfiler();

	/** Rows handed to a completion listener, sized for chat rather than for a file. */
	private static final int REPORT_BACK_ROWS = 10;

	private @Nullable DeepSession session;
	private @Nullable ObjectBreakdown finished;
	private @Nullable MethodBreakdown finishedMethods;
	private @Nullable DeepCompletion completion;
	private boolean draining;
	private long drainUntilNanos;

	private DeepProfiler() {
	}

	public static DeepProfiler get() {
		return INSTANCE;
	}

	public synchronized boolean isRunning() {
		return this.session != null;
	}

	public synchronized @Nullable ObjectBreakdown lastFinished() {
		return this.finished;
	}

	/**
	 * @param duration    how long to inspect, or null to run until stopped by hand
	 * @param withMethods also sample stacks, which needs the object tick windows this
	 *                    inspection produces and so cannot be turned on independently
	 * @param completion  notified once results are final, or null to stay quiet
	 * @return the sampler chosen for method sampling, or null if it was not requested
	 * @throws IllegalStateException if an inspection is already running
	 * @throws UnsupportedOperationException if Flight Recorder is unavailable on this JVM
	 */
	public synchronized @Nullable SamplerFlavour start(int dimensionId, long chunkKey,
			@Nullable Duration duration, boolean withMethods, @Nullable DeepCompletion completion) {
		if (this.session != null) {
			throw new IllegalStateException("an inspection is already running");
		}

		SamplerFlavour flavour = withMethods ? SamplerFlavour.detect() : null;
		DeepSession fresh;
		try {
			fresh = new DeepSession(dimensionId, chunkKey, duration, flavour, METHOD_SAMPLE_RATE_HZ);
		} catch (RuntimeException e) {
			throw new UnsupportedOperationException(
					"Flight Recorder is not available on this JVM: " + e.getMessage(), e);
		}

		this.session = fresh;
		this.finished = null;
		this.finishedMethods = null;
		this.completion = completion;
		this.draining = false;
		DeepProbe.arm(dimensionId, chunkKey);

		WhoTicksTooLong.LOGGER.info("Deep inspection armed on chunk key {} in dimension {}{}{}",
				chunkKey, dimensionId,
				duration == null ? " until stopped" : " for " + duration.toSeconds() + "s",
				flavour == null ? "" : ", sampling methods with " + flavour.eventName());
		return flavour;
	}

	/** Stops immediately, keeping whatever has been gathered so far. */
	public synchronized @Nullable ObjectBreakdown stop() {
		DeepSession current = this.session;
		if (current == null) {
			return null;
		}

		DeepProbe.disarm();
		ObjectBreakdown breakdown = current.breakdown(Integer.MAX_VALUE, Integer.MAX_VALUE);
		this.finishedMethods = current.methodBreakdown(Integer.MAX_VALUE);
		current.close();

		this.session = null;
		this.draining = false;
		this.finished = breakdown;

		WhoTicksTooLong.LOGGER.info("Deep inspection stopped after {} object ticks", breakdown.observedEvents());
		this.notifyCompletion();
		return breakdown;
	}

	/** @return the live breakdown if an inspection is running, otherwise the last finished one */
	public synchronized @Nullable ObjectBreakdown breakdown(int typeLimit, int instanceLimit) {
		DeepSession current = this.session;
		if (current != null) {
			return current.breakdown(typeLimit, instanceLimit);
		}
		// The finished one is stored complete, so it has to be trimmed on the way out.
		return this.finished == null ? null : this.finished.limited(typeLimit, instanceLimit);
	}

	/** @return null when the inspection ran without method sampling, or none has run */
	public synchronized @Nullable MethodBreakdown methodBreakdown(int limit) {
		DeepSession current = this.session;
		if (current != null) {
			return current.methodBreakdown(limit);
		}
		return this.finishedMethods == null ? null : this.finishedMethods.limited(limit);
	}

	/**
	 * Drives expiry and per-tick counting. Called once per level per tick from the server
	 * thread, so state changes here need no extra synchronisation with the game.
	 */
	public synchronized void onLevelTickStart(int dimensionId) {
		DeepSession current = this.session;
		if (current == null) {
			return;
		}

		if (dimensionId == current.dimensionId()) {
			current.onTargetDimensionTick();
		}
		DeepProbe.onLevelTickStart(dimensionId);

		long now = System.nanoTime();
		if (!this.draining && current.hasExpired(now)) {
			// Silence the hot path at once; keep the stream open to catch stragglers.
			DeepProbe.disarm();
			this.draining = true;
			this.drainUntilNanos = now + DRAIN_NANOS;
			return;
		}

		if (this.draining && now >= this.drainUntilNanos) {
			this.finished = current.breakdown(Integer.MAX_VALUE, Integer.MAX_VALUE);
			this.finishedMethods = current.methodBreakdown(Integer.MAX_VALUE);
			current.close();
			this.session = null;
			this.draining = false;
			WhoTicksTooLong.LOGGER.info("Deep inspection finished: {} object ticks recorded",
					this.finished.observedEvents());
			this.notifyCompletion();
		}
	}

	/**
	 * Hands the finished results to whoever asked for them, at most once per inspection.
	 *
	 * <p>A listener that throws must not leave the profiler wedged or take a server tick down
	 * with it, so failures are logged and swallowed.
	 */
	private void notifyCompletion() {
		DeepCompletion listener = this.completion;
		this.completion = null;
		if (listener == null || this.finished == null) {
			return;
		}

		try {
			listener.onFinished(
					this.finished.limited(REPORT_BACK_ROWS, REPORT_BACK_ROWS),
					this.finishedMethods == null ? null : this.finishedMethods.limited(REPORT_BACK_ROWS));
		} catch (RuntimeException e) {
			WhoTicksTooLong.LOGGER.warn("Reporting a finished inspection failed", e);
		}
	}

	public synchronized void shutdown() {
		// Nothing to report into a server that is going away.
		this.completion = null;
		if (this.session != null) {
			this.stop();
		}
		this.finished = null;
		this.finishedMethods = null;
	}
}
