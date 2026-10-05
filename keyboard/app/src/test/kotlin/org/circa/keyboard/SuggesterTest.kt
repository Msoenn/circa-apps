package org.circa.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SuggesterTest {
    private val small = Suggester(listOf("the" to 222, "that" to 200, "this" to 190, "there" to 170, "then" to 160,
        "thermal" to 60, "in" to 210, "into" to 150, "I" to 205, "Monday" to 120, "inn" to 40))

    @Test fun mostFrequentFirst() {
        assertEquals(listOf("the", "that", "this"), small.suggest("th"))
    }

    @Test fun exactWordStaysInFront() {
        // "in" is a word: it beats the more frequent... nothing, but "inn" is not pushed above "into".
        assertEquals(listOf("in", "into", "inn"), small.suggest("in"))
        assertEquals("then", small.suggest("then").first())
    }

    @Test fun lowerCaseWordIsNotPinnedToAName() {
        val s = Suggester(listOf("Tom" to 150, "tomorrow" to 170, "tomb" to 90))
        assertEquals(listOf("tomorrow", "Tom", "tomb"), s.suggest("tom"))
        assertEquals("Tom", s.suggest("Tom").first())
    }

    @Test fun caseFollowsTheTypedWord() {
        assertEquals(listOf("The", "That", "This"), small.suggest("Th"))
        assertEquals(listOf("THE", "THAT", "THIS"), small.suggest("TH"))
        assertEquals("I", small.suggest("i").first())
        assertEquals("Monday", small.suggest("mon").first())
    }

    @Test fun learnedWordsRise() {
        val s = Suggester(listOf("the" to 222, "that" to 200, "this" to 190, "thermal" to 60))
        assertTrue("thorvald" !in s.suggest("th"))
        repeat(3) { s.learn("Thorvald") }
        // Three uses put it above the rare dictionary word, not above "the"/"that"/"this".
        assertEquals(listOf("the", "that", "this", "thorvald"), s.suggest("th", 4))
        assertEquals("thorvald", s.suggest("tho").first())
        repeat(5) { s.learn("thermal") }
        assertEquals("thermal", s.suggest("the").let { it[1] }) // after the exact word "the"
    }

    @Test fun learnRejectsJunk() {
        val s = Suggester(emptyList())
        s.learn("a"); s.learn("abc123"); s.learn("x".repeat(40)); s.learn("")
        assertTrue(s.learnedSnapshot().isEmpty())
        s.learn("don't")
        assertEquals(1, s.learnedCount("DON'T"))
    }

    @Test fun learnedStoreIsBounded() {
        val s = Suggester(emptyList())
        for (i in 0 until Suggester.MAX_LEARNED + 50) s.learn("w" + "abcdefghij"[i % 10] + i.toString().map { 'a' + (it - '0') }.joinToString(""))
        assertTrue(s.learnedSnapshot().size <= Suggester.MAX_LEARNED)
    }

    @Test fun nothingForEmptyOrNonWords() {
        assertTrue(small.suggest("").isEmpty())
        assertTrue(small.suggest("123").isEmpty())
        assertTrue(small.suggest("zzzq").isEmpty())
    }

    @Test fun currentWord() {
        assertEquals("hel", Suggester.currentWord("say hel"))
        assertEquals("don't", Suggester.currentWord("I don't"))
        assertEquals("", Suggester.currentWord("hello "))
        assertEquals("", Suggester.currentWord(null))
        assertEquals("b", Suggester.currentWord("a.b"))
    }

    @Test fun bundledDictionaryLoadsAndRanks() {
        val f = File("src/main/res/raw/words_en.txt")
        val words = f.bufferedReader().useLines { Suggester.parse(it) }
        val s = Suggester(words)
        assertTrue("dictionary has ${s.size} words", s.size > 10000)
        assertEquals("the", s.suggest("th").first())
        assertTrue(s.suggest("hel").contains("hello") || s.suggest("hel").contains("help"))
        assertEquals("I", s.suggest("i").first())
        assertTrue(s.isKnown("watch"))
    }
}
