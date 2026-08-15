package plus.mygo.whotickstoolong.profile;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import plus.mygo.whotickstoolong.WhoTicksTooLong;

/**
 * Reads what the server thread is working on, at a steady rate, from off the server thread.
 *
 * <p>The sampler never blocks, never locks anything the server thread touches, and never
 * holds a reference to a chunk, level, or entity. Its whole interaction with the game is
 * four opaque reads of {@link TickContext}.
 *
 * <p>The interval carries a small random jitter. Without it a fixed rate can phase-lock
 * against the server's fixed 50 ms tick and keep landing on the same part of every tick,
 * which would quietly bias every report.
 */
public final class ChunkHeatSampler {

	/** Fraction of the interval used as jitter either side of the nominal sample time. */
	private static final double JITTER_FRACTION = 0.25;

	private final SampleRing ring;
	private final int rateHz;
	private final long intervalNanos;
	private final long jitterNanos;

	private volatile boolean running;
	private volatile Thread thread;
	private volatile long startedNanos;
	private volatile long lastSampleNanos;
	private volatile long samplesTaken;
	private volatile long samplesDiscarded;

	public ChunkHeatSampler(SampleRing ring, int rateHz) {
		if (rateHz <= 0) {
			throw new IllegalArgumentException("sample rate must be positive, was " + rateHz);
		}
		this.ring = ring;
		this.rateHz = rateHz;
		this.intervalNanos = 1_000_000_000L / rateHz;
		this.jitterNanos = Math.max(1L, (long) (this.intervalNanos * JITTER_FRACTION));
	}

	public void start() {
		if (this.running) {
			return;
		}
		this.running = true;
		this.startedNanos = System.nanoTime();

		Thread worker = new Thread(this::run, "wttl-chunk-sampler");
		worker.setDaemon(true);
		// Below normal: a profiler must never win a core away from the server thread.
		worker.setPriority(Thread.NORM_PRIORITY - 1);
		this.thread = worker;
		worker.start();
	}

	public void stop() {
		this.running = false;
		Thread worker = this.thread;
		if (worker != null) {
			LockSupport.unpark(worker);
			try {
				worker.join(1_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		this.thread = null;
	}

	private void run() {
		long next = System.nanoTime();
		while (this.running) {
			long jitter = ThreadLocalRandom.current().nextLong(-this.jitterNanos, this.jitterNanos + 1);
			next += this.intervalNanos + jitter;

			long delay = next - System.nanoTime();
			if (delay > 0L) {
				LockSupport.parkNanos(delay);
			} else {
				// Fell behind, most likely a coarse platform timer. Resynchronise rather
				// than spinning to catch up, which would burn a core for no extra accuracy.
				next = System.nanoTime();
			}

			if (this.running) {
				this.sample(System.nanoTime());
			}
		}
	}

	/**
	 * Takes one consistent reading of the published context.
	 *
	 * <p>The chunk is read either side of the phase and dimension. If it moved in between,
	 * the server thread switched objects mid-read and the three values may not describe the
	 * same piece of work, so the sample is dropped rather than misattributed. At the default
	 * rate this costs a fraction of a percent of samples.
	 */
	private void sample(long now) {
		long chunkKey = TickContext.readChunk();
		int phase = TickContext.readPhase();
		int dimension = TickContext.readDimension();

		if (TickContext.readChunk() != chunkKey) {
			this.samplesDiscarded++;
			return;
		}

		this.ring.append(now, dimension, chunkKey, phase);
		this.lastSampleNanos = now;
		this.samplesTaken++;
	}

	public SamplerStats stats() {
		long taken = this.samplesTaken;
		long span = this.lastSampleNanos - this.startedNanos;
		double achieved = span > 0L ? taken * 1_000_000_000.0 / span : 0.0;

		return new SamplerStats(
				this.running,
				taken,
				this.samplesDiscarded,
				this.rateHz,
				achieved,
				this.samplerCpuNanos(),
				TickContext.readEnterCount(),
				this.ring.approximateBytes());
	}

	/** Exact CPU time of the sampler thread, or -1 where the JVM does not expose it. */
	private long samplerCpuNanos() {
		Thread worker = this.thread;
		if (worker == null) {
			return -1L;
		}
		try {
			ThreadMXBean threads = ManagementFactory.getThreadMXBean();
			if (!threads.isThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled()) {
				return -1L;
			}
			return threads.getThreadCpuTime(worker.threadId());
		} catch (RuntimeException e) {
			WhoTicksTooLong.LOGGER.debug("Could not read sampler CPU time", e);
			return -1L;
		}
	}
}
