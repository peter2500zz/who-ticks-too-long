package plus.mygo.whotickstoolong.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.ChunkPos;
import plus.mygo.whotickstoolong.WhoTicksTooLong;

/**
 * Writes finished reports into their own folder under the game directory, the way Minecraft
 * keeps {@code logs} and {@code crash-reports}.
 *
 * <p>Nothing here ever touches a world folder. The save is left exactly as the profiler found
 * it; the only footprint is a plain text file next to the game's own logs.
 */
public final class ReportStore {

	/** Sits beside {@code logs} and {@code crash-reports} rather than inside any save. */
	public static final String DIRECTORY_NAME = "whotickstoolong";

	private static final DateTimeFormatter FILE_STAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);

	private ReportStore() {
	}

	public static Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve(DIRECTORY_NAME);
	}

	/**
	 * Writes a report off the server thread.
	 *
	 * <p>Reports are produced rarely and are a few kilobytes each, but a stalled disk must
	 * never become a stalled tick, so the write is handed off rather than done inline.
	 *
	 * @return the path it will be written to
	 */
	public static Path writeAsync(String contents, String label, int dimensionId, long chunkKey) {
		Path target = directory().resolve(fileName(label, dimensionId, chunkKey));
		CompletableFuture.runAsync(() -> write(target, contents));
		return target;
	}

	private static void write(Path target, String contents) {
		try {
			Files.createDirectories(target.getParent());
			Files.writeString(target, contents, StandardCharsets.UTF_8);
			WhoTicksTooLong.LOGGER.info("Wrote report to {}", target);
		} catch (IOException e) {
			WhoTicksTooLong.LOGGER.error("Could not write report to {}", target, e);
		}
	}

	private static String fileName(String label, int dimensionId, long chunkKey) {
		return String.format(Locale.ROOT, "%s_%s_dim%d_chunk_%d_%d.txt",
				LocalDateTime.now().format(FILE_STAMP),
				sanitise(label),
				dimensionId,
				ChunkPos.getX(chunkKey),
				ChunkPos.getZ(chunkKey));
	}

	/** Keeps a caller-supplied label from turning into a path or an illegal file name. */
	private static String sanitise(String label) {
		String cleaned = label.replaceAll("[^A-Za-z0-9._-]", "-");
		return cleaned.isEmpty() ? "report" : cleaned;
	}
}
