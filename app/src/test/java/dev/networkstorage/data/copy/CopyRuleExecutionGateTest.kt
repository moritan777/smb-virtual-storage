package dev.networkstorage.data.copy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CopyRuleExecutionGateTest {
    @Test
    fun `same rule executions are serialized`() = runTest {
        val gate = CopyRuleExecutionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            gate.withRuleLock("rule-1") {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val second = async {
            gate.withRuleLock("rule-1") {
                order += "second"
            }
        }

        testScheduler.runCurrent()
        assertEquals(listOf("first-start"), order)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun `different rules do not share one execution lock`() = runTest {
        val gate = CopyRuleExecutionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            gate.withRuleLock("rule-1") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            gate.withRuleLock("rule-2") {
                secondEntered.complete(Unit)
            }
        }

        secondEntered.await()
        releaseFirst.complete(Unit)
        first.await()
        second.await()
    }
}
