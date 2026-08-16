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
	IDLE("wttl.phase.idle"),
	ENTITY("wttl.phase.entity"),
	BLOCK_ENTITY("wttl.phase.block_entity"),
	RANDOM_TICK("wttl.phase.random_tick"),
	SCHEDULED_BLOCK("wttl.phase.scheduled_block"),
	SCHEDULED_FLUID("wttl.phase.scheduled_fluid");

	/** Cached because {@link #values()} allocates a defensive copy on every call. */
	private static final TickPhase[] VALUES = values();

	private final String translationKey;

	TickPhase(String translationKey) {
		this.translationKey = translationKey;
	}

	/**
	 * The key rather than the text: a phase travels through a JFR event and out to readers in
	 * several languages, so it stays a key until the moment it is rendered.
	 */
	public String translationKey() {
		return this.translationKey;
	}

	public static TickPhase byOrdinal(int ordinal) {
		return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : IDLE;
	}

	public static int count() {
		return VALUES.length;
	}
}
