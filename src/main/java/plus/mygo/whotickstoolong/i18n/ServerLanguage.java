package plus.mygo.whotickstoolong.i18n;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.locale.Language;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.WhoTicksTooLong;

/**
 * Translation tables carried by the server itself.
 *
 * <p>This is a server-side mod, so a client cannot be assumed to have it installed and its
 * language files will not contain these keys. The server therefore loads its own copy of every
 * bundled language and, before sending anything, looks the text up in the language the
 * receiving player reported. That text travels alongside the key as a fallback, so a vanilla
 * client renders readable text in its own language while a client that does have the mod
 * resolves the key itself and can override the wording with a resource pack.
 *
 * <p>The files live at the standard resource pack path, which lets one file serve both roles
 * at once: read from the classpath here, and loaded by the vanilla resource system on any
 * client that installs the jar.
 *
 * <p>Reference and fallback are different jobs. {@link #REFERENCE_LANGUAGE} is where wording
 * is authored and what completeness is checked against; {@link #FALLBACK_LANGUAGE} decides
 * what a player sees when their own language is not bundled.
 */
public final class ServerLanguage {

	/** Wording is authored here first, and every other language is checked against it. */
	public static final String REFERENCE_LANGUAGE = "zh_cn";

	/** Used when a player's language is not bundled, so nobody is shown a bare key. */
	public static final String FALLBACK_LANGUAGE = "en_us";

	private static final List<String> BUNDLED_LANGUAGES = List.of("zh_cn", "en_us");

	private static final String RESOURCE_PATTERN = "/assets/" + WhoTicksTooLong.MOD_ID + "/lang/%s.json";

	/**
	 * Language code to its table. Written once during mod initialisation, before any server
	 * exists or any command can run, and read-only from then on, so it needs no locking.
	 */
	private static final Map<String, Map<String, String>> TABLES = new HashMap<>();

	private ServerLanguage() {
	}

	/** Call once during mod initialisation. A broken language is skipped, never fatal. */
	public static void load() {
		for (String code : BUNDLED_LANGUAGES) {
			Map<String, String> table = read(code);
			if (table != null) {
				TABLES.put(code, Map.copyOf(table));
			}
		}

		if (!TABLES.containsKey(FALLBACK_LANGUAGE)) {
			WhoTicksTooLong.LOGGER.error(
					"Fallback language {} failed to load; clients without this mod will see raw keys",
					FALLBACK_LANGUAGE);
			return;
		}

		WhoTicksTooLong.LOGGER.info("Loaded {} translation tables: {}", TABLES.size(), TABLES.keySet());
		verifyAgainstReference();
	}

	/**
	 * @param languageCode the receiver's client language, or null to go straight to the fallback
	 * @return the text, or null when neither the requested language nor the fallback has the key
	 */
	public static @Nullable String lookup(@Nullable String languageCode, String key) {
		if (languageCode != null) {
			// Vanilla language codes are lower case. Normalising needs an explicit root
			// locale: a Turkish default locale maps 'I' to a dotless form and the lookup misses.
			Map<String, String> table = TABLES.get(languageCode.toLowerCase(Locale.ROOT));
			if (table != null) {
				String text = table.get(key);
				if (text != null) {
					return text;
				}
			}
		}

		Map<String, String> fallback = TABLES.get(FALLBACK_LANGUAGE);
		return fallback == null ? null : fallback.get(key);
	}

	/** Text in the authoring language, for output that has no reader to ask, such as a file. */
	public static @Nullable String reference(String key) {
		return lookup(REFERENCE_LANGUAGE, key);
	}

	private static @Nullable Map<String, String> read(String languageCode) {
		String path = String.format(RESOURCE_PATTERN, languageCode);

		try (InputStream stream = ServerLanguage.class.getResourceAsStream(path)) {
			if (stream == null) {
				WhoTicksTooLong.LOGGER.error("Missing language file {}", path);
				return null;
			}

			Map<String, String> table = new HashMap<>();
			// The vanilla parser, so placeholder handling matches vanilla exactly and no
			// extra JSON dependency is needed.
			Language.loadFromJson(stream, table::put);
			return table;
		} catch (Exception e) {
			WhoTicksTooLong.LOGGER.error("Could not parse language file {}", path, e);
			return null;
		}
	}

	/** Diagnostic only: a missing key still falls back, it just reads in the wrong language. */
	private static void verifyAgainstReference() {
		Map<String, String> reference = TABLES.get(REFERENCE_LANGUAGE);
		if (reference == null) {
			WhoTicksTooLong.LOGGER.error("Reference language {} failed to load; skipping the key check",
					REFERENCE_LANGUAGE);
			return;
		}

		TABLES.forEach((code, table) -> {
			if (code.equals(REFERENCE_LANGUAGE)) {
				return;
			}
			reference.keySet().stream().filter(key -> !table.containsKey(key)).forEach(key ->
					WhoTicksTooLong.LOGGER.warn("Language {} is missing key {}", code, key));
			table.keySet().stream().filter(key -> !reference.containsKey(key)).forEach(key ->
					WhoTicksTooLong.LOGGER.warn("Language {} has key {} that {} does not",
							code, key, REFERENCE_LANGUAGE));
		});
	}
}
