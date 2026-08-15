package plus.mygo.whotickstoolong.profile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Assigns each level a small integer so samples can name their dimension without the
 * sampler ever touching a {@code ServerLevel} reference.
 *
 * <p>Holding level objects from a background thread would risk keeping unloaded worlds
 * alive, so nothing outside this table ever does.
 */
public final class DimensionTable {

	private final List<ResourceKey<Level>> byId = new CopyOnWriteArrayList<>();
	private final Map<ResourceKey<Level>, Integer> ids = new ConcurrentHashMap<>();

	/**
	 * Resolves a level to its id, assigning one on first sight. Called once per level per
	 * tick from the server thread, never per ticked object.
	 */
	public int idOf(ResourceKey<Level> key) {
		Integer existing = this.ids.get(key);
		if (existing != null) {
			return existing;
		}
		synchronized (this) {
			return this.ids.computeIfAbsent(key, k -> {
				this.byId.add(k);
				return this.byId.size() - 1;
			});
		}
	}

	/** Returns the level key for an id, or null if the id was never assigned. */
	public ResourceKey<Level> keyOf(int id) {
		return id >= 0 && id < this.byId.size() ? this.byId.get(id) : null;
	}

	/** A short human-readable name, for example {@code minecraft:overworld}. */
	public String nameOf(int id) {
		ResourceKey<Level> key = this.keyOf(id);
		return key == null ? "unknown" : key.identifier().toString();
	}
}
