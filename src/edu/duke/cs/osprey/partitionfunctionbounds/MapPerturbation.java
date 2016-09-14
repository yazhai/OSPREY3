/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package edu.duke.cs.osprey.partitionfunctionbounds;

import edu.duke.cs.osprey.astar.ConfTree;
import edu.duke.cs.osprey.astar.Mplp;
import edu.duke.cs.osprey.astar.comets.UpdatedPruningMatrix;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.control.ConfigFileParser;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.energy.PoissonBoltzmannEnergy;
import edu.duke.cs.osprey.pruning.Pruner;
import edu.duke.cs.osprey.pruning.PruningControl;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.tools.ExpFunction;
import edu.duke.cs.osprey.tools.ObjectIO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;

/**
 *
 * @author hmn5
 */
public class MapPerturbation {

    final SearchProblem searchSpace;
    EnergyMatrix emat;
    PruningMatrix pruneMat;

    final double constRT = PoissonBoltzmannEnergy.constRT;
    final ExpFunction ef = new ExpFunction();

    //Keep track of the confs of the MAP that we get for each sample
    ///We can analyze these with mutual information to determine how to merge
    public int[][] mapConfsUB;
    public int[][] mapConfsLB;

    //For analyzing probabilities
    int numSamplesAnalysis;
    ArrayList<singlePos> singlePosList;
    ArrayList<pairPos> pairPosList;

    GumbelDistribution gd;

    boolean doPruning = true;
    boolean verbose = true;

    public MapPerturbation(SearchProblem searchSpace) {
        this.searchSpace = searchSpace;
        this.emat = searchSpace.emat;
        this.pruneMat = searchSpace.pruneMat;
        gd = new GumbelDistribution();
    }

    public MapPerturbation(SearchProblem searchSpace, ConfigFileParser cfp) {
        this.searchSpace = searchSpace;
        this.emat = searchSpace.emat;
        this.pruneMat = searchSpace.pruneMat;
        gd = new GumbelDistribution();

    }

    public MapPerturbation(SearchProblem searchSpace, boolean verbose) {
        this.searchSpace = searchSpace;
        this.emat = searchSpace.emat;
        this.pruneMat = searchSpace.pruneMat;
        gd = new GumbelDistribution();
        this.verbose = verbose;
    }

    public MapPerturbation(EnergyMatrix emat, PruningMatrix pruneMat) {
        this.searchSpace = null;
        this.emat = emat;
        this.pruneMat = pruneMat;
        gd = new GumbelDistribution();
    }

    //Returns Upper Bounds on Log Partition Function
    public double calcUBLogZ(int anumSamples) {
        int numSamples = anumSamples;
        mapConfsUB = new int[numSamples][emat.oneBody.size()];
        BigDecimal averageGMECs = new BigDecimal(0.0);

        for (int i = 0; i < numSamples; i++) {
            ArrayList<ArrayList<Double>> originalOneBodyEmat = (ArrayList<ArrayList<Double>>) ObjectIO.deepCopy(emat.oneBody);
            addUBGumbelNoiseOneBody();
            UpdatedPruningMatrix upm = new UpdatedPruningMatrix(pruneMat);
            prune(searchSpace, upm);
            ConfSearch search = new ConfTree(searchSpace.emat, upm);
            int[] conf = search.nextConf();
            mapConfsUB[i] = conf;
            double E = -1.0 * searchSpace.lowerBound(conf);
            averageGMECs = averageGMECs.add(new BigDecimal(E));
            //replace oneBody with original to remove the noise added
            emat.oneBody = originalOneBodyEmat;
            if (verbose) {
                double averageSoFar = averageGMECs.divide(new BigDecimal((i + 1) * this.constRT), ef.mc).doubleValue();
                System.out.println("Sample: " + (i + 1) + " " + E / this.constRT + "  Average: " + averageSoFar);
            }
        }

        return averageGMECs.divide(new BigDecimal(numSamples * this.constRT), ef.mc).doubleValue();
    }

    //Returns Upper Bounds on Log Partition Function
    public double calcUBLogZLP(int anumSamples) {
        int numSamples = anumSamples;
        mapConfsUB = new int[numSamples][emat.oneBody.size()];
        BigDecimal averageGMECs = new BigDecimal(0.0);

        for (int i = 0; i < numSamples; i++) {
            ArrayList<ArrayList<Double>> originalOneBodyEmat = (ArrayList<ArrayList<Double>>) ObjectIO.deepCopy(emat.oneBody);
            addUBGumbelNoiseOneBody();
            Mplp mplp = new Mplp(emat.numPos(), emat, pruneMat);
            int[] conf = new int[emat.numPos()];
            Arrays.fill(conf, -1);
            double E = -mplp.optimizeMPLP(conf, 200);
            averageGMECs = averageGMECs.add(new BigDecimal(E));
            //replace oneBody with original to remove the noise added
            emat.oneBody = originalOneBodyEmat;
            if (verbose) {
                double averageSoFar = averageGMECs.divide(new BigDecimal((i + 1) * this.constRT), ef.mc).doubleValue();
                System.out.println("Sample: " + (i + 1) + " " + E / this.constRT + "  Average: " + averageSoFar);
            }
        }

        return averageGMECs.divide(new BigDecimal(numSamples * this.constRT), ef.mc).doubleValue();
    }

    //Returns Upper Bounds on Log Partition Function
    public double calcUBLogZLP(int[] partialConf, int anumSamples) {
        int numSamples = anumSamples;
        mapConfsUB = new int[numSamples][emat.oneBody.size()];
        BigDecimal averageGMECs = new BigDecimal(0.0);

        for (int i = 0; i < numSamples; i++) {
            ArrayList<ArrayList<Double>> originalOneBodyEmat = (ArrayList<ArrayList<Double>>) ObjectIO.deepCopy(emat.oneBody);
            addUBGumbelNoiseOneBody();
            Mplp mplp = new Mplp(emat.numPos(), emat, pruneMat);
            double E = -mplp.optimizeMPLP(partialConf, 200);
            averageGMECs = averageGMECs.add(new BigDecimal(E));
            //replace oneBody with original to remove the noise added
            emat.oneBody = originalOneBodyEmat;
            if (verbose) {
                double averageSoFar = averageGMECs.divide(new BigDecimal((i + 1) * this.constRT), ef.mc).doubleValue();
                System.out.println("Sample: " + (i + 1) + " " + E / this.constRT + "  Average: " + averageSoFar);
            }
        }

        return averageGMECs.divide(new BigDecimal(numSamples * this.constRT), ef.mc).doubleValue();
    }

    public double calcUBLogZLPMax(int anumSamples) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < anumSamples; i++) {
            double sample = calcUBLogZLP(20);
            max = Math.max(sample, max);
        }
        return max;
    }

    public double calcUBLogZLPMax(int[] partialConf, int anumSamples) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < anumSamples; i++) {
            double sample = calcUBLogZLP(partialConf, 20);
            max = Math.max(sample, max);
        }
        return max;
    }

    //Returns Lower Bound on natural log of Partition Function
    public double calcLBLogZ(int anumSamples) {
        int numSamples = anumSamples;
        mapConfsLB = new int[numSamples][emat.oneBody.size()];
        BigDecimal averageGMECs = new BigDecimal(0.0);

        for (int i = 0; i < numSamples; i++) {
            ArrayList<ArrayList<Double>> originalOneBodyEmat = (ArrayList<ArrayList<Double>>) ObjectIO.deepCopy(emat.oneBody);
            addLBGumbelNoiseOneBody();
            ConfSearch search = new ConfTree(searchSpace);
            int[] conf = search.nextConf();
            mapConfsLB[i] = conf;
            double E = -1.0 * searchSpace.lowerBound(conf);
            averageGMECs = averageGMECs.add(new BigDecimal(E));
            //replace oneBody with original to remove the noise added
            emat.oneBody = originalOneBodyEmat;
            if (i % 100 == 0) {
                System.out.println("Map Pert Iteration: " + i);
            }
        }

        return averageGMECs.divide(new BigDecimal(numSamples * this.constRT), ef.mc).doubleValue();
    }

    //Returns lower bound on log_10 of partition function
    public double calcLBLog10Z(int aNumSamples) {
        return (Math.log10(Math.E)) * calcLBLogZ(aNumSamples);
    }

    public double calcUBLog10Z(int aNumSamples) {
        return (Math.log10(Math.E)) * calcUBLogZ(aNumSamples);
    }

    //add Gumbel noise to one-body terms
    private void addUBGumbelNoiseOneBody() {
        for (int pos = 0; pos < emat.oneBody.size(); pos++) {
            if (pruneMat.unprunedRCsAtPos(pos).size() > 1) {
                for (int superRC : pruneMat.unprunedRCsAtPos(pos)) {
                    double currentE = emat.getOneBody(pos, superRC);
                    double noise = gd.sample(-1.0 * GumbelDistribution.gamma, 1.0) * this.constRT;
                    emat.setOneBody(pos, superRC, currentE - noise);
                }
            }
        }
    }

    //add Gumbel noise to one-body terms
    private void addLBGumbelNoiseOneBody() {
        for (int pos = 0; pos < emat.oneBody.size(); pos++) {
            for (int superRC : searchSpace.pruneMat.unprunedRCsAtPos(pos)) {
                double currentE = emat.getOneBody(pos, superRC);
                double noise = gd.sample(-1.0 * GumbelDistribution.gamma, 1.0) * this.constRT / emat.oneBody.size();
                emat.setOneBody(pos, superRC, currentE - noise);
            }
        }
    }

    //get the counts of each rotamer and each pair of rotamers at each pos or pair of pos
    public void getMapPosRotCounts(int[][] mapConfs) {
        this.numSamplesAnalysis = mapConfs.length;
        int numPos = mapConfs[0].length;

        this.singlePosList = new ArrayList<>();
        this.pairPosList = new ArrayList<>();

        for (int sample = 0; sample < numSamplesAnalysis; sample++) {
            int[] conf = mapConfs[sample];
            for (int posNum1 = 0; posNum1 < numPos; posNum1++) {
                //Get res
                if (singlePosList.size() <= posNum1) {
                    singlePos res = new singlePos(posNum1);
                    singlePosList.add(res);
                }
                singlePos pos1 = singlePosList.get(posNum1);
                //Get rot
                int rotNum1 = conf[posNum1];
                singleRot rot1 = new singleRot(rotNum1);
                //contains uses equals which is overriden 
                if (!pos1.rotList.contains(rot1)) {
                    pos1.rotList.add(rot1);
                } else {
                    //update count
                    int indexRot1 = pos1.rotList.indexOf(rot1);
                    pos1.rotList.get(indexRot1).count++;
                }

                for (int posNum2 = 0; posNum2 < posNum1; posNum2++) {
                    int rotNum2 = conf[posNum2];
                    pairPos twoPos = new pairPos(posNum1, posNum2);
                    if (!pairPosList.contains(twoPos)) {
                        pairPosList.add(twoPos);
                    }
                    int indexPairPos = pairPosList.indexOf(twoPos);
                    pairPos pair = pairPosList.get(indexPairPos);
                    pairRot rotPair = new pairRot(rotNum1, rotNum2);
                    if (!pair.rotList.contains(rotPair)) {
                        pair.rotList.add(rotPair);
                    } else {
                        int indexRotPair = pair.rotList.indexOf(rotPair);
                        pair.rotList.get(indexRotPair).count++;
                    }
                }
            }
        }
    }

    public ArrayList<Integer> getPairWithMaxMutualInfo(boolean useUpperBoundMapConfs) {
        //returns arraylist of size 2, where each element is a position in the pair
        //with maximum mutual information
        if (useUpperBoundMapConfs) {
            getMapPosRotCounts(mapConfsUB);
        } else {
            getMapPosRotCounts(mapConfsLB);
        }
        double maxMutualInfo = 0.0;
        pairPos maxPair = new pairPos(-1, -1);
        for (pairPos pair : pairPosList) {
            double mutualInfo = calcMutualInformation(pair);
            if (mutualInfo > maxMutualInfo) {
                maxMutualInfo = mutualInfo;
                maxPair = pair;
            }
        }
        ArrayList<Integer> pairPosNums = new ArrayList<>();
        if (maxPair.res1 < maxPair.res2) {
            pairPosNums.add(maxPair.res1);
            pairPosNums.add(maxPair.res2);
        } else {
            pairPosNums.add(maxPair.res2);
            pairPosNums.add(maxPair.res1);
        }
        return pairPosNums;
    }

    private double calcMutualInformation(pairPos pair) {
        singlePos pos1 = singlePosList.get(pair.res1);
        singlePos pos2 = singlePosList.get(pair.res2);
        double mutualInfo = calcSinglePosEntropy(pos1) + calcSinglePosEntropy(pos2) - calcPairPosEntropy(pair);
        return mutualInfo;
    }

    private double calcSinglePosEntropy(singlePos pos) {
        double[] probabilities = new double[pos.rotList.size()];
        //get probabilities
        for (int i = 0; i < pos.rotList.size(); i++) {
            singleRot rot = pos.rotList.get(i);
            double prob = (double) rot.count / (double) this.numSamplesAnalysis;
            probabilities[i] = prob;
        }
        //entropy 
        double entropy = 0.0;
        for (double prob : probabilities) {
            entropy += -1.0 * (prob * Math.log(prob));
        }
        return entropy;
    }

    private double calcPairPosEntropy(pairPos pair) {
        double[] probabilities = new double[pair.rotList.size()];
        //get probabilities
        for (int i = 0; i < pair.rotList.size(); i++) {
            pairRot rot = pair.rotList.get(i);
            double prob = (double) rot.count / (double) this.numSamplesAnalysis;
            probabilities[i] = prob;
        }
        //entropy 
        double entropy = 0.0;
        for (double prob : probabilities) {
            entropy += -1.0 * (prob * Math.log(prob));
        }
        return entropy;
    }

    private class singlePos {

        int resNum;
        ArrayList<singleRot> rotList = new ArrayList<>();

        public singlePos(int resNum) {
            this.resNum = resNum;
        }

        @Override
        public boolean equals(Object ares2) {
            singlePos res2 = (singlePos) ares2;
            return this.resNum == res2.resNum;
        }
    }

    private class singleRot {

        int rotNum;
        int count = 1;

        public singleRot(int rotNum) {
            this.rotNum = rotNum;
        }

        @Override
        public boolean equals(Object asingleRot2) {
            singleRot singleRot2 = (singleRot) asingleRot2;
            return this.rotNum == singleRot2.rotNum;
        }
    }

    private class pairPos {

        int res1;
        int res2;

        ArrayList<pairRot> rotList = new ArrayList<>();

        public pairPos(int res1, int res2) {
            this.res1 = res1;
            this.res2 = res2;
        }

        @Override
        public boolean equals(Object aresPair2) {
            pairPos resPair2 = (pairPos) aresPair2;
            boolean match1 = (this.res1 == resPair2.res1) && (this.res2 == resPair2.res2);
            boolean match2 = (this.res1 == resPair2.res2) && (this.res2 == resPair2.res1);
            boolean same = match1 || match2;
            return same;
        }
    }

    private class pairRot {

        int rot1;
        int rot2;
        int count = 1;

        public pairRot(int rot1, int rot2) {
            this.rot1 = rot1;
            this.rot2 = rot2;
        }

        @Override
        public boolean equals(Object apairRot2) {
            pairRot pairRot2 = (pairRot) apairRot2;
            boolean match1 = (this.rot1 == pairRot2.rot1) && (this.rot2 == pairRot2.rot2);
            boolean match2 = (this.rot1 == pairRot2.rot2) && (this.rot2 == pairRot2.rot1);
            return match1 || match2;
        }
    }

    public void prune(SearchProblem searchSpace, double pruningInterval, ConfigFileParser cfp) {
        if (searchSpace.competitorPruneMat == null) {
            System.out.println("PRECOMPUTING COMPETITOR PRUNING MATRIX");
            PruningControl compPruning = cfp.setupPruning(searchSpace, pruningInterval, false, false);
            compPruning.setOnlyGoldstein(true);
            compPruning.prune();
            searchSpace.competitorPruneMat = searchSpace.pruneMat;
            searchSpace.pruneMat = null;
            System.out.println("COMPETITOR PRUNING DONE");
        }

        //Next, do DEE, which will fill in the pruning matrix
        PruningControl pruning = cfp.setupPruning(searchSpace, pruningInterval, false, false);

        pruning.prune();//pass in DEE options, and run the specified types of DEE            
    }

    public void prune(SearchProblem sp, UpdatedPruningMatrix upm) {
        Pruner dee = new Pruner(sp, upm, true, Double.POSITIVE_INFINITY,
                0, false, false, false);
        //this is rigid, type-dependent pruning aiming for sequence GMECs
        //So Ew = Ival = 0
        int numUpdates = 0;
        int oldNumUpdates = 0;
        do {//repeat as long as we're pruning things
            oldNumUpdates = numUpdates;
            dee.prune("GOLDSTEIN");
            numUpdates = upm.countUpdates();
        } while (numUpdates > oldNumUpdates);
    }
    
}