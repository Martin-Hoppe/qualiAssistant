package qualiaIdentifier;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.CoreDocument;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import utils.LanguageManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static utils.CSVFiles.defaultCSVFormat;
import static utils.CSVFiles.readCSVFile;


public class QualiaIdentifier {

    private static final int NUMBER_OF_SKIP_SPACES = 1;
    private static final char SKIP_CHARACTER = ' ';


    private static final Pattern regexQuery = Pattern.compile("<query>(.*?)</query>");
    private static final Pattern regexQualia = Pattern.compile("<qualia>(.*?)</qualia>");
    private static final Pattern regexPOS_TAG_TERM = Pattern.compile("(.*)/(.+).*?");


    public void computeQualiaStructures(
            File selectedFile_preprocessedFile,
            Path pathToPatternAndRoles,
            String query,
            boolean useStemming,
            Path pathToFileWithQualiaRolesForQuery,
            LanguageManager languageManager,
            boolean enableDeepSearch) {
        try {
            CSVParser csvParser_patternAndRoles = Objects.requireNonNull(readCSVFile(pathToPatternAndRoles));
            List<PatternAndRole> patternAndRoles = getPatternAndRoles(csvParser_patternAndRoles);
            csvParser_patternAndRoles.close();

            CSVParser csvParser_preprocessedFile = readCSVFile(Paths.get(String.valueOf(selectedFile_preprocessedFile)));
            List<String> headerOld = Objects.requireNonNull(csvParser_preprocessedFile).getHeaderNames();
            List<String> headerNewElements = Arrays.asList(
                    "role",
                    "inputPattern_withTags", "inputPattern",
                    "immutableMatchingPattern_withTags", "immutableMatchingPattern",
                    "onlyLeafsOfImmutableMatchingPattern_withTags", "onlyLeafsOfImmutableMatchingPattern",
                    "subsentenceMatchingPattern_withTags", "subsentenceMatchingPattern",
                    "query", "qualiaRole");

            List<String> headerNew = new ArrayList<>();
            headerNew.addAll(headerOld);
            headerNew.addAll(headerNewElements);

            CSVPrinter csvPrinter = new CSVPrinter(
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    new FileOutputStream(
                                            String.valueOf(pathToFileWithQualiaRolesForQuery).replace(".csv", "_" + query + ".csv")
                                    ),
                                    StandardCharsets.UTF_8)
                    ),
                    defaultCSVFormat.withHeader(headerNew.toArray(new String[0])));

            List<String> queryTerms = new ArrayList<>();
            if (query != null && !query.trim().isEmpty()) {
                queryTerms = getTerms(query, languageManager, useStemming);
            }

            for (CSVRecord csvRecord : csvParser_preprocessedFile) {

                Set<String> uniqueQualiaQueryPairForSentenceSet = new HashSet<>();

                String constituencyTreeString = csvRecord.get("constituency_tree");
                POSNode constituencyTree = getTreeStructure(constituencyTreeString.substring(1, constituencyTreeString.length()-1));

                List<String> extractedSentenceTerms = getTerms(csvRecord.get("extractedSentence"), languageManager, useStemming);
                if ((query != null && !query.trim().isEmpty()) && !extractedSentenceTerms.containsAll(queryTerms)) {
                    continue;
                }

                for (PatternAndRole patternAndRole : patternAndRoles) {

                    List<List<PatternMatch>> listOfPatternMatchingInformation = getMatchesWithRequiredPOSSequences(constituencyTree, patternAndRole, enableDeepSearch);
                    if (listOfPatternMatchingInformation.isEmpty()) {
                        continue;
                    }

                    for (List<PatternMatch> patternMatches : listOfPatternMatchingInformation) {

                        for (PatternMatch patternMatch : patternMatches) {
                            List<Object> csvEntry = new ArrayList<>();
                            for (String column : headerOld) {
                                csvEntry.add(csvRecord.get(column));
                            }

                            String foundQuery = "";
                            Matcher matcherQuery = regexQuery.matcher(patternMatch.subSentenceMatchingPattern_withTags);
                            if (matcherQuery.find()) {
                                foundQuery = matcherQuery.group(1);
                            }

                            String foundQualia = "";
                            Matcher matcherQualia = regexQualia.matcher(patternMatch.subSentenceMatchingPattern_withTags);
                            if (matcherQualia.find()) {
                                foundQualia = matcherQualia.group(1);
                            }

                            csvEntry.add(patternAndRole.role);
                            csvEntry.add(patternAndRole.inputPattern_withTags);
                            csvEntry.add(patternAndRole.inputPattern);
                            csvEntry.add(patternMatch.immutableMatchingPattern_withTags);
                            csvEntry.add(patternMatch.immutableMatchingPattern);
                            csvEntry.add(patternMatch.onlyLeafsOfImmutableMatchingPattern_withTags);
                            csvEntry.add(patternMatch.onlyLeafsOfImmutableMatchingPattern);
                            csvEntry.add(patternMatch.subSentenceMatchingPattern_withTags);
                            csvEntry.add(patternMatch.subSentenceMatchingPattern);
                            csvEntry.add(foundQuery);
                            csvEntry.add(foundQualia);

                            List<String> foundQueryTerms = new ArrayList<>();
                            if (query != null && !query.trim().isEmpty()) {
                                foundQueryTerms = getTerms(foundQuery, languageManager, useStemming);
                            }

                            if (query != null && !query.trim().isEmpty() && !foundQueryTerms.containsAll(queryTerms)) {
                                continue;
                            } else if (foundQuery.isEmpty() || foundQualia.isEmpty()) {
                                continue;
                            } else if (uniqueQualiaQueryPairForSentenceSet.contains(
                                    getUniqueQualiaQueryRolePatternEntry(csvRecord.get("sentence_id"), foundQuery, foundQualia, patternAndRole.role))) {
                                continue;
                            }

                            uniqueQualiaQueryPairForSentenceSet.add(getUniqueQualiaQueryRolePatternEntry(csvRecord.get("sentence_id"), foundQuery, foundQualia, patternAndRole.role));
                            csvPrinter.printRecord(csvEntry.toArray(new Object[0]));
                        }
                    }

                }
            }

            csvPrinter.flush();
            csvPrinter.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getUniqueQualiaQueryRolePatternEntry (String sentenceId, String query, String qualia, String role) {
        return sentenceId + "$" + query + "$" + qualia + "$" + role;
    }

    private String addQueryAndQualiaToFoundPattern(String patternWithQueryAndQualia, String foundPattern) {

        StringBuilder foundPatternWithQueryAndQualia = new StringBuilder();
        String[] patternWithQueryAndQualiaArray = patternWithQueryAndQualia.split("\\s+");
        String[] foundPatternArray = foundPattern.split("\\s+");
        int i=0;
        int j=0;

        while (i < patternWithQueryAndQualiaArray.length) {
            // TODO: refactor duplicate
            boolean isQuery = false;
            Matcher matcherQuery = regexQuery.matcher(patternWithQueryAndQualiaArray[i]);
            if (matcherQuery.find()) {
                isQuery = true;
                patternWithQueryAndQualiaArray[i] = patternWithQueryAndQualiaArray[i].replaceAll("<query>", "").replaceAll("</query>", "");
            }

            boolean isQualia = false;
            Matcher matcherQualia = regexQualia.matcher(patternWithQueryAndQualiaArray[i]);
            if (matcherQualia.find()) {
                isQualia = true;
                patternWithQueryAndQualiaArray[i] = patternWithQueryAndQualiaArray[i].replaceAll("<qualia>", "").replaceAll("</qualia>", "");
            }

            patternWithQueryAndQualiaArray[i] = patternWithQueryAndQualiaArray[i].replaceAll("/.*", "");

            if (patternWithQueryAndQualiaArray[i].matches("\\[.*?]")) {
                patternWithQueryAndQualiaArray[i] = patternWithQueryAndQualiaArray[i].substring(1,patternWithQueryAndQualiaArray[i].length()-1);
                String[] patternWithQueryAndQualiaArrayOptional = patternWithQueryAndQualiaArray[i].split(",");
                for (String option : patternWithQueryAndQualiaArrayOptional) {
                    if (option.replaceAll("<.*?>", "").equals(foundPatternArray[j])) {
                        patternWithQueryAndQualiaArray[i] = option;
                        break;
                    }
                }
            }

            if (patternWithQueryAndQualiaArray[i].equals(foundPatternArray[j])) {
                if (isQuery) {
                    foundPatternWithQueryAndQualia.append("<query>").append(foundPatternArray[j]).append("</query>");
                } else if (isQualia) {
                    foundPatternWithQueryAndQualia.append("<qualia>").append(foundPatternArray[j]).append("</qualia>");
                } else {
                    foundPatternWithQueryAndQualia.append(foundPatternArray[j]);
                }
                foundPatternWithQueryAndQualia.append(" ");
                j++;
            }
            i++;
        }

        return foundPatternWithQueryAndQualia.toString();
    }


    private List<String> getTerms(String stringToExtractTerms, LanguageManager languageManager, boolean useStemming) {
        List<String> terms = new ArrayList<>();
        CoreDocument coreDocument = new CoreDocument(stringToExtractTerms);
        languageManager.getSentenceSplitting_pipeline().annotate(coreDocument);
        for (CoreLabel coreLabel : coreDocument.tokens()) {
            String term = coreLabel.originalText();

            if (useStemming) {
                languageManager.getStemmer().setCurrent(term);
                languageManager.getStemmer().stem();
                term = languageManager.getStemmer().getCurrent();
            }

            if (term.matches(".*[a-zA-Z0-9].*")) {
                terms.add(term);
            }
        }

        return terms;
    }

    private List<PatternAndRole> getPatternAndRoles(CSVParser csvParser_patternAndRoles) {
        List<PatternAndRole> patternAndRoles = new ArrayList<>();
        for (CSVRecord csvRecord_patternAndRoles : csvParser_patternAndRoles) {
            patternAndRoles.add(
                    new PatternAndRole(
                            csvRecord_patternAndRoles.get("role"),
                            csvRecord_patternAndRoles.get("pattern").replaceAll("<.*?>", ""),
                            csvRecord_patternAndRoles.get("pattern")
                    )
            );
        }
        return patternAndRoles;
    }

    private List<List<PatternMatch>> getMatchesWithRequiredPOSSequences(
            POSNode constituencyTree,
            PatternAndRole patternAndRole,
            boolean enableDeepSearch) {

        List<List<POSNode>> listOfSubGraphsWithoutLeafs = findMatchingSubGraphsWithoutLeafs(constituencyTree, patternAndRole.listOfPossiblePOSSequences, enableDeepSearch);
        List<List<PatternMatch>> listOfPatternMatchesWithoutLeafs = getListOfPatternMatchesWithoutLeafs(listOfSubGraphsWithoutLeafs, patternAndRole.inputPattern_withTags);

        List<List<POSNode>> listOfMatchingLeafs = findMatchingLeafs(constituencyTree, patternAndRole.listOfPossiblePOSSequences);
        List<List<PatternMatch>> listOfPatternMatchesOnlyLeafs = getListOfPatternMatchesOnlyLeafs(listOfMatchingLeafs, patternAndRole.listOfPossiblePOSSequences_withTags);

        List<List<PatternMatch>> listOfPatternMatches = new ArrayList<>();
        listOfPatternMatches.addAll(listOfPatternMatchesWithoutLeafs);
        listOfPatternMatches.addAll(listOfPatternMatchesOnlyLeafs);

        return listOfPatternMatches;
    }

    private static POSNode getTreeStructure (String treeInStringformat) {
        return getTreeStructureOfPrettyPrintedTree(prettyPrintTreeWithIntends(treeInStringformat));
    }

    private static String prettyPrintTreeWithIntends (String treeInStringFormat) {

        int currentOpenBrackets = 0;

        StringBuilder output = new StringBuilder();
        String formattedTree = treeInStringFormat.replace("\\s+", " ");
        for (int i=0; i<formattedTree.length(); i++) {
            char c = formattedTree.charAt(i);
            if (c == '(') {
                currentOpenBrackets++;
                output.append("\n");
                for (int j=0; j< NUMBER_OF_SKIP_SPACES * currentOpenBrackets; j++) {
                    output.append(SKIP_CHARACTER);
                }
            } else if (c == ')') {
                currentOpenBrackets--;
            } else {
                output.append(c);
            }
        }

        return output.toString();
    }

    private static POSNode getTreeStructureOfPrettyPrintedTree(String treeWithIntends) {
        POSNode root = null;
        POSNode lastNode = null;

        String[] treeWithIntendsArray = treeWithIntends.split("\n");
        for (String line : treeWithIntendsArray) {
            if (line.trim().length() == 0) {
                continue;
            }

            int intends = 0;
            while (line.charAt(intends) == SKIP_CHARACTER) {
                intends++;
            }
            String[] restOfLine = line.substring(intends).split("\\s+");
            String posTag = restOfLine[0];
            String term = restOfLine.length == 2 ? restOfLine[1] : null;

            POSNode newPOSNode = new POSNode(posTag, term, intends);

            if (root == null) {
                root = newPOSNode;
                lastNode = root;
            } else {
                if (newPOSNode.getLayer() > lastNode.getLayer()) {
                    lastNode.children.add(newPOSNode);
                    newPOSNode.parent = lastNode;

                } else if (newPOSNode.getLayer() < lastNode.getLayer()) {
                    while (newPOSNode.getLayer() < lastNode.getLayer() && newPOSNode.getLayer() > root.getLayer()) {
                        lastNode = lastNode.parent;
                    }

                    lastNode.parent.children.add(newPOSNode);
                    newPOSNode.parent = lastNode.parent;

                } else if (newPOSNode.getLayer() == lastNode.getLayer()) {
                    lastNode.parent.children.add(newPOSNode);
                    newPOSNode.parent = lastNode.parent;
                }

                lastNode = newPOSNode;
            }
        }

        return root;
    }


    private List<List<POSNode>> findMatchingSubGraphsWithoutLeafs(POSNode root, List<List<String>> listOfPossiblePOSSequences, boolean enableDeepSearch) {
        List<List<POSNode>> listOfSubGraphsWithoutLeafs = new ArrayList<>();

        for (List<String> possiblePOSSequence : listOfPossiblePOSSequences) {
            List<POSNode> foundPOSSequence = new ArrayList<>();
            int positionInPOSSequence = 0;
            Map<POSNode, Boolean> map_posNode_visited = new HashMap<>();
            Map<POSNode,Boolean> map_unexploredSubGraph_alreadySeen = new HashMap<>();

            List<BooleanSubgraph> booleanSubGraphList = new ArrayList<>();
            findSubgraph(root, possiblePOSSequence, map_posNode_visited, foundPOSSequence, positionInPOSSequence, booleanSubGraphList, map_unexploredSubGraph_alreadySeen);
            for (BooleanSubgraph booleanSubGraph : booleanSubGraphList) {
                if (booleanSubGraph.subgraphFound) {
                    listOfSubGraphsWithoutLeafs.add(booleanSubGraph.foundPOSSequence);
                }
            }

            if (enableDeepSearch) {
                deepSearchToFindFurtherSubGraphsWithoutLeafs(listOfSubGraphsWithoutLeafs, possiblePOSSequence, booleanSubGraphList, map_unexploredSubGraph_alreadySeen);
            }
        }

        return listOfSubGraphsWithoutLeafs;
    }

    private List<List<POSNode>> findMatchingLeafs(POSNode constituencyTree, List<List<String>> listOfPossiblePOSSequences) {
        List<List<POSNode>> listOfSubGraphsOnlyLeafs = new ArrayList<>();

        List<POSNode> foundPOSSequence = new ArrayList<>();
        getPOSNodesAsStack(constituencyTree, foundPOSSequence);
        for (int i=foundPOSSequence.size()-1; i>=0; i--) {
            if (!foundPOSSequence.get(i).children.isEmpty()) {
                foundPOSSequence.remove(i);
            }
        }

        for (List<String> possiblePOSSequence : listOfPossiblePOSSequences) {
            for (int i=0; i<foundPOSSequence.size()-possiblePOSSequence.size()+1; i++) {
                boolean foundMatch = true;
                for (int j=0; j<possiblePOSSequence.size(); j++) {
                    String possiblePOSTag = possiblePOSSequence.get(j);
                    String requiredTerm = null;
                    if (possiblePOSTag.contains("/")) {
                        Matcher matcher = regexPOS_TAG_TERM.matcher(possiblePOSSequence.get(j));
                        if (matcher.find()) {
                            possiblePOSTag = matcher.group(1);
                            requiredTerm = matcher.group(2);
                        }
                    }

                    if (!(foundPOSSequence.get(i+j).getPOSTag().equals(possiblePOSTag) && (requiredTerm == null || foundPOSSequence.get(i+j).getTerm().equals(requiredTerm)))) {
                        foundMatch = false;
                        break;
                    }
                }

                if (foundMatch) {
                    BooleanSubgraph booleanSubgraph = new BooleanSubgraph(true, foundPOSSequence.subList(i, i+possiblePOSSequence.size()));
                    listOfSubGraphsOnlyLeafs.add(booleanSubgraph.foundPOSSequence);
                }
            }
        }

        return listOfSubGraphsOnlyLeafs;
    }

    private List<BooleanSubgraph> findSubgraph (
            POSNode root,
            List<String> possiblePOSSequence,
            Map<POSNode, Boolean> map_posNode_visited,
            List<POSNode> foundPOSSequence,
            int positionInPOSSequence,
            List<BooleanSubgraph> booleanSubGraphList,
            Map<POSNode,Boolean> map_unexploredSubGraph_alreadySeen) {

        if (positionInPOSSequence < possiblePOSSequence.size()) {
            POSNode node = getNextNotVisitedNode(root, map_posNode_visited);

            if (node == null) {
                return booleanSubGraphList;
            }

            String possiblePOSTag = possiblePOSSequence.get(positionInPOSSequence);
            String requiredTerm = null;
            Matcher matcher = regexPOS_TAG_TERM.matcher(possiblePOSTag);
            if (matcher.find()) {
                possiblePOSTag = matcher.group(1);
                requiredTerm = matcher.group(2);
            }

            map_posNode_visited.put(node, true);
            if (node.getPOSTag().equals(possiblePOSTag) && (requiredTerm == null || requiredTerm.equals(node.getTerm()))) {
                positionInPOSSequence++;
                foundPOSSequence.add(node);
                if (node.getLayer() > root.getLayer() && !node.children.isEmpty()) {
                    map_unexploredSubGraph_alreadySeen.put(node, false);
                }
                labelSubTreeVisited(node, map_posNode_visited);
            } else {
                positionInPOSSequence = 0;
            }

            return findSubgraph(root, possiblePOSSequence, map_posNode_visited, foundPOSSequence, positionInPOSSequence, booleanSubGraphList, map_unexploredSubGraph_alreadySeen);

        } else {

            if (foundPOSSequence.size() >= possiblePOSSequence.size()) {
                boolean matchFound = false;
                for (int i=0; i<foundPOSSequence.size()-possiblePOSSequence.size()+1; i++) {
                    matchFound = true;
                    for (int j=0; j<possiblePOSSequence.size(); j++) {
                        String foundPOSTag = foundPOSSequence.get(i+j).getPOSTag();
                        String requiredPOSTag = possiblePOSSequence.get(j);
                        if (requiredPOSTag.contains("/")) {
                            requiredPOSTag = requiredPOSTag.split("/")[0];
                        }
                        if (!foundPOSTag.equals(requiredPOSTag)) {
                            matchFound = false;
                            break;
                        }
                    }

                    if (matchFound && !foundPOSSequence.isEmpty()) {
                        foundPOSSequence = foundPOSSequence.subList(i, i+possiblePOSSequence.size());
                        booleanSubGraphList.add(new BooleanSubgraph(true, foundPOSSequence));
                    }
                }

                if (matchFound && !foundPOSSequence.isEmpty()) {
                    booleanSubGraphList.add(new BooleanSubgraph(true, foundPOSSequence));
                }
            } else {
                booleanSubGraphList.add(new BooleanSubgraph(true, foundPOSSequence));
            }
            return booleanSubGraphList;

        }
    }

    private POSNode getNextNotVisitedNode (POSNode root, Map<POSNode, Boolean> map_posNode_visited) {
        if (map_posNode_visited.get(root) == null) {
            return root;
        } else {
            List<POSNode> posNodeList = new ArrayList<>();
            getPOSNodesAsStack(root, posNodeList);
            for (POSNode child : posNodeList) {
                if (map_posNode_visited.get(child) == null) {
                    return child;
                }
            }
        }

        return null;
    }

    private void labelSubTreeVisited(POSNode node, Map<POSNode, Boolean> map_posNode_visited) {
        List<POSNode> posNodeList = new ArrayList<>();
        getPOSNodesAsStack(node, posNodeList);
        for (POSNode posNode : posNodeList) {
            map_posNode_visited.put(posNode, true);
        }
    }

    private void deepSearchToFindFurtherSubGraphsWithoutLeafs(
            List<List<POSNode>> listOfSubGraphsWithoutLeafs,
            List<String> possiblePOSSequence,
            List<BooleanSubgraph> booleanSubgraphList,
            Map<POSNode, Boolean> map_unexploredSubGraph_alreadySeen) {

        while(true) {
            boolean foundUnexploredSubGraph = false;
            for (var unexploredNode : map_unexploredSubGraph_alreadySeen.entrySet()) {
                if (!map_unexploredSubGraph_alreadySeen.get(unexploredNode.getKey())) {
                    map_unexploredSubGraph_alreadySeen.put(unexploredNode.getKey(), true);
                    foundUnexploredSubGraph = true;

                    Map<POSNode, Boolean> map_posNode_visited = new HashMap<>();
                    List<POSNode> foundPOSSequence = new ArrayList<>();
                    int positionInPOSSequence = 0;
                    findSubgraph(unexploredNode.getKey(), possiblePOSSequence, map_posNode_visited, foundPOSSequence, positionInPOSSequence, booleanSubgraphList, map_unexploredSubGraph_alreadySeen);
                    for (BooleanSubgraph booleanSubGraph : booleanSubgraphList) {
                        if (booleanSubGraph.subgraphFound) {
                            listOfSubGraphsWithoutLeafs.add(booleanSubGraph.foundPOSSequence);
                        }
                    }
                }
            }
            if (!foundUnexploredSubGraph) {
                break;
            }
        }
    }

    private List<List<PatternMatch>> getListOfPatternMatchesWithoutLeafs(List<List<POSNode>> listOfSubGraphsWithoutLeafs, String patternWithQueryAndQualia) {
        List<List<PatternMatch>> listOfPatternMatchingInformation = new ArrayList<>();

        for (List<POSNode> subGraphsWithoutLeafs : listOfSubGraphsWithoutLeafs) {
            List<PatternMatch> listOfPatternMatches = new ArrayList<>();

            StringBuilder posRequiredWithRolesSB = new StringBuilder();
            StringBuilder posRequired = new StringBuilder();
            StringBuilder posLeafs = new StringBuilder();
            StringBuilder terms = new StringBuilder();

            for (POSNode subGraphWithoutLeafs : subGraphsWithoutLeafs) {
                posRequiredWithRolesSB.append(subGraphWithoutLeafs.getPOSTag()).append(" ");
            }
            String posRequiredWithRoles = addQueryAndQualiaToFoundPattern(patternWithQueryAndQualia, String.valueOf(posRequiredWithRolesSB));
            StringBuilder posRequiredWithLeafsAndRoles = new StringBuilder();
            String[] posRequiredWithRolesArray = posRequiredWithRoles.split("\\s+");
            StringBuilder termLeafsWithQueryAndQualia = new StringBuilder();
            int i = 0;

            for (POSNode node : subGraphsWithoutLeafs) {
                if (i == posRequiredWithRolesArray.length) {
                    break;
                }

                posRequired.append(node.getPOSTag()).append(" ");

                // TODO: refactor duplicate
                boolean isQuery = false;
                Matcher matcherQuery = regexQuery.matcher(posRequiredWithRolesArray[i]);
                if (matcherQuery.find()) {
                    isQuery = true;
                    posRequiredWithRolesArray[i] = posRequiredWithRolesArray[i].replaceAll("<query>", "").replaceAll("</query>", "");
                }

                boolean isQualia = false;
                Matcher matcherQualia = regexQualia.matcher(posRequiredWithRolesArray[i]);
                if (matcherQualia.find()) {
                    isQualia = true;
                    posRequiredWithRolesArray[i] = posRequiredWithRolesArray[i].replaceAll("<qualia>", "").replaceAll("</qualia>", "");
                }

                if (node.getPOSTag().equals(posRequiredWithRolesArray[i])) {
                    if (isQuery) {
                        if (posRequiredWithLeafsAndRoles.length() > 0 && posRequiredWithLeafsAndRoles.charAt(posRequiredWithLeafsAndRoles.length() - 1) != ' ') {
                            posRequiredWithLeafsAndRoles.append(" ");
                            termLeafsWithQueryAndQualia.append(" ");
                        }
                        posRequiredWithLeafsAndRoles.append("<query>");
                        termLeafsWithQueryAndQualia.append("<query>");
                    } else if (isQualia) {
                        if (posRequiredWithLeafsAndRoles.length() > 0 && posRequiredWithLeafsAndRoles.charAt(posRequiredWithLeafsAndRoles.length() - 1) != ' ') {
                            posRequiredWithLeafsAndRoles.append(" ");
                            termLeafsWithQueryAndQualia.append(" ");
                        }
                        posRequiredWithLeafsAndRoles.append("<qualia>");
                        termLeafsWithQueryAndQualia.append("<qualia>");
                    }
                    i++;
                }

                List<POSNode> leafs = new ArrayList<>();
                leafs.add(node);
                getPOSNodesAsStack(node, leafs);
                for (POSNode leaf : leafs) {
                    if (leaf.getTerm() != null) {
                        posLeafs.append(leaf.getPOSTag()).append(" ");
                        terms.append(leaf.getTerm()).append(" ");

                        if (posRequiredWithLeafsAndRoles.length() > 0 && posRequiredWithLeafsAndRoles.charAt(posRequiredWithLeafsAndRoles.length() - 1) != ' ' && posRequiredWithLeafsAndRoles.charAt(posRequiredWithLeafsAndRoles.length() - 1) != '>') {
                            posRequiredWithLeafsAndRoles.append(" ");
                            termLeafsWithQueryAndQualia.append(" ");
                        }
                        posRequiredWithLeafsAndRoles.append(leaf.getPOSTag());
                        termLeafsWithQueryAndQualia.append(leaf.getTerm());
                    }
                }

                if (isQuery) {
                    posRequiredWithLeafsAndRoles.append("</query> ");
                    termLeafsWithQueryAndQualia.append("</query> ");
                } else if (isQualia) {
                    posRequiredWithLeafsAndRoles.append("</qualia> ");
                    termLeafsWithQueryAndQualia.append("</qualia> ");
                }
            }

            listOfPatternMatches.add(
                    new PatternMatch(
                            posRequiredWithRoles,
                            posRequired.toString(),
                            posRequiredWithLeafsAndRoles.toString(),
                            posLeafs.toString(),
                            termLeafsWithQueryAndQualia.toString(),
                            terms.toString()
                    ));

            listOfPatternMatchingInformation.add(listOfPatternMatches);
        }

        return listOfPatternMatchingInformation;
    }

    private List<List<PatternMatch>> getListOfPatternMatchesOnlyLeafs(List<List<POSNode>> listOfOnlyLeafsSequences, List<List<String>> listOfPossiblePOSSequences) {
        List<List<PatternMatch>> listOfListOfPatternMatches = new ArrayList<>();

        for (List<POSNode> onlyLeafsSequence : listOfOnlyLeafsSequences) {

            List<PatternMatch> listOfPatternMatches = new ArrayList<>();

            for (List<String> possiblePOSSequence : listOfPossiblePOSSequences) {

                StringBuilder posLeafs_withTags = new StringBuilder();
                StringBuilder posLeafs = new StringBuilder();
                StringBuilder terms_withTags = new StringBuilder();
                StringBuilder terms = new StringBuilder();

                for (POSNode node : onlyLeafsSequence) {
                    posLeafs.append(node.getPOSTag()).append(" ");
                    terms.append(node.getTerm()).append(" ");
                }

                String[] posLeafsArray = posLeafs.toString().split(" ");
                String[] termsArray = terms.toString().split(" ");
                if (posLeafsArray.length != possiblePOSSequence.size()) {
                    continue;
                }

                boolean match = true;
                String[] possiblePOSSequenceArray = possiblePOSSequence.toArray(new String[0]);
                for (int i=0; i<possiblePOSSequenceArray.length; i++) {
                    boolean isQuery = false;
                    boolean isQualia = false;

                    String token = possiblePOSSequenceArray[i];

                    if (token.contains("<query>")) {
                        isQuery = true;
                        token = token.replace("<query>", "").replace("</query>", "");
                    } else if (token.contains("<qualia>")) {
                        isQualia = true;
                        token = token.replace("<qualia>", "").replace("</qualia>", "");
                    }

                    Matcher matcher = regexPOS_TAG_TERM.matcher(token);
                    if (matcher.find()) {
                        token = matcher.group(1);
                        if (!termsArray[i].equals(matcher.group(2))) {
                            match = false;
                            break;
                        }
                    }

                    if (!posLeafsArray[i].equals(token)) {
                        match = false;
                        break;
                    }

                    if (posLeafs_withTags.length() > 0) {
                        posLeafs_withTags.append(" ");
                        terms_withTags.append(" ");
                    }

                    if (isQuery) {
                        posLeafs_withTags.append("<query>").append(posLeafsArray[i]).append("</query>");
                        terms_withTags.append("<query>").append(termsArray[i]).append("</query>");
                    } else if (isQualia) {
                        posLeafs_withTags.append("<qualia>").append(posLeafsArray[i]).append("</qualia>");
                        terms_withTags.append("<qualia>").append(termsArray[i]).append("</qualia>");
                    } else {
                        posLeafs_withTags.append(posLeafsArray[i]);
                        terms_withTags.append(termsArray[i]);
                    }
                }

                if (match) {
                    listOfPatternMatches.add(
                            new PatternMatch(
                                    posLeafs_withTags.toString(),
                                    posLeafs.toString(),
                                    posLeafs_withTags.toString(),
                                    posLeafs.toString(),
                                    terms_withTags.toString(),
                                    terms.toString()
                            ));
                    listOfListOfPatternMatches.add(listOfPatternMatches);
                }

            }
        }

        return listOfListOfPatternMatches;
    }

    private void getPOSNodesAsStack(POSNode node, List<POSNode> posNodeList) {
        for (POSNode child : node.children) {
            posNodeList.add(child);
            getPOSNodesAsStack(child, posNodeList);
        }
    }

}
