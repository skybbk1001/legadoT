package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class AutoTaskWakeControllerTest {
    private class Fake(
        var enabledValue: Boolean = true,
        initial: AutoTaskWakeState = AutoTaskWakeState(),
    ) {
        val now = AtomicLong(1_000_000L)
        var state = initial
        var persisted = initial
        var failSave = false
        val installed = mutableListOf<Triple<Long, Long, Boolean>>()
        var cancelled = 0
        val logs = mutableListOf<Pair<String, String>>()
        var starts = 0
        var failPrimary = false
        var failRecovery = false
        var installAttempts = 0
        val controller = AutoTaskWakeController(
            enabled = { enabledValue },
            now = now::get,
            load = { state },
            save = {
                state = it
                if (!failSave) persisted = it
                !failSave
            },
            alarms = object : AutoTaskWakeController.Alarms {
                override fun install(at: Long, generation: Long, recovery: Boolean) {
                    installAttempts++
                    if (if (recovery) failRecovery else failPrimary) throw SecurityException("alarm denied")
                    installed += Triple(at, generation, recovery)
                }
                override fun cancel() { cancelled++ }
            },
            log = { event, details -> logs += event to details },
        )
    }

    @Test
    fun scheduleInstallsPrimaryAndRecoveryWithOneGeneration() {
        val fake = Fake()
        assertTrue(fake.controller.schedule(2_000_000L, "test"))
        assertEquals(2, fake.installed.size)
        assertEquals(2_000_000L, fake.installed[0].first)
        assertEquals(2_300_000L, fake.installed[1].first)
        assertEquals(fake.installed[0].second, fake.installed[1].second)
        assertEquals(1L, fake.state.generation)
        assertFalse(fake.state.needsRecovery)
    }

    @Test
    fun staleDeliveryCannotStartAfterRescheduleOrCancel() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "first")
        val oldGeneration = fake.state.generation
        val oldAt = fake.state.primaryAt
        fake.controller.schedule(3_000_000L, "second")
        assertFalse(fake.controller.receive(oldGeneration, oldAt, false) { fake.starts++ })
        fake.controller.cancel("off")
        assertFalse(fake.controller.receive(fake.state.generation - 1, 3_000_000L, false) { fake.starts++ })
        assertEquals(0, fake.starts)
        assertEquals(1, fake.cancelled)
    }

    @Test
    fun validPrimaryDeliverySchedulesFallbackBeforeDispatch() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "test")
        fake.now.set(2_001_000L)
        val generation = fake.state.generation
        assertTrue(fake.controller.receive(generation, 2_000_000L, false) { fake.starts++ })
        assertEquals(1, fake.starts)
        assertEquals(2L, fake.state.generation)
        assertEquals(2_301_000L, fake.state.primaryAt)
        assertTrue(fake.logs.any { it.first == "wake.dispatched" })
    }

    @Test
    fun failedDispatchMarksRecoveryAndStillHasRetryPlan() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "test")
        fake.now.set(2_000_000L)
        val generation = fake.state.generation
        assertFalse(fake.controller.receive(generation, 2_000_000L, false) {
            throw IllegalStateException("not persisted")
        })
        assertTrue(fake.state.needsRecovery)
        assertTrue(fake.logs.any { it.first == "wake.dispatch_failed" })
        assertTrue(fake.installed.any { it.first == fake.state.primaryAt && it.third.not() })
    }

    @Test
    fun foregroundRecoveryIsThrottledAndCanBeForced() {
        val fake = Fake(initial = AutoTaskWakeState(primaryAt = 1_500_000L))
        assertTrue(fake.controller.recoverForeground(force = true, serviceAlive = { false }) { fake.starts++ })
        assertFalse(fake.controller.recoverForeground(force = true, serviceAlive = { false }) { fake.starts++ })
        fake.now.addAndGet(AutoTaskWakeController.RETRY_DELAY_MS + AutoTaskWakeController.MISSED_GRACE_MS + 1)
        assertTrue(fake.controller.recoverForeground(force = false, serviceAlive = { false }) { fake.starts++ })
        assertEquals(2, fake.starts)
    }

    @Test
    fun missedPrimaryCanRecoverInNewProcessFromPersistedState() {
        val old = Fake()
        old.controller.schedule(2_000_000L, "cron")
        val cold = Fake(initial = old.state)
        cold.now.set(old.state.recoveryAt)
        assertTrue(cold.controller.receive(old.state.generation, old.state.recoveryAt, true) {
            assertTrue(cold.installed.isNotEmpty()) // fallback is installed before dispatch
            cold.starts++
        })
        assertEquals(1, cold.starts)
        assertFalse(cold.controller.receive(old.state.generation, old.state.primaryAt, false) { cold.starts++ })
        assertEquals(1, cold.starts)
    }

    @Test
    fun duplicatePrimaryAndLateBackupCannotDispatchTwice() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "cron")
        val plan = fake.state
        fake.now.set(plan.recoveryAt)
        assertTrue(fake.controller.receive(plan.generation, plan.primaryAt, false) { fake.starts++ })
        assertFalse(fake.controller.receive(plan.generation, plan.primaryAt, false) { fake.starts++ })
        assertFalse(fake.controller.receive(plan.generation, plan.recoveryAt, true) { fake.starts++ })
        assertEquals(1, fake.starts)
    }

    @Test
    fun offThenOnDoesNotLetLateCancelOrOldAlarmDamageNewPlan() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "first")
        val old = fake.state
        fake.enabledValue = false
        fake.controller.cancelWhenDisabled("off")
        assertFalse(fake.controller.requestStart("late_request") { fake.starts++ })
        fake.enabledValue = true
        fake.controller.schedule(3_000_000L, "on")
        val newPlan = fake.state
        fake.controller.cancelWhenDisabled("late_off")
        assertEquals(newPlan, fake.state)
        fake.now.set(3_000_000L)
        assertFalse(fake.controller.receive(old.generation, old.primaryAt, false) { fake.starts++ })
        assertTrue(fake.controller.receive(newPlan.generation, newPlan.primaryAt, false) { fake.starts++ })
        assertEquals(1, fake.starts)
    }

    @Test
    fun healthyForegroundAndLiveServiceDoNotRearmOrStartAgain() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "cron")
        assertFalse(fake.controller.recoverForeground(false, { false }) { fake.starts++ })
        assertFalse(fake.controller.recoverForeground(true, { true }) { fake.starts++ })
        assertEquals(2, fake.installed.size)
        assertEquals(0, fake.starts)
    }

    @Test
    fun partialOrTotalAlarmFailureKeepsRepairState() {
        val fake = Fake()
        fake.failPrimary = true
        assertTrue(fake.controller.schedule(2_000_000L, "partial"))
        assertEquals(0L, fake.state.primaryAt)
        assertEquals(2_300_000L, fake.state.recoveryAt)
        assertTrue(fake.state.needsRecovery)
        fake.failRecovery = true
        assertFalse(fake.controller.schedule(2_000_000L, "total"))
        assertEquals(0L, fake.state.recoveryAt)
        assertTrue(fake.controller.recoverForeground(false, { false }) { fake.starts++ })
        assertEquals(1, fake.starts) // visible UI can still request a service when alarms fail
    }

    @Test
    fun deliveryWithUpdatedExtrasCannotExecuteEarly() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "cron")
        assertFalse(fake.controller.receive(fake.state.generation, fake.state.primaryAt, false) { fake.starts++ })
        assertEquals(0, fake.starts)
        assertTrue(fake.logs.any { it.first == "alarm.early" })
    }

    @Test
    fun delayedOldProducerCannotReplaceOrCancelNewPlan() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "old")
        val oldGeneration = fake.state.generation
        fake.controller.cancel("off")
        fake.controller.schedule(3_000_000L, "new")
        val plan = fake.state
        assertFalse(fake.controller.publishPlan(oldGeneration, 2_100_000L, "old_rules"))
        assertFalse(fake.controller.publishPlan(oldGeneration, null, "old_empty_rules"))
        assertFalse(fake.controller.publishPlan(plan.generation, null, "cancelled_scope") { false })
        assertEquals(plan, fake.state)
    }

    @Test
    fun processDeathBeforeInstallingNewAlarmCanBeRepairedByOldDelivery() {
        val cold = Fake(initial = AutoTaskWakeState(generation = 2, primaryAt = 4_000_000L,
            recoveryAt = 4_300_000L, needsRecovery = true, installing = true))
        cold.now.set(2_000_000L)
        assertFalse(cold.controller.receive(1, 2_000_000L, false) { cold.starts++ })
        assertEquals(0, cold.starts) // old delivery only repairs, never runs a task itself
        assertFalse(cold.state.installing)
        assertEquals(2_060_000L, cold.state.primaryAt)
        cold.now.set(cold.state.primaryAt)
        assertTrue(cold.controller.receive(cold.state.generation, cold.state.primaryAt, false) { cold.starts++ })
        assertEquals(1, cold.starts)
    }

    @Test
    fun processDeathAfterFirstInstallStillAcceptsThatNewAlarm() {
        val cold = Fake(initial = AutoTaskWakeState(generation = 2, primaryAt = 2_000_000L,
            recoveryAt = 2_300_000L, needsRecovery = true, installing = true))
        cold.now.set(2_000_000L)
        assertTrue(cold.controller.receive(2, 2_000_000L, false) { cold.starts++ })
        assertFalse(cold.state.installing)
        assertEquals(1, cold.starts)
    }

    @Test
    fun failedPreparePersistenceDoesNotReplaceExistingAlarms() {
        val fake = Fake()
        fake.controller.schedule(2_000_000L, "old")
        val old = fake.state
        fake.failSave = true
        assertFalse(fake.controller.schedule(3_000_000L, "new"))
        assertEquals(old, fake.state)
        assertEquals(old, fake.persisted)
        assertEquals(2, fake.installed.size)
        assertTrue(fake.logs.any { it.first == "alarm.state_persist_failed" })
    }

    @Test
    fun staleDeliveryDoesNotRestoreWhenUserHasDisabledTasks() {
        val fake = Fake(enabledValue = false, initial = AutoTaskWakeState(generation = 2,
            primaryAt = 2_000_000L, recoveryAt = 2_300_000L, needsRecovery = true, installing = true))
        fake.now.set(2_000_000L)
        assertFalse(fake.controller.receive(1, 2_000_000L, false) { fake.starts++ })
        assertEquals(0, fake.starts)
        assertTrue(fake.installed.isEmpty())
    }

    @Test
    fun disabledScheduleInvalidatesAndCancelsAlarms() {
        val fake = Fake(enabledValue = false)
        assertFalse(fake.controller.schedule(2_000_000L, "disabled"))
        assertEquals(1, fake.cancelled)
        assertEquals(1L, fake.state.generation)
    }

    // ---- background reconcile ----

    private fun plan(primaryAt: Long, recoveryAt: Long, at: Long) = AutoTaskWakeState(
        generation = 7, primaryAt = primaryAt, recoveryAt = recoveryAt, lastReceivedAt = at - 500_000L)

    @Test
    fun coldStartWithExpiredOrMissingPlanRepairsWithoutStartingService() {
        val expired = Fake(initial = plan(1_500_000L, 1_800_000L, at = 1_400_000L))
        expired.now.set(2_000_000L)
        assertTrue(expired.controller.reconcileBackground("boot", { false }))
        assertEquals(2_060_000L, expired.state.primaryAt)
        assertEquals(2_360_000L, expired.state.recoveryAt)
        assertEquals(8L, expired.state.generation)
        assertFalse(expired.state.installing)
        assertEquals(0, expired.starts)

        val absent = Fake(initial = AutoTaskWakeState(generation = 3, lastReceivedAt = 900_000L))
        absent.now.set(2_000_000L)
        assertTrue(absent.controller.reconcileBackground("package_replaced", { false }))
        assertEquals(2_060_000L, absent.state.primaryAt)
        assertEquals(0, absent.starts)
    }

    @Test
    fun latePrimaryWaitsForStillFutureBackup() {
        val fake = Fake(initial = plan(2_000_000L, 2_300_000L, at = 1_500_000L))
        val before = fake.state
        fake.now.set(2_100_000L) // primary late, backup still ahead
        assertFalse(fake.controller.reconcileBackground("late_primary", { false }))
        assertEquals(before, fake.state)
        assertTrue(fake.installed.isEmpty())
        assertTrue(fake.logs.any { it.first == "alarm.repair_skipped" && it.second.contains("plan_pending") })
    }

    @Test
    fun healthyFuturePlanIsNeverPushedForwardByRepeatedEntries() {
        val fake = Fake(initial = plan(2_000_000L, 2_300_000L, at = 1_500_000L))
        val before = fake.state
        fake.now.set(1_990_000L)
        assertFalse(fake.controller.reconcileBackground("boot", { false }))
        assertFalse(fake.controller.reconcileBackground("time_changed", { false }))
        assertFalse(fake.controller.reconcileBackground("app_foreground", { false }))
        fake.now.set(2_250_000L) // between primary and backup: still waiting, not repairing
        assertFalse(fake.controller.reconcileBackground("worker_retry", { false }))
        assertEquals(before, fake.state)
        assertTrue(fake.installed.isEmpty())
    }

    @Test
    fun disabledOrLiveServiceDoesNotRepair() {
        val disabled = Fake(enabledValue = false, initial = plan(1_500_000L, 1_800_000L, at = 1_400_000L))
        disabled.now.set(2_000_000L)
        assertFalse(disabled.controller.reconcileBackground("boot", { false }))
        assertTrue(disabled.installed.isEmpty())
        assertEquals(0, disabled.cancelled)

        val live = Fake(initial = plan(1_500_000L, 1_800_000L, at = 1_400_000L))
        live.now.set(2_000_000L)
        assertFalse(live.controller.reconcileBackground("boot", { true }))
        assertTrue(live.installed.isEmpty())
        assertTrue(live.logs.any { it.second.contains("why=service_alive") })
    }

    @Test
    fun failedRepairIsThrottledThenRetryableAfterWindow() {
        val fake = Fake(initial = plan(1_500_000L, 1_800_000L, at = 1_400_000L))
        fake.now.set(2_000_000L)
        fake.failPrimary = true
        fake.failRecovery = true
        assertFalse(fake.controller.reconcileBackground("boot", { false }))
        assertEquals(2, fake.installAttempts)
        fake.failPrimary = false
        fake.failRecovery = false
        assertFalse(fake.controller.reconcileBackground("boot_again", { false })) // throttled
        assertEquals(2, fake.installAttempts)
        assertTrue(fake.logs.any { it.second.contains("why=throttled") })
        fake.now.addAndGet(AutoTaskWakeController.REPAIR_THROTTLE_MS + 1)
        assertTrue(fake.controller.reconcileBackground("boot_after_window", { false }))
        assertEquals(4, fake.installAttempts)
    }

    @Test
    fun repairBackoffAndForegroundThrottleAreIndependent() {
        val repairedFirst = Fake(initial = plan(1_500_000L, 1_800_000L, at = 1_400_000L))
        repairedFirst.now.set(2_000_000L)
        assertTrue(repairedFirst.controller.reconcileBackground("boot", { false }))
        assertFalse(repairedFirst.controller.reconcileBackground("boot_again", { false }))
        assertTrue(repairedFirst.controller.recoverForeground(force = true, serviceAlive = { false }) {
            repairedFirst.starts++
        })
        assertEquals(1, repairedFirst.starts) // repair backoff must not swallow a foreground request

        val foregroundFirst = Fake(initial = plan(1_500_000L, 1_800_000L, at = 1_400_000L))
        foregroundFirst.now.set(2_000_000L)
        assertTrue(foregroundFirst.controller.recoverForeground(force = true, serviceAlive = { false }) {
            foregroundFirst.starts++
        })
        assertFalse(foregroundFirst.controller.reconcileBackground("boot", { false }))
        assertEquals(1, foregroundFirst.starts) // repairing alarms never starts anything
        assertEquals(2, foregroundFirst.installed.size)
    }

    @Test
    fun partialInstallFailureKeepsWorkingFutureBackup() {
        val fake = Fake(initial = plan(1_500_000L, 1_800_000L, at = 1_400_000L))
        fake.now.set(2_000_000L)
        fake.failPrimary = true
        assertTrue(fake.controller.reconcileBackground("boot", { false }))
        assertEquals(0L, fake.state.primaryAt)
        assertEquals(2_360_000L, fake.state.recoveryAt)
        assertTrue(fake.state.needsRecovery)
        fake.failPrimary = false
        val partial = fake.state
        fake.now.set(2_200_000L)
        assertFalse(fake.controller.reconcileBackground("boot_again", { false }))
        assertFalse(fake.controller.reconcileBackground("worker_retry", { false }))
        assertEquals(partial, fake.state) // needsRecovery must not walk the backup forward
        fake.now.set(2_400_000L) // backup now expired past grace
        assertTrue(fake.controller.reconcileBackground("boot_late", { false }))
        assertEquals(2_460_000L, fake.state.primaryAt)
    }

    // ---- continuation for exhausted service budget ----

    @Test
    fun continuationKeepsAnEarlierPlanAndNeverStartsService() {
        // next alarm is still ahead but earlier than now + RESTORE_DELAY: keep it untouched.
        val fake = Fake(initial = plan(1_830_000L, 2_130_000L, at = 1_500_000L))
        val before = fake.state
        fake.now.set(1_800_000L)
        assertFalse(fake.controller.requestContinuation("budget_exhausted"))
        assertEquals(before, fake.state)
        assertTrue(fake.installed.isEmpty())
        assertEquals(0, fake.starts)
        assertTrue(fake.logs.any { it.second.contains("why=plan_earlier") })
    }

    @Test
    fun continuationPullsAnAlarmForwardAndBumpsGeneration() {
        val fake = Fake(initial = plan(2_000_000L, 2_300_000L, at = 1_500_000L))
        val oldGeneration = fake.state.generation
        fake.now.set(1_800_000L)
        assertTrue(fake.controller.requestContinuation("budget_exhausted"))
        assertEquals(1_860_000L, fake.state.primaryAt)
        assertEquals(2_160_000L, fake.state.recoveryAt)
        assertTrue(fake.state.generation > oldGeneration)
        assertEquals(0, fake.starts)
        assertFalse(fake.controller.publishPlan(oldGeneration, 1_870_000L, "old_rules"))
        assertEquals(1_860_000L, fake.state.primaryAt)
    }

    @Test
    fun directRequestKeepsEarlierCronInsteadOfDelayingFiveMinutes() {
        val fake = Fake()
        fake.controller.schedule(fake.now.get() + 20_000, "cron")
        val at = fake.state.primaryAt
        fake.controller.requestStart("ui_refresh") { fake.starts++ }
        assertEquals(at, fake.state.primaryAt)
        assertEquals(1, fake.starts)
    }

    @Test
    fun continuationHonorsTaskBackoffWhenNoEarlierPlanExists() {
        val fake = Fake()
        assertTrue(fake.controller.requestContinuation("task_timeout", fake.now.get() + 300_000))
        assertEquals(fake.now.get() + 300_000, fake.state.primaryAt)
        assertFalse(fake.controller.requestContinuation("repeat", fake.now.get() + 400_000))
        assertEquals(fake.now.get() + 300_000, fake.state.primaryAt)
    }

    @Test
    fun continuationIgnoresDisabledTasks() {
        val fake = Fake(enabledValue = false, initial = plan(2_000_000L, 2_300_000L, at = 1_500_000L))
        val before = fake.state
        fake.now.set(1_800_000L)
        assertFalse(fake.controller.requestContinuation("budget_exhausted"))
        assertEquals(before, fake.state)
        assertTrue(fake.installed.isEmpty())
        assertEquals(0, fake.starts)
    }
}
