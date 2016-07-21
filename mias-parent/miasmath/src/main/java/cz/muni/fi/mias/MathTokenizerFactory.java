    package cz.muni.fi.mias;

import cz.muni.fi.mias.math.MathTokenizer;
import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.util.TokenizerFactory;
import org.apache.lucene.util.AttributeSource.AttributeFactory;

/**
 * Factory used for calling MathTokenizer from SOLR environment.
 * The following attributes must be specified in the schema.xml file for tokenizer MathTokenizer:
 * <ul>
 *   <li>subformulae - true for analyzer type index, false for analyzer type query</li>
 * </ul>
 * 
 * Complete example:
 * <fieldType name="math" class="solr.TextField">
 *   <analyzer type="index">
 *     <tokenizer class="cz.muni.fi.mias.MathTokenizerFactory" subformulae="true"  /> 
 *   </analyzer>
 *   <analyzer type="query">
 *     <tokenizer class="cz.muni.fi.mias.MathTokenizerFactory" subformulae="false" /> 
 *   </analyzer>
 * </fieldType>
 *
 * @author Martin Liska
 */
public class MathTokenizerFactory extends TokenizerFactory {

    private boolean subformulae;

    public MathTokenizerFactory(Map<String, String> args) {
        super(args);
        String subforms = args.get("subformulae");
        subformulae = Boolean.parseBoolean(subforms);
    }
    
    

//    public Tokenizer create(Reader input) {
//        return new MathTokenizer(input, subformulae, mathmlDtd, MathTokenizer.MathMLType.BOTH);
//    }

//    @Override
//    public void init(Map<String, String> args) {
//        super.init(args);
//        
//        String subforms = args.get("subformulae");
//        subformulae = Boolean.parseBoolean(subforms);
//
//        mathmlDtd = args.get("mathmldtd");
//        if (mathmlDtd != null && !new File(mathmlDtd).isAbsolute()) {
//            mathmlDtd = SolrResourceLoader.locateSolrHome() + mathmlDtd;
//        }
//    }

    @Override
    public Tokenizer create(AttributeFactory af, Reader reader) {
        return new MathTokenizer(reader, subformulae, MathTokenizer.MathMLType.BOTH);
    }

}
