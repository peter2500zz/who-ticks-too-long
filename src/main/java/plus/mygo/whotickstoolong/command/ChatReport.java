package plus.mygo.whotickstoolong.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import plus.mygo.whotickstoolong.i18n.Messages;

/**
 * Collects the lines of one report, in one reader's language, and delivers them as a single
 * message.
 *
 * <p>Sending a report line by line costs a chat packet per line, lets unrelated server output
 * interleave into the middle of a table, and stamps every line of the console log with its own
 * timestamp and prefix. A report is one thing to read, so it is sent as one thing.
 *
 * <p>The reader is captured on construction because every line has to be looked up in their
 * language, so a report cannot be built once and shown to two people.
 */
final class ChatReport {

	private final CommandSourceStack source;
	private final @Nullable ServerPlayer viewer;
	private final MutableComponent body = Component.empty();
	private boolean empty = true;

	ChatReport(CommandSourceStack source) {
		this.source = source;
		this.viewer = source.getPlayer();
	}

	/** A translated line. Numbers must already be strings; see {@link Messages}. */
	ChatReport line(String key, Object... args) {
		return this.raw(Messages.of(this.viewer, key, args));
	}

	ChatReport styled(ChatFormatting style, String key, Object... args) {
		return this.raw(Messages.of(this.viewer, key, args).withStyle(style));
	}

	/** An already-built line, for rows assembled from several translated pieces. */
	ChatReport raw(Component content) {
		if (!this.empty) {
			this.body.append("\n");
		}
		this.body.append(content);
		this.empty = false;
		return this;
	}

	/** Translates a fragment for embedding inside a line rather than adding one. */
	MutableComponent text(String key, Object... args) {
		return Messages.of(this.viewer, key, args);
	}

	/** Translates one of this mod's own labels, leaving registry names untouched. */
	Component label(String text) {
		return Messages.label(this.viewer, text);
	}

	boolean isEmpty() {
		return this.empty;
	}

	/** Sends to the reader alone. Reports are answers to a question, not announcements. */
	void send() {
		if (!this.empty) {
			this.source.sendSuccess(() -> this.body, false);
		}
	}

	/** Sends and also logs to operators, for the state changes worth recording. */
	void broadcast() {
		if (!this.empty) {
			this.source.sendSuccess(() -> this.body, true);
		}
	}
}
