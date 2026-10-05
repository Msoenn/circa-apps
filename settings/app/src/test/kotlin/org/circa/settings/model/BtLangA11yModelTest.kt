package org.circa.settings.model

import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtLangA11yModelTest {
    @Test fun variantsMapToTheirScreen() {
        assertEquals(PairingMode.ENTER_PIN, Pairing.mode(Pairing.PIN))
        assertEquals(PairingMode.ENTER_PIN, Pairing.mode(Pairing.PIN_16_DIGITS))
        assertEquals(PairingMode.ENTER_PASSKEY, Pairing.mode(Pairing.PASSKEY))
        assertEquals(PairingMode.CONFIRM_CODE, Pairing.mode(Pairing.PASSKEY_CONFIRMATION))
        assertEquals(PairingMode.CONSENT, Pairing.mode(Pairing.CONSENT))
        assertEquals(PairingMode.CONSENT, Pairing.mode(Pairing.OOB_CONSENT))
        assertEquals(PairingMode.SHOW_CODE, Pairing.mode(Pairing.DISPLAY_PASSKEY))
        assertEquals(PairingMode.SHOW_CODE, Pairing.mode(Pairing.DISPLAY_PIN))
        assertEquals(PairingMode.UNSUPPORTED, Pairing.mode(-2147483648))
    }

    @Test fun codesKeepLeadingZeros() {
        assertEquals("012345", Pairing.formatKey(Pairing.PASSKEY_CONFIRMATION, 12345))
        assertEquals("000007", Pairing.formatKey(Pairing.DISPLAY_PASSKEY, 7))
        assertEquals("0042", Pairing.formatKey(Pairing.DISPLAY_PIN, 42))
        assertNull(Pairing.formatKey(Pairing.PASSKEY_CONFIRMATION, -1))
    }

    @Test fun submitRules() {
        assertFalse(Pairing.canSubmit(Pairing.PIN, ""))
        assertTrue(Pairing.canSubmit(Pairing.PIN, "0000"))
        assertFalse(Pairing.canSubmit(Pairing.PIN_16_DIGITS, "1234"))
        assertTrue(Pairing.canSubmit(Pairing.PIN_16_DIGITS, "1234567890123456"))
        assertFalse(Pairing.canSubmit(Pairing.PASSKEY, "12345"))
        assertTrue(Pairing.canSubmit(Pairing.PASSKEY, "123456"))
        assertEquals(6, Pairing.maxLength(Pairing.PASSKEY))
        assertEquals(16, Pairing.maxLength(Pairing.PIN))
    }

    @Test fun passkeyBytesAreNativeOrderInt() {
        // 123456 = 0x0001E240
        assertArrayEquals(byteArrayOf(0x40, 0xE2.toByte(), 0x01, 0x00), Pairing.passkeyBytes("123456", littleEndian = true))
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0xE2.toByte(), 0x40), Pairing.passkeyBytes("123456", littleEndian = false))
    }

    @Test fun labelsNeverShowAddresses() {
        assertEquals("Desk", BtLabels.label("Desk", "Speaker X"))
        assertEquals("Speaker X", BtLabels.label("  ", "Speaker X"))
        assertEquals(BtLabels.UNNAMED, BtLabels.label(null, null))
        assertNull(BtLabels.cleanAlias("   "))
        assertEquals("Kitchen", BtLabels.cleanAlias(" Kitchen "))
    }

    @Test fun deviceClassIcons() {
        assertEquals(BtKind.PHONE, BtLabels.kind(0x0200))
        assertEquals(BtKind.AUDIO, BtLabels.kind(0x0400))
        assertEquals(BtKind.INPUT, BtLabels.kind(0x0500))
        assertEquals(BtKind.OTHER, BtLabels.kind(null))
        assertEquals(BtKind.OTHER, BtLabels.kind(0x1F00))
    }

    @Test fun fontScaleSnapsToNearestStep() {
        assertEquals(1, FontScale.nearest(1.0f))
        assertEquals(0, FontScale.nearest(0.8f))
        assertEquals(2, FontScale.nearest(1.1f))
        assertEquals(3, FontScale.nearest(2.0f))
        assertEquals("Largest", FontScale.label(1.3f))
        assertTrue(FontScale.isBold("300"))
        assertFalse(FontScale.isBold("0"))
        assertFalse(FontScale.isBold(null))
    }

    @Test fun localeChoicesDropPseudoAndDuplicates() {
        val c = LocaleModel.choices(listOf("en-US", "en-XA", "ar-XB", "", "de-DE", "de", "en_US", "en", "fr", "en-GB"))
        assertEquals(listOf("de-DE", "en-GB", "en-US", "fr"), c.map { it.toLanguageTag() })
        assertEquals("Deutsch (Deutschland)", LocaleModel.nativeName(Locale.forLanguageTag("de-DE")))
        assertTrue(LocaleModel.isCurrent(Locale.forLanguageTag("en-US"), Locale.US))
        assertFalse(LocaleModel.isCurrent(Locale.forLanguageTag("en-GB"), Locale.US))
    }

    @Test fun languageSearch() {
        val all = listOf(Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("en-US"), Locale.forLanguageTag("fr-FR"))
        assertEquals(all, LocaleModel.filter(all, "  "))
        assertEquals(listOf("de-DE"), LocaleModel.filter(all, "deu").map { it.toLanguageTag() })
        assertEquals(listOf("en-US"), LocaleModel.filter(all, "english united").map { it.toLanguageTag() })
        assertEquals(listOf("fr-FR"), LocaleModel.filter(all, "fr-").map { it.toLanguageTag() })
        assertTrue(LocaleModel.filter(all, "zzz").isEmpty())
    }
}
