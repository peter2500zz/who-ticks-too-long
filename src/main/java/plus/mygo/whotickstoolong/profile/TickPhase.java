package plus.mygo.whotickstoolong.profile;

/**
 * The kind of work the server thread is doing at the moment a sample is taken.
 *
 * <p>Vanilla ticks these in batches — all scheduled block ticks, then all fluid ticks,
 * then all randomly ticking chunks, then all entities, then all block entities — so
 * knowing the phase alongside the chunk explains <em>why</em> a chunk is expensive,
 * not just that it is.
 */
public enum TickPhase {
	/** The server thread is not inside any chunk's tick: networking, chunk IO, block events, and so on. */
	IDLE("idle"),
	ENTITY("entity"),
	BLOCK_ENTITY("block entity"),
	RANDOM_TICK("random tick"),
	SCHEDULED_BLOCK("scheduled block"),
	SCHEDULED_FLUID("scheduled fluid");

	/** Cached because {@link #values()} allocates a defensive copy on every call. */
	private static final TickPhase[] VALUES = values();

	private final String displayName;

	TickPhase(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return this.displayName;
	}

	public static TickPhase byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : IDLE;
	}

	public static int count() {
		return VALUES.length;
	}
}
