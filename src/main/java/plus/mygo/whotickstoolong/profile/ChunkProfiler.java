package plus.mygo.whotickstoolong.profile;

import net.minecraft.server.level.ServerLevel;
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

	private static final ChunkProfiler INSTANCE = new ChunkProfiler();

	private final DimensionTable dimensions = new DimensionTable();

	private @Nullable SampleRing ring;
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

		int capacity = Math.multiplyExact(HeatWindow.longest().seconds(), this.sampleRateHz);
		SampleRing freshRing = new SampleRing(capacity);
		ChunkHeatSampler freshSampler = new ChunkHeatSampler(freshRing, this.sampleRateHz);

		this.ring = freshRing;
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

		WhoTicksTooLong.LOGGER.info("Chunk heat monitoring off; sample buffer released.");
		return true;
	}

	/** @return null when monitoring is off, so the caller can say so rather than show zeroes */
	public @Nullable HeatReport report(HeatWindow window, int limit) {
		SampleRing current = this.ring;
		return current == null ? null : current.aggregate(window, limit, System.nanoTime());
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
	public void onLevelTickStart(ServerLevel level) {
		if (!this.isEnabled()) {
			return;
		}
		TickContext.setDimension(this.dimensions.idOf(level.dimension()));
	}

	/** Releases everything on shutdown so a reloading server never leaks a sampler thread. */
	public synchronized void shutdown() {
		if (this.isEnabled()) {
			this.disable();
		}
	}
}
