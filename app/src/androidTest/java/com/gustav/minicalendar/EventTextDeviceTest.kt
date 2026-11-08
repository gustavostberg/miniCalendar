package com.gustav.minicalendar

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventTextDeviceTest {
    @Test
    fun htmlDescriptionKeepsSwedishLettersAndLinks() {
        val text = EventText.description("<p>Tr\u00e4ning &amp; m\u00f6te</p><p><a href=\"https://example.com/meeting\">Anslut</a><br>Ta med kl\u00e4der.</p>")
        assertTrue(text.contains("Tr\u00e4ning & m\u00f6te"))
        assertTrue(text.contains("Anslut (https://example.com/meeting)"))
        assertFalse(text.contains("<p>"))
    }

    @Test
    fun syncMetadataIsHiddenWithoutChangingStoredDescription() {
        val raw = "Synced from Exchange\nSYNC_KEY:123456\nSYNC_HASH:meeting|2026-11-10\nOrganizer: example@example.com\n\nAgenda"
        val event = CalendarRepository.Event(1, 1, "Meeting", false, 1, 2, description = raw)
        assertEquals(raw, event.description)
        assertEquals("Synced from Exchange\nOrganizer: example@example.com\n\nAgenda", event.displayDescription)
    }

    @Test
    fun plainTextAndEmailAddressesRemainReadable() {
        val raw = "A < B\nMail: <test@example.com>\n\u00c5\u00e4\u00f6 & details"
        assertEquals(raw, EventText.description(raw))
        assertEquals("A < B\nNext & last", EventText.description("A < B\nNext &amp; last"))
    }
}
