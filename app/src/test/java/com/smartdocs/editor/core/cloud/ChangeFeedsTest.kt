package com.smartdocs.editor.core.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fc-sync: delta-feed and change-notification parsing against recorded response shapes (test resources fc_sync/). */
class ChangeFeedsTest {
    private fun fx(name: String): String = javaClass.classLoader!!.getResource("fc_sync/$name")!!.readText()

    @Test fun driveMiddlePageHasChangesAndNextToken() {
        val p = ChangeFeeds.drive(fx("drive_changes_page.json"))
        assertTrue(p.changed); assertEquals("4512", p.next); assertNull(p.steady)
    }

    @Test fun driveLastPageGivesNewStartToken() {
        val p = ChangeFeeds.drive(fx("drive_changes_last.json"))
        assertFalse(p.changed); assertNull(p.next); assertEquals("4600", p.steady)
        assertEquals("4511", ChangeFeeds.driveStartToken(fx("drive_start_token.json")))
    }

    @Test fun graphRootEntryIsNotAChange() {
        val last = ChangeFeeds.graph(fx("graph_delta_rootonly.json"))
        assertFalse(last.changed); assertNull(last.next)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/delta?token=xyz", last.steady)
        val mid = ChangeFeeds.graph(fx("graph_delta_next.json"))
        assertTrue(mid.changed); assertNull(mid.steady)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/delta?token=abc", mid.next)
    }

    @Test fun dropboxContinueFollowsHasMore() {
        val more = ChangeFeeds.dropbox(fx("dropbox_continue_more.json"), "old")
        assertTrue(more.changed); assertEquals("AAE-next", more.next); assertNull(more.steady)
        val last = ChangeFeeds.dropbox(fx("dropbox_continue_last.json"), "old")
        assertFalse(last.changed); assertNull(last.next); assertEquals("AAE-steady", last.steady)
        // no cursor in the answer: keep the previous one
        assertEquals("old", ChangeFeeds.dropbox("{\"entries\":[]}", "old").steady)
    }

    @Test fun boxEventsKeepStreamPosition() {
        val full = ChangeFeeds.box(fx("box_events_full.json"), "1", limit = 2)
        assertTrue(full.changed); assertEquals("1152922976252290886", full.next)
        val partial = ChangeFeeds.box(fx("box_events_full.json"), "1")
        assertTrue(partial.changed); assertNull(partial.next); assertEquals("1152922976252290886", partial.steady)
        val empty = ChangeFeeds.box(fx("box_events_empty.json"), "1")
        assertFalse(empty.changed); assertEquals("1152922976252290890", empty.steady)
        assertEquals("1152922976252290890", ChangeFeeds.boxNow(fx("box_events_empty.json")))
    }

    @Test fun dropboxLongPollAnswers() {
        assertEquals(ChangeFeeds.LongPoll(true, 30), ChangeFeeds.dropboxLongPoll(fx("dropbox_longpoll_changes.json")))
        assertEquals(ChangeFeeds.LongPoll(false, 0), ChangeFeeds.dropboxLongPoll(fx("dropbox_longpoll_none.json")))
        assertEquals(ChangeFeeds.LongPoll(false, 0), ChangeFeeds.dropboxLongPoll(""))
    }

    @Test fun boxRealtimeServerAndMessages() {
        val rt = ChangeFeeds.boxRealtime(fx("box_options.json"))!!
        assertTrue(rt.url.startsWith("https://2.realtime.services.box.net/subscribe?channel="))
        assertEquals(610, rt.retryTimeoutSec); assertEquals(10, rt.maxRetries)
        assertEquals(rt.url + "&stream_position=42", ChangeFeeds.boxSubscribeUrl(rt.url, "42"))
        assertEquals("https://x/s?stream_position=7", ChangeFeeds.boxSubscribeUrl("https://x/s", "7"))
        assertNull(ChangeFeeds.boxRealtime("{\"entries\":[]}"))
        assertEquals(ChangeFeeds.BoxMessage.NEW_CHANGE, ChangeFeeds.boxMessage(fx("box_realtime_new.json")))
        assertEquals(ChangeFeeds.BoxMessage.RECONNECT, ChangeFeeds.boxMessage(fx("box_realtime_reconnect.json")))
        assertEquals(ChangeFeeds.BoxMessage.OTHER, ChangeFeeds.boxMessage("not json"))
    }

    @Test fun dropboxTeamSpaceRoot() {
        assertEquals("3235641", DropboxSpace.teamRoot(JSONObject(fx("dropbox_account_team.json"))))
        assertNull(DropboxSpace.teamRoot(JSONObject(fx("dropbox_account_user.json"))))
        assertNull(DropboxSpace.teamRoot(JSONObject("{}")))
        val h = JSONObject(DropboxSpace.header("3235641")!!)
        assertEquals("root", h.getString(".tag")); assertEquals("3235641", h.getString("root"))
        assertNull(DropboxSpace.header(""))
    }

    @Test fun sharePointIdsAndListings() {
        val id = SharePoint.encode("b!abc", "01XYZ")
        assertEquals("b!abc" to "01XYZ", SharePoint.decode(id))
        assertNull(SharePoint.decode(SharePoint.LIBRARIES)); assertNull(SharePoint.decode("ABC!123")); assertNull(SharePoint.decode(null))
        assertTrue(SharePoint.isLibraryId(id)); assertFalse(SharePoint.isLibraryId("ABC!123"))
        val sites = SharePoint.sites(fx("sp_followed.json"))
        assertEquals(listOf("Marketing", "legal"), sites.map { it.second })
        val libs = SharePoint.libraries("Marketing", fx("sp_drives.json"))
        assertEquals(1, libs.size); assertEquals("Marketing — Documents", libs[0].second)
    }

    @Test fun oneDriveScopeOnlyWidensAfterGrantedSignIn() {
        val base = SharePoint.Scopes.BASE; val work = SharePoint.Scopes.WORK
        assertEquals(base, SharePoint.Scopes.scope(refresh = false, ask = false, granted = false))
        assertEquals(work, SharePoint.Scopes.scope(refresh = false, ask = true, granted = false))
        // asked but not granted yet: refreshes keep the old scope (a refused consent must not sign the user out)
        assertEquals(base, SharePoint.Scopes.scope(refresh = true, ask = true, granted = false))
        assertEquals(work, SharePoint.Scopes.scope(refresh = true, ask = false, granted = true))
    }
}
