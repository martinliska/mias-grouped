/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cz.muni.fi.mias;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Utilities class.
 * 
 * @author Martin Liska
 */
public class MIaSMathUtils {
    
    private static final String MATHML_DTD = "/cz/muni/fi/mias/math/xhtml-math11-f.dtd";
    
    public static DocumentBuilder prepareDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(new EntityResolver() {

            public InputSource resolveEntity(String publicId, String systemId)
                    throws SAXException, java.io.IOException {
                System.out.println(publicId+" "+systemId);
                if (systemId.endsWith("dtd")) {
                    System.out.println(MIaSMathUtils.class.getResourceAsStream(MATHML_DTD));
                    return new InputSource(MIaSMathUtils.class.getResourceAsStream(MATHML_DTD));
                } else {
                    return null;
                }
            }
        });
        return builder;
    }
}
