package com.smartdocs.editor.core.sign

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Row 9: the trusted-list XML parsing (ETSI TS 119 612) on small fixtures shaped like the EU LOTL / a member-state list. */
class TslParserTest {
    private val lotl = """<?xml version="1.0" encoding="UTF-8"?>
<tsl:TrustServiceStatusList xmlns:tsl="http://uri.etsi.org/02231/v2#" xmlns:ns3="http://uri.etsi.org/02231/v2/additionaltypes#">
  <tsl:SchemeInformation>
    <tsl:SchemeTerritory>EU</tsl:SchemeTerritory>
    <tsl:PointersToOtherTSL>
      <tsl:OtherTSLPointer>
        <tsl:TSLLocation>https://ec.europa.eu/tools/lotl/eu-lotl.xml</tsl:TSLLocation>
        <tsl:AdditionalInformation><tsl:OtherInformation><tsl:SchemeTerritory>EU</tsl:SchemeTerritory></tsl:OtherInformation>
          <tsl:OtherInformation><ns3:MimeType>application/vnd.etsi.tsl+xml</ns3:MimeType></tsl:OtherInformation></tsl:AdditionalInformation>
      </tsl:OtherTSLPointer>
      <tsl:OtherTSLPointer>
        <tsl:TSLLocation>https://tl.example.de/TL-DE.xml</tsl:TSLLocation>
        <tsl:AdditionalInformation><tsl:OtherInformation><tsl:SchemeTerritory>DE</tsl:SchemeTerritory></tsl:OtherInformation>
          <tsl:OtherInformation><ns3:MimeType>application/vnd.etsi.tsl+xml</ns3:MimeType></tsl:OtherInformation></tsl:AdditionalInformation>
      </tsl:OtherTSLPointer>
      <tsl:OtherTSLPointer>
        <tsl:TSLLocation>https://tl.example.de/TL-DE.pdf</tsl:TSLLocation>
        <tsl:AdditionalInformation><tsl:OtherInformation><tsl:SchemeTerritory>DE</tsl:SchemeTerritory></tsl:OtherInformation>
          <tsl:OtherInformation><ns3:MimeType>application/pdf</ns3:MimeType></tsl:OtherInformation></tsl:AdditionalInformation>
      </tsl:OtherTSLPointer>
      <tsl:OtherTSLPointer>
        <tsl:TSLLocation>http://insecure.example.fr/TL-FR.xml</tsl:TSLLocation>
        <tsl:AdditionalInformation><tsl:OtherInformation><tsl:SchemeTerritory>FR</tsl:SchemeTerritory></tsl:OtherInformation>
          <tsl:OtherInformation><ns3:MimeType>application/vnd.etsi.tsl+xml</ns3:MimeType></tsl:OtherInformation></tsl:AdditionalInformation>
      </tsl:OtherTSLPointer>
    </tsl:PointersToOtherTSL>
  </tsl:SchemeInformation>
</tsl:TrustServiceStatusList>"""

    private fun service(type: String, status: String, cert: String, ext: String = "", history: String = "") = """
      <TSPService>
        <ServiceInformation>
          <ServiceTypeIdentifier>$type</ServiceTypeIdentifier>
          <ServiceName><Name xml:lang="en">svc</Name></ServiceName>
          <ServiceDigitalIdentity><DigitalId><X509Certificate>
            $cert
          </X509Certificate></DigitalId></ServiceDigitalIdentity>
          <ServiceStatus>$status</ServiceStatus>
          $ext
        </ServiceInformation>
        $history
      </TSPService>"""

    private val ms = """<?xml version="1.0" encoding="UTF-8"?>
<TrustServiceStatusList xmlns="http://uri.etsi.org/02231/v2#">
  <SchemeInformation><SchemeTerritory>DE</SchemeTerritory></SchemeInformation>
  <TrustServiceProviderList><TrustServiceProvider><TSPServices>
    ${service(TslParser.CA_QC, TslParser.GRANTED, "AQID")}
    ${service(TslParser.CA_QC, "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn", "BAUG")}
    ${service("http://uri.etsi.org/TrstSvc/Svctype/TSA/QTST", TslParser.GRANTED, "BwgJ")}
    ${service(TslParser.CA_QC, TslParser.GRANTED, "CgsM", ext = "<ServiceInformationExtensions><Extension><AdditionalServiceInformation><URI>http://uri.etsi.org/TrstSvc/TrustedList/SvcInfoExt/ForeSeals</URI></AdditionalServiceInformation></Extension></ServiceInformationExtensions>")}
    ${service(TslParser.CA_QC, TslParser.GRANTED, "DQ4P", ext = "<ServiceInformationExtensions><Extension><AdditionalServiceInformation><URI>http://uri.etsi.org/TrstSvc/TrustedList/SvcInfoExt/ForeSignatures</URI></AdditionalServiceInformation></Extension></ServiceInformationExtensions>")}
    ${service(TslParser.CA_QC, "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn", "EBES",
        history = "<ServiceHistory><ServiceHistoryInstance><ServiceTypeIdentifier>${TslParser.CA_QC}</ServiceTypeIdentifier><ServiceStatus>${TslParser.GRANTED}</ServiceStatus></ServiceHistoryInstance></ServiceHistory>")}
  </TSPServices></TrustServiceProvider></TrustServiceProviderList>
</TrustServiceStatusList>"""

    @Test fun lotlPointersToMemberStateXmlListsOverHttps() {
        val p = TslParser.pointers(lotl.byteInputStream())
        assertEquals(1, p.size)
        assertEquals("DE", p[0].territory)
        assertEquals("https://tl.example.de/TL-DE.xml", p[0].url)
    }

    @Test fun grantedQualifiedCasForESignatures() {
        val certs = TslParser.qualifiedCaCerts(ms.byteInputStream())
        assertEquals(2, certs.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), certs[0])
        assertArrayEquals(byteArrayOf(13, 14, 15), certs[1])
        assertEquals("DE", TslParser.territory(ms.byteInputStream()))
    }

    @Test fun doctypesAreRefused() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><TrustServiceStatusList><a>&e;</a></TrustServiceStatusList>"""
        try { TslParser.pointers(xxe.byteInputStream()); fail("a DOCTYPE must be refused") } catch (e: Exception) { assertTrue(true) }
    }

    @Test fun onlyTheEuLotlAddressIsUsed() {
        assertTrue(TrustLists.LOTL_URL.startsWith("https://ec.europa.eu/"))
        try { TrustLists.fetch("http://ec.europa.eu/tools/lotl/eu-lotl.xml"); fail() } catch (e: IllegalArgumentException) { assertTrue(true) }
    }
}
