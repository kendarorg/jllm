package org.kendar.llm.harness.settings;

/**
 * @param tempDir            root of the per chat and agent scratch directories; default {@code java.io.tmpdir/agent-harness}
 * @param backend            {@code local} (default, no isolation), {@code shared-local} (minimal environment, bwrap
 *                           on Linux) or {@code docker}
 * @param localFallback      with the docker backend: continue in a shared-local sandbox (never on the plain host) when
 *                           docker is unavailable (default false; the web decides per role)
 * @param docker             docker backend settings; unset values keep the defaults
 * @param outputLogMaxBytes  cap of the spill log of a long shell output; default 51200, 0 = unlimited
 * @param shell              local backend: the shell the bash tool runs ({@code cmd}, {@code powershell},
 *                           {@code pwsh}, {@code bash}, {@code zsh}, {@code sh} or a path to one); default the OS
 *                           one ({@code cmd} on Windows, {@code zsh} on macOS, {@code bash} elsewhere)
 */
public record SandboxSettings(String tempDir, Integer timeoutSeconds, String backend, Boolean localFallback,
                              DockerSettings docker, Long outputLogMaxBytes, String shell) {
}
