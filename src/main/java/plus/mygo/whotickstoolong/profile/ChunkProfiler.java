package plus.mygo.whotickstoolong.profile;

import java.time.Duration;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.WhoTicksTooLong;

/**
 * Owns the chunk-heat level of monitoring: the sample ring, the sampler thread, and the
 * switch that decides whether the hot path publishes anything at all.
 *
 * <p>Nothing here is allocated until monitoring is switched on, and everything is released
 * when it is switched off, so an idle install costs no heap and no threads.
 */
public final class ChunkProfiler {

	/**
	 * Samples per second. A thousand gives roughly fifty readings per server tick, which is
	 * plenty to rank chunks, while the sampler thread stays far below one percent of a core.
	 */
	public static final int DEFAULT_SAMPLE_RATE_HZ = 1000;

	/**
	 * How far back whole samples are kept. Windows within this reach are answered exactly,
	 * down to the individual sample.
	 */
	public static final Duration RAW_RETENTION = Duration.ofMinutes(5);

	/**
	 * How far back counted history reaches. Beyond {@link #RAW_RETENTION} samples are folded
	 * into buckets, which answer a ranking just as well for a fraction of the memory.
	 */
	public static final Duration RETENTION = Duration.ofHours(6);

	private static final ChunkProfiler INSTANCE = new ChunkProfiler();

	private final DimensionTable dimensions = new DimensionTable();

	private @Nullable SampleRing ring;
	private @Nullable BucketRing buckets;
	private @Nullable ChunkHeatSampler sampler;
	private int sampleRateHz = DEFAULT_SAMPLE_RATE_HZ;

	private ChunkProfiler() {
	}

	public static ChunkProfiler get() {
		return INSTANCE;
	}

	public DimensionTable dimensions() {
		return this.dimensions;
	}

	public boolean isEnabled() {
		return TickContext.isRecording();
	}

	public int sampleRateHz() {
		return this.sampleRateHz;
	}

	/** @return false if it was already running */
	public synchronized boolean enable() {
		if (this.isEnabled()) {
			return false;
		}

		int capacity = Math.multiplyExact(Math.toIntExact(RAW_RETENTION.toSeconds()), this.sampleRateHz);
		SampleRing freshRing = new SampleRing(capacity);
		BucketRing freshBuckets = new BucketRing(RETENTION);
		ChunkHeatSampler freshSampler = new ChunkHeatSampler(freshRing, freshBuckets, this.sampleRateHz);

		this.ring = freshRing;
		this.buckets = freshBuckets;
		this.sampler = freshSampler;

		// The hot path must only start publishing once there is somewhere to publish to.
		TickContext.setRecording(true);
		freshSampler.start();

		WhoTicksTooLong.LOGGER.info(
				"Chunk heat monitoring on: {} Hz, {} samples buffered, {} KiB",
				this.sampleRateHz, capacity, freshRing.approximateBytes() / 1024L);
		return true;
	}

	/** @return false if it was already stopped */
	public synchronized boolean disable() {
		if (!this.isEnabled()) {
			return false;
		}

		// Silence the hot path first so the sampler cannot record anything after it stops.
		TickContext.setRecording(false);

		ChunkHeatSampler current = this.sampler;
		if (current != null) {
			current.stop();
		}
		this.sampler = null;
		this.ring = null;
		this.buckets = null;

		WhoTicksTooLong.LOGGER.info("Chunk heat monitoring off; sample buffer released.");
		return true;
	}

	/**
	 * Answers from whichever tier reaches far enough.
	 *
	 * <p>Short windows come from whole samples, which is exact to the individual sample.
	 * Longer ones come from counted buckets, which give the same ranking because a ranking is
	 * a comparison of counts, and cost a fraction of the memory to keep.
	 *
	 * @return null when monitoring is off, so the caller can say so rather than show zeroes
	 */
	public @Nullable HeatReport report(Duration window, int limit) {
		long now = System.nanoTime();
		if (window.compareTo(RAW_RETENTION) <= 0) {
			SampleRing current = this.ring;
			return current == null ? null : current.aggregate(window, limit, now);
		}

		BucketRing counted = this.buckets;
		return counted == null ? null : counted.aggregate(window, limit, now);
	}

	public @Nullable SamplerStats stats() {
		ChunkHeatSampler current = this.sampler;
		return current == null ? null : current.stats();
	}

	/**
	 * Publishes which level the server thread is about to tick.
	 *
	 * <p>Called once per level per tick rather than once per ticked object, which is why the
	 * per-object hot path only has to carry a chunk and a phase.
	 */
	public void onLevelTickStart(int dimensionId) {
		if (!this.isEnabled()) {
			return;
		}
		TickContext.setDimension(dimensionId);
	}

	/** Releases everything on shutdown so a reloading server never leaks a sampler thread. */
	public synchronized void shutdown() {
		if (this.isEnabled()) {
			this.disable();
		}
	}
}
