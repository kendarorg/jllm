package org.kendar.jllm.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Session-scoped, mutable list of todo items maintained by the model through the
 * {@link TodoWriteTool}. Holds the single source of truth for the current plan so
 * the model can track multi-step work across tool-calling iterations.
 */
public class TodoStore {

  /** A single planned step and its current status. */
  public static final class Item {
    private String content;
    private String status; // pending | in_progress | completed

    public Item() {
    }

    public Item(String content, String status) {
      this.content = content;
      this.status = status;
    }

    public String getContent() {
      return content;
    }

    public void setContent(String content) {
      this.content = content;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }
  }

  private final List<Item> items = new ArrayList<>();

  /** Replaces the whole list (the model always sends the full, updated list). */
  public synchronized void replace(List<Item> newItems) {
    items.clear();
    if (newItems != null) {
      items.addAll(newItems);
    }
  }

  public synchronized List<Item> items() {
    return new ArrayList<>(items);
  }

  public synchronized boolean isEmpty() {
    return items.isEmpty();
  }

  /** Human/model readable rendering, e.g. {@code [x] done step}. */
  public synchronized String render() {
    if (items.isEmpty()) {
      return "(no todos)";
    }
    StringBuilder sb = new StringBuilder();
    for (Item item : items) {
      sb.append(marker(item.getStatus())).append(' ').append(item.getContent()).append('\n');
    }
    return sb.toString().trim();
  }

  private static String marker(String status) {
    if ("completed".equalsIgnoreCase(status)) {
      return "[x]";
    }
    if ("in_progress".equalsIgnoreCase(status)) {
      return "[~]";
    }
    return "[ ]";
  }
}
