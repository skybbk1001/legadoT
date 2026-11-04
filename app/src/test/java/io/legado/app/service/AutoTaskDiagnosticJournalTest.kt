package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AutoTaskDiagnosticJournalTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun appendsAcrossReopenWithoutTruncatingEarlierEvents() {
        val directory = temporary.newFolder("logs")
        assertTrue(AutoTaskDiagnosticJournal(directory).append("first"))
        assertTrue(AutoTaskDiagnosticJournal(directory).append("second"))
        assertEquals(listOf("first", "second"), File(directory, AutoTaskDiagnosticJournal.CURRENT_FILE).readLines())
    }

    @Test
    fun rotatesToOnlyTwoBoundedFilesAndKeepsNewestEvent() {
        val directory = temporary.newFolder("logs")
        val max = AutoTaskDiagnosticJournal.MAX_LINE_BYTES.toLong()
        val journal = AutoTaskDiagnosticJournal(directory, max)
        repeat(40) { assertTrue(journal.append("event=$it " + "中".repeat(6_000))) }
        val current = File(directory, AutoTaskDiagnosticJournal.CURRENT_FILE)
        val previous = File(directory, AutoTaskDiagnosticJournal.PREVIOUS_FILE)
        assertTrue(current.readText().contains("event=39 "))
        assertTrue(previous.exists())
        assertTrue(current.length() <= max)
        assertTrue(previous.length() <= max)
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test
    fun untrustedMetadataCannotInjectExtraLogLines() {
        val directory = temporary.newFolder("logs")
        val line = AutoTaskDiagnosticJournal.format("pid\r\nspoof", "task\tbegin", "name=a\u2028b\u2029c\u0000", 0L)
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
        assertFalse(line.contains('\u2028'))
        assertFalse(line.contains('\u2029'))
        assertTrue(line.contains("[AutoTaskDiag] service=pid  spoof event=task begin"))
        assertTrue(AutoTaskDiagnosticJournal(directory).append(line + "\nnext"))
        assertEquals(1, File(directory, AutoTaskDiagnosticJournal.CURRENT_FILE).readLines().size)
    }

    @Test
    fun storageFailureReturnsFalseAndCanRecover() {
        val blocked = temporary.newFile("not-a-directory")
        val journal = AutoTaskDiagnosticJournal(blocked)
        assertFalse(journal.append("unavailable"))
        assertFalse(journal.append("still unavailable"))
        val good = AutoTaskDiagnosticJournal(temporary.newFolder("available"))
        assertTrue(good.append("available"))
    }

    @Test
    fun concurrentAppendsRemainWholeAndOrderedPerWriter() {
        val directory = temporary.newFolder("logs")
        val journal = AutoTaskDiagnosticJournal(directory)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..100).map { id -> executor.submit<Boolean> { journal.append("event=$id") } }
            futures.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            val lines = File(directory, AutoTaskDiagnosticJournal.CURRENT_FILE).readLines()
            assertEquals(100, lines.size)
            assertEquals((1..100).map { "event=$it" }.toSet(), lines.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun overloadDropsOldestAndReportsLossWithoutBlockingProducer() {
        val queue = AutoTaskDiagnosticQueue(2)
        queue.offer("first")
        queue.offer("second")
        queue.offer("third")
        assertEquals("second droppedBeforeWrite=1", queue.take())
        assertEquals("third", queue.take())
        queue.offer("fourth")
        assertEquals("fourth", queue.take())
    }
}
