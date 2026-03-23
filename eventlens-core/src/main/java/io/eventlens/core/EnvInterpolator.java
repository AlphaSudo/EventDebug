package io.eventlens.core;

import io.eventlens.core.exception.ConfigurationException;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpolates environment variables in config strings.
 *
 * Supported forms:
 * - ${VAR}
 * - ${VAR:-default}
 *
 * Escape sequence:
 * - $${VAR} -> literal ${VAR}
 */
public final class EnvInterpolator {

    private static final Pattern ENV_PATTERN =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-(.*?))?\\}");

    private static final String ESCAPED_SENTINEL = "__EVENTLENS_ESCAPED_DOLLAR_BRACE__";

    private EnvInterpolator() {
    }

    public static String interpolate(String raw) {
        return interpolate(raw, System::getenv);
    }

    static String interpolate(String raw, Function<String, String> getenv) {
        if (raw == null) return null;
        if (getenv == null) throw new IllegalArgumentException("getenv cannot be null");

        // Support "$${VAR}" => literal "${VAR}"
        String working = raw.replace("$${", ESCAPED_SENTINEL);

        Matcher m = ENV_PATTERN.matcher(working);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            String defaultVal = m.group(2);
            String resolved = getenv.apply(varName);
            if (resolved == null && defaultVal != null) {
                resolved = defaultVal;
            }
            if (resolved == null) {
                throw new ConfigurationException(
                        ("Environment variable '%s' is not set and no default was provided (in value: %s)")
                                .formatted(varName, raw));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);

        String out = sb.toString().replace(ESCAPED_SENTINEL, "${");

        // Explicitly reject nested interpolation like "${A_${B}}"
        // (after normal interpolation pass, any remaining unescaped "${" indicates unsupported nesting/literals)
        if (out.contains("${") && !raw.contains("$${")) {
            throw new ConfigurationException(
                    ("Unsupported interpolation pattern in value: %s. " +
                            "Nested interpolation is not supported; to render a literal ${...}, escape as $${...}.")
                            .formatted(raw));
        }

        return out;
    }
}

