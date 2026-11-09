package org.kendar.llm.harness.auth;

/** How a login talks to the user; the command line prints, the web shows. */
public interface UserInteraction {

    /** Shows a message (a URL to open, a device code, progress). */
    void show(String message);

    /** Asks for a line (e.g. a pasted code). @return null when the front end cannot ask */
    String ask(String prompt);

    /** Opens the URL in a browser, or shows it when none can be opened. */
    default void openUrl(String url) {
        if (!BrowserLauncher.open(url)) show("Open this URL in a browser: " + url);
        else show("A browser was opened. If it did not, open: " + url);
    }
}
