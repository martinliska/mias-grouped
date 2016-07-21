/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package cz.muni.fi.mias.math;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

/**
 * Helper class containing static MathML configuration
 * 
 * @author Martin Liska
 */
public class MathMLConf {

    private static final String[] ignoreNodeArray =
        {"semantics", "annotation-xml"};
    private static List<String> ignoreNode = new ArrayList<String>(Arrays.asList(ignoreNodeArray));
    private static final String[] ignoreAllArray = {"annotation"};
    private static List<String> ignoreAll = new ArrayList<String>(Arrays.asList(ignoreAllArray));
    
    private static final String[] presentationArray = {"mi","mn","mo","mtext","mspace","ms","mglyph","mrow","mfrac","msqrt","mroot","mstyle","merror",
        "mpadded","mphantom","mfenced","menclose","msub","msup","msubsup","munder","mover","munderover","mmultiscripts","mtable","mlabeledtr","mtr","mtd"};
    private static List<String> presentationElements = new ArrayList<String>(Arrays.asList(presentationArray));
    
    private static final String[] contentArray = {"ci","cn","csymbol","apply","cs","bind","bvar","share","cerror","cbytes","set","domainofapplication",
        "interval","condition","lowlimit","uplimit","degree","momentabout","logbase","union","piecewise","piece","otherwise","reln","fn","declare","ident",
        "domain","codomain","image","ln","log","moment","lambda","compose","quotient","divide","minues","power","rem","root","factorial","abs","conjugate",
        "arg","real","imaginary","floor","ceiling","exp","max","min","plus","times","gcd","lcm","and","or","xor","not","implies","equivalent","forall",
        "exists","eq","gt","lt","geq","leq","neq","approx","factorof","tendsto","int","diff","partialdiff","divergence","grad","curl","laplacian","set",
        "\\list","union","intersect","cartesianproduct","in","notin","notsubset","notprsubset","setdiff","subset","prsubset","card","sum","product",
        "limit","sin","cos","tan","sec","csc","cot","sinh","cosh","tanh","sech","csch","coth","arcsin","arccos","arctan","arccosh","arccot","arccoth",
        "arccsc","arccsch","arcsec","arccsch","arcsec","arcsinh","arctanh","mean","sdev","variance","median","mode","vector","matrix","matrixrow",
        "determinant","transpose","selector","vectorproduct","scalarproduct","outerproduct","integers","reals","rationals","naturalnumbers","complexes",
        "primes","emptyset","exponentiale","imaginaryi","notanumber","true","false","pi","eulergamma","infinity"};
    private static List<String> contentElements = new ArrayList<String>(Arrays.asList(contentArray));
    
    public static final String MATHML_NAMESPACE_URI = "http://www.w3.org/1998/Math/MathML";

    public static List<String> getPresentationElements() {
        return presentationElements;
    }
    
    public static List<String> getContentElements() {
        return contentElements;
    }
    
    /**
     * @return List of MathML nodes which should be ignored during the processing
     */
    public static List<String> getIgnoreNode() {
        return ignoreNode;
    }

    /**
     * 
     * @return List of MathML nodes which should be ignored together with their children during the processing
     */
    public static List<String> getIgnoreAll() {
        return ignoreAll;
    }

    /**
     * 
     * @return default dictionary for substituting standard MathML element names for custom ones
     */
    public static Map<String, String> getElementDictionary() {
        Map<String, String> result = new HashMap<String, String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(MathMLConf.class.getResourceAsStream("element-dictionary")));
            String line = null;
            while ((line = br.readLine()) != null) {
                String[] entry = line.split("=");
                result.put(entry[0], entry[1]);
            }
        } catch (Exception e) {
            System.out.println("Cannot load element dictionary file.");
            e.printStackTrace();
            System.exit(2);
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                System.out.println("Cannot close element dictionary file.");
                e.printStackTrace();
                System.exit(2);
            }
        }
        return result;
    }

    /**
     * 
     * @return default dictionary for substituting standard MathML attribute names and their values for custom ones
     */
    public static Map<String, String> getAttrDictionary() {
        Map<String, String> result = new HashMap<String, String>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(MathMLConf.class.getResourceAsStream("attr-dictionary")));
            String line = null;
            while ((line = br.readLine()) != null) {
                String[] entry = line.split("=");
                result.put(entry[0], entry[1]);
            }
        } catch (Exception e) {
            System.out.println("Cannot load element dictionary file.");
            e.printStackTrace();
            System.exit(2);
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                System.out.println("Cannot close element dictionary file.");
                e.printStackTrace();
                System.exit(2);
            }
        }
        return result;
    }
    
    /**
     * 
     * @return Map of commutative operators and their priorities. The key is a commutative operator and the value is a list of operators that have 
     * a higher priority than the key
     */
    public static Map<String, List<String>> getOperators() {
        Map<String, List<String>> result = new HashMap<String, List<String>>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(MathMLConf.class.getResourceAsStream("operators")));
            String line = null;
            while ((line = br.readLine()) != null) {
                String[] op = line.substring(0, line.indexOf(";")).split(",");
                String[] ops = (line.substring(line.indexOf(";") + 1)).split(",");
                List<String> opsList = Arrays.asList(ops);
                for (int i = 0; i < op.length; i++) {
                    result.put(op[i], opsList);
                }
            }
        } catch (Exception e) {
            System.out.println("Cannot load operators file.");
            e.printStackTrace();
            System.exit(2);
        }
        try {
            br.close();
        } catch (IOException e) {
            System.out.println("Cannot close operators file");
            e.printStackTrace();
            System.exit(2);
        }
        return result;
    }
    
    public static boolean isContentElement(String s) {
        return getContentElements().contains(s);
    }
    
    public static boolean isPresentationElement(String s) {
        return getPresentationElements().contains(s);
    }
    
    public static boolean isIndexableElement(String s) {
        return isContentElement(s) || isPresentationElement(s);
    }
    
    public static boolean ignoreNode(String s) {
        return getIgnoreNode().contains(s);
    } 
    
    public static boolean ignoreNodeAndChildren(String s) {
        return getIgnoreAll().contains(s);
    }
    
}
