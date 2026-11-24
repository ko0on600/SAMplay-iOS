package com.nuvio.app.features.ai

import com.nuvio.app.features.reshaped.sync.SyncDoc
import com.nuvio.app.features.reshaped.sync.SyncSections
import kotlinx.serialization.json.JsonElement
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/** SAMplay: Pro AI keys going from the phone to the TV (and back) through the Google sync file. */
class ProAiSyncTest {
    private val shared = "settings/shared"

    private fun stored(
        keys: Map<ProProvider, String> = emptyMap(),
        models: Map<ProProvider, String> = emptyMap(),
        provider: ProProvider = ProProvider.CLAUDE,
    ) = ProAiSync.Stored(keys, models, provider)

    /** One sync round of a device: what it uploads, and the changes it applies here. */
    private fun round(
        base: SyncSections,
        remote: SyncSections,
        here: ProAiSync.Stored,
        now: Long,
    ): Triple<SyncSections, List<ProAiSync.Change>, Map<String, JsonElement>> {
        val current = mapOf(shared to ProAiSync.values(here))
        val local = SyncDoc.stamp(base, current, now)
        val merged = SyncDoc.merge(local, remote)
        val changes = ProAiSync.changes(
            current[shared].orEmpty(),
            merged[shared].orEmpty().mapValues { (_, entry) -> entry.value },
            here.provider,
        )
        return Triple(merged, changes, current[shared].orEmpty())
    }

    @Test
    fun aDeviceWithoutKeysSendsNothing() {
        assertTrue(ProAiSync.values(stored()).isEmpty())
        assertTrue(ProAiSync.values(stored(keys = mapOf(ProProvider.GEMINI to "  "))).isEmpty())
    }

    @Test
    fun keysModelsAndTheChosenServiceGoFromThePhoneToANewTV() {
        val phone = stored(
            keys = mapOf(ProProvider.GEMINI to "AIza-phone", ProProvider.CLAUDE to "sk-ant-phone"),
            models = mapOf(ProProvider.GEMINI to "gemini-2.5-pro"),
            provider = ProProvider.GEMINI,
        )
        val (file, phoneChanges, _) = round(emptyMap(), emptyMap(), phone, now = 1_000)
        assertTrue(phoneChanges.isEmpty())

        val (_, tvChanges, _) = round(emptyMap(), file, stored(), now = 2_000)
        assertEquals(
            setOf(
                ProAiSync.Change.Key(ProProvider.GEMINI, "AIza-phone"),
                ProAiSync.Change.Key(ProProvider.CLAUDE, "sk-ant-phone"),
                ProAiSync.Change.Model(ProProvider.GEMINI, "gemini-2.5-pro"),
                ProAiSync.Change.Select(ProProvider.GEMINI),
            ),
            tvChanges.toSet(),
        )
    }

    @Test
    fun aNewTVWithItsOwnKeyDoesNotReplaceTheAccountSKey() {
        val (file, _, _) = round(emptyMap(), emptyMap(), stored(keys = mapOf(ProProvider.CLAUDE to "sk-ant-phone")), now = 1_000)
        // The TV never synced: its own values count as older than the account's.
        val (_, tvChanges, _) = round(emptyMap(), file, stored(keys = mapOf(ProProvider.CLAUDE to "sk-ant-tv")), now = 5_000)
        assertEquals(listOf<ProAiSync.Change>(ProAiSync.Change.Key(ProProvider.CLAUDE, "sk-ant-phone")), tvChanges)
    }

    @Test
    fun aKeyChangedLaterOnTheTVReachesThePhone() {
        val phone = stored(keys = mapOf(ProProvider.CLAUDE to "sk-ant-old"))
        val (file1, _, _) = round(emptyMap(), emptyMap(), phone, now = 1_000)
        val tvBase = file1
        val (file2, _, _) = round(tvBase, file1, stored(keys = mapOf(ProProvider.CLAUDE to "sk-ant-new")), now = 2_000)
        val (_, phoneChanges, _) = round(file1, file2, phone, now = 3_000)
        assertEquals(listOf<ProAiSync.Change>(ProAiSync.Change.Key(ProProvider.CLAUDE, "sk-ant-new")), phoneChanges)
    }

    @Test
    fun aKeyRemovedOnOneDeviceIsRemovedOnTheOther() {
        val phone = stored(keys = mapOf(ProProvider.GROK to "xai-key"), provider = ProProvider.GROK)
        val (file1, _, _) = round(emptyMap(), emptyMap(), phone, now = 1_000)
        // The TV had it, then the viewer cleared it there.
        val (file2, _, _) = round(file1, file1, stored(provider = ProProvider.GROK), now = 2_000)
        val (_, phoneChanges, _) = round(file1, file2, phone, now = 3_000)
        assertTrue(phoneChanges.contains(ProAiSync.Change.Key(ProProvider.GROK, "")))
    }

    @Test
    fun nothingChangesWhenBothDevicesAgree() {
        val same = stored(keys = mapOf(ProProvider.CHATGPT to "sk-x"), provider = ProProvider.CHATGPT)
        val (file, _, _) = round(emptyMap(), emptyMap(), same, now = 1_000)
        val (_, changes, _) = round(file, file, same, now = 2_000)
        assertTrue(changes.isEmpty())
    }

    @Test
    fun otherSharedSettingsAreLeftAlone() {
        val merged = mapOf<String, JsonElement?>(
            "autosync_tolerance_ms" to kotlinx.serialization.json.JsonPrimitive(200),
            "live_tv" to null,
        )
        assertTrue(ProAiSync.changes(emptyMap(), merged, ProProvider.CLAUDE).isEmpty())
    }
}
