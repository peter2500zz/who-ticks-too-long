package plus.mygo.whotickstoolong.profile.deep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;

/**
 * Attributes stack samples to the chunk that was being ticked when they were taken.
 *
 * <p>Sampling a thread from outside cannot say which chunk it was working on: by the time a
 * cross-thread stack capture completes, the server thread has moved through several objects.
 * The way around it is that both the object tick events and the stack samples are timestamped
 * by Flight Recorder on the same clock, so a sample can simply be tested against the time
 * windows of the objects the chunk ticked. No clock calibration is involved and nothing has
 * to be captured atomically.
 *
 * <p>Both sides are buffered during the inspection and joined once, when a report is asked
 * for. Buffers are bounded; if they fill, collection stops and the report says so rather than
 * quietly reporting a partial picture as if it were whole.
 */
final class MethodSampleCollector {

	private static final int MAX_WINDOWS = 300_000;
	private static final int MAX_SAMPLES = 200_000;

	/** How deep a representative call path is worth keeping for a chat report. */
	private static final int STACK_DEPTH = 8;

	private final SamplerFlavour flavour;
	private final String serverThreadName;

	private final List<Window> windows = new ArrayList<>();
	private final List<Sample> samples = new ArrayList<>();

	/** Folded call path to its id, so each distinct stack is stored once. */
	private final Map<String, Integer> stackIds = new HashMap<>();
	private final List<List<String>> stacksById = new ArrayList<>();

	private int samplesOnThread;
	private int lostSamples = -1;
	private boolean capReached;

	MethodSampleCollector(SamplerFlavour flavour, String serverThreadName) {
		this.flavour = flavour;
		this.serverThreadName = serverThreadName;
	}

	static long epochNanos(Instant instant) {
		return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
	}

	/** Records the time window of one object tick, taken from the object tick event itself. */
	synchronized void recordWindow(RecordedEvent event, String objectType) {
		if (this.windows.size() >= MAX_WINDOWS) {
			this.capReached = true;
			return;
		}
		this.windows.add(new Window(
				epochNanos(event.getStartTime()), epochNanos(event.getEndTime()), objectType));
	}

	synchronized void recordLost(int lost) {
		this.lostSamples = Math.max(this.lostSamples, 0) + lost;
	}

	/** Records one stack sample, if it was taken on the server thread. */
	synchronized void recordSample(RecordedEvent event) {
		RecordedThread thread = event.hasField("sampledThread")
				? event.getThread("sampledThread")
				: event.getThread();
		if (thread == null || !this.serverThreadName.equals(thread.getJavaName())) {
			return;
		}

		RecordedStackTrace stackTrace = event.getStackTrace();
		if (stackTrace == null) {
			return;
		}
		List<RecordedFrame> frames = stackTrace.getFrames();
		if (frames.isEmpty()) {
			return;
		}

		this.samplesOnThread++;
		if (this.samples.size() >= MAX_SAMPLES) {
			this.capReached = true;
			return;
		}
		this.samples.add(new Sample(epochNanos(event.getStartTime()), this.internStack(frames)));
	}

	private int internStack(List<RecordedFrame> frames) {
		int depth = Math.min(STACK_DEPTH, frames.size());
		List<String> names = new ArrayList<>(depth);
		StringBuilder folded = new StringBuilder();
		for (int i = 0; i < depth; i++) {
			String name = describe(frames.get(i));
			names.add(name);
			folded.append(name).append(';');
		}

		Integer existing = this.stackIds.get(folded.toString());
		if (existing != null) {
			return existing;
		}
		int id = this.stacksById.size();
		this.stacksById.add(List.copyOf(names));
		this.stackIds.put(folded.toString(), id);
		return id;
	}

	private static String describe(RecordedFrame frame) {
		RecordedMethod method = frame.getMethod();
		if (method == null) {
			return "(unknown)";
		}
		String owner = method.getType() == null ? "?" : simpleName(method.getType().getName());
		return owner + '.' + method.getName();
	}

	private static String simpleName(String binaryName) {
		int dot = binaryName.lastIndexOf('.');
		return dot < 0 ? binaryName : binaryName.substring(dot + 1);
	}

	/**
	 * Joins samples against windows and ranks methods by the samples that caught them running.
	 *
	 * <p>Windows are sorted once and searched by bisection, so the join is linearithmic in the
	 * number of samples rather than quadratic.
	 */
	synchronized MethodBreakdown breakdown(int limit) {
		List<Window> sorted = new ArrayList<>(this.windows);
		sorted.sort(Comparator.comparingLong(Window::start));

		Map<Integer, StackTally> perStack = new HashMap<>();
		int inChunk = 0;

		for (Sample sample : this.samples) {
			Window window = find(sorted, sample.timestamp());
			if (window == null) {
				continue;
			}
			inChunk++;
			perStack.computeIfAbsent(sample.stackId(), key -> new StackTally())
					.record(window.objectType());
		}

		// A method's own cost is the samples that caught it at the top of the stack; several
		// distinct call paths can lead to the same method, so they are merged here.
		Map<String, MethodTally> perMethod = new HashMap<>();
		perStack.forEach((stackId, tally) -> {
			List<String> stack = this.stacksById.get(stackId);
			perMethod.computeIfAbsent(stack.get(0), key -> new MethodTally()).record(tally, stack);
		});

		List<MethodBreakdown.MethodRow> rows = new ArrayList<>(perMethod.size());
		perMethod.forEach((method, tally) -> rows.add(new MethodBreakdown.MethodRow(
				method, tally.dominantType(), tally.samples, tally.representativeStack)));
		rows.sort(Comparator.comparingInt(MethodBreakdown.MethodRow::samples).reversed());

		return new MethodBreakdown(
				this.flavour,
				this.flavour.isBiased(),
				this.samplesOnThread,
				inChunk,
				this.lostSamples,
				this.capReached,
				List.copyOf(rows.subList(0, Math.min(limit, rows.size()))));
	}

	/** Largest window whose start is at or before the timestamp, if it also contains it. */
	private static Window find(List<Window> sorted, long timestamp) {
		int low = 0;
		int high = sorted.size() - 1;
		int candidate = -1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (sorted.get(mid).start() <= timestamp) {
				candidate = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		if (candidate < 0) {
			return null;
		}
		Window window = sorted.get(candidate);
		return timestamp <= window.end() ? window : null;
	}

	private record Window(long start, long end, String objectType) {
	}

	private record Sample(long timestamp, int stackId) {
	}

	private static final class StackTally {
		private int samples;
		private final Map<String, Integer> byType = new HashMap<>();

		void record(String objectType) {
			this.samples++;
			this.byType.merge(objectType, 1, Integer::sum);
		}
	}

	private static final class MethodTally {
		private int samples;
		private final Map<String, Integer> byType = new HashMap<>();
		private int bestStackSamples;
		private List<String> representativeStack = List.of();

		void record(StackTally tally, List<String> stack) {
			this.samples += tally.samples;
			tally.byType.forEach((type, count) -> this.byType.merge(type, count, Integer::sum));
			// The most-sampled call path is the one worth showing as "how we got here".
			if (tally.samples > this.bestStackSamples) {
				this.bestStackSamples = tally.samples;
				this.representativeStack = stack;
			}
		}

		String dominantType() {
			return this.byType.entrySet().stream()
					.max(Map.Entry.comparingByValue())
					.map(Map.Entry::getKey)
					.orElse("?");
		}
	}
}
