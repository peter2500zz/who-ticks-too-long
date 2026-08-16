package plus.mygo.whotickstoolong.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Collects the lines of one report and delivers them as a single message.
 *
 * <p>Sending a report line by line costs a chat packet per line, lets unrelated server output
 * interleave into the middle of a table, and stamps every line of the console log with its own
 * timestamp and prefix. A report is one thing to read, so it is sent as one thing.
 */
final class ChatReport {

	private final MutableComponent body = Component.empty();
	private boolean empty = true;

	ChatReport line(Component content) {
		if (!this.empty) {
			this.body.append("\n");
		}
		this.body.append(content);
		this.empty = false;
		return this;
	}

	ChatReport line(String content) {
		return this.line(Component.literal(content));
	}

	boolean isEmpty() {
		return this.empty;
	}

	/** Sends to the source alone. Reports are answers to a question, not announcements. */
	void send(CommandSourceStack source) {
		if (this.empty) {
			return;
		}
		source.sendSuccess(() -> this.body, false);
	}

	/** Sends and also logs to operators, for the state changes worth recording. */
	void broadcast(CommandSourceStack source) {
		if (this.empty) {
			return;
		}
		source.sendSuccess(() -> this.body, true);
	}
}
