package io.github.mbanucu.a11yprobe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The arguments of one provider command: named extras and a positional
 * {@code --arg}.
 *
 * <p>Everything is a string because that is what {@code content call --extra
 * name:s:value} carries; {@link #longValue} and {@link #bool} parse the two
 * types the commands need. No Android type is involved, so the batch parser and
 * the argument fallbacks are testable off-device.
 */
public final class ProbeArgs {

    private final String arg;
    private final Map<String, String> values;

    public ProbeArgs(String arg, Map<String, String> values) {
        this.arg = arg == null || arg.isEmpty() ? null : arg;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ProbeArgs of(String arg) {
        return new ProbeArgs(arg, Collections.emptyMap());
    }

    public String arg() {
        return arg;
    }

    public Map<String, String> values() {
        return values;
    }

    public String get(String name) {
        return values.get(name);
    }

    /** The first named value that is present and not empty. */
    public String first(String... names) {
        for (String name : names) {
            String value = values.get(name);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Like {@link #first}, with the positional argument as the last resort. */
    public String firstOrArg(String... names) {
        String value = first(names);
        return value != null ? value : arg;
    }

    public long longValue(long defaultValue, String... names) {
        String raw = first(names);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(
                    "'" + raw + "' is not a number for " + names[0]);
        }
    }

    public boolean bool(String... names) {
        String raw = first(names);
        if (raw == null) {
            return false;
        }
        return "true".equalsIgnoreCase(raw.trim())
                || "1".equals(raw.trim())
                || "yes".equalsIgnoreCase(raw.trim());
    }

    /**
     * A batch call's shared arguments, for a line that does not set them.
     *
     * <p>Only the named values are inherited: a line's own positional argument
     * must not be replaced by the outer {@code --arg} (which is the batch
     * itself), and a line's own {@code find=} must win over a default one.
     */
    public ProbeArgs inherit(ProbeArgs defaults, String... names) {
        if (defaults == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (String name : names) {
            String value = defaults.values.get(name);
            if (value != null) {
                merged.put(name, value);
            }
        }
        merged.putAll(values);
        return new ProbeArgs(arg, merged);
    }

    /**
     * A copy with one more value, unless the key is already present.
     *
     * <p>For a value the command itself supplies (a batch's start sequence),
     * which a caller's extras must not be able to overwrite by accident.
     */
    public ProbeArgs withDefault(String name, String value) {
        if (values.containsKey(name)) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(values);
        merged.put(name, value);
        return new ProbeArgs(arg, merged);
    }

    /**
     * A file name safe to append to the probe's own files directory.
     *
     * <p>The provider is reachable by any app on the device, and a name is a
     * path fragment: a slash or a parent directory would put a dump outside the
     * directory the probe is documented to write in.
     */
    public static String safeFileName(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return fallback;
        }
        return cleaned;
    }
}
