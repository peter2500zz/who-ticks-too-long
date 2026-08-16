package plus.mygo.whotickstoolong.profile.deep;

import java.util.List;

/**
 * Where the time inside an inspected chunk actually went, at method granularity.
 *
 * @param sampler             which JFR sampler produced these, for the reader to judge them by
 * @param biased              true when the sampler lands on safepoints rather than real hot spots
 * @param samplesOnThread     stack samples seen on the server thread during the inspection
 * @param samplesInChunk      of those, the ones that fell inside a tick of the inspected chunk
 * @param lostSamples         samples the JVM reported dropping, or -1 when not reported
 * @param bufferCapReached    true if the join buffers filled and later data was discarded
 */
public record MethodBreakdown(
		SamplerFlavour sampler,
		boolean biased,
		int samplesOnThread,
		int samplesInChunk,
		int lostSamples,
		boolean bufferCapReached,
		List<MethodRow> methods
) {
	/**
	 * How much of the server thread's sampled time landed in this chunk. A low share is not
	 * an error: it just means the chunk is a small part of what the server is doing.
	 */
	public double chunkShareOfThread() {
		return this.samplesOnThread == 0 ? 0.0 : (double) this.samplesInChunk / this.samplesOnThread;
	}

	/** The same breakdown with only the hottest methods kept; totals stay whole. */
	public MethodBreakdown limited(int limit) {
		return new MethodBreakdown(this.sampler, this.biased, this.samplesOnThread,
				this.samplesInChunk, this.lostSamples, this.bufferCapReached,
				this.methods.subList(0, Math.min(limit, this.methods.size())));
	}

	/**
	 * One method, ranked by the samples that caught it running.
	 *
	 * @param dominantType        the object type most often being ticked when it was sampled
	 * @param representativeStack the most common call path leading to it, innermost first
	 */
	public record MethodRow(
			String method,
			String dominantType,
			int samples,
			List<String> representativeStack
	) {
		public double shareOf(MethodBreakdown breakdown) {
			return breakdown.samplesInChunk == 0 ? 0.0 : (double) this.samples / breakdown.samplesInChunk;
		}
	}
}
