package plus.mygo.whotickstoolong.i18n;

import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * Builds text in the language of whoever is going to read it.
 *
 * <p>The text is looked up in the server's own tables for the receiver's reported language and
 * sent as the fallback beside the key. A client without this mod cannot resolve the key and
 * renders that fallback, which is already in its own language; a client that has the mod
 * resolves the key normally and the fallback is ignored.
 *
 * <p>Arguments are serialised over the network with the message, so pass only strings and
 * components. Numbers must be formatted to a string first: vanilla's template syntax
 * understands {@code %s} and {@code %1$s} and nothing else, so a format such as
 * {@code %.3f} would survive neither the table nor the wire.
 */
public final class Messages {

	/** Every key this mod owns starts here, which is how a key is told from a registry name. */
	public static final String KEY_PREFIX = "wttl.";

	private Messages() {
	}

	/**
	 * Translates one of this mod's own labels while leaving anything else alone.
	 *
	 * <p>Object types arrive mixed: most are registry names such as {@code minecraft:villager},
	 * which must never be translated, but a few are labels this mod invented for work that has
	 * no registry entry. The prefix separates them.
	 */
	public static Component label(@Nullable ServerPlayer viewer, String text) {
		return text.startsWith(KEY_PREFIX) ? of(viewer, text) : Component.literal(text);
	}

	public static String plainLabel(String text) {
		return text.startsWith(KEY_PREFIX) ? plain(text) : text;
	}

	/** Text for one player, or in the fallback language when there is no player to ask. */
	public static MutableComponent of(@Nullable ServerPlayer viewer, String key, Object... args) {
		// ClientInformation is the options snapshot a client reports on join and on change.
		String languageCode = viewer == null ? null : viewer.clientInformation().language();
		String fallback = ServerLanguage.lookup(languageCode, key);

		if (fallback == null) {
			// Only reachable if the tables failed to load or a key is misspelled, both of
			// which ServerLanguage has already reported at startup.
			return Component.translatable(key, args);
		}
		return Component.translatableWithFallback(key, fallback, args);
	}

	/** Text for a command source, which may be the console rather than a player. */
	public static MutableComponent of(CommandSourceStack source, String key, Object... args) {
		return of(source.getPlayer(), key, args);
	}

	/** Text in the authoring language, for a file or a log line that nobody is receiving. */
	public static String plain(String key, Object... args) {
		String template = ServerLanguage.reference(key);
		if (template == null) {
			return key;
		}
		return args.length == 0 ? template : String.format(Locale.ROOT, template, args);
	}
}
