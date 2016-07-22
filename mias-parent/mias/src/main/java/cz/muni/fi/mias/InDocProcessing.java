/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cz.muni.fi.mias;

import cz.muni.fi.mias.indexing.doc.DocumentSource;
import cz.muni.fi.mias.indexing.doc.FileDocument;
import cz.muni.fi.mias.indexing.doc.ZipEntryDocument;
import cz.muni.fi.mias.math.Formula;
import cz.muni.fi.mias.math.MathMLConf;
import cz.muni.fi.mias.math.MathTokenizer;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Class for processing MathML and inserting M-terms back to the documents and storing as a copy.
 * 
 * @author Martin Liska
 */
public class InDocProcessing {

    private long progress = 0;
    private long count = 0;
    private File inPath;
    private String outDir;
    private char dirSep = System.getProperty("file.separator").charAt(0);
    private Date start;

    /**
     * 
     * @param inPath Directory containing documents to process
     * @param outDir Root direcotry from the @inPath to determine the output directory. Processed files will be stored under
     * <outDir>_indoc directory
     */
    public InDocProcessing(String inPath, String outDir) {
        this.inPath = new File(inPath);
        this.outDir = outDir;
    }

    /**
     * Runs threaded processing of the documents. Number of threads is taken from the settings file.
     * Parses MathML contained in the documents and stores created M-terms in a new <annotation encoding="M-terms"></annotation> element.
     */
    public void process() {
        try {
            start = new Date();
            List<File> docs = getDocs(inPath);
            count = docs.size();
            processDocsThreaded(docs);
        } catch (IOException ex) {
            Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    private List<File> getDocs(File file) throws IOException {
        List<File> result = new ArrayList<File>();
        if (file.canRead()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        result.addAll(getDocs(files[i]));
                    }
                }
            } else {
                result.add(file);
            }
        }
        return result;
    }

    private void processDocsThreaded(List<File> files) {
        Iterator<File> it = files.iterator();
        ExecutorService executor = Executors.newFixedThreadPool(Settings.getNumThreads());
        while (it.hasNext()) {
            File f = it.next();
            executor.execute(new InDocProcessor(f));
        }
        executor.shutdown();
        printTimes();
    }

    private String resolveNewPath(File file) throws IOException {
        StringBuilder path = new StringBuilder(file.getCanonicalPath());
        path.insert(path.indexOf(outDir) + outDir.length(), "_indoc");
        return path.toString();
    }

    private class InDocProcessor implements Runnable {

        private File file;

        public InDocProcessor(File file) {
            this.file = file;
        }

        public void run() {
            processDoc(file);
        }

        private void processDoc(File file) {
            System.out.println("Processing " + file);
            System.out.println("Progress: " + (++progress) + " of " + count + " done...");
            if (progress % 10000 == 0) {
                printTimes();
            }
            String path = file.getPath();
            String ext = path.substring(path.lastIndexOf(".") + 1);

            if (ext.equals("zip")) {
                try {
                    ZipFile zipFile = new ZipFile(file);
                    Enumeration e = zipFile.entries();
                    while (e.hasMoreElements()) {
                        ZipEntry entry = (ZipEntry) e.nextElement();
                        insertMathToXML(new ZipEntryDocument(zipFile, path, entry));
                    }
                } catch (ZipException ex) {
                    Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
                } catch (IOException ex) {
                    Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
                }
            } else {
                insertMathToXML(new FileDocument(file, path));
            }
        }

        private void insertMathToXML(DocumentSource source) {
            InputStreamReader isr1 = null;
            InputStreamReader isr2 = null;
            try {
                isr1 = new InputStreamReader(source.resetStream(), "UTF-8");
                isr2 = new InputStreamReader(source.resetStream(), "UTF-8");
                MathTokenizer mt = new MathTokenizer(isr1, true, MathTokenizer.MathMLType.BOTH);
                Map<Integer, List<Formula>> forms = mt.getFormulae();
                DocumentBuilder builder = MIaSUtils.prepareDocumentBuilder();
                InputSource is = new InputSource(isr2);
                Document document = builder.parse(is);
                NodeList maths = document.getElementsByTagNameNS(MathMLConf.MATHML_NAMESPACE_URI, "math");
                for (int i = 0; i < maths.getLength(); i++) {
                    List<Formula> formulae = forms.get(i);
                    String mterms = "";
                    for (int j = 0; j < formulae.size(); j++) {
                        Formula f = formulae.get(j);
                        mterms += "{\"" + Formula.nodeToString(f.getNode(), false, MathMLConf.getElementDictionary(), MathMLConf.getAttrDictionary(), MathMLConf.getIgnoreNode()) + "\",\"" + f.getWeight() + "}";
                    }
                    if (mterms.length() > 1) {
                        mterms = "[" + mterms + "]";
                    }
                    Element el = document.createElementNS(MathMLConf.MATHML_NAMESPACE_URI, "annotation");
                    el.setAttribute("encoding", "M-terms");
                    el.setTextContent(mterms);
                    maths.item(i).appendChild(el);
                }
                writeToFile(document, resolveNewPath(file));
            } catch (SAXException ex) {
                Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
            } catch (ParserConfigurationException ex) {
                Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
            } catch (UnsupportedEncodingException ex) {
                Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
            } catch (IOException ex) {
                Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
            } finally {
                try {
                    isr1.close();
                    isr2.close();
                } catch (IOException ex) {
                    Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }

        private void writeToFile(Document document, String path) {
            try {
                String ext = path.substring(path.lastIndexOf(".") + 1);
                File outFile = new File(path);
                outFile.getParentFile().mkdirs();
                OutputStream out = null;
                if (ext.equals("zip")) {
                    BufferedInputStream origin = null;
                    FileOutputStream dest = new FileOutputStream(path);
                    out = new ZipOutputStream(new BufferedOutputStream(dest));
                    String entryName = path.substring(path.lastIndexOf(dirSep));
                    entryName = entryName.substring(0, entryName.lastIndexOf(".") + 1) + "xhtml";
                    ZipEntry entry = new ZipEntry(entryName);
                    ((ZipOutputStream) out).putNextEntry(entry);
                } else {
                    out = new FileOutputStream(path);
                }
                // Use a Transformer for output
                TransformerFactory tFactory = TransformerFactory.newInstance();
                Transformer transformer = tFactory.newTransformer();
                DOMSource source = new DOMSource(document);
                if (document.getDoctype() != null) {
                    String systemValue = (new File(document.getDoctype().getSystemId())).getName();
                    transformer.setOutputProperty(
                            OutputKeys.DOCTYPE_SYSTEM, systemValue);
                }
                StreamResult result = new StreamResult(out);
                transformer.transform(source, result);
                out.close();
            } catch (IOException ex) {
                Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
            } catch (TransformerException ex) {
                Logger.getLogger(InDocProcessing.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

    private void printTimes() {
        Date intermediate = new Date();
        System.out.println("---------------------------------");
        System.out.println();
        System.out.println(progress + " DONE in " + (intermediate.getTime() - start.getTime()) + " ms");
        System.out.println();
    }
}
