package plus.mygo.whotickstoolong.profile.deep;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * One ticked object inside the chunk currently under deep inspection.
 *
 * <p>This is a JDK Flight Recorder event rather than a hand-rolled timer for two reasons.
 * It carries an exact duration for free, which answers "how long did this hopper take", and
 * it is timestamped on the same clock as {@code jdk.ExecutionSample}, which is what will let
 * a later milestone attribute stack samples to the chunk that was being ticked when they
 * were taken. A hand-rolled {@code nanoTime} pair costs the same and gives only the first.
 *
 * <p>While no recording has the event enabled, {@code begin()} and {@code commit()} are
 * folded away by the JIT and cost nothing measurable, so this instrumentation can sit
 * permanently in the hot path without a guard of its own.
 *
 * <p>Stack traces are off: they would be captured at commit time, which is the end of the
 * tick rather than wherever the time actually went, so they would cost a great deal and
 * mean very little.
 */
@Name(ObjectTickEvent.NAME)
@Label("Object Tick")
@Category({"Who Ticks Too Long"})
@Description("Duration of one entity, block entity or scheduled tick inside an inspected chunk")
@StackTrace(false)
public final class ObjectTickEvent extends Event {

	public static final String NAME = "whotickstoolong.ObjectTick";

	@Label("Chunk Key")
	public long chunkKey;

	@Label("Kind")
	public String kind;

	@Label("Object Type")
	public String objectType;

	/**
	 * Entity id, or zero for objects identified by position. Entities move, so their
	 * position cannot identify them across ticks the way a block entity's can.
	 */
	@Label("Object Id")
	public int objectId;

	@Label("X")
	public int x;

	@Label("Y")
	public int y;

	@Label("Z")
	public int z;
}
