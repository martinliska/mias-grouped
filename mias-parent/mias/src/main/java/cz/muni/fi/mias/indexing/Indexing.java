package cz.muni.fi.mias.indexing;

import cz.muni.fi.mias.PayloadSimilarity;
import cz.muni.fi.mias.Settings;
import cz.muni.fi.mias.indexing.doc.FileExtDocumentHandler;
import cz.muni.fi.mias.math.MathTokenizer;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

/**
 * Indexing class responsible for adding, updating and deleting files from index,
 * creating, deleting whole index, printing statistics.
 *
 * @author Martin Liska
 * @since 14.5.2010
 */
public class Indexing {

    private File indexDir;
    private Analyzer analyzer = new StandardAnalyzer(Version.LUCENE_45);
    private long docLimit = Settings.getDocLimit();
    private long count = 0;
    private long progress = 0;
    private long fileProgress = 0;
    private String storage;
    private Date startTime;

    /**
     * Constructor creates Indexing instance. Directory with the index is taken from the Settings.
     *
     */
    public Indexing() {
        this.indexDir = new File(Settings.getIndexDir());
    }

    /**
     * Indexes files located in given input path.
     * @param path Path to the documents directory. Can be a single file as well.
     * @param rootDir A path in the @path parameter which is a root directory for the document storage. It determines the relative path
     * the files will be index with.
     */
    public void indexFiles(String path, String rootDir) {
        storage = rootDir;
        if (!storage.endsWith(File.separator)) {
            storage += File.separator;
        }
        final File docDir = new File(path);
        if (!docDir.exists() || !docDir.canRead()) {
            System.out.println("Document directory '" + docDir.getAbsolutePath() + "' does not exist or is not readable, please check the path");
            System.exit(1);
        }
        try {
            startTime = new Date();
            IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_45, analyzer);
            PayloadSimilarity ps = new PayloadSimilarity();
            ps.setDiscountOverlaps(false);
            config.setSimilarity(ps);
            config.setIndexDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
            IndexWriter writer = new IndexWriter(FSDirectory.open(indexDir), config);
            List<File> files = getDocs(docDir);
            countFiles(files);
            indexDocsThreaded(files, writer);
            writer.close();
        } catch (IOException ex) {
            ex.printStackTrace();
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
                if (docLimit == 0) {
                    return result;
                } else if (isFileIndexable(file)){
                    result.add(file);
                    docLimit--;
                }
            }
        }
        return result;
    }

    private void indexDocsThreaded(List<File> files, IndexWriter writer) {
        try {
            boolean overWrite = Settings.getUpdateFiles();
            DirectoryReader reader = null;
            if (DirectoryReader.indexExists(FSDirectory.open(indexDir))) {
                reader = DirectoryReader.open(FSDirectory.open(indexDir));
            } else {
                overWrite = true;
            }
            Iterator<File> it = files.iterator();
            ExecutorService executor = Executors.newFixedThreadPool(Settings.getNumThreads());
            FutureTask[] tasks = new FutureTask[1];
            int running = 0;
            while (it.hasNext() || running > 0) {
                for (int i = 0; i < tasks.length; i++) {
                    if (tasks[i] == null && it.hasNext()) {
                        boolean write = overWrite;
                        File f = it.next();
                        String path = resolvePath(f);
                        if (!write) {
                            int docFreq = reader.docFreq(new Term("path", path));
                            write = docFreq>0;
                        }
                        if (write) {
                            Callable callable = new FileExtDocumentHandler(f, path);
                            FutureTask ft = new FutureTask(callable);
                            tasks[i] = ft;
                            executor.execute(ft);
                            running++;
                        }
                    } else if (tasks[i] != null && tasks[i].isDone()) {
                        List<Document> docs = (List<Document>) tasks[i].get();
                        running--;
                        tasks[i] = null;
                        for (Document doc : docs) {
                            if (doc != null) {
                                System.out.println("adding " + doc.get("path"));
                                System.out.println("Documents processed: " + (++progress));
                                if (progress % 10000 == 0) {
                                    printTimes();
                                    writer.commit();
                                }
                                writer.updateDocument(new Term("id", doc.get("id")), doc);
                                
                                System.out.println("---------------------------------");
                            }
                        }
                        System.out.println("File progress: " + (++fileProgress) + " of " + count + " done...");
                    }
                }
            }
            printTimes();
            executor.shutdown();

        } catch (IOException ex) {
            Logger.getLogger(Indexing.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InterruptedException ex) {
            Logger.getLogger(Indexing.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ExecutionException ex) {
            Logger.getLogger(Indexing.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Optimizes the index.
     */
    public void optimize() {
        try {
            startTime = new Date();
            IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_31, analyzer);
            config.setIndexDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
            IndexWriter writer = new IndexWriter(FSDirectory.open(indexDir), config);
//            writer.optimize();
            writer.close();
            Date end = new Date();
            System.out.println("Optimizing time: "+ (end.getTime()-startTime.getTime())+" ms");
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /**
     * Deletes whole current index directory
     */
    public void deleteIndexDir() {
        deleteDir(indexDir);
    }

    private void deleteDir(File f) {
        if (f.exists()) {
            File[] files = f.listFiles();
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    deleteDir(files[i]);
                } else {
                    files[i].delete();
                }
            }
            f.delete();
        }
    }

    /**
     * Deletes files located in given path from the index
     *
     * @param path Path of the files to be deleted
     */
    public void deleteFiles(String path) {
        final File docDir = new File(path);
        if (!docDir.exists() || !docDir.canRead()) {
            System.out.println("Document directory '" + docDir.getAbsolutePath() + "' does not exist or is not readable, please check the path");
            System.exit(1);
        }
        try {
            IndexWriterConfig config = new IndexWriterConfig(Version.LUCENE_31, analyzer);
            config.setIndexDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
            IndexWriter writer = new IndexWriter(FSDirectory.open(indexDir), config);
            deleteDocs(writer, docDir);
            writer.close();
        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        }
    }

    private void deleteDocs(IndexWriter writer, File file) throws IOException {
        if (file.canRead()) {
            if (file.isDirectory()) {
                File[] files = file.listFiles();
                if (files != null) {
                    for (int i = 0; i < files.length; i++) {
                        deleteDocs(writer, files[i]);
                    }
                }
            } else {
                System.out.println("deleting " + file.getAbsolutePath());
                writer.deleteDocuments(new Term("path",resolvePath(file)));
            }
        }
    }

    /**
     * Prints statistic about the current index
     */
    public void getStats() {
        String stats = "\nIndex statistics: \n\n";
        DirectoryReader ir = null;
        try {
            ir = DirectoryReader.open(FSDirectory.open(indexDir));
            stats += "Index directory: "+indexDir.getAbsolutePath() + "\n";
            stats += "Number of indexed documents: " + ir.numDocs() + "\n";
            
            long fileSize = 0;
            for (int i = 0; i < ir.numDocs(); i++) {
                Document doc = ir.document(i);
                if (doc.getField("filesize")!=null) {
                    String size = doc.getField("filesize").stringValue();
                    fileSize += Long.valueOf(size);
                }
            }
            long indexSize = 0;
            File[] files = indexDir.listFiles();
            for (File f : files) {
                indexSize += f.length();
            }
            stats += "Index size: " + indexSize + " bytes \n";
            stats += "Approximated size of indexed files: " + fileSize + " bytes \n";

            System.out.println(stats);
        } catch (Exception e) {
            System.out.println(e.getMessage());
        } finally {
            if (ir!=null) {
                try {
                    ir.close();
                } catch (IOException ex) {
                    System.out.println(ex.getMessage());
                }
            }
        }
    }

    

    private String resolvePath(File file) throws IOException {
        String path = file.getCanonicalPath();
        return path.substring(storage.length());
    }

    private long getCpuTime() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long result = 0;
        if (bean.isThreadCpuTimeSupported()) {
            final long[] ids = bean.getAllThreadIds();
            for (long id : ids) {
                result += bean.getThreadCpuTime(id) / 1000000;
            }
        }
        return result;
    }
    
    private long getUserTime() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long result = 0;
        if (bean.isThreadCpuTimeSupported()) {
            final long[] ids = bean.getAllThreadIds();
            for (long id : ids) {
                result += bean.getThreadUserTime(id) / 1000000;
            }
        }
        return result;
    }

    private void printTimes() {
        Date intermediate = new Date();
        System.out.println("---------------------------------");
        System.out.println();
        System.out.println(progress + " DONE in total time " + (intermediate.getTime() - startTime.getTime()) + " ms,");
        System.out.println("CPU time " + (getCpuTime()) + " ms");
        System.out.println("user time " + (getUserTime()) + " ms");
        MathTokenizer.printFormulaeCount();
        System.out.println();
    }

    private boolean isFileIndexable(File file) {
        String path = file.getAbsolutePath();
        String ext = path.substring(path.lastIndexOf(".") + 1);
        if (ext.equals("xhtml") || ext.equals("zip")) {
            return true;
        }
        return false;
    }

    private void countFiles(List<File> files) {
        if (docLimit > 0) {
            count = Math.min(files.size(), docLimit);
        } else {
            count = files.size();
        }
    }
}
