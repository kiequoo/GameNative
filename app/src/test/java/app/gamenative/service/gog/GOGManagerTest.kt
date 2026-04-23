package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGCloudSavesLocation
import app.gamenative.data.GOGGame
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GOGManagerTest {
    @Test
    fun `syncCloudSaves returns InProgress when same app is already syncing`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = mock<GOGCloudSavesManager>(),
        )
        manager.startSync("GOG_123")

        val result = manager.syncCloudSaves("GOG_123", SaveLocation.None)

        assertEquals(SyncResult.InProgress, result.syncResult)
        tempDir.delete()
    }

    @Test
    fun `syncCloudSaves returns Success when all locations sync successfully`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)
        createAuthFile(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = spyk(
            GOGManager(
                gogGameDao = mock<GOGGameDao>(),
                context = context,
                cloudSavesManager = cloudSavesManager,
            ),
        )
        val locations = listOf(
            createLocation(tempDir.root, "slot-success-a", "client-a", "secret-a"),
            createLocation(tempDir.root, "slot-success-b", "client-b", "secret-b"),
        )

        coEvery { manager.getGameFromDbById("123") } returns GOGGame(id = "123", title = "Test", installPath = "/games/test")
        coEvery { manager.getSaveDirectoryPath(context, "GOG_123") } returns locations
        whenever(
            cloudSavesManager.syncSaves(
                localPath = locations[0].location,
                dirname = locations[0].name,
                clientId = locations[0].clientId,
                clientSecret = locations[0].clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncOutcome(GOGCloudSavesManager.SyncAction.UPLOAD, newTimestamp = 101L))
        whenever(
            cloudSavesManager.syncSaves(
                localPath = locations[1].location,
                dirname = locations[1].name,
                clientId = locations[1].clientId,
                clientSecret = locations[1].clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncOutcome(GOGCloudSavesManager.SyncAction.DOWNLOAD, newTimestamp = 202L))

        val result = manager.syncCloudSaves("GOG_123", SaveLocation.None)

        assertEquals(SyncResult.Success, result.syncResult)
        assertEquals("101", manager.getCloudSaveSyncTimestamp("GOG_123", "slot-success-a"))
        assertEquals("202", manager.getCloudSaveSyncTimestamp("GOG_123", "slot-success-b"))
        tempDir.delete()
    }

    @Test
    fun `syncCloudSaves returns Conflict with timestamps from most diverged location`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)
        createAuthFile(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = spyk(
            GOGManager(
                gogGameDao = mock<GOGGameDao>(),
                context = context,
                cloudSavesManager = cloudSavesManager,
            ),
        )
        val locations = listOf(
            createLocation(tempDir.root, "slot-conflict-a", "client-ca", "secret-ca"),
            createLocation(tempDir.root, "slot-conflict-b", "client-cb", "secret-cb"),
        )

        coEvery { manager.getGameFromDbById("456") } returns GOGGame(id = "456", title = "Conflict", installPath = "/games/conflict")
        coEvery { manager.getSaveDirectoryPath(context, "GOG_456") } returns locations
        whenever(
            cloudSavesManager.syncSaves(
                localPath = locations[0].location,
                dirname = locations[0].name,
                clientId = locations[0].clientId,
                clientSecret = locations[0].clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.CONFLICT,
                localTimestampMs = 1_000L,
                remoteTimestampMs = 2_000L,
            ),
        )
        whenever(
            cloudSavesManager.syncSaves(
                localPath = locations[1].location,
                dirname = locations[1].name,
                clientId = locations[1].clientId,
                clientSecret = locations[1].clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.CONFLICT,
                localTimestampMs = 1_000L,
                remoteTimestampMs = 9_000L,
            ),
        )

        val result = manager.syncCloudSaves("GOG_456", SaveLocation.None)

        assertEquals(SyncResult.Conflict, result.syncResult)
        assertEquals(1_000L, result.localTimestamp)
        assertEquals(9_000L, result.remoteTimestamp)
        tempDir.delete()
    }

    @Test
    fun `syncCloudSaves returns UnknownFail when any location fails`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)
        createAuthFile(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = spyk(
            GOGManager(
                gogGameDao = mock<GOGGameDao>(),
                context = context,
                cloudSavesManager = cloudSavesManager,
            ),
        )
        val locations = listOf(
            createLocation(tempDir.root, "slot-fail-a", "client-fa", "secret-fa"),
            createLocation(tempDir.root, "slot-fail-b", "client-fb", "secret-fb"),
        )

        coEvery { manager.getGameFromDbById("789") } returns GOGGame(id = "789", title = "Failure", installPath = "/games/failure")
        coEvery { manager.getSaveDirectoryPath(context, "GOG_789") } returns locations
        whenever(
            cloudSavesManager.syncSaves(
                localPath = locations[0].location,
                dirname = locations[0].name,
                clientId = locations[0].clientId,
                clientSecret = locations[0].clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncOutcome(GOGCloudSavesManager.SyncAction.DOWNLOAD, newTimestamp = 505L))
        whenever(
            cloudSavesManager.syncSaves(
                localPath = locations[1].location,
                dirname = locations[1].name,
                clientId = locations[1].clientId,
                clientSecret = locations[1].clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncOutcome(GOGCloudSavesManager.SyncAction.NONE, failed = true))

        val result = manager.syncCloudSaves("GOG_789", SaveLocation.None)

        assertEquals(SyncResult.UnknownFail, result.syncResult)
        tempDir.delete()
    }

    @Test
    fun `syncCloudSaves applies forced choice only to conflicted locations across full flow`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)
        createAuthFile(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = spyk(
            GOGManager(
                gogGameDao = mock<GOGGameDao>(),
                context = context,
                cloudSavesManager = cloudSavesManager,
            ),
        )
        val conflicted = createLocation(tempDir.root, "slot-scope-conflict", "client-sc", "secret-sc")
        val clean = createLocation(tempDir.root, "slot-scope-clean", "client-sn", "secret-sn")

        coEvery { manager.getGameFromDbById("987") } returns GOGGame(id = "987", title = "Scoped", installPath = "/games/scoped")
        coEvery { manager.getSaveDirectoryPath(context, "GOG_987") } returns listOf(conflicted, clean)
        whenever(
            cloudSavesManager.checkSync(
                localPath = conflicted.location,
                dirname = conflicted.name,
                clientId = conflicted.clientId,
                clientSecret = conflicted.clientSecret,
                lastSyncTimestamp = 0L,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncCheckResult(GOGCloudSavesManager.SyncAction.CONFLICT, 10L, 20L))
        whenever(
            cloudSavesManager.checkSync(
                localPath = clean.location,
                dirname = clean.name,
                clientId = clean.clientId,
                clientSecret = clean.clientSecret,
                lastSyncTimestamp = 0L,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncCheckResult(GOGCloudSavesManager.SyncAction.DOWNLOAD, 10L, 20L))
        whenever(
            cloudSavesManager.syncSaves(
                localPath = conflicted.location,
                dirname = conflicted.name,
                clientId = conflicted.clientId,
                clientSecret = conflicted.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.Local,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncOutcome(GOGCloudSavesManager.SyncAction.UPLOAD, newTimestamp = 606L))
        whenever(
            cloudSavesManager.syncSaves(
                localPath = clean.location,
                dirname = clean.name,
                clientId = clean.clientId,
                clientSecret = clean.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncOutcome(GOGCloudSavesManager.SyncAction.DOWNLOAD, newTimestamp = 707L))

        val result = manager.syncCloudSaves("GOG_987", SaveLocation.Local)

        assertEquals(SyncResult.Success, result.syncResult)
        verify(cloudSavesManager).syncSaves(
            localPath = conflicted.location,
            dirname = conflicted.name,
            clientId = conflicted.clientId,
            clientSecret = conflicted.clientSecret,
            lastSyncTimestamp = 0L,
            preferredSave = SaveLocation.Local,
        )
        verify(cloudSavesManager).syncSaves(
            localPath = clean.location,
            dirname = clean.name,
            clientId = clean.clientId,
            clientSecret = clean.clientSecret,
            lastSyncTimestamp = 0L,
            preferredSave = SaveLocation.None,
        )
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation forwards forced choice only for conflicted locations`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-a",
            location = tempDir.root.resolve("slot-a").apply { mkdirs() }.absolutePath,
            clientId = "client-1",
            clientSecret = "secret-1",
        )

        whenever(
            cloudSavesManager.checkSync(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncCheckResult(GOGCloudSavesManager.SyncAction.CONFLICT, 1_000L, 2_000L))
        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.Local,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.UPLOAD,
                newTimestamp = 123L,
            ),
        )

        val result = callSyncSaveLocation(
            manager = manager,
            appId = "GOG_123",
            gameId = 123,
            location = location,
            preferredSave = SaveLocation.Local,
        )

        val outcome = result.javaClass.getDeclaredField("outcome").apply { isAccessible = true }.get(result)
        val newTimestamp = outcome.javaClass.getDeclaredField("newTimestamp").apply { isAccessible = true }.getLong(outcome)
        assertEquals(123L, newTimestamp)
        verify(cloudSavesManager).syncSaves(
            localPath = location.location,
            dirname = location.name,
            clientId = location.clientId,
            clientSecret = location.clientSecret,
            lastSyncTimestamp = 0L,
            preferredSave = SaveLocation.Local,
        )
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation downgrades forced choice to auto for clean locations`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-b",
            location = tempDir.root.resolve("slot-b").apply { mkdirs() }.absolutePath,
            clientId = "client-2",
            clientSecret = "secret-2",
        )

        whenever(
            cloudSavesManager.checkSync(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
            ),
        ).thenReturn(GOGCloudSavesManager.SyncCheckResult(GOGCloudSavesManager.SyncAction.DOWNLOAD, 1_000L, 2_000L))
        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.DOWNLOAD,
                newTimestamp = 456L,
            ),
        )

        callSyncSaveLocation(
            manager = manager,
            appId = "GOG_456",
            gameId = 456,
            location = location,
            preferredSave = SaveLocation.Remote,
        )

        verify(cloudSavesManager).syncSaves(
            localPath = location.location,
            dirname = location.name,
            clientId = location.clientId,
            clientSecret = location.clientSecret,
            lastSyncTimestamp = 0L,
            preferredSave = SaveLocation.None,
        )
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation returns conflict outcome metadata when cloud manager reports conflict`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-c",
            location = tempDir.root.resolve("slot-c").apply { mkdirs() }.absolutePath,
            clientId = "client-3",
            clientSecret = "secret-3",
        )

        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.CONFLICT,
                localTimestampMs = 111L,
                remoteTimestampMs = 222L,
            ),
        )

        val result = callSyncSaveLocation(
            manager = manager,
            appId = "GOG_789",
            gameId = 789,
            location = location,
            preferredSave = SaveLocation.None,
        )

        val outcome = result.javaClass.getDeclaredField("outcome").apply { isAccessible = true }.get(result)
        val action = outcome.javaClass.getDeclaredField("action").apply { isAccessible = true }.get(outcome)
        val localTimestampMs = outcome.javaClass.getDeclaredField("localTimestampMs").apply { isAccessible = true }.getLong(outcome)
        val remoteTimestampMs = outcome.javaClass.getDeclaredField("remoteTimestampMs").apply { isAccessible = true }.getLong(outcome)
        assertEquals(GOGCloudSavesManager.SyncAction.CONFLICT, action)
        assertEquals(111L, localTimestampMs)
        assertEquals(222L, remoteTimestampMs)
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation returns upload outcome metadata for simple local upload`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-upload",
            location = tempDir.root.resolve("slot-upload").apply { mkdirs() }.absolutePath,
            clientId = "client-upload",
            clientSecret = "secret-upload",
        )

        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.UPLOAD,
                newTimestamp = 333L,
            ),
        )

        val result = callSyncSaveLocation(
            manager = manager,
            appId = "GOG_333",
            gameId = 333,
            location = location,
            preferredSave = SaveLocation.None,
        )

        val outcome = result.javaClass.getDeclaredField("outcome").apply { isAccessible = true }.get(result)
        val action = outcome.javaClass.getDeclaredField("action").apply { isAccessible = true }.get(outcome)
        val newTimestamp = outcome.javaClass.getDeclaredField("newTimestamp").apply { isAccessible = true }.getLong(outcome)
        assertEquals(GOGCloudSavesManager.SyncAction.UPLOAD, action)
        assertEquals(333L, newTimestamp)
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation returns download outcome metadata for simple remote download`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-download",
            location = tempDir.root.resolve("slot-download").apply { mkdirs() }.absolutePath,
            clientId = "client-download",
            clientSecret = "secret-download",
        )

        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.DOWNLOAD,
                newTimestamp = 444L,
            ),
        )

        val result = callSyncSaveLocation(
            manager = manager,
            appId = "GOG_444",
            gameId = 444,
            location = location,
            preferredSave = SaveLocation.None,
        )

        val outcome = result.javaClass.getDeclaredField("outcome").apply { isAccessible = true }.get(result)
        val action = outcome.javaClass.getDeclaredField("action").apply { isAccessible = true }.get(outcome)
        val newTimestamp = outcome.javaClass.getDeclaredField("newTimestamp").apply { isAccessible = true }.getLong(outcome)
        assertEquals(GOGCloudSavesManager.SyncAction.DOWNLOAD, action)
        assertEquals(444L, newTimestamp)
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation stores updated timestamp for successful sync`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-d",
            location = tempDir.root.resolve("slot-d").apply { mkdirs() }.absolutePath,
            clientId = "client-4",
            clientSecret = "secret-4",
        )

        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.DOWNLOAD,
                newTimestamp = 999L,
            ),
        )

        callSyncSaveLocation(
            manager = manager,
            appId = "GOG_999",
            gameId = 999,
            location = location,
            preferredSave = SaveLocation.None,
        )

        assertEquals("999", manager.getCloudSaveSyncTimestamp("GOG_999", "slot-d"))
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation fails immediately when client secret is missing`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-e",
            location = tempDir.root.resolve("slot-e").apply { mkdirs() }.absolutePath,
            clientId = "client-5",
            clientSecret = "",
        )

        val result = callSyncSaveLocation(
            manager = manager,
            appId = "GOG_555",
            gameId = 555,
            location = location,
            preferredSave = SaveLocation.None,
        )

        val outcome = result.javaClass.getDeclaredField("outcome").apply { isAccessible = true }.get(result)
        val failed = outcome.javaClass.getDeclaredField("failed").apply { isAccessible = true }.getBoolean(outcome)
        assertTrue(failed)
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation keeps forced choice on auto when sync check is unavailable`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-f",
            location = tempDir.root.resolve("slot-f").apply { mkdirs() }.absolutePath,
            clientId = "client-6",
            clientSecret = "secret-6",
        )

        whenever(
            cloudSavesManager.checkSync(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
            ),
        ).thenReturn(null)
        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.NONE,
                newTimestamp = 111L,
            ),
        )

        callSyncSaveLocation(
            manager = manager,
            appId = "GOG_666",
            gameId = 666,
            location = location,
            preferredSave = SaveLocation.Remote,
        )

        verify(cloudSavesManager).syncSaves(
            localPath = location.location,
            dirname = location.name,
            clientId = location.clientId,
            clientSecret = location.clientSecret,
            lastSyncTimestamp = 0L,
            preferredSave = SaveLocation.None,
        )
        tempDir.delete()
    }

    @Test
    fun `syncSaveLocation marks failed outcome when cloud manager fails`() = runBlocking {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val cloudSavesManager = mock<GOGCloudSavesManager>()
        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = cloudSavesManager,
        )
        val location = GOGCloudSavesLocation(
            name = "slot-g",
            location = tempDir.root.resolve("slot-g").apply { mkdirs() }.absolutePath,
            clientId = "client-7",
            clientSecret = "secret-7",
        )

        whenever(
            cloudSavesManager.syncSaves(
                localPath = location.location,
                dirname = location.name,
                clientId = location.clientId,
                clientSecret = location.clientSecret,
                lastSyncTimestamp = 0L,
                preferredSave = SaveLocation.None,
            ),
        ).thenReturn(
            GOGCloudSavesManager.SyncOutcome(
                action = GOGCloudSavesManager.SyncAction.NONE,
                failed = true,
            ),
        )

        val result = callSyncSaveLocation(
            manager = manager,
            appId = "GOG_777",
            gameId = 777,
            location = location,
            preferredSave = SaveLocation.None,
        )

        val outcome = result.javaClass.getDeclaredField("outcome").apply { isAccessible = true }.get(result)
        val failed = outcome.javaClass.getDeclaredField("failed").apply { isAccessible = true }.getBoolean(outcome)
        assertTrue(failed)
        tempDir.delete()
    }

    @Test
    fun `endSync completes waiting deferred with supplied success state`() {
        val tempDir = TemporaryFolder().apply { create() }
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir.root)

        val manager = GOGManager(
            gogGameDao = mock<GOGGameDao>(),
            context = context,
            cloudSavesManager = mock<GOGCloudSavesManager>(),
        )

        val prior = manager.startSync("GOG_321")
        assertEquals(null, prior)

        val deferredField = manager.javaClass.getDeclaredField("activeSyncs").apply { isAccessible = true }
        val activeSyncs = deferredField.get(manager) as Map<*, *>
        val deferred = activeSyncs["GOG_321"] as kotlinx.coroutines.CompletableDeferred<*>

        manager.endSync("GOG_321", true)

        assertTrue(deferred.isCompleted)
        assertEquals(true, runBlocking { deferred.await() })
        tempDir.delete()
    }

    private suspend fun callSyncSaveLocation(
        manager: GOGManager,
        appId: String,
        gameId: Int,
        location: GOGCloudSavesLocation,
        preferredSave: SaveLocation,
    ): Any = suspendCoroutine<Any> { continuation ->
        val method = manager.javaClass.declaredMethods.first { it.name == "syncSaveLocation" }
        method.isAccessible = true
        val result = method.invoke(manager, appId, gameId, location, 0, 1, preferredSave, continuation)
        if (result != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
            continuation.resume(result as Any)
        }
    }

    private fun createLocation(root: File, name: String, clientId: String, clientSecret: String) =
        GOGCloudSavesLocation(
            name = name,
            location = root.resolve(name).apply { mkdirs() }.absolutePath,
            clientId = clientId,
            clientSecret = clientSecret,
        )

    private fun createAuthFile(root: File) {
        root.resolve("gog_auth.json").writeText("{}")
    }
}
