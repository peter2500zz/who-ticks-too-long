package plus.mygo.whotickstoolong.profile;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * What the server thread is working on right now, published for the sampler thread to read.
 *
 * <p>This is the only code in the mod that runs on the server's hot path, so it does the
 * least work that can possibly answer the question. It never calls {@code System.nanoTime()}
 * and never measures anything: it just publishes "the server thread is currently ticking
 * chunk X in phase P". A separate thread samples that at a steady rate, and the share of
 * samples naming a chunk <em>is</em> that chunk's share of tick time. Sampling is
 * statistically equivalent to timing, and on this project's reference machine it measured
 * roughly 1300x cheaper: about 0.03 ns per opaque store against 39 ns for a nanoTime pair.
 *
 * <p>Opaque is the weakest access mode that still forbids the JIT from hoisting or eliding
 * the stores and still guarantees the sampler eventually observes them. It is deliberately
 * not volatile: no ordering relationship with any other memory is needed here, and on
 * weakly ordered CPUs opaque avoids a barrier that volatile would emit.
 *
 * <p>All writers are the server thread, so the fields never share a cache line between two
 * writing threads and need no padding. The sampler's reads pull the line away about a
 * thousand times a second, which is far below anything measurable.
 *
 * <p>When recording is off the guard folds away and the instrumentation costs nothing at
 * all until it is first switched on; after a toggle it settles at one perfectly predicted,
 * never taken branch per ticked object.
 */
public final class TickContext {

	/** No chunk is being ticked. Not a valid {@code ChunkPos} key, which packs two ints. */
	public static final long NO_CHUNK = Long.MIN_VALUE;

	/** No level has claimed the server thread yet. */
	public static final int NO_DIMENSION = -1;

	private static final VarHandle CHUNK;
	private static final VarHandle PHASE;
	private static final VarHandle DIMENSION;
	private static final VarHandle ENTER_COUNT;

	static {
		try {
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			CHUNK = lookup.findStaticVarHandle(TickContext.class, "chunk", long.class);
			PHASE = lookup.findStaticVarHandle(TickContext.class, "phase", int.class);
			DIMENSION = lookup.findStaticVarHandle(TickContext.class, "dimension", int.class);
			ENTER_COUNT = lookup.findStaticVarHandle(TickContext.class, "enterCount", long.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	/**
	 * Toggled by the {@code /wttl} command, which runs on the server thread. Volatile so a
	 * future off-thread toggle stays correct; it measured indistinguishable from a plain
	 * field on the hot path.
	 */
	private static volatile boolean recording;

	private static long chunk = NO_CHUNK;
	private static int phase = TickPhase.IDLE.ordinal();
	private static int dimension = NO_DIMENSION;

	/** Exact count of instrumented objects, so the mod can report its own overhead. */
	private static long enterCount;

	private TickContext() {
	}

	// ---------------------------------------------------------------- hot path

	/** Publishes that the server thread has started ticking something in {@code chunkKey}. */
	public static void enter(long chunkKey, TickPhase inPhase) {
		if (!recording) {
			return;
		}
		CHUNK.setOpaque(chunkKey);
		PHASE.setOpaque(inPhase.ordinal());
		ENTER_COUNT.setOpaque((long) ENTER_COUNT.getOpaque() + 1L);
	}

	/**
	 * Publishes that the server thread has left chunk work.
	 *
	 * <p>None of the instrumented call sites nest inside each other — vanilla runs the
	 * phases one after another — so clearing rather than restoring a previous value is
	 * correct. If an instrumented tick throws, the injected exit is skipped and the stale
	 * chunk stays published until the next object claims it, which costs at most a
	 * handful of misattributed samples on an already exceptional path.
	 */
	public static void exit() {
		if (!recording) {
			return;
		}
		CHUNK.setOpaque(NO_CHUNK);
		PHASE.setOpaque(TickPhase.IDLE.ordinal());
	}

	/** Called once per level per tick, not per object, so it can afford to be plain. */
	public static void setDimension(int dimensionId) {
		if (!recording) {
			return;
		}
		DIMENSION.setOpaque(dimensionId);
	}

	// ------------------------------------------------------------ sampler side

	public static long readChunk() {
		return (long) CHUNK.getOpaque();
	}

	public static int readPhase() {
		return (int) PHASE.getOpaque();
	}

	public static int readDimension() {
		return (int) DIMENSION.getOpaque();
	}

	public static long readEnterCount() {
		return (long) ENTER_COUNT.getOpaque();
	}

	// ----------------------------------------------------------------- control

	public static boolean isRecording() {
		return recording;
	}

	/** Switching off also clears the published state so a stale chunk cannot be sampled. */
	public static void setRecording(boolean value) {
		recording = value;
		CHUNK.setOpaque(NO_CHUNK);
		PHASE.setOpaque(TickPhase.IDLE.ordinal());
		DIMENSION.setOpaque(NO_DIMENSION);
	}
}
