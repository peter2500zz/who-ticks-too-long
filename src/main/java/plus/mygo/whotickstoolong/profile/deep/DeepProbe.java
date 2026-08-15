package plus.mygo.whotickstoolong.profile.deep;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.profile.TickContext;
import plus.mygo.whotickstoolong.profile.TickPhase;

/**
 * The hot-path half of deep inspection: decides, as cheaply as possible, whether the object
 * about to be ticked is one we are inspecting, and wraps it in a JFR event if so.
 *
 * <p>Call sites always ask {@link #isTarget(long)} first and only then gather the object's
 * type and position, so a server with an inspection running pays a single long comparison
 * for every object outside the inspected chunk, and the roughly 40 ns event only for objects
 * inside it. With one chunk inspected that is a few thousand events a second.
 *
 * <p>The dimension is matched once per level tick rather than per object, which keeps the
 * per-object test down to two loads and a compare.
 */
public final class DeepProbe {

	private static volatile boolean active;
	private static volatile long targetChunk = TickContext.NO_CHUNK;
	private static volatile int targetDimension = TickContext.NO_DIMENSION;

	/** Whether the level currently being ticked is the one holding the target chunk. */
	private static boolean inTargetDimension;

	/** Server thread only: the event opened by the most recent {@link #begin}. */
	private static ObjectTickEvent open;

	private DeepProbe() {
	}

	// ---------------------------------------------------------------- hot path

	/** Cheap enough to call for every ticked object; folds away entirely while inactive. */
	public static boolean isTarget(long chunkKey) {
		return active && inTargetDimension && chunkKey == targetChunk;
	}

	/**
	 * Opens an event for one object. Only call after {@link #isTarget} has returned true,
	 * because gathering {@code type} and {@code pos} at the call site is the expensive part.
	 */
	public static void begin(long chunkKey, TickPhase phase, String type, int objectId, int x, int y, int z) {
		ObjectTickEvent event = new ObjectTickEvent();
		event.chunkKey = chunkKey;
		event.kind = phase.displayName();
		event.objectType = type;
		event.objectId = objectId;
		event.x = x;
		event.y = y;
		event.z = z;
		event.begin();
		// Any previously open event belongs to a tick that threw before reaching its exit.
		// Dropping it is correct: an event that is never committed is simply never recorded.
		open = event;
	}

	/** For objects fixed in place, where the position is the identity. */
	public static void begin(long chunkKey, TickPhase phase, String type, BlockPos pos) {
		begin(chunkKey, phase, type, 0, pos.getX(), pos.getY(), pos.getZ());
	}

	/**
	 * For blocks and fluids, which are named by a registry lookup that can in principle
	 * come back empty for an unregistered object.
	 */
	public static void begin(long chunkKey, TickPhase phase, @Nullable Identifier type, BlockPos pos) {
		begin(chunkKey, phase, type == null ? "(unregistered)" : type.toString(), pos);
	}

	/** Closes whatever {@link #begin} opened. Safe to call unconditionally. */
	public static void end() {
		if (!active) {
			return;
		}
		ObjectTickEvent event = open;
		if (event != null) {
			open = null;
			event.commit();
		}
	}

	// ----------------------------------------------------------------- control

	/** Called once per level per tick, so the per-object test never has to check a dimension. */
	public static void onLevelTickStart(int dimensionId) {
		if (active) {
			inTargetDimension = dimensionId == targetDimension;
		}
	}

	public static void arm(int dimensionId, long chunkKey) {
		targetDimension = dimensionId;
		targetChunk = chunkKey;
		inTargetDimension = false;
		open = null;
		active = true;
	}

	public static void disarm() {
		active = false;
		inTargetDimension = false;
		open = null;
		targetChunk = TickContext.NO_CHUNK;
		targetDimension = TickContext.NO_DIMENSION;
	}

	public static boolean isArmed() {
		return active;
	}

	public static long targetChunk() {
		return targetChunk;
	}

	public static int targetDimension() {
		return targetDimension;
	}
}
