package org.kendar.jllm.base;

import org.kendar.jllm.exceptions.LLMConfigManagerException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Copies the bundled default config (classpath {@code jllm-bundled/.jllm/}) into a
 * setting dir's {@code .jllm/}, never overwriting files that already exist.
 */
public class DefaultsSeeder {

  private static final String ROOT = "jllm-bundled/.jllm";

  public static void seed(File settingDir) {
    URL root = DefaultsSeeder.class.getClassLoader().getResource(ROOT);
    if (root == null) {
      return; // no bundled defaults on the classpath - defensive no-op
    }
    File destRoot = new File(settingDir, ".jllm");
    try {
      switch (root.getProtocol()) {
        case "file" -> seedFromFile(Path.of(root.toURI()), destRoot);
        case "jar" -> seedFromJar(root, destRoot);
        default -> { /* unknown protocol - ignore */ }
      }
    } catch (Exception e) {
      throw new LLMConfigManagerException("Unable to seed defaults into " + destRoot.getAbsolutePath(), e);
    }
  }

  private static void seedFromFile(Path rootPath, File destRoot) throws IOException {
    try (Stream<Path> paths = Files.walk(rootPath)) {
      paths.filter(Files::isRegularFile).forEach(p -> {
        String rel = rootPath.relativize(p).toString().replace(File.separatorChar, '/');
        try (InputStream in = Files.newInputStream(p)) {
          copyIfMissing(in, new File(destRoot, rel));
        } catch (IOException e) {
          throw new LLMConfigManagerException("Unable to copy " + rel, e);
        }
      });
    }
  }

  private static void seedFromJar(URL root, File destRoot) throws Exception {
    URI uri = root.toURI();
    String prefix = ROOT + "/";
    // Prefer a direct JarFile read to avoid leaking shared filesystems.
    String jarPath = uri.getSchemeSpecificPart();
    int sep = jarPath.indexOf("!/");
    String filePart = jarPath.substring("file:".length(), sep);
    try (JarFile jar = new JarFile(filePart)) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
          continue;
        }
        String rel = entry.getName().substring(prefix.length());
        try (InputStream in = jar.getInputStream(entry)) {
          copyIfMissing(in, new File(destRoot, rel));
        }
      }
    }
  }

  private static void copyIfMissing(InputStream in, File dest) throws IOException {
    if (dest.exists()) {
      return; // never overwrite user edits
    }
    File parent = dest.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs()) {
      throw new IOException("Unable to create directory " + parent.getAbsolutePath());
    }
    Files.copy(in, dest.toPath());
  }
}
