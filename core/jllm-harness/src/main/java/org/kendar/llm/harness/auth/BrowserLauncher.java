package org.kendar.llm.harness.auth;

import java.awt.Desktop;
import java.net.URI;

/** Opens a URL in the default browser; reports false (headless, WSL, SSH) so the caller prints the URL instead. */
public final class BrowserLauncher {

    private BrowserLauncher() {
    }

    public static boolean open(String url) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return false;
            Desktop.getDesktop().browse(URI.create(url));
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
