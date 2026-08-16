package plus.mygo.whotickstoolong.profile.deep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.WhoTicksTooLong;
import plus.mygo.whotickstoolong.profile.TickPhase;

/**
 * One deep inspection of one chunk: opens a Flight Recorder stream, consumes the object tick
 * events the hot path emits, and keeps a running breakdown.
 *
 * <p>Events are consumed in process on JFR's own streaming thread, so nothing is ever written
 * to disk and the server thread does no aggregation work at all.
 *
 * <p>Distinct-instance tracking is bounded. A chunk full of short-lived entities would
 * otherwise grow the map without limit, so once the bound is reached new instances stop being
 * tracked, existing ones keep accumulating, and the report says the bound was hit.
 */
public final class DeepSession implements AutoCloseable {

	private static final int MAX_TRACKED_INSTANCES = 4096;

	/** Reports samples the JVM had to drop, so a thin report can be explained rather than guessed at. */
	private static final String LOST_SAMPLES_EVENT = "jdk.CPUTimeSamplesLost";

	private final int dimensionId;
	private final long chunkKey;
	private final long startedNanos;
	private final long expiresAtNanos;
	private final RecordingStream stream;
	private final @Nullable MethodSampleCollector collector;

	private final Map<TypeKey, TypeAccumulator> types = new HashMap<>();
	private final Map<InstanceKey, InstanceAccumulator> instances = new HashMap<>();

	private long ticksObserved;
	private long observedEvents;
	private long totalTickNanos;
	private boolean instanceCapReached;

	/**
	 * @param duration how long to run, or null to run until stopped by hand
	 * @param flavour  the stack sampler to run alongside, or null for object costs only
	 * @throws java.lang.UnsupportedOperationException if Flight Recorder is unavailable
	 */
	public DeepSession(int dimensionId, long chunkKey, @Nullable Duration duration,
			@Nullable SamplerFlavour flavour, int sampleRateHz) {
		this.dimensionId = dimensionId;
		this.chunkKey = chunkKey;
		this.startedNanos = System.nanoTime();
		this.expiresAtNanos = duration == null ? Long.MAX_VALUE : this.startedNanos + duration.toNanos();

		this.stream = new RecordingStream();
		// Without this JFR drops events shorter than its default threshold, which is most
		// of them: a cheap object tick is measured in microseconds.
		this.stream.enable(ObjectTickEvent.NAME).withoutThreshold();
		this.stream.onEvent(ObjectTickEvent.NAME, this::onEvent);

		if (flavour == null) {
			this.collector = null;
		} else {
			// Constructed on the server thread, whose name is how samples get filtered down
			// to the one thread that ticks chunks.
			this.collector = new MethodSampleCollector(flavour, Thread.currentThread().getName());
			flavour.configureRate(this.stream.enable(flavour.eventName()), sampleRateHz);
			this.stream.onEvent(flavour.eventName(), this.collector::recordSample);

			if (flavour == SamplerFlavour.CPU_TIME) {
				this.stream.enable(LOST_SAMPLES_EVENT);
				this.stream.onEvent(LOST_SAMPLES_EVENT,
						event -> this.collector.recordLost(event.getInt("lostSamples")));
			}
		}

		this.stream.startAsync();
	}

	private void onEvent(RecordedEvent event) {
		// Events already in flight when a previous inspection ended can still arrive.
		if (event.getLong("chunkKey") != this.chunkKey) {
			return;
		}

		String kind = event.getString("kind");
		String type = event.getString("objectType");
		int objectId = event.getInt("objectId");
		int x = event.getInt("x");
		int y = event.getInt("y");
		int z = event.getInt("z");
		long nanos = event.getDuration().toNanos();

		// The window this object occupied is what lets a stack sample be tied back to it.
		MethodSampleCollector methods = this.collector;
		if (methods != null) {
			methods.recordWindow(event, type);
		}

		synchronized (this) {
			this.observedEvents++;
			this.totalTickNanos += nanos;

			this.types.computeIfAbsent(new TypeKey(kind, type), key -> new TypeAccumulator())
					.record(nanos);

			// Movable objects change position every tick, so only fixed ones can be
			// identified by where they are; the rest are identified by their entity id.
			boolean movable = TickPhase.ENTITY.displayName().equals(kind);
			InstanceKey instanceKey = movable
					? new InstanceKey(kind, type, objectId, 0, 0, 0)
					: new InstanceKey(kind, type, 0, x, y, z);

			InstanceAccumulator instance = this.instances.get(instanceKey);
			if (instance == null) {
				if (this.instances.size() >= MAX_TRACKED_INSTANCES) {
					this.instanceCapReached = true;
					return;
				}
				instance = new InstanceAccumulator();
				this.instances.put(instanceKey, instance);
			}
			instance.record(nanos, x, y, z);
		}
	}

	/** Called once per tick of the inspected dimension, so cost per tick is exact. */
	public synchronized void onTargetDimensionTick() {
		this.ticksObserved++;
	}

	public boolean hasExpired(long now) {
		return now >= this.expiresAtNanos;
	}

	public int dimensionId() {
		return this.dimensionId;
	}

	public long chunkKey() {
		return this.chunkKey;
	}

	/** @return null when this inspection was started without method sampling */
	public @Nullable MethodBreakdown methodBreakdown(int limit) {
		MethodSampleCollector methods = this.collector;
		return methods == null ? null : methods.breakdown(limit);
	}

	public synchronized ObjectBreakdown breakdown(int typeLimit, int instanceLimit) {
		List<ObjectBreakdown.TypeRow> typeRows = new ArrayList<>(this.types.size());
		this.types.forEach((key, accumulator) -> typeRows.add(new ObjectBreakdown.TypeRow(
				key.kind(), key.type(), accumulator.ticks, accumulator.totalNanos, accumulator.maxNanos)));
		typeRows.sort(Comparator.comparingLong(ObjectBreakdown.TypeRow::totalNanos).reversed());

		List<ObjectBreakdown.InstanceRow> instanceRows = new ArrayList<>(this.instances.size());
		this.instances.forEach((key, accumulator) -> instanceRows.add(new ObjectBreakdown.InstanceRow(
				key.kind(), key.type(), key.objectId(), accumulator.x, accumulator.y, accumulator.z,
				accumulator.ticks, accumulator.totalNanos)));
		instanceRows.sort(Comparator.comparingLong(ObjectBreakdown.InstanceRow::totalNanos).reversed());

		return new ObjectBreakdown(
				this.dimensionId,
				this.chunkKey,
				System.nanoTime() - this.startedNanos,
				this.ticksObserved,
				this.observedEvents,
				this.totalTickNanos,
				this.instanceCapReached,
				List.copyOf(typeRows.subList(0, Math.min(typeLimit, typeRows.size()))),
				List.copyOf(instanceRows.subList(0, Math.min(instanceLimit, instanceRows.size()))));
	}

	@Override
	public void close() {
		try {
			this.stream.close();
		} catch (RuntimeException e) {
			WhoTicksTooLong.LOGGER.warn("Failed to close the deep inspection recording stream", e);
		}
	}

	private record TypeKey(String kind, String type) {
	}

	private record InstanceKey(String kind, String type, int objectId, int x, int y, int z) {
	}

	private static final class TypeAccumulator {
		private long ticks;
		private long totalNanos;
		private long maxNanos;

		void record(long nanos) {
			this.ticks++;
			this.totalNanos += nanos;
			if (nanos > this.maxNanos) {
				this.maxNanos = nanos;
			}
		}
	}

	private static final class InstanceAccumulator {
		private long ticks;
		private long totalNanos;
		private int x;
		private int y;
		private int z;

		void record(long nanos, int x, int y, int z) {
			this.ticks++;
			this.totalNanos += nanos;
			// Keep the most recent position so a moving entity is reported where it is now.
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}
}
