/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.astar.kadee;

import edu.duke.cs.osprey.astar.AStarNode;
import edu.duke.cs.osprey.astar.AStarTree;
import edu.duke.cs.osprey.astar.ConfTree;
import edu.duke.cs.osprey.astar.comets.LME;
import edu.duke.cs.osprey.astar.comets.UpdatedPruningMatrix;
import edu.duke.cs.osprey.confspace.RCTuple;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang.ArrayUtils;

/**
 * This class behaves similarly to COMETSTree and COMETSTreeSuper It optimizes
 * for the sequences with the best K* score
 *
 * @author hmn5
 */
public class KaDEETree extends AStarTree {

    int numTreeLevels; //number of mutable residues

    LME objFcn; //objective function to minimize
    LME[] constraints; //constraints on our sequence

    ArrayList<ArrayList<String>> AATypeOptions; //The allowed amino-acids at each level

    int numMaxMut; //number of mutatations allowed away from wtSeq (-1 means no cap)
    String wtSeq[]; //wt sequence
    //information on states
    int numStates;//how many states there are
    //they have to have the same mutable residues & AA options,
    //though the residues involved may be otherwise different

    SearchProblem[] mutableSearchProblems;//SearchProblems involved in COMETS search

    public SearchProblem nonMutableSearchProblem;

    ArrayList<ArrayList<Integer>> mutable2StatePosNums;
    //mutable2StatePosNum.get(state) maps levels in this tree to flexible positions for state
    //(not necessarily an onto mapping)

    int stateNumPos[];

    int numSeqsReturned = 0;
    int stateGMECsForPruning = 0;//how many state GMECs have been calculated for nodes that are pruned    

    //Maps the bound res num to the corresponding unbound emat
    HashMap<Integer, EnergyMatrix> boundResNumToUnboundEmat;
    //Maps the bound res num to the corresponding unbound res num
    HashMap<Integer, Integer> boundPosNumToUnboundPosNum;
    //Maps the bound res num to boolean that is true if res num is part of mutable
    //strand
    HashMap<Integer, Boolean> boundResNumToIsMutableStrand;
    //determines if two residues are on the same strand
    boolean[][] belongToSameStrand;

    public KaDEETree(int numTreeLevels, LME objFcn, LME[] constraints,
            ArrayList<ArrayList<String>> AATypeOptions, int numMaxMut, String[] wtSeq,
            int numStates, SearchProblem[] stateSP, SearchProblem nonMutableSearchProblem,
            ArrayList<ArrayList<Integer>> mutable2StatePosNums) {

        this.numTreeLevels = numTreeLevels;
        this.objFcn = objFcn;
        this.constraints = constraints;
        this.AATypeOptions = AATypeOptions;
        this.numMaxMut = numMaxMut;
        this.wtSeq = wtSeq;
        this.numStates = numStates;
        this.mutableSearchProblems = stateSP;
        this.nonMutableSearchProblem = nonMutableSearchProblem;
        this.mutable2StatePosNums = mutable2StatePosNums;

        stateNumPos = new int[numStates];
        for (int state = 0; state < numStates; state++) {
            stateNumPos[state] = stateSP[state].confSpace.numPos;
        }

        this.boundResNumToUnboundEmat = getBoundPosNumToUnboundEmat();
        this.boundPosNumToUnboundPosNum = getBoundPosNumToUnboundPosNum();
        this.boundResNumToIsMutableStrand = getBoundPosNumberToIsMutableStrand();
        this.belongToSameStrand = getSameStrandMatrix();
    }

    @Override
    public ArrayList<AStarNode> getChildren(AStarNode curNode) {
        KaDEENode seqNode = (KaDEENode) curNode;
        ArrayList<AStarNode> ans = new ArrayList<>();

        if (seqNode.isFullyDefined()) {
            ans.add(seqNode);
            return ans;
        } else {
            //expand next position...
            int[] curAssignments = seqNode.getNodeAssignments();

            for (int splitPos = 0; splitPos < numTreeLevels; splitPos++) {
                if (curAssignments[splitPos] < 0) {//we can split this level

                    for (int aa = 0; aa < AATypeOptions.get(splitPos).size(); aa++) {

                        int[] childAssignments = curAssignments.clone();
                        childAssignments[splitPos] = aa;

                        UpdatedPruningMatrix[] childPruneMat = new UpdatedPruningMatrix[numStates];
                        for (int state = 0; state < numStates; state++) {
                            childPruneMat[state] = doChildPruning(state, seqNode.pruneMat[state], splitPos, aa);
                        }

                        KaDEENode childNode = new KaDEENode(childAssignments, childPruneMat);

                        if (splitPos == numTreeLevels - 1) {//sequence is fully defined...make conf trees
                            makeSeqConfTrees(childNode);
                        }

                        //TODO: create boundFreeEnergyChange()
                        //childNode.setScore(boundFreeEnergyChange(childNode));
                        ans.add(childNode);
                    }

                    return ans;
                }
            }

            throw new RuntimeException("ERROR: Not splittable position found but sequence not fully defined...");
        }
    }

    /**
     * calcLBPartialSeqImproved: Computes a lower bound on a multi state energy
     * of a partial sequence assignments for a PROTEIN:LIGAND interaction. Our
     * bound consists of (in BOLTZMANN weighted terms):
     * MAX(P,LA,P:LA)/(MAX(P)*MAX(LA)) *
     * MAX_S((MAX(P:LU_s)*MAX(LA:LU_s))/(MIN(LA:LU_s))) where P is the target
     * protein whose sequence is known, LA are the ligand assigned residues
     * whose sequence has been defined in seqNode, and LU_s are the ligand
     * unassigned residues In ENERGIES, our bound is: GMinEC(P,LA,P:LA) -
     * GMinEC(P) - GMinEC(LA) + MIN_S(GMinEC(P:LU_s) + GMinEC(LA:LU_s) -
     * GMaxEC(LA:LU_s)) where GMinEC is the energy of the global minimum energy
     * conformation and GMaxEC is the energy of the global maximum energy
     * conformation
     *
     * @param seqNode
     * @param boundResNumToUnboundEmat
     * @param boundResNumToUnboundResNum
     * @param boundresNumToIsMutableStrand
     * @param belongToSameStrand
     * @return
     */
    private double calcLBPartialSeqImproved(KaDEENode seqNode) {
        SearchProblem boundSP = mutableSearchProblems[0];
        SearchProblem ligandSP = mutableSearchProblems[1];
        SearchProblem proteinSP = nonMutableSearchProblem;

        /*Get the posNums corresponding to protein in bound state, ligand assigned
         in bound state, ligand unassigned in bound state to help make partial search
         spaces
         */
        ArrayList<Integer> proteinBoundPosNums = getProteinPosNums(true);
        ArrayList<Integer> ligandAssignedBoundPosNums = getLigandAssignedPosNums(seqNode, true);
        ArrayList<Integer> ligandUnassignedBoundPosNums = getLigandUnassignedPosNums(seqNode, true);
        ArrayList<Integer> ligandAssignedUnboundPosNums = getLigandUnassignedPosNums(seqNode, false);
        ArrayList<Integer> ligandUnassignedUnboundPosNums = getLigandUnassignedPosNums(seqNode, false);
        // First compute GMinEC(P,LA,P:LA). Here an upper bound can be used, but ideally it should be computed exactly
        double gminec_p_la_pla = 0;
        //This involves a bound state
        //get subset of positions corresponding to Protein, and Ligand Assigned
        ArrayList<Integer> subsetPos_p_la_pla = new ArrayList<>();
        subsetPos_p_la_pla.addAll(proteinBoundPosNums);
        subsetPos_p_la_pla.addAll(ligandAssignedBoundPosNums);
        Collections.sort(subsetPos_p_la_pla);
        SearchProblem searchSpace_p_la_pla = boundSP.getPartialSearchProblem(subsetPos_p_la_pla, seqNode.pruneMat[0]);

        gminec_p_la_pla = getMAP(searchSpace_p_la_pla);

        // GMinEC(P) can be precomputed because it is a constant for the system or computed here. 
        double gminec_p = objFcn.getConstTerm();

        // Now compute GMinEC(LA). This has to be computed exactly (or through an upper bound)
        double gminec_la = 0;
        //This involves an unbound state
        ArrayList<Integer> subsetPos_la = new ArrayList<>();
        subsetPos_la.addAll(ligandAssignedUnboundPosNums);
        SearchProblem searchSpace_la = ligandSP.getPartialSearchProblem(subsetPos_la, seqNode.pruneMat[1]);
        gminec_la = getMAP(searchSpace_la);
        
        ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
        // Finally, compute MIN_S(GMinEC(P:LU_s) + GMinEC(LA:LU_s) - GMaxEC(LA:LU_s)).        
        // The following section should be "modular" because there are two ways to do this. One way is using a greedy algorithm to compute it
        // exactly. We have not developed it yet. For now let's compute it t as follows :
        //   MIN_S(GMinEC(P:LU_s) + GMinEC(LA:LU_s) - GMaxEC(LA:LU_s)) <= MIN_S(GMinEC(P:LU_s) + GMinEC(LA:LU_s)) - MAX_S(GMaxEC(LA:LU_s)
        // Thus, first compute: MIN_S(GMinEC(P:LU_s) + GMinEC(LA:LU_s)), which can be easily computed by computing a min gmec over: 
        //		GMinEC(LA:LU_s, P:LU_s), including all rotamers for all amino acids defined in s.
        double gminec_lalus_plus = 0;
        //This involves a bound state
        ArrayList<Integer> allPos = new ArrayList<>();
        allPos.addAll(ligandAssignedBoundPosNums);
        allPos.addAll(ligandUnassignedBoundPosNums);
        allPos.addAll(proteinBoundPosNums);
        Collections.sort(allPos);
        boolean[][] interactionGraph_plus_bound = createInteractionGraph(allPos, proteinBoundPosNums, ligandUnassignedBoundPosNums);
        boolean[][] interactionGraph_lalus_bound = createInteractionGraph(allPos, ligandAssignedBoundPosNums, ligandUnassignedBoundPosNums);
        boolean[][] interactionGraph = addInteractionGraphs(interactionGraph_plus_bound, interactionGraph_lalus_bound);
        SearchProblem searchSpace_lalus_plus = boundSP.getPartialSearchProblem(allPos, seqNode.pruneMat[0]);
        searchSpace_lalus_plus.updateMatrixCrossTerm(interactionGraph);
        searchSpace_lalus_plus.substractUnboundInternalEnergies(ligandSP, ligandUnassignedBoundPosNums , boundPosNumToUnboundPosNum);
        gminec_lalus_plus = getMAP(searchSpace_lalus_plus);

        // Then compute the maximum MAX_S(GMaxEC(LA:LU_s), which can be computed by either negating all the energies in the matrix or something similar.
        double gmaxec_lalus = 0;
        //This involves an unbound state
        ArrayList<Integer> subsetPos_lalus = new ArrayList<Integer>();
        subsetPos_lalus.addAll(ligandAssignedUnboundPosNums);
        subsetPos_lalus.addAll(ligandUnassignedUnboundPosNums);
        Collections.sort(subsetPos_lalus);
        boolean[][] interactionGraph_lalus_unbound = createInteractionGraph(subsetPos_lalus, ligandAssignedUnboundPosNums, ligandUnassignedBoundPosNums);
        SearchProblem searchSpace_lalus = ligandSP.getPartialSearchProblem(subsetPos_lalus, seqNode.pruneMat[1]);
        searchSpace_lalus.updateMatrixCrossTerm(interactionGraph_lalus_unbound);
        searchSpace_lalus.negateEnergies();
        gmaxec_lalus = -getMAP(searchSpace_lalus);
        

        return gminec_p_la_pla - gminec_p - gminec_la + gminec_lalus_plus - gmaxec_lalus;

    }

    /**
     * Prunes rotamers to reflex the new allowed amino-acids
     *
     * @param state bound vs unbound state
     * @param parentMat parent pruning matrix
     * @param splitPos position that was split
     * @param aa amino acid label for new positions that was split
     * @return updated pruning matrix
     */
    private UpdatedPruningMatrix doChildPruning(int state, PruningMatrix parentMat, int splitPos, int aa) {
        //Create an update to parentMat (without changing parentMat)
        //to reflect that splitPos has been assigned an amino-acid type

        String assignedAAType = AATypeOptions.get(splitPos).get(aa);

        UpdatedPruningMatrix ans = new UpdatedPruningMatrix(parentMat);
        int posAtState = mutable2StatePosNums.get(state).get(splitPos);

        //first, prune all other AA types at splitPos
        for (int rc : parentMat.unprunedRCsAtPos(posAtState)) {
            //HUNTER: TODO: AATYperPerRes should only be one residue for now
            //We should change this to allow for rcs at the sequence search level
            String rcAAType = mutableSearchProblems[state].confSpace.posFlex.get(posAtState).RCs.get(rc).AAType;

            if (!rcAAType.equalsIgnoreCase(assignedAAType)) {
                ans.markAsPruned(new RCTuple(posAtState, rc));
            }
        }
        return ans;
    }

    private void makeSeqConfTrees(KaDEENode node) {
        //Given a node with a fully defined sequence, build its conformational search trees
        //for each state
        //If a state has no viable conformations, leave it null, with stateUB[state] = inf

        node.stateTrees = new ConfTree[numStates];

        for (int state = 0; state < numStates; state++) {

            //first make sure there are RCs available at each position
            boolean RCsAvailable = true;
            for (int pos = 0; pos < stateNumPos[state]; pos++) {
                if (node.pruneMat[state].unprunedRCsAtPos(pos).isEmpty()) {
                    RCsAvailable = false;
                    break;
                }
            }

            if (RCsAvailable) {
                node.stateTrees[state] = new ConfTree(mutableSearchProblems[state], node.pruneMat[state], false);

                AStarNode rootNode = node.stateTrees[state].rootNode();

                int blankConf[] = new int[stateNumPos[state]];//set up root node UB
                Arrays.fill(blankConf, -1);
                rootNode.UBConf = blankConf;

                node.stateTrees[state].initQueue(rootNode);//allocate queue and add root node
            } else {//no confs available for this state!
                node.stateTrees[state] = null;
            }
        }
    }

    @Override
    public boolean isFullyAssigned(AStarNode node) {
        //HMN: TODO: We need to decide when a K* score is fully calculated

        if (!node.isFullyDefined()) {
            return false;
        }
        return true;
    }

    @Override
    public AStarNode rootNode() {
        int[] conf = new int[numTreeLevels];
        Arrays.fill(conf, -1);//indicates the sequence is not assigned

        PruningMatrix[] pruneMats = new PruningMatrix[numStates];
        for (int state = 0; state < numStates; state++) {
            pruneMats[state] = mutableSearchProblems[state].pruneMat;
        }

        KaDEENode root = new KaDEENode(conf, pruneMats);
        //TODO: root.setScore(boundLME(root,objFcn));
        return root;
    }

    @Override
    public boolean canPruneNode(AStarNode node) {
        //Check if node can be pruned
        //This is traditionally based on constraints, thought we could pruned nodes
        //that are provably suboptimal

        //TODO: Implement constraints as in COMETS if desired
        KaDEENode seqNode = (KaDEENode) node;

        if (numMaxMut != -1) {
            //cap on number of mutations
            int mutCount = 0;
            int assignments[] = seqNode.getNodeAssignments();

            for (int level = 0; level < numTreeLevels; level++) {
                if (assignments[level] >= 0) {//AA type at level is assigned
                    if (!AATypeOptions.get(level).get(assignments[level]).equalsIgnoreCase(wtSeq[level]))//and is different from wtSeq
                    {
                        mutCount++;
                    }
                }
            }

            if (mutCount > numMaxMut)//prune based on number of mutations
            {
                return true;
            }
        }
        //TODO: for (LME constr : constraints) {....

        return false;
    }

    public void UpdateSubsetPruningMatrix(PruningMatrix partSearchSpacePruneMat, UpdatedPruningMatrix seqNodePruneMat, ArrayList<Integer> subsetPos) {
        //Make sure subsetPos is in order
        Collections.sort(subsetPos);

        //Iterate over each position in partial search space
        for (int i = 0; i < partSearchSpacePruneMat.oneBody.size(); i++) {
            //get the original pos num in full search space
            int originalPosNum = subsetPos.get(i);
            for (int rc = 0; rc < partSearchSpacePruneMat.oneBody.get(i).size(); rc++) {
                boolean isPruned = seqNodePruneMat.getOneBody(originalPosNum, rc);
                partSearchSpacePruneMat.setOneBody(i, rc, isPruned);
            }

            for (int j = 0; j < i; j++) {
                int originalPosNumJ = subsetPos.get(j);
                for (int rcI = 0; rcI < partSearchSpacePruneMat.oneBody.get(i).size(); rcI++) {
                    for (int rcJ = 0; rcJ < partSearchSpacePruneMat.oneBody.get(j).size(); rcJ++) {
                        boolean isPruned = seqNodePruneMat.getPairwise(originalPosNum, rcI, originalPosNumJ, rcJ);
                        partSearchSpacePruneMat.setPairwise(i, rcI, j, rcJ, isPruned);
                    }
                }
            }
        }
    }

    /**
     * Creates an interaction graph over the set of residues defined by
     * allPositions Every element in subsetI is interacting with every element
     * in subsetJ interactions = {(i,j) | i in subsetI and j in subsetJ}
     *
     * @param allPositions all the positions that we are considering sorted by
     * original posNumber
     * @param subsetI list of positions interacting with subsetJ
     * @param subsetJ list of positions interacting with subsetI
     * @return interaction graph interactions = {(i,j) | i in subsetI and j in
     * subsetJ}
     */
    private boolean[][] createInteractionGraph(ArrayList<Integer> allPositions, ArrayList<Integer> subsetI, ArrayList<Integer> subsetJ) {
        int numPos = allPositions.size();
        boolean[][] interactionGraph = new boolean[numPos][numPos];

        //Initialize interactoin graph
        for (int posI = 0; posI < numPos; posI++) {
            for (int posJ = 0; posJ < numPos; posJ++) {
                interactionGraph[posI][posJ] = false;
            }
        }

        for (int posI : subsetI) {
            int newPosNum_I = allPositions.indexOf(posI);
            for (int posJ : subsetJ) {
                int newPosNum_J = allPositions.indexOf(posJ);
                interactionGraph[newPosNum_I][newPosNum_J] = true;
                interactionGraph[newPosNum_J][newPosNum_I] = true;
            }
        }
        return interactionGraph;
    }

    /**
     * Given two interaction graphs over the same graph we "add" them By this I
     * mean, (i,j) is interacting if (i,j) is interacting in either graphI or
     * graphJ
     *
     * @param interactionGraphI
     * @param interactionGraphJ
     * @return
     */
    private boolean[][] addInteractionGraphs(boolean[][] interactionGraphI, boolean[][] interactionGraphJ) {
        //Check to make sure each graph is the same size
        if (interactionGraphI.length != interactionGraphJ.length) {
            throw new RuntimeException("ERROR: Cannot add two interaction graphs of different size");
        }

        int numPos = interactionGraphI.length;
        boolean[][] interactionGraph = new boolean[numPos][numPos];
        for (int i = 0; i < numPos; i++) {
            for (int j = i + 1; j < numPos; j++) {
                interactionGraph[i][j] = (interactionGraphI[i][j] || interactionGraphJ[i][j]);
                interactionGraph[j][i] = interactionGraph[i][j];
            }
        }
        return interactionGraph;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * corresponding unbound energy matrix
     *
     * @return
     */
    public HashMap<Integer, EnergyMatrix> getBoundPosNumToUnboundEmat() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding energy matrix
        List<EnergyMatrix> unboundEmatPerPos = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? unBoundMutableState.emat : unBoundNonMutableState.emat)
                .collect(Collectors.toCollection(ArrayList::new));
        HashMap<Integer, EnergyMatrix> boundPosNumToUnboundEmat = new HashMap<>();
        for (int posNum = 0; posNum < unboundEmatPerPos.size(); posNum++) {
            boundPosNumToUnboundEmat.put(posNum, unboundEmatPerPos.get(posNum));
        }
        return boundPosNumToUnboundEmat;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * corresponding position number in the unbound matrix
     *
     * @return
     */
    public HashMap<Integer, Integer> getBoundPosNumToUnboundPosNum() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding unbound position number
        List<Integer> unboundPosNumsPerPos = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? ArrayUtils.indexOf(resNumsUnboundMutable.toArray(), posNum)
                                : ArrayUtils.indexOf(resNumsUnboundNonMutable.toArray(), posNum))
                .collect(Collectors.toCollection(ArrayList::new));

        HashMap<Integer, Integer> boundPosNumToUnboundPosNum = new HashMap<>();
        for (int posNum = 0; posNum < unboundPosNumsPerPos.size(); posNum++) {
            boundPosNumToUnboundPosNum.put(posNum, unboundPosNumsPerPos.get(posNum));
        }
        return boundPosNumToUnboundPosNum;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * corresponding strand number
     *
     * @return
     */
    public boolean[][] getSameStrandMatrix() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toList());

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toList());

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toList());
        //Map to corresponding unbound position number
        List<Integer> boundPosNumToStrandNum = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum) ? 0 : 1)
                .collect(Collectors.toList());

        int numResidues = boundPosNumToStrandNum.size();
        boolean[][] belongToSameStrand = new boolean[numResidues][numResidues];
        for (int i = 0; i < numResidues; i++) {
            for (int j = i + 1; j < numResidues; j++) {
                if (boundPosNumToStrandNum.get(i).equals(boundPosNumToStrandNum.get(j))) {
                    belongToSameStrand[i][j] = true;
                    belongToSameStrand[j][i] = true;
                } else {
                    belongToSameStrand[i][j] = false;
                    belongToSameStrand[j][i] = false;
                }
            }
        }
        return belongToSameStrand;
    }

    /**
     * For flexible position in bound matrix (0,1,2,...,numRes-1) we map to the
     * boolean that is true if the res is part of boolean strand and false
     * otherwise
     *
     * @return
     */
    public HashMap<Integer, Boolean> getBoundPosNumberToIsMutableStrand() {
        SearchProblem boundState = this.mutableSearchProblems[0];
        //Get res number from each flexible position in the bound state
        List<Integer> resNumsBound = boundState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound mutable state
        SearchProblem unBoundMutableState = this.mutableSearchProblems[1];
        List<Integer> resNumsUnboundMutable = unBoundMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));

        //Get res number for each flexible position in the unbound non-mutable state
        SearchProblem unBoundNonMutableState = this.nonMutableSearchProblem;
        List<Integer> resNumsUnboundNonMutable = unBoundNonMutableState.confSpace.posFlex.stream()
                .map(posFlex -> posFlex.res.resNum)
                .collect(Collectors.toCollection(ArrayList::new));
        //Map to corresponding unbound position number
        List<Boolean> boundPosNumIsMutable = resNumsBound.stream()
                .map(posNum -> ArrayUtils.contains(resNumsUnboundMutable.toArray(), posNum))
                .collect(Collectors.toCollection(ArrayList::new));

        HashMap<Integer, Boolean> boundPosNumToIsMutableStrand = new HashMap<>();
        for (int posNum = 0; posNum < boundPosNumIsMutable.size(); posNum++) {
            boundPosNumToIsMutableStrand.put(posNum, boundPosNumIsMutable.get(posNum));
        }
        return boundPosNumToIsMutableStrand;
    }

    /**
     * This returns the list of integers corresponding to the assigned position
     * numbers of the ligand in the confSpace This is useful for computing GMECs
     * over partial spaces.
     *
     * @param seqNode the current sequence node
     * @param useBoundState do we want to position numbers corresponding to
     * bound state (true) or unbound state (false)
     * @return
     */
    private ArrayList<Integer> getLigandAssignedPosNums(KaDEENode seqNode, boolean useBoundState) {
        int[] partialSeq = seqNode.getNodeAssignments();
        //Get the mapping between mutable position in sequence node assignment and
        //the corresponding position number in the confSpace for bound or unbound ligand
        ArrayList<Integer> mutable2PosNum = useBoundState ? this.mutable2StatePosNums.get(0) : this.mutable2StatePosNums.get(1);
        //Get the corresponding searchProblem for bound or unbound ligand
        SearchProblem searchProblem = useBoundState ? this.mutableSearchProblems[0] : this.mutableSearchProblems[1];

        ArrayList<Integer> ligandAssignedPosNums = new ArrayList<>();

        //Iterate over flexible position numbers 
        for (int posNum = 0; posNum < searchProblem.confSpace.numPos; posNum++) {

            //If we are using the bound state, we must check if the position belongs to 
            //Ligand or protein
            //If we are not using the bound state then it must belong to ligand
            if (this.boundResNumToIsMutableStrand.get(posNum) || !(useBoundState)) {
                //position is on mutable strand, implying it belongs to ligand

                //Now check if the posNum is mutable
                if (mutable2PosNum.contains(posNum)) {
                    //It is mutable so get the index of mutable position wrt sequence node assignment
                    int index = mutable2PosNum.indexOf(posNum);
                    //Now we check if this mutable position is assigned
                    if (partialSeq[index] >= 0) {
                        //It is assigned so add it to our list
                        ligandAssignedPosNums.add(posNum);
                    }
                } else {//if the posNum is NOT mutable then it is assigned already assigned
                    //add since it is assigned and on ligand
                    ligandAssignedPosNums.add(posNum);
                }
            }
        }
        return ligandAssignedPosNums;
    }

    /**
     * This returns the list of integers corresponding to the assigned position
     * numbers of the ligand in the confSpace This is useful for computing GMECs
     * over partial spaces.
     *
     * @param seqNode current sequence node in tree
     * @param useBoundState do we want the posNums to correspond to the bound
     * state or unbound state
     * @return
     */
    private ArrayList<Integer> getLigandUnassignedPosNums(KaDEENode seqNode, boolean useBoundState) {
        int[] partialSeq = seqNode.getNodeAssignments();

        //Get the mapping between mutable position in sequence node assignment and
        //the corresponding position number in the confSpace for bound or unbound ligand
        ArrayList<Integer> mutable2PosNum = useBoundState ? this.mutable2StatePosNums.get(0) : this.mutable2StatePosNums.get(1);
        //Get the corresponding searchProblem for bound or unbound ligand
        SearchProblem searchProblem = useBoundState ? this.mutableSearchProblems[0] : this.mutableSearchProblems[1];

        ArrayList<Integer> ligandUnassignedPosNums = new ArrayList<>();

        //Iterate over flexible position numbers
        for (int posNum = 0; posNum < searchProblem.confSpace.numPos; posNum++) {

            //If we are using the bound state, we must check if the position belongs to 
            //Ligand or protein
            //If we are not using the bound state then it must belong to ligand
            if (this.boundResNumToIsMutableStrand.get(posNum) || !(useBoundState)) {
                //Position is on the mutable (ligand) strand

                //Now check if the posNum is mutable
                if (mutable2PosNum.contains(posNum)) {
                    //It is mutable so get the index of the mutable position wrt sequence node assignment
                    int index = mutable2PosNum.indexOf(posNum);
                    //Now we check if this mutable position is unassigned
                    if (partialSeq[index] < 0) {
                        //It is unassigned so add to our list
                        ligandUnassignedPosNums.add(posNum);
                    }
                }
            }
        }
        return ligandUnassignedPosNums;
    }

    /**
     * Returns the list of posNums corresponding to the protein residues for
     * either the bound search space or unbound search space
     *
     * @param useBoundState if true, use bound search space
     * @return
     */
    private ArrayList<Integer> getProteinPosNums(boolean useBoundState) {
        SearchProblem searchSpace = useBoundState ? mutableSearchProblems[0] : nonMutableSearchProblem;

        ArrayList<Integer> proteinPosNums = new ArrayList<>();

        for (int pos = 0; pos < searchSpace.confSpace.numPos; pos++) {
            //If we are using the bound state we must check if posNum
            //belongs to protein or ligand
            if (useBoundState) {
                //Check if pos belongs to nonmutable (protein) strand
                if (!this.boundResNumToIsMutableStrand.get(pos)) {
                    proteinPosNums.add(pos);
                }
            } else {//If we are using the unbound state, every posNum is part of protein
                proteinPosNums.add(pos);
            }
        }
        return proteinPosNums;
    }

    /**
     * Returns the list of posNums corresponding to the ligand residues for
     * either the bound search space or the unbound searchSpace
     *
     * @param useBoundState if true, use bound search space
     * @return
     */
    private ArrayList<Integer> getLigandPosNums(boolean useBoundState) {
        SearchProblem searchSpace = useBoundState ? mutableSearchProblems[0] : nonMutableSearchProblem;

        ArrayList<Integer> ligandPosNums = new ArrayList<>();

        for (int pos = 0; pos < searchSpace.confSpace.numPos; pos++) {
            if (useBoundState) {
                if (this.boundResNumToIsMutableStrand.get(pos)) {
                    ligandPosNums.add(pos);
                }
            } else {
                ligandPosNums.add(pos);
            }
        }
        return ligandPosNums;
    }

    private double getMAP(SearchProblem searchSpace) {
        ConfTree confTree = new ConfTree(searchSpace);

        if (searchSpace.contSCFlex) {
            throw new RuntimeException("Continuous Flexibility Not Yet Supported in KaDEE");
        }

        int[] MAPconfig = confTree.nextConf();
        double E = searchSpace.emat.getInternalEnergy(new RCTuple(MAPconfig));
        return E;
    }

}
