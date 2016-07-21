/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cz.muni.fi.webmias.suggest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.spell.LuceneDictionary;
import org.apache.lucene.search.suggest.Lookup.LookupResult;
import org.apache.lucene.search.suggest.analyzing.AnalyzingSuggester;
import org.apache.lucene.util.Version;

/**
 *
 * @author mato
 */
public class TitlesSuggester implements Suggester {
    
    private IndexReader reader;
    private AnalyzingSuggester suggester;

    public TitlesSuggester(IndexReader reader) {
//        try {
            this.reader = reader;
            suggester = new AnalyzingSuggester(new StandardAnalyzer(Version.LUCENE_45));
//            suggester.setPreservePositionIncrements(false);
//            suggester.build(new TitleListIterator(getTitles()));
//            suggester.build(new LuceneDictionary(reader, "title"));
//        } catch (IOException ex) {
//            Logger.getLogger(TitlesSuggester.class.getName()).log(Level.SEVERE, null, ex);
//        }
    }
    
    private List<String> getTitles() throws IOException {
        List<String> result = new ArrayList<String>();
        for (int i = 0; i < reader.maxDoc(); i++) {
            Document document = reader.document(i);
            result.add(document.get("title"));
        }
        return result;
    }
        
    @Override
    public List<String> suggest(String key) {
        System.out.println("Suggestions for " + key);
        List<LookupResult> lookup = suggester.lookup(key, false, 10);
        List<String> result = new ArrayList<String>();
        System.out.println(lookup.size() + " suggestions");
        for (LookupResult lookupResult : lookup) {
            System.out.println(lookupResult.key + ":" + lookupResult.value);
            result.add(lookupResult.key.toString());
        }
        return result;
    }
    
}
