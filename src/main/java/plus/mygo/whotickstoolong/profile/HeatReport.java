package plus.mygo.whotickstoolong.profile;

import java.util.List;

/**
 * The result of aggregating raw samples over one rolling window.
 *
 * @param window        the window that was asked for
 * @param spanNanos     the wall time actually covered by the samples that were found
 * @param totalSamples  every sample in the window, including idle ones
 * @param idleSamples   samples taken while the server thread was not ticking any chunk
 * @param chunks        the hottest chunks, already sorted, longest first
 */
public record HeatReport(
		HeatWindow window,
		long spanNanos,
		int totalSamples,
		int idleSamples,
		List<ChunkHeat> chunks
) {
	/** Samples taken while the server thread was inside some chunk's tick. */
	public int busySamples() {
		return this.totalSamples - this.idleSamples;
	}

	/** Share of wall time the server thread spent ticking chunks at all, in the range 0..1. */
	public double busyShare() {
		return this.totalSamples == 0 ? 0.0 : (double) this.busySamples() / this.totalSamples;
	}

	/**
	 * One chunk's slice of the window.
	 *
	 * @param byPhase sample counts indexed by {@link TickPhase#ordinal()}
	 */
	public record ChunkHeat(int dimensionId, long chunkKey, int samples, int[] byPhase) {

		/** Share of total wall time in the window, in the range 0..1. */
		public double shareOfWall(HeatReport report) {
			return report.totalSamples == 0 ? 0.0 : (double) this.samples / report.totalSamples;
		}

		/** Share of the time the server thread spent ticking chunks, in the range 0..1. */
		public double shareOfBusy(HeatReport report) {
			int busy = report.busySamples();
			return busy == 0 ? 0.0 : (double) this.samples / busy;
		}

		/** The phase that accounts for the most of this chunk's cost. */
		public TickPhase dominantPhase() {
			int best = 0;
			int bestCount = -1;
			for (int i = 0; i < this.byPhase.length; i++) {
				if (this.byPhase[i] > bestCount) {
					bestCount = this.byPhase[i];
					best = i;
				}
			}
			return TickPhase.byOrdinal(best);
		}
	}
}
