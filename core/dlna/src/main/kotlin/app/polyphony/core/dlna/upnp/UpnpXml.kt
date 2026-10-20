package app.polyphony.core.dlna.upnp

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Small, defensive DOM helpers that work identically on Android and the JVM (tests). */
internal object UpnpXml {

    /** Why the last [parse] returned null; surfaced in logs and the diagnostics export. */
    @Volatile var lastError: String? = null

    fun parse(xml: String): Document? {
        val cleaned = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (cleaned.isEmpty()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                isXIncludeAware = false
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(cleaned)))
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }

    fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        var node: Node? = firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) out.add(node as Element)
            node = node.nextSibling
        }
        return out
    }

    fun Element.children(localName: String): List<Element> =
        childElements().filter { it.local().equals(localName, ignoreCase = true) }

    fun Element.child(localName: String): Element? = children(localName).firstOrNull()

    fun Element.childText(localName: String): String? = child(localName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** Depth-first search for the first element with this local name. */
    fun Element.findFirst(localName: String): Element? {
        if (local().equals(localName, ignoreCase = true)) return this
        for (child in childElements()) {
            child.findFirst(localName)?.let { return it }
        }
        return null
    }

    fun Element.local(): String = localName ?: nodeName.substringAfter(':')

    fun escape(text: String): String = buildString(text.length + 16) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
