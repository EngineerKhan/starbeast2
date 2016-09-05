package starbeast2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import beast.core.Description;
import beast.core.Input;
import beast.core.Input.Validate;
import beast.core.util.Log;
import beast.evolution.alignment.Taxon;
import beast.evolution.alignment.TaxonSet;
import beast.evolution.operators.TreeOperator;
import beast.evolution.tree.Node;
import beast.evolution.tree.Tree;
import beast.util.Randomizer;

@Description("Tree operator which randomly changes the height of a node, " +
        "then reconstructs the tree from node heights.")
public class NodeReheight2 extends TreeOperator {
    public final Input<TaxonSet> taxonSetInput = new Input<>("taxonset", "taxon set describing species tree taxa and their gene trees", Validate.REQUIRED);
    public final Input<List<GeneTree>> geneTreesInput = new Input<>("genetree", "list of gene trees that constrain species tree movement", new ArrayList<>());
    Node[] m_nodes;

    /**
     * map node number of leafs in gene trees to leaf nr in species tree *
     */
    //List<Map<Integer, Integer>> m_taxonMap;
    int[][] m_taxonMap;
    int nrOfGeneTrees;
    int nrOfSpecies;

    @Override
    public void initAndValidate() {
        /** maps gene taxa names to species number **/
        final Map<String, Integer> taxonMap = new HashMap<>();
        final List<Taxon> list = taxonSetInput.get().taxonsetInput.get();

        if (list.size() <= 1) {
        	Log.warning.println("NodeReheight operator requires at least 2 taxa while the taxon set (id=" + taxonSetInput.get().getID() +") has only " + list.size() + " taxa. "
        			+ "If the XML file was set up in BEAUti, this probably means a taxon assignment needs to be set up in the taxonset panel.");
        	// assume we are in BEAUti, back off for now
        	return;
        }

        for (int i = 0; i < list.size(); i++) {
            final Taxon taxa = list.get(i);
            // cast should be ok if taxon-set is the set for the species tree
            final TaxonSet set = (TaxonSet) taxa;
            for (final Taxon taxon : set.taxonsetInput.get()) {
                taxonMap.put(taxon.getID(), i);
            }
        }

        /** build the taxon map for each gene tree **/
        m_taxonMap = new int[geneTreesInput.get().size()][];
        int i = 0;
        for (final GeneTree tree : geneTreesInput.get()) {
        	m_taxonMap[i++] = tree.getTipNumberMap();
        }

        nrOfGeneTrees = geneTreesInput.get().size();
        nrOfSpecies = treeInput.get().getLeafNodeCount();
    }

    @Override
    public double proposal() {
        final Tree tree = treeInput.get();
        m_nodes = tree.getNodesAsArray();
        final int nodeCount = tree.getNodeCount();
        // randomly change left/right order
        tree.startEditing(this);  // we change the tree
        reorder(tree.getRoot());
        // collect heights
        final double[] heights = new double[nodeCount];
        final int[] reverseOrder = new int[nodeCount];
        collectHeights(tree.getRoot(), heights, reverseOrder, 0);
        // change height of an internal node
        int nodeIndex = Randomizer.nextInt(heights.length);
        while (m_nodes[reverseOrder[nodeIndex]].isLeaf()) {
            nodeIndex = Randomizer.nextInt(heights.length);
        }
        final double maxHeight = calcMaxHeight(reverseOrder, nodeIndex);
        final double minHeight = calcMinHeight(m_nodes[reverseOrder[nodeIndex]]);
        heights[nodeIndex] = minHeight + Randomizer.nextDouble() * (maxHeight - minHeight);
        m_nodes[reverseOrder[nodeIndex]].setHeight(heights[nodeIndex]);
        // reconstruct tree from heights
        final Node root = reconstructTree(heights, reverseOrder, 0, heights.length, new boolean[heights.length]);

        assert checkConsistency(root, new boolean[heights.length]) ;

        root.setParent(null);
        tree.setRoot(root);
        return 0;
    }

    private double calcMinHeight(Node node) {
    	if (node.isLeaf()) {
    		return node.getHeight();
    	} else {
    		double maxLeft = calcMinHeight(node.getLeft());
    		double maxRight = calcMinHeight(node.getRight());
    		return Math.max(maxLeft, maxRight);    		
    	}
	}

	private boolean checkConsistency(final Node node, final boolean[] used) {
        if (used[node.getNr()]) {
            // used twice? tha's bad
            return false;
        }
        used[node.getNr()] = true;
        if ( node.isLeaf() ) {
            return true;
        }
        return checkConsistency(node.getLeft(), used) && checkConsistency(node.getRight(), used);
    }

    /**
     * calculate maximum height that node nodeIndex can become restricted
     * by nodes on the left and right
     */
    private double calcMaxHeight(final int[] reverseOrder, final int nodeIndex) {
        // find maximum height between two species. Only upper right part is populated
        final double[][] maxHeight = new double[nrOfSpecies][nrOfSpecies];
        for (int i = 0; i < nrOfSpecies; i++) {
            Arrays.fill(maxHeight[i], Double.POSITIVE_INFINITY);
        }

        // find species on the left of selected node
        final boolean[] isLowerSpecies = new boolean[nrOfSpecies];
        final Node[] nodes = treeInput.get().getNodesAsArray();
        for (int i = 0; i < nodeIndex; i++) {
            final Node node = nodes[reverseOrder[i]];
            if (node.isLeaf()) {
                isLowerSpecies[node.getNr()] = true;
            }
        }
        // find species on the right of selected node
        final boolean[] isUpperSpecies = new boolean[nrOfSpecies];
        for (int i = nodeIndex + 1; i < nodes.length; i++) {
            final Node node = nodes[reverseOrder[i]];
            if (node.isLeaf()) {
                isUpperSpecies[node.getNr()] = true;
            }
        }

        final boolean[] isUsedSpecies = new boolean[nrOfSpecies];
        for (int i = 0; i < nrOfSpecies; i++) {
        	isUpperSpecies[i] = isLowerSpecies[i] || isUpperSpecies[i];
        }

        // calculate for every species tree the maximum allowable merge point
        for (int i = 0; i < nrOfGeneTrees; i++) {
            final GeneTree tree = geneTreesInput.get().get(i);
            findMaximaInGeneTree(tree.getRoot(), new boolean[nrOfSpecies], m_taxonMap[i], maxHeight, isUsedSpecies);
        }

        // find max
        double max = Double.POSITIVE_INFINITY;
        for (int i = 0; i < nrOfSpecies; i++) {
            if (isLowerSpecies[i]) {
                for (int j = 0; j < nrOfSpecies; j++) {
                    if (j != i && isUpperSpecies[j]) {
                        final int x = Math.min(i, j);
                        final int y = Math.max(i, j);
                        max = Math.min(max, maxHeight[x][y]);
                    }
                }
            }
        }
        return max;
    }


    /**
     * for every species in the left on the gene tree and for every species in the right
     * cap the maximum join height by the lowest place the two join in the gene tree
     */
    private void findMaximaInGeneTree(final Node nodeX, final boolean[] taxonSet, final int [] taxonMap, final double[][] maxHeight, boolean[] isUsedSpecies) {
    	Tree tree = nodeX.getTree();
    	int nrOfNodes = tree.getNodeCount();
    	int [][] speciesList = new int[nrOfNodes][nrOfSpecies];
    	int [] speciesCount = new int[nrOfNodes];
    	for (Node node : tree.listNodesPostOrder(null, null)) { 	
	    	if (node.isLeaf()) {
	    		int nodeNr = node.getNr();
	            final int species = taxonMap[nodeNr];
	    		//if (isUsedSpecies[species]) {
		            speciesList[nodeNr][0] = species;
		            speciesCount[nodeNr] = 1;
		    	//}
	        } else {
	            int left = node.getLeft().getNr();
	            int right = node.getRight().getNr();
	            for (int i = 0; i < speciesCount[left]; i++) {
		            for (int j = 0; j < speciesCount[right]; j++) {
		            	if (speciesList[i] != speciesList[j]) {
		            		int sp1 = speciesCount[i];
		            		int sp2 = speciesCount[j];
	                        final int x;
                            final int y;
		            		if (sp1 < sp2) {
		            			x = sp1; y = sp2;
		            		} else {
		            			x = sp2; y = sp1;
		            		}
                            maxHeight[x][y] = Math.min(maxHeight[x][y], node.getHeight());
	                    }
	                }
	            }
	            int i = 0;
	            int j = 0;
	            int k = 0;
	            int nodeNr = node.getNr();
	            int [] spList = speciesList[nodeNr];
	            int [] leftList = speciesList[left];
	            int [] rightList = speciesList[right];
	            while (i < speciesCount[left] && j < speciesCount[right]) {
	            	if (leftList[i] == rightList[j]) {
	            		spList[k++] = leftList[i];
	            		i++;
	            		j++;
	            	} else if (leftList[i] < rightList[j]) {
	            		spList[k++] = leftList[i++];
	            	} else {
	            		spList[k++] = rightList[j++];
	            	}
	            }
            	if (i == speciesCount[left]) {
            		while (j < speciesCount[right]) {
	            		spList[k++] = rightList[j++];
            		}
            	} else if (j == speciesCount[right]) {
            		while (i < speciesCount[left]) {
	            		spList[k++] = leftList[i++];
            		}
            	}
	            speciesCount[node.getNr()] = k;
	        }
    	}
    }

    /**
     * construct tree top down by joining heighest left and right nodes *
     */
    private Node reconstructTree(final double[] heights, final int[] reverseOrder, final int from, final int to, final boolean[] hasParent) {
        //nodeIndex = maxIndex(heights, 0, heights.length);
        int nodeIndex = -1;
        double max = Double.NEGATIVE_INFINITY;
        for (int j = from; j < to; j++) {
            if (max < heights[j] && !m_nodes[reverseOrder[j]].isLeaf()) {
                max = heights[j];
                nodeIndex = j;
            }
        }
        if (nodeIndex < 0) {
            return null;
        }
        final Node node = m_nodes[reverseOrder[nodeIndex]];

        //int left = maxIndex(heights, 0, nodeIndex);
        int left = -1;
        max = Double.NEGATIVE_INFINITY;
        for (int j = from; j < nodeIndex; j++) {
            if (max < heights[j] && !hasParent[j]) {
                max = heights[j];
                left = j;
            }
        }

        //int right = maxIndex(heights, nodeIndex+1, heights.length);
        int right = -1;
        max = Double.NEGATIVE_INFINITY;
        for (int j = nodeIndex + 1; j < to; j++) {
            if (max < heights[j] && !hasParent[j]) {
                max = heights[j];
                right = j;
            }
        }

        node.setLeft(m_nodes[reverseOrder[left]]);
        node.getLeft().setParent(node);
        node.setRight(m_nodes[reverseOrder[right]]);
        node.getRight().setParent(node);
        if (node.getLeft().isLeaf()) {
            heights[left] = Double.NEGATIVE_INFINITY;
        }
        if (node.getRight().isLeaf()) {
            heights[right] = Double.NEGATIVE_INFINITY;
        }
        hasParent[left] = true;
        hasParent[right] = true;
        heights[nodeIndex] = Double.NEGATIVE_INFINITY;


        reconstructTree(heights, reverseOrder, from, nodeIndex, hasParent);
        reconstructTree(heights, reverseOrder, nodeIndex, to, hasParent);
        return node;
    }

   /**
      ** gather height of each node, and the node index associated with the height.*
      **/
    private int collectHeights(final Node node, final double[] heights, final int[] reverseOrder, int current) {
        if (node.isLeaf()) {
            heights[current] = node.getHeight();
            reverseOrder[current] = node.getNr();
            current++;
        } else {
            current = collectHeights(node.getLeft(), heights, reverseOrder, current);
            heights[current] = node.getHeight();
            reverseOrder[current] = node.getNr();
            current++;
            current = collectHeights(node.getRight(), heights, reverseOrder, current);
        }
        return current;
    }

    /**
     * randomly changes left and right children in every internal node *
     */
    private void reorder(final Node node) {
        if (!node.isLeaf()) {
            if (Randomizer.nextBoolean()) {
                final Node tmp = node.getLeft();
                node.setLeft(node.getRight());
                node.setRight(tmp);
            }
            reorder(node.getLeft());
            reorder(node.getRight());
        }
    }
}
