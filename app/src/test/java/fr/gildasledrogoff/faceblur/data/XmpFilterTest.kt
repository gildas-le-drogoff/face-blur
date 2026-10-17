// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data
import org.junit.Assert.*
import org.junit.Test
class XmpFilterTest {
    private val xmp = """
        <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Aves">
          <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about=""
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
                xmlns:mwg-rs="http://www.metadataworkinggroup.com/schemas/regions/"
                xmlns:stArea="http://ns.adobe.com/xmp/sType/Area#"
                xmlns:GImage="http://ns.google.com/photos/1.0/image/"
                xmp:Rating="4"
                tiff:Orientation="6"
                GImage:Data="BASE64ORIGINAL">
              <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Été à Paris</rdf:li></rdf:Alt></dc:title>
              <dc:subject><rdf:Bag><rdf:li>vacances</rdf:li></rdf:Bag></dc:subject>
              <mwg-rs:Regions>
                <rdf:Description><mwg-rs:RegionList><rdf:Bag><rdf:li rdf:parseType="Resource">
                  <mwg-rs:Name>Alice</mwg-rs:Name>
                  <mwg-rs:Area stArea:x="0.5" stArea:y="0.4" stArea:w="0.1" stArea:h="0.2"/>
                </rdf:li></rdf:Bag></mwg-rs:RegionList></rdf:Description>
              </mwg-rs:Regions>
            </rdf:Description>
          </rdf:RDF>
        </x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()
    @Test
    fun `keeps descriptive properties`() {
        val filtered = XmpFilter.filter(xmp.toByteArray() + ByteArray(8))
        assertNotNull(filtered)
        filtered!!
        assertTrue(filtered, filtered.contains("Rating"))
        assertTrue(filtered, filtered.contains("&#xc9;t&#xe9; &#xe0; Paris"))
        assertTrue(filtered, filtered.contains("vacances"))
        assertTrue(filtered, filtered.all { it.code < 0x80 })
    }
    @Test
    fun `drops face regions, embedded image and orientation`() {
        val filtered = XmpFilter.filter(xmp.toByteArray())!!
        assertFalse(filtered, filtered.contains("Alice"))
        assertFalse(filtered, filtered.contains("Regions"))
        assertFalse(filtered, filtered.contains("BASE64ORIGINAL"))
        assertFalse(filtered, filtered.contains("Orientation"))
    }
    @Test
    fun `output is valid xmp that filters to itself`() {
        val filtered = XmpFilter.filter(xmp.toByteArray())!!
        assertEquals(filtered, XmpFilter.filter(filtered.toByteArray()))
    }
    @Test
    fun `nothing kept gives null`() {
        assertNull(XmpFilter.filter(xmp.replace(Regex("<dc:.*</dc:\\w+>"), "").replace("xmp:Rating=\"4\"", "").toByteArray()))
        assertNull(XmpFilter.filter("pas du xml".toByteArray()))
    }
}
