package plus.mygo.whotickstoolong.profile;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The long tier of history: samples folded into fixed time buckets.
 *
 * <p>Ranking chunks only ever asks what share of samples named each chunk, and a share is a
 * count. Old samples therefore do not need to be kept one by one; counting them into a bucket
 * as they arrive answers exactly the same question at a fraction of the memory. Sub-second
 * resolution is the only thing lost, and nothing queries it beyond the raw tier's reach.
 *
 * <p>The saving comes from concentration. A second of sampling produces a thousand samples but
 * usually touches only a few dozen chunks, because a few chunks dominate; the bucket stores one
 * entry per chunk rather than one per sample.
 *
 * <p>Sealed buckets hold parallel primitive arrays rather than maps. A map of a hundred entries
 * costs several times its own contents in object headers, and there are thousands of buckets.
 */
final class BucketRing {

	/** How much time one bucket covers. */
	static final Duration PERIOD = Duration.ofSeconds(10);

	private final long periodNanos = PERIOD.toNanos();
	private final Bucket[] buckets;
	private int cursor;
	private int size;

	/** Counts for the bucket still being filled, keyed by dimension and then by chunk. */
	private final Int2ObjectOpenHashMap<Long2ObjectMap<int[]>> open = new Int2ObjectOpenHashMap<>();
	private long openStartNanos;
	private int openTotal;
	private int openIdle;
	private boolean opened;

	BucketRing(Duration retention) {
		int capacity = Math.toIntExact(retention.toNanos() / this.periodNanos);
		if (capacity <= 0) {
			throw new IllegalArgumentException("retention must cover at least one bucket");
		}
		this.buckets = new Bucket[capacity];
	}

	Duration retention() {
		return Duration.ofNanos(this.periodNanos * this.buckets.length);
	}

	/** Counts one sample. Called for every sample, alongside the raw ring. */
	synchronized void record(long nano, int dimension, long chunkKey, int phase) {
		if (!this.opened) {
			this.openStartNanos = nano;
			this.opened = true;
		} else if (nano - this.openStartNanos >= this.periodNanos) {
			this.seal(nano);
			this.openStartNanos = nano;
		}

		this.openTotal++;
		if (chunkKey == TickContext.NO_CHUNK) {
			this.openIdle++;
			return;
		}

		int[] counts = this.open
				.computeIfAbsent(dimension, key -> new Long2ObjectOpenHashMap<>())
				.computeIfAbsent(chunkKey, key -> new int[TickPhase.count()]);
		counts[phase]++;
	}

	private void seal(long endNanos) {
		int entries = 0;
		for (Long2ObjectMap<int[]> chunks : this.open.values()) {
			entries += chunks.size();
		}

		long[] chunkKeys = new long[entries];
		int[] dimensions = new int[entries];
		int[] phaseCounts = new int[entries * TickPhase.count()];

		int index = 0;
		for (Int2ObjectOpenHashMap.Entry<Long2ObjectMap<int[]>> perDimension : this.open.int2ObjectEntrySet()) {
			for (Long2ObjectMap.Entry<int[]> perChunk : perDimension.getValue().long2ObjectEntrySet()) {
				chunkKeys[index] = perChunk.getLongKey();
				dimensions[index] = perDimension.getIntKey();
				System.arraycopy(perChunk.getValue(), 0, phaseCounts,
						index * TickPhase.count(), TickPhase.count());
				index++;
			}
		}

		this.buckets[this.cursor] = new Bucket(this.openStartNanos, endNanos,
				this.openTotal, this.openIdle, chunkKeys, dimensions, phaseCounts);
		this.cursor = this.cursor + 1 == this.buckets.length ? 0 : this.cursor + 1;
		if (this.size < this.buckets.length) {
			this.size++;
		}

		this.open.clear();
		this.openTotal = 0;
		this.openIdle = 0;
	}

	/**
	 * Sums the buckets overlapping the window, newest first, plus whatever is in the bucket
	 * still being filled.
	 *
	 * <p>A bucket is counted whole when it overlaps the cutoff at all. At ten seconds against
	 * windows measured in minutes the edge error is negligible, and rejecting a partly covered
	 * bucket would be a larger error than including it.
	 */
	synchronized HeatReport aggregate(Duration window, int limit, long now) {
		long cutoff = now - window.toNanos();
		Int2ObjectOpenHashMap<Long2ObjectMap<int[]>> merged = new Int2ObjectOpenHashMap<>();

		int total = this.openTotal;
		int idle = this.openIdle;
		long oldestSeen = this.opened ? this.openStartNanos : now;
		this.open.int2ObjectEntrySet().forEach(perDimension ->
				perDimension.getValue().long2ObjectEntrySet().forEach(perChunk ->
						add(merged, perDimension.getIntKey(), perChunk.getLongKey(),
								perChunk.getValue(), 0)));

		int index = this.cursor == 0 ? this.buckets.length - 1 : this.cursor - 1;
		for (int visited = 0; visited < this.size; visited++) {
			Bucket bucket = this.buckets[index];
			if (bucket == null || bucket.endNanos < cutoff) {
				break;
			}

			total += bucket.totalSamples;
			idle += bucket.idleSamples;
			oldestSeen = bucket.startNanos;
			for (int entry = 0; entry < bucket.chunkKeys.length; entry++) {
				add(merged, bucket.dimensions[entry], bucket.chunkKeys[entry],
						bucket.phaseCounts, entry * TickPhase.count());
			}

			index = index == 0 ? this.buckets.length - 1 : index - 1;
		}

		List<HeatReport.ChunkHeat> collected = new ArrayList<>();
		merged.int2ObjectEntrySet().forEach(perDimension ->
				perDimension.getValue().long2ObjectEntrySet().forEach(perChunk -> {
					int[] counts = perChunk.getValue();
					int sum = 0;
					for (int count : counts) {
						sum += count;
					}
					collected.add(new HeatReport.ChunkHeat(
							perDimension.getIntKey(), perChunk.getLongKey(), sum, counts));
				}));
		collected.sort(Comparator.comparingInt(HeatReport.ChunkHeat::samples).reversed());

		List<HeatReport.ChunkHeat> hottest = collected.size() > limit
				? collected.subList(0, limit)
				: collected;

		return new HeatReport(window, total == 0 ? 0L : now - oldestSeen, total, idle,
				List.copyOf(hottest));
	}

	private static void add(Int2ObjectOpenHashMap<Long2ObjectMap<int[]>> merged,
			int dimension, long chunkKey, int[] source, int offset) {
		int[] target = merged
				.computeIfAbsent(dimension, key -> new Long2ObjectOpenHashMap<>())
				.computeIfAbsent(chunkKey, key -> new int[TickPhase.count()]);
		for (int phase = 0; phase < TickPhase.count(); phase++) {
			target[phase] += source[offset + phase];
		}
	}

	synchronized long approximateBytes() {
		long bytes = 0L;
		for (Bucket bucket : this.buckets) {
			if (bucket != null) {
				bytes += (long) bucket.chunkKeys.length * (Long.BYTES + Integer.BYTES)
						+ (long) bucket.phaseCounts.length * Integer.BYTES;
			}
		}
		return bytes;
	}

	private record Bucket(long startNanos, long endNanos, int totalSamples, int idleSamples,
			long[] chunkKeys, int[] dimensions, int[] phaseCounts) {
	}
}
