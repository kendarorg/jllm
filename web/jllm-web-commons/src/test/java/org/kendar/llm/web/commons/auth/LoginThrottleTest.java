package org.kendar.llm.web.commons.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {

    static final class Moving extends Clock {
        volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final Moving clock = new Moving();
    private final LoginThrottle t = new LoginThrottle(clock);

    private void fail(String key) {
        assertEquals(0, t.begin(key), "admitted");
        t.failure(key);
    }

    @Test
    void threeFailuresLockALoginForThirtyMinutes() {
        for (int i = 0; i < 3; i++) fail("id:rita");

        long left = t.begin("id:rita");
        assertTrue(left > 29 * 60 && left <= 30 * 60, "locked for about 30 minutes: " + left);
        clock.advance(Duration.ofMinutes(29));
        assertTrue(t.begin("id:rita") > 0, "still locked after 29 minutes");
        clock.advance(Duration.ofMinutes(2));
        assertEquals(0, t.begin("id:rita"), "free after 30 minutes");
    }

    @Test
    void aSuccessForgetsTheFailuresOfALogin() {
        fail("id:rita");
        fail("id:rita");
        assertEquals(0, t.begin("id:rita"));
        t.success("id:rita");
        fail("id:rita");
        fail("id:rita");
        assertEquals(0, t.begin("id:rita"), "the count started again");
    }

    @Test
    void checkAndCountIsOneStepSoParallelGuessesCannotPassTheLimit() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    go.await();
                    if (t.begin("id:victim") == 0) {
                        admitted.incrementAndGet(); // a wrong guess: it reports its failure afterwards
                        t.failure("id:victim");
                    }
                    return null;
                });
            }
            go.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(LoginThrottle.USER_MAX_FAILURES, admitted.get(), "no more guesses than the limit, however parallel");
        assertTrue(t.lockedFor("id:victim") > 0);
    }

    @Test
    void attemptsInFlightUseUpTheLimitBeforeAnyFailureIsReported() {
        for (int i = 0; i < 3; i++) assertEquals(0, t.begin("id:x")); // three requests still checking the password
        assertTrue(t.begin("id:x") > 0, "the fourth is refused already");
    }

    @Test
    void anAddressHasItsOwnWiderPolicyAndSuccessesDoNotBuyGuesses() {
        for (int i = 0; i < LoginThrottle.ADDRESS_MAX_ATTEMPTS; i++) {
            assertEquals(0, t.begin("ip:10.0.0.9"), "attempt " + i);
            t.success("ip:10.0.0.9"); // the attacker's own valid account in between
        }
        assertTrue(t.begin("ip:10.0.0.9") > 0);
        t.failure("ip:10.0.0.9");
        assertTrue(t.lockedFor("ip:10.0.0.9") > 0 && t.lockedFor("ip:10.0.0.9") <= LoginThrottle.ADDRESS_LOCK.toSeconds());
        clock.advance(LoginThrottle.ADDRESS_LOCK.plusSeconds(1));
        assertEquals(0, t.begin("ip:10.0.0.9"));
    }

    @Test
    void theAttemptsOfAKeyExpireWithTheirWindow() {
        fail("id:slow");
        fail("id:slow");
        clock.advance(LoginThrottle.USER_LOCK.plusSeconds(1));
        fail("id:slow");
        fail("id:slow");
        assertEquals(0, t.begin("id:slow"), "two old and two new failures are not four");
    }

    @Test
    void resetLiftsALock() {
        for (int i = 0; i < 3; i++) fail("id:rita");
        assertTrue(t.lockedFor("id:rita") > 0);
        assertTrue(t.reset("id:rita"));
        assertEquals(0, t.lockedFor("id:rita"));
        assertEquals(0, t.begin("id:rita"));
        assertFalse(t.reset("id:nobody"));
    }

    @Test
    void aStrangerLocksTheLoginOnlyForTheirOwnAddress() {
        String attacker = LoginThrottle.userKey("admin", "203.0.113.9");
        String owner = LoginThrottle.userKey("admin", "198.51.100.4");
        for (int i = 0; i < 3; i++) fail(attacker);
        assertTrue(t.begin(attacker) > 0, "locked for the attacker");
        assertEquals(0, t.begin(owner), "the owner's address is not affected");
        assertTrue(t.lockedForUser("admin") > 0);
        assertEquals(0, t.lockedForUser("adminx"), "another login is not matched by prefix");
    }

    @Test
    void resetUserLiftsTheLocksOfEveryAddress() {
        for (String a : new String[]{"10.0.0.1", "10.0.0.2"}) for (int i = 0; i < 3; i++) fail(LoginThrottle.userKey("rita", a));
        fail(LoginThrottle.userKey("ritaa", "10.0.0.1"));
        assertTrue(t.resetUser("rita"));
        assertEquals(0, t.lockedForUser("rita"));
        assertEquals(0, t.begin(LoginThrottle.userKey("rita", "10.0.0.1")));
        assertTrue(t.resetUser("ritaa"), "a login that only shares a prefix is kept apart");
        assertFalse(t.resetUser("nobody"));
    }

    @Test
    void theMapIsBoundedAndNeverFreezesOutNewKeys() {
        LoginThrottle small = new LoginThrottle(clock, 100);
        for (int i = 0; i < 5000; i++) assertEquals(0, small.begin("id:user" + i));
        assertEquals(100, small.size());
        assertEquals(0, small.begin("id:fresh"), "a new key is still admitted when full");
        assertTrue(small.size() <= 100);
    }

    @Test
    void expiredEntriesAreDroppedBeforeLiveOnes() {
        LoginThrottle small = new LoginThrottle(clock, 3);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, small.begin("id:locked"));
            small.failure("id:locked");
        }
        assertEquals(0, small.begin("id:a"));
        assertEquals(0, small.begin("id:b"));
        assertTrue(small.size() <= 3);
        clock.advance(LoginThrottle.USER_LOCK.plusSeconds(1));
        assertEquals(0, small.begin("id:c"));
        assertEquals(1, small.size(), "everything had expired but the new one");
    }

    @Test
    void aLockIsNeverEvictedToMakeRoom() {
        LoginThrottle small = new LoginThrottle(clock, 3);
        for (int i = 0; i < 3; i++) {
            assertEquals(0, small.begin("id:victim"));
            small.failure("id:victim");
        }
        assertTrue(small.lockedFor("id:victim") > 0);
        for (String k : new String[]{"id:a", "id:b", "id:c", "id:d", "id:e"}) {
            assertEquals(0, small.begin(k), "unlocked keys are evicted instead: " + k);
        }
        assertTrue(small.lockedFor("id:victim") > 0, "the lock survived the flood");
        assertTrue(small.begin("id:victim") > 0);
    }

    @Test
    void aTableFullOfLocksRefusesNewKeysUntilOneExpires() {
        LoginThrottle small = new LoginThrottle(clock, 2);
        for (String k : new String[]{"id:a", "id:b"}) {
            for (int i = 0; i < 3; i++) {
                assertEquals(0, small.begin(k));
                small.failure(k);
            }
        }
        long wait = small.begin("id:new");
        assertTrue(wait > 0, "refused: no slot without evicting a lock");
        assertEquals(2, small.size());
        assertTrue(small.lockedFor("id:a") > 0 && small.lockedFor("id:b") > 0);

        clock.advance(LoginThrottle.USER_LOCK.plusSeconds(1));
        assertEquals(0, small.begin("id:new"), "a slot is free once the locks have expired");
    }
}
