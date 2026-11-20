package org.kendar.llm.eval.suite;

import org.kendar.llm.eval.EvalLog;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logs the progress of the suite cases: at start the planned count, after each case
 * {@code progress done/planned, to do, passed, failed}, at the end the totals. The plan follows the JUnit selection
 * ({@code -Dgroups}, {@code -Dtest}), so the count matches what runs. Registered through
 * {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}; silent when no suite factory is selected.
 */
public final class EvalProgressListener implements TestExecutionListener {

    /** Factory container unique id to the tests it is planned to produce. */
    private final Map<String, Integer> plannedByFactory = new ConcurrentHashMap<>();
    /** Factory container unique id to the tests it produced so far. */
    private final Map<String, AtomicInteger> seenByFactory = new ConcurrentHashMap<>();
    private final AtomicInteger planned = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger aborted = new AtomicInteger();
    private final AtomicInteger notRun = new AtomicInteger();

    @Override
    public void testPlanExecutionStarted(TestPlan plan) {
        for (TestIdentifier root : plan.getRoots()) {
            for (TestIdentifier id : plan.getDescendants(root)) {
                if (!(id.getSource().orElse(null) instanceof MethodSource ms)) continue;
                if (!SuiteBase.FACTORIES.containsKey(ms.getMethodName())) continue;
                Class<?> cls;
                try {
                    cls = ms.getJavaClass();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!SuiteBase.class.isAssignableFrom(cls) || java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) continue;
                try {
                    int n = SuiteBase.plannedTests(cls.asSubclass(SuiteBase.class), ms.getMethodName());
                    if (n > 0) {
                        plannedByFactory.put(id.getUniqueId(), n);
                        seenByFactory.put(id.getUniqueId(), new AtomicInteger());
                        planned.addAndGet(n);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    EvalLog.info("progress: cannot count the cases of " + cls.getSimpleName() + "." + ms.getMethodName()
                            + " (" + e + ")");
                }
            }
        }
        if (planned.get() > 0) EvalLog.info("progress: " + planned.get() + " case run(s) planned");
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (planned.get() == 0) return;
        String parent = id.getParentId().orElse(null);
        if (id.isTest() && parent != null && plannedByFactory.containsKey(parent)) {
            seenByFactory.get(parent).incrementAndGet();
            done.incrementAndGet();
            switch (result.getStatus()) {
                case SUCCESSFUL -> passed.incrementAndGet();
                case FAILED -> failed.incrementAndGet();
                case ABORTED -> aborted.incrementAndGet();
            }
            EvalLog.info(line("progress"));
        } else if (plannedByFactory.containsKey(id.getUniqueId())) {
            // a factory that ended early (e.g. failed before producing its tests) leaves its remaining cases unrun
            int missing = plannedByFactory.get(id.getUniqueId()) - seenByFactory.get(id.getUniqueId()).get();
            if (missing > 0) {
                notRun.addAndGet(missing);
                EvalLog.info(line("progress: " + missing + " case run(s) of " + id.getDisplayName() + " not run;"));
            }
        }
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        Integer n = plannedByFactory.get(id.getUniqueId());
        if (n == null) return;
        notRun.addAndGet(n);
        EvalLog.info(line("progress: " + n + " case run(s) of " + id.getDisplayName() + " skipped (" + reason + ");"));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        if (planned.get() > 0) EvalLog.info(line("progress final"));
        org.kendar.llm.eval.EvalRun.finish();
    }

    private String line(String prefix) {
        int d = done.get();
        int todo = Math.max(0, planned.get() - d - notRun.get());
        return prefix + " " + d + "/" + planned.get() + " done, " + todo + " to do: "
                + passed.get() + " passed, " + failed.get() + " failed"
                + (aborted.get() > 0 ? ", " + aborted.get() + " aborted" : "")
                + (notRun.get() > 0 ? ", " + notRun.get() + " not run" : "");
    }
}
