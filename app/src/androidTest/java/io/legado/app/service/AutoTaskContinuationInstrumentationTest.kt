package io.legado.app.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.model.AutoTaskRule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutoTaskContinuationInstrumentationTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun checkpointSurvivesStoreRecreationAndDoesNotContainScripts() {
        val name = "auto_task_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
        }
        try {
            val rule = AutoTaskRule(id = "pending", name = "test", cron = "*/5 * * * *", script = "secret-script", header = "secret-header")
            val first = AutoTaskContinuationStore(context)
            assertEquals(listOf("pending"), first.reconcile(listOf(rule), setOf(rule.id)))
            first.started(rule.id, 0, 123)
            first.defer(rule.id, 0, 500)
            val restored = AutoTaskContinuationStore(context)
            assertEquals(listOf("pending"), restored.reconcile(listOf(rule), emptySet()))
            assertTrue(restored.eligibleIds(499).isEmpty())
            assertEquals(setOf("pending"), restored.eligibleIds(500))
            val stored = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("pending", "")!!
            assertFalse(stored.contains("secret-script"))
            assertFalse(stored.contains("secret-header"))
            restored.completed(rule.id, 0)
            assertFalse(AutoTaskContinuationStore(context).hasPending())
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun staleCompletionCannotClearNewOccurrenceAndDisabledTaskIsRemoved() {
        val name = "auto_task_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String?, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
        }
        try {
            val rule = AutoTaskRule(id = "one", lastRunAt = 100)
            val store = AutoTaskContinuationStore(context)
            store.reconcile(listOf(rule), setOf(rule.id))
            store.completed(rule.id, 0)
            assertTrue(store.hasPending())
            store.reconcile(listOf(rule.copy(enable = false)), emptySet())
            assertFalse(store.hasPending())
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun executionWakeLeaseCanBeReleasedTwice() {
        AutoTaskWakeLease(base, "instrumentation").use { lease ->
            lease.acquire(1000)
            lease.close()
        }
    }
}
