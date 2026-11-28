package org.kendar.llm.harness.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.llm.harness.HarnessConfig;
import org.kendar.llm.harness.settings.HarnessSettings;

import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class SandboxFactoryTest {

    @TempDir
    Path base;

    @Test
    void sharedLocalBackendIsTheSharedLocalSandboxNeverThePlainLocalRunner() {
        HarnessSettings settings = HarnessSettings.builder().sandboxBackend("shared-local").build();
        assertTrue(settings.sharedLocalSandbox());
        HarnessConfig config = HarnessConfig.defaults();
        SandboxExecutor box;
        try {
            box = new SandboxFactory().create(settings, config, new ChatDirLayout(base),
                    RemoteExecSandboxExecutor.Kind.WEB);
        } catch (SandboxUnavailableException e) {
            // Linux without a working bwrap: only the shared-local sandbox fails closed, the plain runner never does
            assertTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"), e.getMessage());
            return;
        }
        assertInstanceOf(SharedLocalSandbox.class, box);
        HarnessSettings plain = HarnessSettings.builder().sandboxBackend("local").build();
        assertFalse(new SandboxFactory().create(plain, config, new ChatDirLayout(base),
                RemoteExecSandboxExecutor.Kind.WEB) instanceof SharedLocalSandbox);
    }
}
