package com.stbplay.app.data

import org.junit.Assert.*
import org.junit.Test

class AudioAndPinTest {
    @Test fun pinsRequireFourToSixAsciiDigits() {
        assertTrue(PinHash.valid("1234")); assertTrue(PinHash.valid("123456"))
        listOf("", "123", "1234567", "abcd", "12 34", "১২৩৪").forEach { assertFalse(PinHash.valid(it)) }
    }
    @Test fun saltedVerifiersAcceptOnlyTheCorrectPin() {
        val first = PinHash.create("493821")
        val second = PinHash.create("493821")
        assertNotEquals(first, second)
        assertFalse(first.contains("493821"))
        assertTrue(PinHash.verify("493821", first))
        assertFalse(PinHash.verify("493822", first))
    }
    @Test fun malformedVerifiersFailClosedWithoutUnboundedWork() {
        listOf("", "1234", "2|PBKDF2WithHmacSHA256|150000|00|00", "1|unknown|150000|00|00", "1|PBKDF2WithHmacSHA256|999999999|00|00", "1|PBKDF2WithHmacSHA256|150000|zz|00").forEach {
            assertFalse(PinHash.verify("1234", it))
        }
    }
    @Test fun olderAndroidPinAlgorithmRemainsVerifiable() {
        val record = PinHash.create("6789", "PBKDF2WithHmacSHA1")
        assertTrue(PinHash.verify("6789", record))
        assertFalse(PinHash.verify("6790", record))
    }
    @Test fun audioDiagnosticsDistinguishMissingUnsupportedAndUnselectedTracks() {
        assertTrue(AudioDiagnostics.messages(true, 0, 0, 0, 100, false).single().contains("no audio track"))
        assertTrue(AudioDiagnostics.messages(true, 2, 0, 0, 100, false).single().contains("cannot play"))
        assertTrue(AudioDiagnostics.messages(true, 2, 2, 0, 100, false).single().contains("No audio track is selected"))
        assertTrue(AudioDiagnostics.messages(false, 0, 0, 0, 100, false).single().contains("after the stream loads"))
    }
    @Test fun mutedPlayerAndMutedDeviceAreReportedIndependently() {
        val messages = AudioDiagnostics.messages(true, 1, 1, 1, 0, true)
        assertTrue(messages.any { it.startsWith("Player is muted") })
        assertTrue(messages.any { it.startsWith("Device media volume") })
        assertTrue(messages.any { it.startsWith("An audio track is selected") })
    }
}
