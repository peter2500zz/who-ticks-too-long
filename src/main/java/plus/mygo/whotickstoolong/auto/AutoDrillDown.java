package plus.mygo.whotickstoolong.auto;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.WhoTicksTooLong;
import plus.mygo.whotickstoolong.profile.ChunkProfiler;
import plus.mygo.whotickstoolong.profile.HeatReport;
import plus.mygo.whotickstoolong.profile.deep.DeepProfiler;
import plus.mygo.whotickstoolong.profile.deep.MethodBreakdown;
import plus.mygo.whotickstoolong.profile.deep.ObjectBreakdown;
import plus.mygo.whotickstoolong.report.ReportStore;
import plus.mygo.whotickstoolong.report.TextReport;

/**
 * Catches the lag spike nobody was awake for.
 *
 * <p>Watches the server's tick time and the chunk ranking together. Either signal alone is
 * misleading: a slow server does not mean a chunk is to blame, and a chunk holding most of
 * chunk tick time means nothing when the server is idle and that time is a rounding error.
 * Only when the server is genuinely behind <em>and</em> one chunk dominates does it start a
 * full inspection, write the result to disk, and remember it.
 *
 * <p>The expensive part of the check — aggregating the sample ring — is only reached once the
 * cheap tick-time comparison has already failed, and then at most once a second.
 */
public final class AutoDrillDown {

	public static final double DEFAULT_MSPT_THRESHOLD_MS = 40.0;
	public static final double DEFAULT_SHARE_THRESHOLD = 0.30;

	/** Long enough that a chunk misbehaving in bursts does not capture over and over. */
	private static final Duration COOLDOWN_PER_CHUNK = Duration.ofMinutes(5);
	private static final Duration CAPTURE_DURATION = Duration.ofSeconds(30);

	/** A short window, so a spike is judged on what is happening now rather than an average. */
	private static final Duration TRIGGER_WINDOW = Duration.ofSeconds(10);

	private static final long EVALUATION_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();
	private static final int MAX_KEPT_CAPTURES = 8;
	private static final int MAX_COOLDOWN_ENTRIES = 256;
	private static final int REPORT_ROWS = 20;

	private static final AutoDrillDown INSTANCE = new AutoDrillDown();

	private volatile boolean enabled;
	private double msptThresholdMs = DEFAULT_MSPT_THRESHOLD_MS;
	private double shareThreshold = DEFAULT_SHARE_THRESHOLD;

	private long lastEvaluationNanos;
	private final Map<Long, Long> cooldownUntilNanos = new HashMap<>();
	private final Deque<AutoCapture> captures = new ArrayDeque<>();
	private @Nullable Pending pending;

	private AutoDrillDown() {
	}

	public static AutoDrillDown get() {
		return INSTANCE;
	}

	public boolean isEnabled() {
		return this.enabled;
	}

	public double msptThresholdMs() {
		return this.msptThresholdMs;
	}

	public double shareThreshold() {
		return this.shareThreshold;
	}

	public synchronized void enable(double msptThresholdMs, double shareThreshold) {
		this.msptThresholdMs = msptThresholdMs;
		this.shareThreshold = shareThreshold;
		this.enabled = true;
		WhoTicksTooLong.LOGGER.info("Automatic drill-down on: trigger above {} ms/tick with a chunk over {}%",
				msptThresholdMs, Math.round(shareThreshold * 100.0));
	}

	public synchronized void disable() {
		this.enabled = false;
		this.pending = null;
		WhoTicksTooLong.LOGGER.info("Automatic drill-down off.");
	}

	public synchronized List<AutoCapture> captures() {
		return List.copyOf(new ArrayList<>(this.captures));
	}

	public synchronized void clear() {
		this.captures.clear();
		this.cooldownUntilNanos.clear();
	}

	/** Runs once per server tick, at the end, when the tick time for it is already known. */
	public synchronized void onServerTickEnd(MinecraftServer server) {
		if (!this.enabled) {
			return;
		}

		if (this.pending != null) {
			if (!DeepProfiler.get().isRunning()) {
				this.harvest();
			}
			return;
		}

		// Never fight a manual inspection for the one deep session available.
		if (DeepProfiler.get().isRunning() || !ChunkProfiler.get().isEnabled()) {
			return;
		}

		double mspt = server.getAverageTickTimeNanos() / 1e6;
		if (mspt < this.msptThresholdMs) {
			return;
		}

		long now = System.nanoTime();
		if (now - this.lastEvaluationNanos < EVALUATION_INTERVAL_NANOS) {
			return;
		}
		this.lastEvaluationNanos = now;

		HeatReport report = ChunkProfiler.get().report(TRIGGER_WINDOW, 1);
		if (report == null || report.chunks().isEmpty()) {
			return;
		}

		HeatReport.ChunkHeat suspect = report.chunks().get(0);
		double share = suspect.shareOfBusy(report);
		if (share < this.shareThreshold) {
			return;
		}
		if (this.isCoolingDown(suspect.dimensionId(), suspect.chunkKey(), now)) {
			return;
		}

		this.trigger(suspect, mspt, share, now);
	}

	private void trigger(HeatReport.ChunkHeat suspect, double mspt, double share, long now) {
		try {
			DeepProfiler.get().start(suspect.dimensionId(), suspect.chunkKey(), CAPTURE_DURATION, true);
		} catch (IllegalStateException | UnsupportedOperationException e) {
			WhoTicksTooLong.LOGGER.warn("Automatic drill-down could not start an inspection", e);
			return;
		}

		this.rememberCooldown(suspect.dimensionId(), suspect.chunkKey(), now);
		this.pending = new Pending(LocalDateTime.now(), mspt, share,
				suspect.dimensionId(), suspect.chunkKey());

		WhoTicksTooLong.LOGGER.warn(
				"Server averaging {} ms/tick with chunk key {} holding {}% of chunk tick time; capturing for {}s",
				String.format(Locale.ROOT, "%.1f", mspt), suspect.chunkKey(),
				Math.round(share * 100.0), CAPTURE_DURATION.toSeconds());
	}

	private void harvest() {
		Pending captured = this.pending;
		this.pending = null;
		if (captured == null) {
			return;
		}

		ObjectBreakdown objects = DeepProfiler.get().breakdown(REPORT_ROWS, REPORT_ROWS);
		if (objects == null) {
			return;
		}
		MethodBreakdown methods = DeepProfiler.get().methodBreakdown(REPORT_ROWS);

		String dimensionName = ChunkProfiler.get().dimensions().nameOf(captured.dimensionId());
		String heading = String.format(Locale.ROOT,
				"Automatic capture at %s - server averaging %.1f ms/tick, chunk held %.1f%% of chunk tick time",
				captured.triggeredAt().format(AutoCapture.TIMESTAMP), captured.mspt(), captured.share() * 100.0);
		Path file = ReportStore.writeAsync(
				TextReport.render(heading, dimensionName, objects, methods),
				"auto", captured.dimensionId(), captured.chunkKey());

		this.captures.addFirst(new AutoCapture(captured.triggeredAt(), captured.mspt(), captured.share(),
				captured.dimensionId(), captured.chunkKey(), objects, methods, file));
		while (this.captures.size() > MAX_KEPT_CAPTURES) {
			this.captures.removeLast();
		}
	}

	private boolean isCoolingDown(int dimensionId, long chunkKey, long now) {
		Long until = this.cooldownUntilNanos.get(cooldownKey(dimensionId, chunkKey));
		return until != null && now < until;
	}

	private void rememberCooldown(int dimensionId, long chunkKey, long now) {
		if (this.cooldownUntilNanos.size() >= MAX_COOLDOWN_ENTRIES) {
			this.cooldownUntilNanos.values().removeIf(until -> until <= now);
		}
		this.cooldownUntilNanos.put(cooldownKey(dimensionId, chunkKey), now + COOLDOWN_PER_CHUNK.toNanos());
	}

	/**
	 * A chunk key already fills a long, so the dimension is mixed in rather than packed
	 * alongside it. Collisions would only cost an unnecessary cooldown, never a wrong report.
	 */
	private static long cooldownKey(int dimensionId, long chunkKey) {
		return chunkKey * 31L + dimensionId;
	}

	private record Pending(LocalDateTime triggeredAt, double mspt, double share,
			int dimensionId, long chunkKey) {
	}
}
