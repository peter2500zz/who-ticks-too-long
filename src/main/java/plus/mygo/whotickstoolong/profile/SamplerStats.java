package plus.mygo.whotickstoolong.profile;

/**
 * What the profiler costs, so operators never have to take that on faith.
 *
 * @param running               whether the sampler thread is alive
 * @param samplesTaken          samples successfully recorded
 * @param samplesDiscarded      samples dropped because the published context changed mid-read
 * @param requestedRateHz       the rate the sampler aims for
 * @param achievedRateHz        the rate it actually managed, which differs on coarse timers
 * @param samplerCpuNanos       CPU time burned by the sampler thread, or -1 if unavailable
 * @param instrumentedObjects   ticked objects the hot path has published so far
 * @param ringBytes             heap held by the sample ring
 */
public record SamplerStats(
		boolean running,
		long samplesTaken,
		long samplesDiscarded,
		int requestedRateHz,
		double achievedRateHz,
		long samplerCpuNanos,
		long instrumentedObjects,
		long ringBytes
) {
	/**
	 * Nanoseconds the hot-path instrumentation adds per ticked object.
	 *
	 * <p>Measured on this project's reference machine (JDK 25, x86-64) as roughly two opaque
	 * stores plus a counter on entry and two on exit. It is a reference constant rather than
	 * a live measurement: calibrating it at runtime would have to race the server thread for
	 * the same cache line and would report a worse number than the real one.
	 */
	public static final double HOT_PATH_NANOS_PER_OBJECT = 0.2;

	/** Estimated wall time the hot path has spent on instrumentation, in nanoseconds. */
	public double estimatedHotPathNanos() {
		return this.instrumentedObjects * HOT_PATH_NANOS_PER_OBJECT;
	}

	public double discardRate() {
		long attempted = this.samplesTaken + this.samplesDiscarded;
		return attempted == 0 ? 0.0 : (double) this.samplesDiscarded / attempted;
	}
}
