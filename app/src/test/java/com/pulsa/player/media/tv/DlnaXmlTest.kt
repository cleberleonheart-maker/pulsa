package com.pulsa.player.media.tv

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 — XML do protocolo DLNA/UPnP. Cada asserção é um defeito que a Roku Media Player só
 * revela na TV: description malformada e a TV nem lista o servidor, Browse sem
 * NumberReturned/TotalMatches e a lista aparece vazia com a barra de rolagem sumindo.
 */
class DlnaXmlTest {

    private fun parseOk(xml: String) {
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        // Não lança: o teste falha com a exceção do parser, que é a mensagem que importa.
        f.newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test
    fun descriptionEServicosCertos() {
        val xml = DlnaXml.deviceDescription("abc-123", "http://192.168.0.5:8200")
        parseOk(xml)
        assertTrue(xml.contains("urn:schemas-upnp-org:device:MediaServer:1"))
        assertTrue(xml.contains("<friendlyName>Pulsa</friendlyName>"))
        assertTrue(xml.contains("uuid:abc-123"))
        assertTrue(xml.contains("ContentDirectory"))
        assertTrue(xml.contains("ConnectionManager"))
        assertTrue(xml.contains("/ctl"))
    }

    @Test
    fun scpdContentDirectoryTemBrowseComArgumentos() {
        val xml = DlnaXml.scpdContentDirectory()
        parseOk(xml)
        assertTrue(xml.contains("<name>Browse</name>"))
        assertTrue(xml.contains("ObjectID"))
        assertTrue(xml.contains("StartingIndex"))
        assertTrue(xml.contains("RequestedCount"))
        assertTrue(xml.contains("NumberReturned"))
        assertTrue(xml.contains("TotalMatches"))
    }

    @Test
    fun scpdConnectionManagerTemGetProtocolInfo() {
        val xml = DlnaXml.scpdConnectionManager()
        parseOk(xml)
        assertTrue(xml.contains("GetProtocolInfo"))
    }

    @Test
    fun browseResponseEscapaDidlDentroDeResult() {
        // O DIDL vai como TEXTO dentro de <Result> — colar cru cria XML malformado que a
        // TV descarta em silêncio e a lista vem vazia.
        val didl = "<DIDL-Lite><item><dc:title>Rock & Roll</dc:title></item></DIDL-Lite>"
        val xml = DlnaXml.browseResponse(didl, 1, 1)
        parseOk(xml) // não pode lançar
        assertTrue(xml.contains("&lt;dc:title&gt;Rock &amp; Roll&lt;/dc:title&gt;"))
        assertFalse(xml.contains("<dc:title>Rock"))
        assertTrue(xml.contains("<NumberReturned>1</NumberReturned>"))
        assertTrue(xml.contains("<TotalMatches>1</TotalMatches>"))
    }

    @Test
    fun protocolInfoAnunciaHttpGetGenerico() {
        val xml = DlnaXml.getProtocolInfoResponse()
        parseOk(xml)
        assertTrue(xml.contains("http-get:*:*:*"))
    }

    @Test
    fun faultInvalidaEValida() {
        val xml = DlnaXml.faultInvalidAction()
        parseOk(xml)
        assertTrue(xml.contains("<errorCode>401</errorCode>"))
        assertTrue(xml.contains("Invalid Action"))
    }

    @Test
    fun parseBrowseRequestExtraiCampos() {
        val soap = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <ObjectID>music</ObjectID>
                  <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                  <Filter>*</Filter>
                  <StartingIndex>50</StartingIndex>
                  <RequestedCount>25</RequestedCount>
                  <SortCriteria></SortCriteria>
                </u:Browse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val req = DlnaXml.parseBrowseRequest(soap)
        assertNotNull(req)
        assertEquals("music", req!!.objectId)
        assertTrue(req.isDirectChildren)
        assertEquals(50, req.startingIndex)
        assertEquals(25, req.requestedCount)
    }

    @Test
    fun parseBrowseMetadataENaoDirectChildren() {
        val soap = """
            <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
              <ObjectID>m42</ObjectID>
              <BrowseFlag>BrowseMetadata</BrowseFlag>
              <StartingIndex>0</StartingIndex>
              <RequestedCount>1</RequestedCount>
            </u:Browse>
        """.trimIndent()
        val req = DlnaXml.parseBrowseRequest(soap)
        assertFalse(req!!.isDirectChildren)
    }

    @Test
    fun parseBrowseXmlInvalidoDevolveNull() {
        assertNull(DlnaXml.parseBrowseRequest("<s:Envelope>"))
        assertNull(DlnaXml.parseBrowseRequest("lixo"))
    }

    @Test
    fun soapActionIdentificaServicoEAcao() {
        val soap = """
            <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
              <ObjectID>0</ObjectID>
            </u:Browse>
        """.trimIndent()
        val pair = DlnaXml.soapAction(soap)
        assertEquals(DlnaXml.SERVICE_CD, pair!!.first)
        assertEquals("Browse", pair.second)

        val cm = """
            <u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1"/>
        """.trimIndent()
        val pair2 = DlnaXml.soapAction(cm)
        assertEquals(DlnaXml.SERVICE_CM, pair2!!.first)
        assertEquals("GetProtocolInfo", pair2.second)
    }

    @Test
    fun ssdpResponseECabecalhoHttpValido() {
        val r = DlnaXml.ssdpResponse(
            "http://192.168.0.5:8200/desc.xml", "uuid-1", DlnaXml.DEVICE_TYPE,
        )
        assertTrue(r.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(r.contains("LOCATION: http://192.168.0.5:8200/desc.xml"))
        assertTrue(r.contains("ST: ${DlnaXml.DEVICE_TYPE}"))
        assertTrue(r.endsWith("\r\n\r\n"))
    }

    @Test
    fun stAtendeSoParaMediaServerEAll() {
        assertTrue(DlnaXml.stAtende(DlnaXml.DEVICE_TYPE))
        assertTrue(DlnaXml.stAtende("ssdp:all"))
        assertFalse(DlnaXml.stAtende("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertFalse(DlnaXml.stAtende("upnp:rootdevice"))
    }

    @Test
    fun byebyeAnunciaSaida() {
        val r = DlnaXml.ssdpByebye("uuid-1")
        assertTrue(r.contains("NTS: ssdp:byebye"))
        assertTrue(r.contains("uuid:uuid-1"))
    }
}
