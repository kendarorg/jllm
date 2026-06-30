package org.kendar.jllm.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoStoreTest {

  @Test
  void emptyStoreHasNoIncomplete() {
    assertFalse(new TodoStore().hasIncomplete());
  }

  @Test
  void allCompletedHasNoIncomplete() {
    TodoStore store = new TodoStore();
    store.replace(List.of(
        new TodoStore.Item("a", "completed"),
        new TodoStore.Item("b", "COMPLETED")));
    assertFalse(store.hasIncomplete());
  }

  @Test
  void pendingOrInProgressIsIncomplete() {
    TodoStore pending = new TodoStore();
    pending.replace(List.of(
        new TodoStore.Item("a", "completed"),
        new TodoStore.Item("b", "pending")));
    assertTrue(pending.hasIncomplete());

    TodoStore inProgress = new TodoStore();
    inProgress.replace(List.of(new TodoStore.Item("a", "in_progress")));
    assertTrue(inProgress.hasIncomplete());
  }
}
