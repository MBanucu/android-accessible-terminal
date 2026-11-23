package io.github.mbanucu.samples.a11yprobe;

/**
 * The old component name, kept for one release.
 *
 * <p>{@code a11y-probe/build.sh} also produces a legacy APK whose manifest
 * package is {@code io.github.mbanucu.samples.a11yprobe}; a phone still running
 * that app can enable {@code io.github.mbanucu.samples.a11yprobe/.ProbeService}
 * exactly as before. The class lives in the new APK too, but the app package is
 * what {@code enabled_accessibility_services} matches, so only the legacy APK
 * makes the old component name useful.
 */
public final class ProbeService extends io.github.mbanucu.a11yprobe.ProbeService {
}
