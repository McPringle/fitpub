package net.javahippie.fitpub.util;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Shared factory helpers that produce XML parsers hardened against XXE
 * (XML External Entity) attacks. All XML parsing in the application that
 * touches user-controlled bytes (e.g. uploaded GPX files) MUST go through
 * one of these helpers rather than calling {@link DocumentBuilderFactory#newInstance()}
 * directly.
 *
 * <p>The hardening applied here disables DTDs, external entities, parameter
 * entities, and external DTD loading, and enables the JAXP secure-processing
 * feature. Together these defeat the standard XXE payloads (file disclosure
 * via {@code SYSTEM "file:///..."}, billion laughs, SSRF via external entities).
 */
public final class SecureXmlFactories {

    private SecureXmlFactories() {
    }

    /**
     * Returns a {@link DocumentBuilderFactory} hardened against XXE.
     *
     * @param namespaceAware whether to enable namespace awareness
     * @return a hardened factory
     */
    public static DocumentBuilderFactory newDocumentBuilderFactory(boolean namespaceAware) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            // Any underlying parser that does not support these features is
            // unsafe — fail loudly rather than silently fall back.
            throw new IllegalStateException("Failed to configure secure XML parser", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
