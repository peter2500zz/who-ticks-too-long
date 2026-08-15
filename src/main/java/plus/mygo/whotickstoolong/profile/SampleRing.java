package plus.mygo.whotickstoolong.profile;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A fixed-size ring of raw samples covering the longest supported window.
 *
 * <p>Keeping raw samples rather than pre-aggregated buckets means every window is answered
 * exactly, from one allocation, and adding a new window later costs nothing. At the default
 * rate the ring holds five minutes of samples in a few megabytes, allocated only while
 * recording is on and released the moment it is switched off.
 *
 * <p>Appends and aggregation are guarded by the monitor. The sampler takes it a thousand
 * times a second for a handful of array stores, which is far below anything measurable,
 * and aggregation only runs when an operator asks for a report.
 */
public final class SampleRing {

	private final int capacity;
	private final long[] nanos;
	private final long[] chunkKeys;
	private final int[] dimensions;
	private final byte[] phases;

	private int size;
	private int cursor;

	public SampleRing(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive, was " + capacity);
		}
		this.capacity = capacity;
		this.nanos = new long[capacity];
		this.chunkKeys = new long[capacity];
		this.dimensions = new int[capacity];
		this.phases = new byte[capacity];
	}

	public int capacity() {
		return this.capacity;
	}

	public synchronized int size() {
		return this.size;
	}

	/** Approximate heap footprint, reported by {@code /wttl status} as part of self-monitoring. */
	public long approximateBytes() {
		return (long) this.capacity * (Long.BYTES + Long.BYTES + Integer.BYTES + Byte.BYTES);
	}

	public synchronized void append(long nano, int dimension, long chunkKey, int phase) {
		this.nanos[this.cursor] = nano;
		this.chunkKeys[this.cursor] = chunkKey;
		this.dimensions[this.cursor] = dimension;
		this.phases[this.cursor] = (byte) phase;

		this.cursor = this.cursor + 1 == this.capacity ? 0 : this.cursor + 1;
		if (this.size < this.capacity) {
			this.size++;
		}
	}

	/**
	 * Groups the samples newer than {@code now - window} by chunk.
	 *
	 * <p>Walks backwards from the newest sample and stops at the first one older than the
	 * window, so a short window costs proportionally little even when the ring is full.
	 */
	public synchronized HeatReport aggregate(HeatWindow window, int limit, long now) {
		long cutoff = now - window.nanos();
		Int2ObjectOpenHashMap<Long2ObjectMap<Accumulator>> perDimension = new Int2ObjectOpenHashMap<>();

		int total = 0;
		int idle = 0;
		long oldestSeen = now;
		int index = this.cursor == 0 ? this.capacity - 1 : this.cursor - 1;

		for (int visited = 0; visited < this.size; visited++) {
			long nano = this.nanos[index];
			if (nano < cutoff) {
				break;
			}

			total++;
			oldestSeen = nano;
			long chunkKey = this.chunkKeys[index];
			if (chunkKey == TickContext.NO_CHUNK) {
				idle++;
			} else {
				Long2ObjectMap<Accumulator> chunks = perDimension.computeIfAbsent(
						this.dimensions[index], key -> new Long2ObjectOpenHashMap<>());
				Accumulator accumulator = chunks.get(chunkKey);
				if (accumulator == null) {
					accumulator = new Accumulator();
					chunks.put(chunkKey, accumulator);
				}
				accumulator.total++;
				accumulator.byPhase[this.phases[index]]++;
			}

			index = index == 0 ? this.capacity - 1 : index - 1;
		}

		List<HeatReport.ChunkHeat> collected = new ArrayList<>();
		perDimension.forEach((dimension, chunks) -> chunks.forEach((chunkKey, accumulator) ->
				collected.add(new HeatReport.ChunkHeat(dimension, chunkKey, accumulator.total, accumulator.byPhase))));
		collected.sort(Comparator.comparingInt(HeatReport.ChunkHeat::samples).reversed());

		List<HeatReport.ChunkHeat> hottest = collected.size() > limit
				? collected.subList(0, limit)
				: collected;

		return new HeatReport(window, total == 0 ? 0L : now - oldestSeen, total, idle, List.copyOf(hottest));
	}

	private static final class Accumulator {
		private int total;
		private final int[] byPhase = new int[TickPhase.count()];
	}
}
