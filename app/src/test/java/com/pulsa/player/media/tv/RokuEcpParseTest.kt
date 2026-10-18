package com.pulsa.player.media.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1 — parsers do ECP. O XML do Roku muda de forma entre firmware (user-device-name vs
 * friendly-name, app com atributo vs filho); os testes travam as duas formas para a
 * descoberta não "esquecer" a TV nova quando o firmware mudar.
 */
class RokuEcpParseTest {

    @Test
    fun deviceInfoLeFriendlyNameEReal() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <device-info xmlns="urn:roku-com:service:ecp:1">
              <user-device-name>Sala</user-device-name>
              <model-name>8290X</model-name>
              <model-number>8290X</model-number>
              <serial-number>YX00ABC</serial-number>
              <network-type>WIFI</network-type>
            </device-info>
        """.trimIndent()
        val d = RokuEcp.parseDeviceInfo("192.168.0.15", xml)
        assertNotNull(d)
        assertEquals("Sala", d!!.friendlyName)
        assertEquals("8290X", d.model)
        assertEquals("192.168.0.15", d.host)
    }

    @Test
    fun deviceInfoFallbackParaFriendlyName() {
        // Firmware antigo não tem user-device-name.
        val xml = """
            <device-info>
              <friendly-name>Philco Roku</friendly-name>
              <model-name>3911X</model-name>
            </device-info>
        """.trimIndent()
        val d = RokuEcp.parseDeviceInfo("10.0.0.2", xml)
        assertEquals("Philco Roku", d!!.friendlyName)
    }

    @Test
    fun deviceInfoXmlInvalidoDevolveNull() {
        assertNull(RokuEcp.parseDeviceInfo("x", "not xml at all"))
        assertNull(RokuEcp.parseDeviceInfo("x", "<device-info></device-info>"))
    }

    @Test
    fun appsLeAtributoEFilho() {
        val xml = """
            <apps>
              <app id="12" version="4.1" name="Roku Media Player">
                <id>12</id>
                <name>Roku Media Player</name>
              </app>
              <app id="11" version="1.0">
                <id>11</id>
                <name>Media Player</name>
              </app>
              <app id="14" name="Netflix" />
            </apps>
        """.trimIndent()
        val apps = RokuEcp.parseApps(xml)
        assertEquals(3, apps.size)
        assertEquals("Roku Media Player", apps[0].name)
        assertEquals("12", apps[0].id)
        assertEquals("Media Player", apps[1].name)
        assertEquals("Netflix", apps[2].name)
    }

    @Test
    fun appsXmlInvalidoDevolveVazio() {
        assertTrue(RokuEcp.parseApps("<apps>").isEmpty())
        assertTrue(RokuEcp.parseApps("lixo").isEmpty())
    }

    @Test
    fun mediaPlayerConverteSegundosParaMs() {
        val xml = """
            <player>
              <state>play</state>
              <position>42</position>
              <duration>240</duration>
              <plugin>Roku Media Player</plugin>
            </player>
        """.trimIndent()
        val s = RokuEcp.parseMediaPlayer(xml)
        assertNotNull(s)
        assertTrue(s!!.isPlaying)
        assertEquals(42_000L, s.positionMs)
        assertEquals(240_000L, s.durationMs)
    }

    @Test
    fun mediaPlayerPauseNaoEMusicaTocando() {
        val xml = "<player><state>pause</state><position>0</position><duration>0</duration></player>"
        val s = RokuEcp.parseMediaPlayer(xml)
        assertFalse(s!!.isPlaying)
    }

    @Test
    fun mediaPlayerSemStateDevolveNull() {
        assertNull(RokuEcp.parseMediaPlayer("<player><position>1</position></player>"))
    }

    @Test
    fun ssdpLocationExtraiHostPorta() {
        assertEquals(
            "192.168.0.15:8060",
            RokuEcp.parseSsdpLocation("LOCATION: http://192.168.0.15:8060/query/device-info"),
        )
        assertEquals(
            "10.0.0.7:8060",
            RokuEcp.parseSsdpLocation("location: http://10.0.0.7:8060/"),
        )
        assertNull(RokuEcp.parseSsdpLocation("CACHE-CONTROL: max-age=1800"))
    }

    @Test
    fun activeAppLeIdENome() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <app id="12">
              <name>Roku Media Player</name>
            </app>
        """.trimIndent()
        val app = RokuEcp.parseActiveApp(xml)
        assertNotNull(app)
        assertEquals("12", app!!.id)
        assertEquals("Roku Media Player", app.name)
    }

    @Test
    fun activeAppRaizWrapperCaiNoFilhoApp() {
        // Alguns firmwares envolvem em <active-app>; o id/nome continuam no <app>.
        val xml = "<active-app><app id=\"home\"><name>Roku Home</name></app></active-app>"
        val app = RokuEcp.parseActiveApp(xml)
        assertEquals("home", app!!.id)
        assertEquals("Roku Home", app.name)
    }

    @Test
    fun activeAppSemIdOuXmlInvalidoDevolveNull() {
        assertNull(RokuEcp.parseActiveApp("<app></app>"))
        assertNull(RokuEcp.parseActiveApp("<app"))
        assertNull(RokuEcp.parseActiveApp("lixo"))
    }
}
