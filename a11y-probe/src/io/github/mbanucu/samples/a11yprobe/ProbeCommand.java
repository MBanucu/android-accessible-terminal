package io.github.mbanucu.samples.a11yprobe;

/**
 * The old command receiver name, kept for one release.
 *
 * <p>The commands themselves are the new receiver's: this class inherits
 * {@link io.github.mbanucu.a11yprobe.ProbeCommand#onReceive}, and the legacy APK
 * declares it in place of the new one. The old broadcast action is declared by
 * the new APK as well, so a harness that still sends
 * {@code io.github.mbanucu.samples.a11yprobe.COMMAND} needs no change.
 */
public final class ProbeCommand extends io.github.mbanucu.a11yprobe.ProbeCommand {
}
