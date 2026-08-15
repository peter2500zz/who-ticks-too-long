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

	/** Long enough for JFR to hand over events emitted just before the probe was disarmed. */
	private static final long DRAIN_NANOS = Duration.ofSeconds(3).toNanos();

	private static final DeepProfiler INSTANCE = new DeepProfiler();

	private @Nullable DeepSession session;
	private @Nullable ObjectBreakdown finished;
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
	 * @param duration how long to inspect, or null to run until stopped by hand
	 * @throws IllegalStateException if an inspection is already running
	 * @throws UnsupportedOperationException if Flight Recorder is unavailable on this JVM
	 */
	public synchronized void start(int dimensionId, long chunkKey, @Nullable Duration duration) {
		if (this.session != null) {
			throw new IllegalStateException("an inspection is already running");
		}

		DeepSession fresh;
		try {
			fresh = new DeepSession(dimensionId, chunkKey, duration);
		} catch (RuntimeException e) {
			throw new UnsupportedOperationException(
					"Flight Recorder is not available on this JVM: " + e.getMessage(), e);
		}

		this.session = fresh;
		this.finished = null;
		this.draining = false;
		DeepProbe.arm(dimensionId, chunkKey);

		WhoTicksTooLong.LOGGER.info("Deep inspection armed on chunk key {} in dimension {}{}",
				chunkKey, dimensionId,
				duration == null ? " until stopped" : " for " + duration.toSeconds() + "s");
	}

	/** Stops immediately, keeping whatever has been gathered so far. */
	public synchronized @Nullable ObjectBreakdown stop() {
		DeepSession current = this.session;
		if (current == null) {
			return null;
		}

		DeepProbe.disarm();
		ObjectBreakdown breakdown = current.breakdown(Integer.MAX_VALUE, Integer.MAX_VALUE);
		current.close();

		this.session = null;
		this.draining = false;
		this.finished = breakdown;

		WhoTicksTooLong.LOGGER.info("Deep inspection stopped after {} object ticks", breakdown.observedEvents());
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
			current.close();
			this.session = null;
			this.draining = false;
			WhoTicksTooLong.LOGGER.info("Deep inspection finished: {} object ticks recorded",
					this.finished.observedEvents());
		}
	}

	public synchronized void shutdown() {
		if (this.session != null) {
			this.stop();
		}
		this.finished = null;
	}
}
