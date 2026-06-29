package org.kendar.jllm.session;

/**
 * Mutable holder for whether the session is currently in <em>plan mode</em>.
 * While active the session exposes only read-only tools and instructs the model
 * to research and produce a plan instead of mutating the project. The model
 * leaves plan mode by calling the {@code exit_plan_mode} tool.
 */
public class PlanState {

  private volatile boolean active;

  public PlanState(boolean active) {
    this.active = active;
  }

  public boolean isActive() {
    return active;
  }

  public void exit() {
    this.active = false;
  }
}
