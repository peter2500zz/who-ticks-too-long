package plus.mygo.whotickstoolong.profile.deep;

import org.jetbrains.annotations.Nullable;

/**
 * Notified once an inspection has ended and its results are final.
 *
 * <p>Exists so a manual inspection can hand its findings straight back to whoever asked for
 * it. An inspection started with a time limit ends long after the command that began it, and
 * expecting an operator to remember to come back and ask for the report is a good way to lose
 * the capture.
 *
 * <p>Called on the server thread. Automatic captures pass nothing here: they already write
 * themselves to disk and record a line in the log.
 */
@FunctionalInterface
public interface DeepCompletion {

	/**
	 * @param objects trimmed to a size worth reading in chat, not the full stored result
	 * @param methods null when the inspection ran without method sampling
	 */
	void onFinished(ObjectBreakdown objects, @Nullable MethodBreakdown methods);
}
