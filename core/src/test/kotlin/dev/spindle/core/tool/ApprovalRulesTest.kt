package dev.spindle.core.tool

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The approval-pattern glob: `*`/`**`/`?` widen a rule, every other character
 * (regex metacharacters included) is literal, and a wildcard-free pattern is
 * plain equality.
 */
class ApprovalRulesTest {

    @Test
    fun `single star matches within one segment only`() {
        assertTrue(ApprovalRules.matches("src/*.kt", "src/Main.kt"))
        assertFalse(ApprovalRules.matches("src/*.kt", "src/nested/Main.kt"))
        assertTrue(ApprovalRules.matches("git *", "git status"))
        assertFalse(ApprovalRules.matches("git *", "git/status"))
        assertFalse(ApprovalRules.matches("git *", "git"))
    }

    @Test
    fun `double star matches across separators and whitespace`() {
        assertTrue(ApprovalRules.matches("src/**", "src/a/b/Main.kt"))
        assertTrue(ApprovalRules.matches("**", "anything at all/with/slashes"))
        assertTrue(ApprovalRules.matches("a/**/z", "a/b/c/z"))
    }

    @Test
    fun `question mark matches exactly one non-separator`() {
        assertTrue(ApprovalRules.matches("a?c", "abc"))
        assertFalse(ApprovalRules.matches("a?c", "ac"))
        assertFalse(ApprovalRules.matches("a?c", "a/c"))
        assertFalse(ApprovalRules.matches("a?c", "abbc"))
    }

    @Test
    fun `no wildcard is exact equality`() {
        assertTrue(ApprovalRules.matches("git", "git"))
        assertFalse(ApprovalRules.matches("git", "gits"))
        assertFalse(ApprovalRules.matches("git", "Git"))
    }

    @Test
    fun `regex metacharacters are literal`() {
        assertTrue(ApprovalRules.matches("a.b", "a.b"))
        assertFalse(ApprovalRules.matches("a.b", "axb"))
        assertTrue(ApprovalRules.matches("a+b", "a+b"))
        assertTrue(ApprovalRules.matches("(x)[y]{2}^$", "(x)[y]{2}^$"))
        assertFalse(ApprovalRules.matches("a+", "aaa"))
    }

    @Test
    fun `empty pattern matches only the empty value`() {
        assertTrue(ApprovalRules.matches("", ""))
        assertFalse(ApprovalRules.matches("", "x"))
    }
}
