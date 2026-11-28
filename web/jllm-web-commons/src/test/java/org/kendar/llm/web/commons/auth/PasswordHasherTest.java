package org.kendar.llm.web.commons.auth;

import org.junit.jupiter.api.Test;
import org.kendar.llm.harness.identity.User;
import org.kendar.llm.web.commons.me.MeApi;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void theDefaultPasswordPathPaysOnePbkdf2LikeEveryOtherPath() {
        long before = PasswordHasher.burnCount();
        assertTrue(PasswordHasher.verify("rita", "rita", null));
        assertFalse(PasswordHasher.verify("rita", "nope", ""));
        assertEquals(2, PasswordHasher.burnCount() - before, "no stored hash: the time of one hash is spent, not skipped");

        before = PasswordHasher.burnCount();
        String stored = PasswordHasher.hash("correct horse");
        assertTrue(PasswordHasher.verify("rita", "correct horse", stored));
        assertEquals(0, PasswordHasher.burnCount() - before, "a stored hash already costs one PBKDF2; no extra burn");
    }

    @Test
    void anOversizedOrZeroIterationCountMatchesNothingAndIsNotComputed() {
        String h = PasswordHasher.hash("secret-password");
        String[] p = h.split("\\$");
        long start = System.nanoTime();
        assertFalse(PasswordHasher.verify("x", "secret-password", "pbkdf2$2000000000$" + p[2] + "$" + p[3]));
        assertFalse(PasswordHasher.verify("x", "secret-password",
                "pbkdf2$" + (PasswordHasher.MAX_ITERATIONS + 1) + "$" + p[2] + "$" + p[3]));
        assertFalse(PasswordHasher.verify("x", "secret-password", "pbkdf2$0$" + p[2] + "$" + p[3]));
        assertFalse(PasswordHasher.verify("x", "secret-password", "pbkdf2$-5$" + p[2] + "$" + p[3]));
        assertFalse(PasswordHasher.verify("x", "secret-password", "pbkdf2$notanumber$" + p[2] + "$" + p[3]));
        assertTrue((System.nanoTime() - start) / 1_000_000_000L < 5, "refused without running the hash");
        assertTrue(PasswordHasher.verify("x", "secret-password", h), "the real one still works");
    }

    @Test
    void aTemporaryHashVerifiesAndIsMarked() {
        String t = PasswordHasher.hashTemporary("one-time-pass");

        assertTrue(t.startsWith(User.TEMPORARY_PREFIX + "pbkdf2$"));
        assertTrue(PasswordHasher.verify("x", "one-time-pass", t));
        assertFalse(PasswordHasher.verify("x", "other", t));
        User u = new User("x", "x", "reader", null, t, null, null, null, null, null);
        assertTrue(u.temporaryPassword() && u.mustChangePassword() && !u.usesDefaultPassword());
        User normal = new User("x", "x", "reader", null, PasswordHasher.hash("a-good-password"), null, null, null, null, null);
        assertFalse(normal.mustChangePassword());
        assertTrue(new User("x", "x", "reader", null).mustChangePassword(), "the default password must be changed too");
    }

    @Test
    void generatedPasswordsAreLongRandomAndWithoutLookAlikes() {
        String a = PasswordHasher.generate();
        String b = PasswordHasher.generate();

        assertEquals(PasswordHasher.GENERATED_LENGTH, a.length());
        assertNotEquals(a, b);
        assertTrue(a.matches("[A-HJ-NP-Za-km-z2-9]+"), a);
        assertNull(MeApi.invalidPassword("x", a), "it passes the password rules");
    }

    @Test
    void malformedHashesMatchNothing() {
        assertFalse(PasswordHasher.verify("x", "p", "garbage"));
        assertFalse(PasswordHasher.verify("x", "p", "pbkdf2$1$!!!$!!!"));
        assertFalse(PasswordHasher.verify("x", "p", "temp$garbage"));
        assertFalse(PasswordHasher.verify("x", null, null));
    }
}
