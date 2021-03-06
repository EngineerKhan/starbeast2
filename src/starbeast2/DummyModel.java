package starbeast2;


import java.text.DecimalFormat;

import beast.core.CalculationNode;
import beast.evolution.tree.Node;

/**
* @author Huw Ogilvie
 */

// Used to help BEAUTi with Analytical population sizes
public class DummyModel extends CalculationNode implements PopulationModel {
    @Override
    public void initAndValidate() {}

    @Override
    public double branchLogP(int speciesTreeNodeNumber, Node speciesTreeNode, double ploidy, double[] branchCoalescentTimes, int branchLineageCount, int branchEventCount) {
        return 0.0;
    }

    @Override
    public void initPopSizes(double initialPopSizes) {}

    @Override
    public void serialize(Node speciesTreeNode, StringBuffer buf, DecimalFormat df) {}

    @Override
    public boolean isDirtyBranch(Node speciesTreeNode) {
        return false;
    }

    @Override
    public PopulationModel getBaseModel() {
        return this;
    }
}
