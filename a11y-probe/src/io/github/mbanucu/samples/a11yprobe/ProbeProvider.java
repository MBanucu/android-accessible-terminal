package io.github.mbanucu.samples.a11yprobe;

/**
 * The old provider name, kept for one release.
 *
 * <p>The legacy APK's manifest declares this class and renames the authority to
 * {@code io.github.mbanucu.samples.a11yprobe.control}, so a device with both
 * APKs installed does not have two providers claiming one authority. The
 * command code is the canonical provider's.
 */
public final class ProbeProvider extends io.github.mbanucu.a11yprobe.ProbeProvider {
}
