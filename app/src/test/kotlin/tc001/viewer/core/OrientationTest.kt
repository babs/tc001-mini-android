package tc001.viewer.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class OrientationTest {
    @Test
    fun eachQuarterIsTakenFromAnyOther() { // 003-AC4
        for (current in 0..3) for (q in 0..3) {
            assertEquals("from $current at ${q * 90}", q, snapQuarter(q * 90, current))
        }
    }

    @Test
    fun aQuarterIsTakenOnlyWithin30Degrees() { // 003-AC4
        for (q in 0..3) {
            val prev = (q + 3) % 4
            val next = (q + 1) % 4
            // Coming from the neighbour on either side: switch at 30 degrees off q, hold at 31.
            for ((angle, from) in listOf(q * 90 - 30 to prev, q * 90 + 30 to next)) {
                assertEquals("at $angle from $from", q, snapQuarter(angle.mod(360), from))
            }
            for ((angle, from) in listOf(q * 90 - 31 to prev, q * 90 + 31 to next)) {
                assertEquals("at $angle from $from", from, snapQuarter(angle.mod(360), from))
            }
        }
    }

    @Test
    fun aroundFortyFiveTheCurrentQuarterHolds() { // 003-AC4
        for (current in 0..1) for (angle in 31..59) assertEquals("at $angle", current, snapQuarter(angle, current))
    }

    @Test
    fun portraitWrapsAroundZero() { // 003-AC4
        for (angle in listOf(345, 359, 1, 15)) assertEquals("at $angle", 0, snapQuarter(angle, 3))
    }

    @Test
    fun flatKeepsTheCurrentQuarter() { // 003-AC4
        for (current in 0..3) assertEquals(current, snapQuarter(-1, current))
    }

    @Test
    fun aRecordingHoldsItsTurnWhateverThePhoneDoes() { // 003-AC6
        for (held in 0..3) for (phone in 0..3) assertEquals("held $held, phone $phone", held, turnToDraw(phone, held))
        for (phone in 0..3) assertEquals("not recording follows the phone", phone, turnToDraw(phone, null))
    }

    @Test
    fun theReadoutBoxIsTheBandAsTheUserSeesIt() { // 003-AC3
        for (turns in listOf(0, 2)) assertEquals("turns $turns", 1080 to 573, turnedBox(1080, 573, turns))
        for (turns in listOf(1, 3)) assertEquals("turns $turns: band height wide, band width tall", 573 to 1080, turnedBox(1080, 573, turns))
    }

    @Test
    fun minAndMaxStayBelowCenter() { // 003-AC3, 002-AC4
        assertEquals("stacked, all width-bound: Min/Max kept below Center", 240f * PAIR_MAX_SP / CENTRE_MAX_SP, pairTextSize(240f, 240f), 1e-3f)
        assertEquals("already below: unchanged", 170f, pairTextSize(170f, 240f), 0f)
        assertEquals("the caps themselves agree", PAIR_MAX_SP, pairTextSize(PAIR_MAX_SP, CENTRE_MAX_SP), 0f)
    }

    @Test
    fun aTurnedValueIsNeverWiderThanItsBox() { // 003-AC3
        // "-20.0" is about 2.75 ems wide; a narrow turned box leaves it 200 px, below the 32 sp floor (88 px at 2.75 px/sp) x 2.75.
        val em = 2.75f
        val width = 200f
        val floor = 88f
        for (turns in listOf(1, 3)) {
            val size = fitTextSize(em, 1.2f, width, 400f, readoutFloor(turns, floor), 385f)
            assertTrue("turns $turns: ${size * em} px within $width", size * em <= width)
        }
        assertEquals("upright keeps the 002-AC4 floor", floor, fitTextSize(em, 1.2f, width, 400f, readoutFloor(0, floor), 385f), 0f)
    }

    @Test
    fun aReadoutLabelFitsItsColumn() { // 003-AC3
        val max = 44f // 16 sp at 2.75 px/sp
        for (item in Readout.entries) {
            // About 0.6 em per glyph in the default sans: "Center" is about 3.6 ems, 158 px at 16 sp.
            val em = item.label.length * 0.6f
            val narrow = 70f
            val size = labelTextSize(em, narrow, max)
            assertTrue("${item.label}: ${size * em} px within $narrow", size * em <= narrow + 1e-3f)
            assertEquals("${item.label}: a wide column keeps 16 sp", max, labelTextSize(em, 1080f, max), 0f)
        }
    }

    @Test
    fun theActivityIsLockedToPortraitAndHandlesTurnsItself() { // 003-AC1
        // Unit tests run from the module dir; the fallback covers a run from the project root.
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.isFile }
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }.single { it.getAttributeNS(ns, "name") == ".MainActivity" }
        assertEquals("portrait", main.getAttributeNS(ns, "screenOrientation"))
        val changes = main.getAttributeNS(ns, "configChanges").split('|')
        assertTrue("configChanges $changes", changes.containsAll(listOf("orientation", "screenSize")))
    }

    @Test
    fun pictureTurnsAreTheRotateSettingThenThePhoneClockwise() { // 003-AC2, 003-AC7
        val t = FloatArray(SIZE * SIZE) { it.toFloat() }
        for (q in 0..3) for (mirror in listOf(false, true)) for (phone in 0..3) {
            val expected = orient(orient(t, q, mirror), phone, false)
            assertArrayEquals("q=$q mirror=$mirror phone=$phone", expected, orient(t, pictureTurns(q, mirror, phone), mirror), 0f)
        }
    }
}
