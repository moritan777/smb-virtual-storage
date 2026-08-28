package dev.networkstorage.data.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbBoundaryContractTest {
    @Test
    fun `read only boundary exposes no mutation operations`() {
        val methodNames = SmbClient::class.java.methods.map { it.name }.toSet()

        listOf(
            "createDirectories",
            "createPart",
            "promotePart",
            "moveToBackup",
            "restoreBackup",
            "removePart",
            "removePromotedForRestore",
        ).forEach { mutation ->
            assertFalse("SmbClient must remain read-only: $mutation", mutation in methodNames)
        }
    }

    @Test
    fun `copy boundary exposes only scoped mutation vocabulary`() {
        val declared = SmbCopyClient::class.java.declaredMethods.map { it.name }.toSet()

        assertTrue("exists" in declared)
        assertTrue("createDirectories" in declared)
        assertTrue("createPart" in declared)
        assertTrue("openPartRead" in declared)
        assertTrue("promotePart" in declared)
        assertTrue("moveToBackup" in declared)
        assertTrue("restoreBackup" in declared)
        assertTrue("removePart" in declared)
        assertTrue("removePromotedForRestore" in declared)

        listOf("delete", "remove", "rename", "move", "write").forEach { genericMutation ->
            assertFalse(
                "SmbCopyClient must not expose generic mutation API: $genericMutation",
                genericMutation in declared,
            )
        }
    }
}
