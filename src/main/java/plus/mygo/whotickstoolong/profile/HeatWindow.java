package plus.mygo.whotickstoolong.profile;

import java.util.Locale;

/**
 * The rolling windows a report can be asked for.
 *
 * <p>A short window catches a spike that a long one would average away; a long window
 * shows the steady drain that a short one might miss. All three are served from the same
 * ring of raw samples, so keeping three costs no extra memory over keeping the longest.
 */
public enum HeatWindow {
	SHORT("10s", 10),
	MEDIUM("1m", 60),
	LONG("5m", 300);

	private final String label;
	private final int seconds;

	HeatWindow(String label, int seconds) {
		this.label = label;
		this.seconds = seconds;
	}

	public String label() {
		return this.label;
	}

	public int seconds() {
		return this.seconds;
	}

	public long nanos() {
		return this.seconds * 1_000_000_000L;
	}

	/** The window that bounds the sample ring's capacity. */
	public static HeatWindow longest() {
		return LONG;
	}

	public static HeatWindow byName(String name) {
		for (HeatWindow window : values()) {
			if (window.label.equalsIgnoreCase(name) || window.name().equalsIgnoreCase(name)) {
				return window;
			}
		}
		throw new IllegalArgumentException("unknown window: " + name.toLowerCase(Locale.ROOT));
	}
}
