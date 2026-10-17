package org.example.faceblur.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val out = XmpFilter.filter(xmp.toByteArray() + ByteArray(8))
        assertNotNull(out)
        out!!
        assertTrue(out, out.contains("Rating"))
        assertTrue(out, out.contains("&#xc9;t&#xe9; &#xe0; Paris"))
        assertTrue(out, out.contains("vacances"))
        assertTrue(out, out.all { it.code < 0x80 })
    }

    @Test
    fun `drops face regions, embedded image and orientation`() {
        val out = XmpFilter.filter(xmp.toByteArray())!!
        assertFalse(out, out.contains("Alice"))
        assertFalse(out, out.contains("Regions"))
        assertFalse(out, out.contains("BASE64ORIGINAL"))
        assertFalse(out, out.contains("Orientation"))
    }

    @Test
    fun `output is valid xmp that filters to itself`() {
        val out = XmpFilter.filter(xmp.toByteArray())!!
        assertEquals(out, XmpFilter.filter(out.toByteArray()))
    }

    @Test
    fun `nothing kept gives null`() {
        assertNull(XmpFilter.filter(xmp.replace(Regex("<dc:.*</dc:\\w+>"), "").replace("xmp:Rating=\"4\"", "").toByteArray()))
        assertNull(XmpFilter.filter("pas du xml".toByteArray()))
    }
}
