// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object XmpFilter {
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val XMP_META_NS = "adobe:ns:meta/"
    val KEPT_PROPERTIES: Map<String, Pair<String, Set<String>>> = mapOf(
        "http://purl.org/dc/elements/1.1/" to ("dc" to setOf(
            "title",
            "description",
            "creator",
            "rights",
            "subject"
        )),
        "http://ns.adobe.com/xap/1.0/" to ("xmp" to setOf("Rating", "Label", "CreateDate")),
        "http://ns.adobe.com/xap/1.0/rights/" to ("xmpRights" to setOf(
            "Marked",
            "UsageTerms",
            "WebStatement"
        )),
        "http://ns.adobe.com/photoshop/1.0/" to
                ("photoshop" to setOf(
                    "Headline",
                    "City",
                    "State",
                    "Country",
                    "Credit",
                    "Source",
                    "DateCreated"
                )),
        "http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/" to ("Iptc4xmpCore" to setOf(
            "Location",
            "CountryCode"
        )),
        "http://ns.adobe.com/lightroom/1.0/" to ("lr" to setOf("hierarchicalSubject")),
    )

    fun filter(xmp: ByteArray): String? = try {
        val end = xmp.indexOfLast { it == '>'.code.toByte() } + 1
        val source = newBuilderFactory().newDocumentBuilder().parse(xmp.inputStream(0, end))
        val out = newBuilderFactory().newDocumentBuilder().newDocument()
        val description = out.createElementNS(RDF, "rdf:Description")
            .apply { setAttributeNS(RDF, "rdf:about", "") }
        val descriptions = source.getElementsByTagNameNS(RDF, "Description")
        for (i in 0 until descriptions.length) copyKept(
            descriptions.item(i) as Element,
            out,
            description
        )
        if (description.childNodes.length == 0) null else serialize(out, description)
    } catch (e: Exception) {
        null
    }

    private fun copyKept(from: Element, out: Document, to: Element) {
        val attributes = from.attributes
        for (i in 0 until attributes.length) {
            val attr = attributes.item(i) as Attr
            val prefix = keptPrefix(attr.namespaceURI, attr.localName) ?: continue
            to.appendChild(
                out.createElementNS(attr.namespaceURI, "$prefix:${attr.localName}").apply {
                    textContent = attr.value
                })
        }
        var child = from.firstChild
        while (child != null) {
            if (child is Element) {
                val prefix = keptPrefix(child.namespaceURI, child.localName)
                if (prefix != null) to.appendChild(
                    renamed(
                        out.importNode(child, true) as Element,
                        prefix
                    )
                )
            }
            child = child.nextSibling
        }
    }

    private fun keptPrefix(namespace: String?, name: String?): String? =
        KEPT_PROPERTIES[namespace]?.takeIf { name in it.second }?.first

    private fun renamed(element: Element, prefix: String): Element =
        element.ownerDocument.renameNode(
            element,
            element.namespaceURI,
            "$prefix:${element.localName}"
        ) as Element

    private fun serialize(out: Document, description: Element): String {
        val meta = out.createElementNS(XMP_META_NS, "x:xmpmeta")
        val rdf = out.createElementNS(RDF, "rdf:RDF")
        out.appendChild(meta).appendChild(rdf).appendChild(description)
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(out), StreamResult(writer))
        return asciiOnly(writer.toString())
    }

    internal fun asciiOnly(xml: String): String = buildString {
        xml.codePoints()
            .forEach { if (it < 0x80) append(it.toChar()) else append("&#x${Integer.toHexString(it)};") }
    }

    private fun newBuilderFactory() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
}
