package org.kendar.llm.harness.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ProcessDockerCommandRunner implements DockerCommandRunner {

    @Override
    public Result run(List<String> argv, Duration timeout) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).start();
        p.getOutputStream().close();
        CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> drain(p.getInputStream()));
        CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(p.getErrorStream()));
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        try {
            return new Result(out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS),
                    finished ? p.exitValue() : -1, !finished);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Result("", "", finished ? p.exitValue() : -1, !finished);
        }
    }

    private static String drain(InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
