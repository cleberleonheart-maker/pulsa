package com.pulsa.player.media.tv

import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * A2 — todo XML do protocolo DLNA/UPnP, puro (sem Android, sem rede).
 *
 * **Por que separado do [DlnaServer].** O servidor é I/O (socket, SSDP, lock de Wi-Fi) e
 * não tem como cobrir em teste JVM; o XML é o que a Roku Media Player **valida** —
 * description malformada e a TV nem lista o servidor, resposta Browse sem os campos
 * `NumberReturned`/`TotalMatches` e a lista aparece vazia com a barra de rolagem sumindo.
 * São defeitos que só aparecem na TV e que aqui viram teste.
 *
 * **Sobre o `<Result>` do Browse.** O DIDL-Lite vai **escapado como texto** dentro do
 * `<Result>` (a UPnP manda: o Result carrega um documento como string). Colar o DIDL cru
 * cria XML malformado que o parser da TV descarta em silêncio — o servidor "funciona",
 * a lista vem vazia, e ninguém acha o motivo.
 */
object DlnaXml {

    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaServer:1"
    const val SERVICE_CD = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val SERVICE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/"
    const val UPDATE_ID = "1"

    /** Pedido de Browse extraído do SOAP. */
    data class BrowseRequest(
        val objectId: String,
        val browseFlag: String,
        val startingIndex: Int,
        val requestedCount: Int,
    ) {
        /** "BrowseDirectChildren" (pastas) vs "BrowseMetadata" (metadado de um nó). */
        val isDirectChildren: Boolean get() = browseFlag != "BrowseMetadata"
    }

    // ── descrição do dispositivo ────────────────────────────────────────────

    /**
     * Description XML do MediaServer. `baseUrl` (ex.: `http://192.168.0.15:8200`) define
     * os caminhos relativos; `uuid` estável entre sessões para a TV não criar servidor
     * duplicado a cada pareamento.
     */
    fun deviceDescription(uuid: String, baseUrl: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0" configId="0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>$DEVICE_TYPE</deviceType>
            <friendlyName>Pulsa</friendlyName>
            <manufacturer>Pulsa</manufacturer>
            <manufacturerURL>https://github.com/</manufacturerURL>
            <modelDescription>Pulsa — servidor de mídia local</modelDescription>
            <modelName>Pulsa</modelName>
            <modelNumber>1</modelNumber>
            <serialNumber>1</serialNumber>
            <UDN>uuid:$uuid</UDN>
            <serviceList>
              <service>
                <serviceType>$SERVICE_CD</serviceType>
                <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                <SCPDURL>/cds.xml</SCPDURL>
                <controlURL>/ctl</controlURL>
                <eventSubURL>/evt</eventSubURL>
              </service>
              <service>
                <serviceType>$SERVICE_CM</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <SCPDURL>/cms.xml</SCPDURL>
                <controlURL>/ctl</controlURL>
                <eventSubURL>/evt</eventSubURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    /**
     * SCPD do ContentDirectory — mínimo com só o que a Roku Media Player chama (Browse).
     * SCPD completo é ~400 linhas de variável de estado que ninguém lê; o que a TV usa é a
     * lista de argumentos, e sem ela o parser dela reclama.
     */
    fun scpdContentDirectory(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action>
              <name>Browse</name>
              <argumentList>
                <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
                <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
                <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
                <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
                <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
                <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
                <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
                <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
              </argumentList>
            </action>
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType>
              <allowedValueList><allowedValue>BrowseMetadata</allowedValue><allowedValue>BrowseDirectChildren</allowedValue></allowedValueList>
            </stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
            <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
    """.trimIndent()

    /** SCPD do ConnectionManager — mínimo, só GetProtocolInfo (o que a TV pergunta). */
    fun scpdConnectionManager(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action>
              <name>GetProtocolInfo</name>
              <argumentList>
                <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
                <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
              </argumentList>
            </action>
          </actionList>
          <serviceStateTable>
            <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
            <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
          </serviceStateTable>
        </scpd>
    """.trimIndent()

    // ── respostas SOAP ──────────────────────────────────────────────────────

    /**
     * Resposta do Browse: DIDL escapado em `<Result>`, paginação honesta em
     * `NumberReturned`/`TotalMatches` (sem TotalMatches a barra de rolagem da TV some
     * depois da primeira página).
     */
    fun browseResponse(didl: String, returned: Int, total: Int): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="$SOAP_NS" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:BrowseResponse xmlns:u="$SERVICE_CD">
              <Result>${ContentTree.esc(didl)}</Result>
              <NumberReturned>$returned</NumberReturned>
              <TotalMatches>$total</TotalMatches>
              <UpdateID>$UPDATE_ID</UpdateID>
            </u:BrowseResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    /**
     * GetProtocolInfo: `http-get:*:*:*` — "sirvo qualquer coisa por HTTP". A TV filtra
     * pelos MIME que ela mesma suporta; anunciar a lista toda é o comportamento que a
     * maioria dos servidores (MiniDLNA incluído) adota e que a Roku aceita.
     */
    fun getProtocolInfoResponse(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="$SOAP_NS" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:GetProtocolInfoResponse xmlns:u="$SERVICE_CM">
              <Source>http-get:*:*:*</Source>
              <Sink></Sink>
            </u:GetProtocolInfoResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    /**
     * Resposta SOAP de ação sem argumento de saída (GetSortCapabilities,
     * GetSearchCapabilities, GetCurrentConnectionIDs). Ações que a TV pergunta antes de
     * Browse; responder 401 Invalid Action funciona na maioria dos firmwares, mas responder
     * certo custa três linhas e elimina a variável.
     */
    fun emptyActionResponse(service: String, action: String, outArgs: String = ""): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="$SOAP_NS" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:${action}Response xmlns:u="$service">$outArgs</u:${action}Response>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    /** Fault UPnP padrão para ação desconhecida (401). */
    fun faultInvalidAction(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="$SOAP_NS" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <s:Fault>
              <faultcode>s:Client</faultcode>
              <faultstring>UPnPError</faultstring>
              <detail>
                <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
                  <errorCode>401</errorCode>
                  <errorDescription>Invalid Action</errorDescription>
                </UPnPError>
              </detail>
            </s:Fault>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    // ── parse de pedido SOAP (puro — coberto por teste) ─────────────────────

    /**
     * Extrai os campos do Browse do corpo SOAP. `null` quando o XML não parseia ou não é
     * um Browse (o chamador responde fault em vez de 500 genérico, que a TV reporta como
     * "erro de rede").
     */
    fun parseBrowseRequest(soapXml: String): BrowseRequest? = runCatching {
        val doc = parseXml(soapXml)
        // O corpo pode vir com prefixo <u:Browse> ou sem; procura o elemento cujo nome
        // local é "Browse" (namespace-aware=false cai no nodeName com prefixo — por isso
        // a varredura por localName manual).
        val browse = findElement(doc.documentElement, "Browse") ?: return null
        BrowseRequest(
            objectId = text(browse, "ObjectID") ?: "0",
            browseFlag = text(browse, "BrowseFlag") ?: "BrowseDirectChildren",
            startingIndex = text(browse, "StartingIndex")?.toIntOrNull() ?: 0,
            requestedCount = text(browse, "RequestedCount")?.toIntOrNull() ?: 0,
        )
    }.getOrNull()

    /** Qual serviço/ação o SOAP pede — usado para rotear /ctl. */
    fun soapAction(soapXml: String): Pair<String, String>? = runCatching {
        val doc = parseXml(soapXml)
        val el = doc.documentElement ?: return null
        val name = el.localName ?: el.nodeName.substringAfter(':')
        val ns = el.namespaceURI ?: ""
        val service = when {
            ns.contains("ContentDirectory") -> SERVICE_CD
            ns.contains("ConnectionManager") -> SERVICE_CM
            else -> return null
        }
        service to name
    }.getOrNull()

    // ── SSDP ────────────────────────────────────────────────────────────────

    /**
     * Resposta unicast ao M-SEARCH. Formato crua, sem passar por XML: é HTTP, não SOAP.
     * `max-age=1800` curto de propósito — sessão DLNA vive pouco (o servidor cai quando o
     * app sai), TTL longo deixa a TV tentando servidor morto.
     */
    fun ssdpResponse(location: String, uuid: String, st: String): String {
        val usn = if (st == "ssdp:all") "uuid:$uuid" else "uuid:$uuid::$st"
        return (
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "DATE: \r\n" +
                "EXT:\r\n" +
                "LOCATION: $location\r\n" +
                "SERVER: Linux/UPnP/1.0 Pulsa/1\r\n" +
                "ST: $st\r\n" +
                "USN: $usn\r\n" +
                "\r\n"
            )
    }

    /**
     * NOTIFY byebye (anuncia saída da rede ao parar o servidor). Sem isto a TV fica com o
     * servidor fantasma na lista até o próximo boot do Roku.
     */
    fun ssdpByebye(uuid: String): String {
        val nt = DEVICE_TYPE
        return (
            "NOTIFY * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "NT: $nt\r\n" +
                "NTS: ssdp:byebye\r\n" +
                "USN: uuid:$uuid::$nt\r\n" +
                "\r\n"
            )
    }

    /** O ST de um M-SEARCH é alvo nosso? MediaServer:1 e ssdp:all (o generalista). */
    fun stAtende(st: String): Boolean {
        val s = st.trim()
        return s == DEVICE_TYPE || s == "ssdp:all" ||
            s == "urn:schemas-upnp-org:device:MediaServer:1"
    }

    // ── XML util (mesmo motivo do FeedParser) ────────────────────────────────

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { f.isNamespaceAware = true }
        return f.newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun findElement(root: Node?, name: String): Element? {
        var node = root ?: return null
        if (node is Element && (node.localName ?: node.nodeName.substringAfter(':')) == name) return node
        val kids = node.childNodes
        for (i in 0 until kids.length) {
            findElement(kids.item(i), name)?.let { return it }
        }
        return null
    }

    private fun text(parent: Element, name: String): String? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == name) {
                return n.textContent?.trim()?.ifBlank { null }
            }
        }
        return null
    }
}
