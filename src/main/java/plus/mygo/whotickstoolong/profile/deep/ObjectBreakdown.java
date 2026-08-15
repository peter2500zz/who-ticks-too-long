package plus.mygo.whotickstoolong.profile.deep;

import java.util.List;
import plus.mygo.whotickstoolong.profile.TickPhase;

/**
 * What a deep inspection found inside one chunk: which kinds of object cost the most in
 * aggregate, and which individual objects are the worst offenders.
 *
 * @param sessionNanos        wall time the inspection has been running
 * @param ticksObserved       level ticks the inspected dimension has run during it
 * @param observedEvents      individual object ticks recorded
 * @param totalTickNanos      summed duration of every recorded object tick
 * @param instanceCapReached  true if distinct-instance tracking hit its bound and stopped
 */
public record ObjectBreakdown(
		int dimensionId,
		long chunkKey,
		long sessionNanos,
		long ticksObserved,
		long observedEvents,
		long totalTickNanos,
		boolean instanceCapReached,
		List<TypeRow> types,
		List<InstanceRow> instances
) {
	/** The chunk's own cost per server tick, in milliseconds. The headline number. */
	public double millisPerTick() {
		return this.ticksObserved == 0L ? 0.0 : this.totalTickNanos / 1e6 / this.ticksObserved;
	}

	/**
	 * The same breakdown with only the worst offenders kept.
	 *
	 * <p>A finished inspection is stored complete so it can be re-read at any depth; the
	 * totals and shares stay those of the whole chunk, only the rows shown are trimmed.
	 */
	public ObjectBreakdown limited(int typeLimit, int instanceLimit) {
		return new ObjectBreakdown(
				this.dimensionId,
				this.chunkKey,
				this.sessionNanos,
				this.ticksObserved,
				this.observedEvents,
				this.totalTickNanos,
				this.instanceCapReached,
				this.types.subList(0, Math.min(typeLimit, this.types.size())),
				this.instances.subList(0, Math.min(instanceLimit, this.instances.size())));
	}

	/**
	 * One kind of object, summed.
	 *
	 * @param ticks      object ticks observed for this type
	 * @param maxNanos   the single most expensive tick seen, which exposes rare stalls that
	 *                   an average hides
	 */
	public record TypeRow(String kind, String type, long ticks, long totalNanos, long maxNanos) {
		public double shareOf(ObjectBreakdown breakdown) {
			return breakdown.totalTickNanos == 0L ? 0.0 : (double) this.totalNanos / breakdown.totalTickNanos;
		}

		public double averageMicros() {
			return this.ticks == 0L ? 0.0 : this.totalNanos / 1e3 / this.ticks;
		}

		public double millisPerTick(ObjectBreakdown breakdown) {
			return breakdown.ticksObserved == 0L ? 0.0 : this.totalNanos / 1e6 / breakdown.ticksObserved;
		}
	}

	/**
	 * One individual object.
	 *
	 * @param kind      the tick phase it was seen in, which also says whether it can move
	 * @param objectId  entity id for movable objects, otherwise 0
	 */
	public record InstanceRow(String kind, String type, int objectId, int x, int y, int z,
			long ticks, long totalNanos) {
		public double shareOf(ObjectBreakdown breakdown) {
			return breakdown.totalTickNanos == 0L ? 0.0 : (double) this.totalNanos / breakdown.totalTickNanos;
		}

		/** Movable objects are reported at their most recent position, not a fixed one. */
		public boolean isMovable() {
			return TickPhase.ENTITY.displayName().equals(this.kind);
		}
	}
}
