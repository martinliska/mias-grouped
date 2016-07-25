package cz.muni.fi.mias.math;

import cz.muni.fi.mias.MIaSMathUtils;
import cz.muni.fi.mir.mathmlcanonicalization.MathMLCanonicalizer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import org.apache.commons.io.input.ReaderInputStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.BytesRef;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/**
 * Implementation of Lucene Tokenizer. Provides math formulae contained in the input as
 * string tokens and their weight. These attributes are held in
 * TermAttribute and PayloadAttribute and carried over the stream.
 *
 * @author Martin Liska
 * @since 14.5.2010
 */
public class MathTokenizer extends Tokenizer {

    private CharTermAttribute termAtt;
    private PayloadAttribute payAtt;
    private PositionIncrementAttribute posAtt;
    private Iterator<Formula> itForms;
    private Iterator<Map.Entry<Integer, List<Formula>>> itMap;
    private Map<Integer, List<Formula>> formulae = new LinkedHashMap<Integer, List<Formula>>();
    private boolean subformulae;
    private int increment;
    private static FormulaValuator valuator;
    private static float lCoef = 0.7f;
    private static float vCoef = 0.8f;
    private static float cCoef = 0.5f;
    private static float aCoef = 1.2f;
    private static Map<String, List<String>> ops;
    private static Map<String, String> eldict;
    private static Map<String, String> attrdict;
    private MathMLType mmlType;
    private int formulaPosition;
    
    private static final Logger LOG = Logger.getLogger(MathTokenizer.class.getName());
    
    public enum MathMLType {
        CONTENT, PRESENTATION, BOTH
    }
    
    /**
     * @param input Reader containing the input to process
     * @param subformulae if true, subformulae will be extracted
     * @param mmldtd Path to MathML DTD file
     * @param type type of MathML that should be processed
     */
    public MathTokenizer(Reader input, boolean subformulae, MathMLType type) {
        super(input);
        if (eldict == null) {
            eldict = MathMLConf.getElementDictionary();
        }
        if (attrdict == null) {
            attrdict = MathMLConf.getAttrDictionary();
        }
        this.mmlType = type;
        valuator = new CountNodesFormulaValuator();
        if (ops == null) {
            ops = MathMLConf.getOperators();
        }
        this.subformulae = subformulae;
        if (!subformulae) {
            lCoef = 1;
            vCoef = 1;
            cCoef = 1;
        }
        init();
    }
    
    /**
     * Overrides the position attribute for all processed formulae
     * 
     * @param formulaPosition Position number to be used for all processed formulae
     */
    public void setFormulaPosition(int formulaPosition) {
        this.formulaPosition = formulaPosition;
        increment = formulaPosition;
    }
    
    @Override
    public boolean incrementToken() {
        clearAttributes();
        if (nextIt()) {
            Formula f = itForms.next();
            termAtt.setEmpty();
            termAtt.append(nodeToString(f.getNode(), false));
            byte[] payload = PayloadHelper.encodeFloatToShort(f.getWeight());
            payAtt.setPayload(new BytesRef(payload));
            posAtt.setPositionIncrement(increment);
            increment = 0;
            return true;
        }
        return false;
    }

    /**
     * Shifts iterator in the formulae map, helping incrementToken() to decide whether or not
     * is there another token available.
     * @return true if there is another formulae in the map,
     *         false otherwise
     */
    private boolean nextIt() {
        if (itForms == null) {
            return false;
        }
        if (itForms.hasNext()) {
            return true;
        } else {
            if (itMap.hasNext()) {
                Map.Entry<Integer, List<Formula>> entry = itMap.next();
                increment++;
                List<Formula> forms = entry.getValue();
                if (forms != null && !forms.isEmpty()) {
                    itForms = entry.getValue().iterator();
                    return true;
                } else {
                    return nextIt();
                }
            }
        }
        return false;
    }

    /**
     * Initializes the tokenizer by gaining formulae from MathProcessing.getFormulae method
     * and setting the iterator to the first formula of the first map.
     */
    private void init() {
        termAtt = (CharTermAttribute) addAttribute(CharTermAttribute.class);
        payAtt = (PayloadAttribute) addAttribute(PayloadAttribute.class);
        posAtt = (PositionIncrementAttribute) addAttribute(PositionIncrementAttribute.class);

        try {
            reset();

            processFormulae(input, subformulae);

            if (formulae != null && !formulae.isEmpty()) {
                itMap = formulae.entrySet().iterator();
                Map.Entry<Integer, List<Formula>> entry = itMap.next();
                itForms = entry.getValue().iterator();
            }
            increment = 1;
            formulaPosition = 1;

        } catch (IOException ex) {
            Logger.getLogger(MathTokenizer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        if (formulae != null && !formulae.isEmpty()) {
            itMap = formulae.entrySet().iterator();
            Map.Entry<Integer, List<Formula>> entry = itMap.next();
            itForms = entry.getValue().iterator();
            increment = formulaPosition;
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        formulae.clear();
    }
    
    

    private static AtomicLong inputF = new AtomicLong(0);
    private static long producedF = 0;

    /**
     * Performs all the parsing, sorting, modifying and ranking of the formulae contained in the given
     * InputStream.
     * Internal representation of the formula is w3c.dom.Node.
     *
     * @param is InputStream with the formuale.
     * @param subformulae Specifies whether or to process also all the subformuale of each formula.
     * @return Collection of the formulae in the form of Map<Double, List<String>>.
     *         this map gives pairs {created formula, it's rank}. Key of the map is the
     *         rank of the all formulae located in the list specified by the value of the Map.Entry.
     */
    private void processFormulae(Reader input, boolean subformulae) {
        try {
            formulae.clear();
            
            InputSource inputSource;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MathMLCanonicalizer canonicalizer = MathMLCanonicalizer.getDefaultCanonicalizer();
            canonicalizer.canonicalize(new ReaderInputStream(input, "UTF-8"), out);
            inputSource = new InputSource(new ByteArrayInputStream(out.toByteArray()));
            
//            InputSource inputSource = new InputSource(input);
            DocumentBuilder builder = MIaSMathUtils.prepareDocumentBuilder();
            Document doc = builder.parse(inputSource);
            load(doc, subformulae);
            
            order();
            modify();
//            printMap(formulae);
            if (subformulae) {
                for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
                    List<Formula> forms = entry.getValue();
                    producedF += forms.size();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads all the formulae located in given w3c.dom.Document.
     *
     * @param doc DOM Document with formulae
     * @param subformulae Specifies whether or not to load also all subformulae of each formula
     */
    private void load(Document doc, boolean subformulae) {
        String mathMLNamespace = MathMLConf.MATHML_NAMESPACE_URI;
        if (!subformulae) {
            mathMLNamespace = "*";
        }
        NodeList list = doc.getElementsByTagNameNS(mathMLNamespace, "math");
        inputF.addAndGet(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            Node node = list.item(i);
            formulae.put(i, new ArrayList<Formula>());
            if (subformulae) {
                loadNode(node, 1 / valuator.count(node, mmlType), true, i);
            } else {
                loadNode(node, valuator.count(node, mmlType), false, i);
            }
        }
    }

    /**
     * Recursively called when loading also subformulae. Adds all the relevant nodes to the
     * formuale1 map.
     *
     * @param n Node current MathML node.
     * @param level current depth in the original formula tree which is also rank of the this Node
     */
    private void loadNode(Node n, float level, boolean subformulae, int position) {
        if (n instanceof Element) {
            String name = n.getLocalName();
            if (!MathMLConf.ignoreNodeAndChildren(name)) {
                boolean store = false;
                if ((mmlType==MathMLType.BOTH && MathMLConf.isIndexableElement(name)) ||
                    (mmlType==MathMLType.PRESENTATION && MathMLConf.isPresentationElement(name)) || 
                    (mmlType==MathMLType.CONTENT && MathMLConf.isContentElement(name))) {
                    store = true;
                }
                removeTextNodes(n);
                NodeList nl = n.getChildNodes();
                int length = nl.getLength();
                if (subformulae || !store) {
                    for (int j = 0; j < length; j++) {
                        Node node = nl.item(j);
                        loadNode(node, store? level * lCoef : level, subformulae, position);
                    }
                }
                if (store && !MathMLConf.ignoreNode(name)) {
                    formulae.get(position).add(new Formula(n, level));
                }            
            }
        }
    }
    
    /**
     * Removes unnecessary text nodes from the markup
     * 
     * @param node 
     */
    private void removeTextNodes(Node node) {
        NodeList nl = node.getChildNodes();
        int length = nl.getLength();
        int removed = 0;
        for (int j = 0; j < length; j++) {
            Node n = nl.item(removed);
            if ((n instanceof Text) && (n.getTextContent().trim().length() == 0)) {
                node.removeChild(n);
            } else {
                removed++;
                removeTextNodes(n);
            }
        }
    }

    /**
     * Removes all attributes except those specified in the attr-dict configuration file
     * 
     * @param rank factor by which the formulae keeping the attributes increase their weight
     */
    private void processAttributes(float rank) {
        List<Formula> result = new ArrayList<Formula>();
        for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
            result.clear();
            List<Formula> forms = entry.getValue();
            for (Formula f : forms) {
                Node node = f.getNode();
                Node newNode = node.cloneNode(true);
                removeAttributes(node);
                boolean changed = processAttributesNode(newNode);
                if (changed) {
                    result.add(new Formula(newNode, f.getWeight() * rank));
                }
            }
            forms.addAll(result);
        }
    }

    private boolean processAttributesNode(Node node) {
        boolean result = false;
        NodeList nl = node.getChildNodes();
        int length = nl.getLength();
        for (int j = 0; j < length; j++) {
            Node n = nl.item(j);
            result = processAttributesNode(n) == false ? result : true;
        }
        if (node.hasAttributes()) {
            NamedNodeMap attrs = node.getAttributes();
            Set<Node> keepAttrs = new HashSet<Node>();
            for (String dictAttr : attrdict.keySet()) {
                Node keepAttr = attrs.getNamedItem(dictAttr);
                if (keepAttr != null) {
                    keepAttrs.add(keepAttr);
                }
            }
            removeAttributes(node);
            for (Node n : keepAttrs) {
                attrs.setNamedItem(n);
                result = true;
            }
        }
        return result;
    }

    private void removeAttributes(Node node) {
        removeAttributesNode(node);
        NodeList nl = node.getChildNodes();
        int length = nl.getLength();
        for (int j = 0; j < length; j++) {
            removeAttributes(nl.item(j));
        }
    }

    private void removeAttributesNode(Node node) {
        if (node.hasAttributes()) {
            NamedNodeMap attrs = node.getAttributes();
            String[] names = new String[attrs.getLength()];
            for (int i = 0; i < names.length; i++) {
                names[i] = attrs.item(i).getNodeName();
            }
            for (int i = 0; i < names.length; i++) {
                attrs.removeNamedItem(names[i]);
            }
        }
    }

    /**
     * Provides sorting of elements in MathML formula based on the NodeName. Sorting is
     * done for operators from the operators configuration file. All sorted formulae
     * replace their original forms in the formulae map.
     */
    private void order() {
        for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
            for (Formula f : entry.getValue()) {
                Node newNode = f.getNode().cloneNode(true);
                f.setNode(orderNode(newNode));
            }
        }
    }

    private Node orderNode(Node node) {
        if (node instanceof Element) {
        List<Node> nodes = new ArrayList<Node>();
        NodeList nl = node.getChildNodes();
        if (nl.getLength() > 1) {
            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                orderNode(n);
                nodes.add(n);
            }
            if (mmlType == MathMLType.PRESENTATION) {
                    boolean switched;
                    for (Node justCycle : nodes) {
                        switched = false;
                        for (int i = 1; i < nodes.size() - 1; i++) {
                            Node n = nodes.get(i);
                            String name = n.getLocalName();
                            if (name.equals("mo")) {
                                String text = n.getTextContent();
                                if (ops.containsKey(text)) {
                                    Node n1 = nodes.get(i - 1);
                                    Node n2 = nodes.get(i + 1);
                                    boolean toSwap = toSwapNodes(n1, n2);
                                    if (toSwap && canSwap(text, i, nodes)) {
                                        nodes.set(i - 1, n2);
                                        nodes.set(i + 1, n1);
                                        switched = true;
                                    }
                                }
                            }
                        }
                        if (!switched) {
                            break;
                        }
                    }
                }
                if (mmlType == MathMLType.CONTENT) {
                    Node n = node.getFirstChild();
                    String name = n.getLocalName();
//                    printNode(node, name);
                    if (name.equals("times") || name.equals("plus")) {
                        boolean swapped = true;
                        while (swapped) {
                            swapped = false;
                            for (int j = 1; j < nodes.size() - 1; j++) {
                                Node n1 = nodes.get(j);
                                Node n2 = nodes.get(j + 1);
                                if (toSwapNodes(n1, n2)) {
                                    nodes.set(j, n2);
                                    nodes.set(j + 1, n1);
                                    swapped = true;
                                }
                            }
                        }
                    }
                }
                for (Node n : nodes) {
                    node.appendChild(n);
                }
            }
        }
        return node;
    }

    private boolean toSwapNodes(Node n1, Node n2) {
        int c = n1.getNodeName().compareTo(n2.getNodeName());
        if (c == 0) {
            String n1Children = getNodeChildren(n1);
            String n2Children = getNodeChildren(n2);
            c = n1Children.compareTo(n2Children);
        }
        return c > 0 ? true : false;
    }

    private String getNodeChildren(Node node) {
        String result = "";
        result = nodeToString(node, true);
        return result;
    }
    
    /**
     * Converts a node to M-term styled string representation
     */
    private String nodeToString(Node node, boolean withoutTextContent) {
        return Formula.nodeToString(node, withoutTextContent, eldict, attrdict, MathMLConf.getIgnoreNode());
    }

    /**
     * Determines if nodes around Node i in given list of Nodes can be swapped.
     *
     * @param i number of Node in the given list that sorrounding are to be swap
     * @param nodes List of childNodes of some formula, that are to be sorted
     * @return true if the Node i was operation + or * and the surrounding can be swapped,
     *         false otherwise
     */
    private boolean canSwap(String text, int i, List<Node> nodes) {
        boolean result = true;
        List<String> priorOps = ops.get(text);
        if (i - 2 >= 0) {
            Node n11 = nodes.get(i - 2);
            String n11text = n11.getTextContent();
            if (n11.getLocalName().equals("mo") && priorOps.contains(n11text)) {
                result = false;
            }
        }
        if (i + 2 < nodes.size()) {
            Node n22 = nodes.get(i + 2);
            String n22text = n22.getTextContent();
            if (n22.getLocalName().equals("mo") && priorOps.contains(n22text)) {
                result = false;
            }
        }
        return result;
    }

    /**
     * Provides all the modifying on the loaded formulae located in formuale map.
     * Calls several modifiing methods and specifies how they should alter the rank of
     * modified formula.
     */
    private void modify() {
        unifyVariables(vCoef);
        unifyConst(cCoef);
        processAttributes(aCoef);
    }
    
    /**
     * Unifies variables of each formula in formulae map
     *
     * @param rank Specifies the factor by which it should alter the rank of modified formula
     * 
     */
    private void unifyVariables(float rank) {
        List<Formula> result = new ArrayList<Formula>();
        for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
            result.clear();
            List<Formula> forms = entry.getValue();
            for (Formula f : forms) {
                Node node = f.getNode();
                NodeList nl = node.getChildNodes();
                boolean hasElement = true;
                if (((nl.getLength() == 1) && !(nl.item(0) instanceof Element)) || nl.getLength() == 0) {
                    hasElement = false;
                }
                if (hasElement) {
                    Map<String, String> changes = new HashMap<String, String>();
                    Node newNode = node.cloneNode(true);
                    boolean changed = unifyVariablesNode(newNode, changes);
                    if (changed) {
                        result.add(new Formula(newNode, f.getWeight() * rank));
                    }
                }
            }
            forms.addAll(result);
        }
    }

    /**
     * Recursively modifying variables of the formula or subformula specified by given Node
     *
     * @param node Node representing current formula or subformula that is being modified
     * @param changes Map holding the performed changes, so that the variables with the same
     *        name are always substituted with the same unified name within the scope of each formula.
     * @return Saying whether or not this formula was modified
     */
    private boolean unifyVariablesNode(Node node, Map<String, String> changes) {
        boolean result = false;
        if (node instanceof Element) {
            NodeList nl = node.getChildNodes();
            for (int j = 0; j < nl.getLength(); j++) {
                result = unifyVariablesNode(nl.item(j), changes) == false ? result : true;
            }
            if (node.getLocalName().equals("mi") || node.getLocalName().equals("ci")) {
                String oldVar = node.getTextContent();
                String newVar = toVar(oldVar, changes);
                node.setTextContent(newVar);
                return true;
            }
        }
        return result;
    }

    /**
     * Helping method performs substitution of the variable based on the given map of
     * already done changes.
     *
     * @param oldVar Variable to be unified
     * @param changes Map with already done changes.
     * @return new name of the variable
     */
    private String toVar(String oldVar, Map<String, String> changes) {
        String newVar = changes.get(oldVar);
        if (newVar == null) {
            newVar = "" + (changes.size() + 1);
            changes.put(oldVar, newVar);
        }
        return newVar;
    }

    /**
     * Performing unifying of all the constants in the formula by substituting them for "const" string.
     *
     * @param rank Specifies how the method should alter modified formulae
     */
    private void unifyConst(float rank) {
        List<Formula> result = new ArrayList<Formula>();
        for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
            result.clear();
            List<Formula> forms = entry.getValue();
            for (Formula f : forms) {
                Node node = f.getNode();
                NodeList nl = node.getChildNodes();
                boolean hasElement = true;
                if (((nl.getLength() == 1) && !(nl.item(0) instanceof Element)) || nl.getLength() == 0) {
                    hasElement = false;
                }
                if (hasElement) {
                    Node newNode = node.cloneNode(true);
                    boolean changed = unifyConstNode(newNode);
                    if (changed) {
                        result.add(new Formula(newNode, f.getWeight() * rank));
                    }
                }
            }
            forms.addAll(result);
        }
    }

    /**
     * Recursively modifying constants of the formula or subformula specified by given Node
     *
     * @param node Node representing current formula or subformula that is being modified
     * @return Saying whether or not this formula was modified
     */
    private boolean unifyConstNode(Node node) {
        boolean result = false;
        if (node instanceof Element) {
            NodeList nl = node.getChildNodes();
            for (int j = 0; j < nl.getLength(); j++) {
                result = unifyConstNode(nl.item(j)) == false ? result : true;
            }
            if (node.getLocalName().equals("mn") || node.getLocalName().equals("cn")) {
                node.setTextContent("\u00B6");
                return true;
            }
        }
        return result;
    }

    /**
     * @return Processed formulae to be used for a query. No subformulae are extracted.
     */
    public Map<String, Float> getQueryFormulae() {
        Map<String, Float> result = new HashMap<String, Float>();
        for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
            List<Formula> forms = entry.getValue();
            for (Formula f : forms) {
                result.put(nodeToString(f.getNode(), false), f.getWeight());
            }
        }
        return result;
    }

    private void printMap(Map<Integer, List<Formula>> formulae) {
        for (Map.Entry<Integer, List<Formula>> entry : formulae.entrySet()) {
            List<Formula> forms = entry.getValue();
            for (Formula f : forms) {
                System.out.println(entry.getKey() + " " + nodeToString(f.getNode(), false) + " " + f.getWeight());
            }
            System.out.println("");
        }
    }

    /**
     * Prints numbers of processed formulae to standard output
     */
    public static void printFormulaeCount() {
        
        LOG.log(Level.INFO, "Input formulae: " + inputF);
        LOG.log(Level.INFO, "Indexed formulae: " + producedF);
    }

    /**
     * @return A map with formulae as if they are indexed. Key of the map is the original document position of the extracted formulae contained in the value of the map.
     */
    public Map<Integer, List<Formula>> getFormulae() {
        return formulae;
    }
    
}
