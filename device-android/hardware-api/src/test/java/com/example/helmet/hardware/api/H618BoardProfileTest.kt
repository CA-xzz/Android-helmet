package com.example.helmet.hardware.api

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H618BoardProfileTest {
    @Test
    fun profileCoversEverySpreadsheetRowAndKeepsConflictsBlocked() {
        val profile = H618BoardProfile.profile
        val coveredRows = profile.resources.flatMap(BoardResourceDefinition::spreadsheetRows)

        assertEquals((2..45).toList(), coveredRows.sorted())
        assertEquals(coveredRows.size, coveredRows.toSet().size)
        assertTrue(H618BoardProfile.validation.productionSafe)
        assertTrue(
            H618BoardProfile.validation.issues.any {
                it.code == "BLOCKED_PIN_CONFLICT" &&
                    setOf("camera_power_enable", "mma8452_int2").all(it.resourceIds::contains)
            },
        )
        assertTrue(
            H618BoardProfile.validation.issues.any {
                it.code == "BLOCKED_PIN_CONFLICT" &&
                    setOf("hsl_uart2", "mma8452_i2c0").all(it.resourceIds::contains)
            },
        )
        assertTrue(
            H618BoardProfile.validation.issues.any {
                it.code == "BLOCKED_PIN_CONFLICT" && "modem_pwrkey" in it.resourceIds
            },
        )
    }

    @Test
    fun duplicateSpreadsheetRowRejectsProfile() {
        val duplicate = H618BoardProfile.profile.resources.first().copy(id = "duplicate-row")
        val validation = BoardProfileValidator.validate(
            H618BoardProfile.profile.copy(resources = H618BoardProfile.profile.resources + duplicate),
        )

        assertFalse(validation.productionSafe)
        assertTrue(validation.issues.any { it.code == "DUPLICATE_SPREADSHEET_ROW" })
    }

    @Test
    fun exportedHardwareContractMatchesCheckedInHandoffFiles() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val jsonFile = File(root, "docs/hardware-contract/h618-helmet-v3.json")
        val markdownFile = File(root, "docs/hardware-contract/H618_APP_HARDWARE_CONTRACT.md")
        val json = HardwareContractExporter.toJson(H618BoardProfile.profile)
        val markdown = HardwareContractExporter.toMarkdown(H618BoardProfile.profile)
        if (System.getenv("UPDATE_HARDWARE_CONTRACT") == "1") {
            jsonFile.parentFile?.mkdirs()
            jsonFile.writeText(json)
            markdownFile.writeText(markdown)
        }

        assertTrue("missing generated JSON handoff", jsonFile.isFile)
        assertTrue("missing generated Markdown handoff", markdownFile.isFile)
        assertEquals(json, jsonFile.readText())
        assertEquals(markdown, markdownFile.readText())
    }

    @Test
    fun uart4AndHslHaveDistinctConfirmedDeviceNodes() {
        val byId = H618BoardProfile.profile.resources.associateBy(BoardResourceDefinition::id)

        assertEquals(setOf("/dev/ttyAS2"), byId.getValue("hsl_uart2").endpoints)
        assertEquals(setOf("PI5", "PI6"), byId.getValue("hsl_uart2").socPins)
        assertEquals(setOf("/dev/ttyAS4"), byId.getValue("rtk_uart4").endpoints)
        assertEquals(setOf("PI13", "PI14"), byId.getValue("rtk_uart4").socPins)
        assertFalse(byId.getValue("rtk_uart4").enabled)
    }

    @Test
    fun enablingConflictingMmaBusRejectsProfile() {
        val resources = H618BoardProfile.profile.resources.map {
            if (it.id == "mma8452_i2c0") it.copy(enabled = true) else it
        }

        val validation = BoardProfileValidator.validate(H618BoardProfile.profile.copy(resources = resources))

        assertFalse(validation.productionSafe)
        assertTrue(
            validation.issues.any {
                it.code == "ACTIVE_PIN_CONFLICT" &&
                    setOf("hsl_uart2", "mma8452_i2c0").all(it.resourceIds::contains)
            },
        )
    }

    @Test
    fun arbitraryDevicePathIsRejected() {
        val invalid = H618BoardProfile.profile.resources.first().copy(
            id = "invalid-path",
            endpoints = setOf("/dev/mem"),
        )

        val validation = BoardProfileValidator.validate(
            H618BoardProfile.profile.copy(resources = H618BoardProfile.profile.resources + invalid),
        )

        assertFalse(validation.productionSafe)
        assertTrue(validation.issues.any { it.code == "INVALID_ENDPOINT" })
    }

    @Test
    fun blockedPowerControlCannotBeEnabled() {
        val resources = H618BoardProfile.profile.resources.map {
            if (it.id == "peripheral_3v3_enable") it.copy(enabled = true) else it
        }

        val validation = BoardProfileValidator.validate(H618BoardProfile.profile.copy(resources = resources))

        assertFalse(validation.productionSafe)
        assertTrue(validation.issues.any { it.code == "ENABLED_CONTROL_IS_BLOCKED" })
    }
}
