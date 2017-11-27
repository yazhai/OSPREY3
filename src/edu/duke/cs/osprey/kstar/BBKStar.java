package edu.duke.cs.osprey.kstar;

import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.ematrix.SimplerEnergyMatrixCalculator;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.EnergyCalculator;
import edu.duke.cs.osprey.kstar.KStar.ConfSearchFactory;
import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.kstar.pfunc.SimplePartitionFunction;
import edu.duke.cs.osprey.tools.MathTools;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/**
 * Implementation of the BBK* algorithm to predict protein sequence mutations that improve
 * binding affinity by computing provably accurate Boltzmann-weighted ensembles
 * {@cite Ojewole2008 Adegoke A. Ojewole, Jonathan D. Jou, Vance G. Fowler, Jr., and Bruce R. Donald, 2017.
 * BBK* (Branch and Bound over K*): A Provable and Efficient Ensemble-Based Algorithm to Optimize
 * Stability and Binding Affinity over Large Sequence Spaces
 * In Research in Computational Molecular Biology (accepted, in press).}.
 */
public class BBKStar {

	// *sigh* Java makes this stuff so verbose to do...
	// Kotlin would make this so much easier
	public static class Settings {

		public static class Builder {

			/** The number of best (by K* score) sequences to evaluate before finishing */
			private int numBestSequences = 1;

			/**
			 * The number of conformations to evaluate per batch of partition function refinement
			 *
			 * For best results, make this a multiple of the available parallelism. e.g., if using 4 threads,
			 * try a batch size of 4, 8, 12, 16, etc.
			 */
			private int numConfsPerBatch = 8;

			public Builder setNumBestSequences(int val) {
				numBestSequences = val;
				return this;
			}

			public Builder setNumConfsPerBatch(int val) {
				numConfsPerBatch = val;
				return this;
			}

			public Settings build() {
				return new Settings(numBestSequences, numConfsPerBatch);
			}
		}

		public final int numBestSequences;
		public final int numConfsPerBatch;

		public Settings(int numBestSequences, int numConfsPerBatch) {
			this.numBestSequences = numBestSequences;
			this.numConfsPerBatch = numConfsPerBatch;
		}
	}

	public class ConfSpaceInfo {

		public final KStar.ConfSpaceType type;
		public final SimpleConfSpace confSpace;
		public final ConfEnergyCalculator rigidConfEcalc;
		public final ConfEnergyCalculator minimizingConfEcalc;

		public EnergyMatrix rigidNegatedEmat = null;
		public EnergyMatrix minimizedEmat = null;
		public BigDecimal stabilityThreshold = BigDecimal.ZERO;

		public ConfSpaceInfo(KStar.ConfSpaceType type, SimpleConfSpace confSpace, ConfEnergyCalculator rigidConfEcalc, ConfEnergyCalculator minimizingConfEcalc) {
			this.type = type;
			this.confSpace = confSpace;
			this.rigidConfEcalc = rigidConfEcalc;
			this.minimizingConfEcalc = minimizingConfEcalc;
		}

		public void calcEmatsIfNeeded() {
			if (rigidNegatedEmat == null) {
				SimplerEnergyMatrixCalculator.Builder builder = new SimplerEnergyMatrixCalculator.Builder(rigidConfEcalc);
				if (kstarSettings.energyMatrixCachePattern != null) {
					builder.setCacheFile(new File(kstarSettings.applyEnergyMatrixCachePattern(type.name().toLowerCase() + ".rigidNegated")));
				}
				rigidNegatedEmat = builder.build().calcEnergyMatrix();

				// negate the rigid energy matrix, so we can convince A* to return scores
				// in descending order instead of ascending order
				// (of course, we'll have to negate the scores returned by A* too)
				rigidNegatedEmat.negate();
			}
			if (minimizedEmat == null) {
				SimplerEnergyMatrixCalculator.Builder builder = new SimplerEnergyMatrixCalculator.Builder(minimizingConfEcalc);
				if (kstarSettings.energyMatrixCachePattern != null) {
					builder.setCacheFile(new File(kstarSettings.applyEnergyMatrixCachePattern(type.name().toLowerCase())));
				}
				minimizedEmat = builder.build().calcEnergyMatrix();
			}
		}
	}

	public static enum PfuncsStatus {
		Estimating,
		Estimated,
		Blocked
	}

	private abstract class Node implements Comparable<Node> {

		public final KStar.Sequence sequence;
		public final KStar.Sequence proteinSequence;
		public final KStar.Sequence ligandSequence;

		/** for comparing in the tree, higher is first */
		public double score;

		/** signals whether or not partition function values are allowed the stability threshold */
		public boolean isUnboundUnstable;

		protected Node(KStar.Sequence sequence) {

			this.sequence = sequence;

			// split complex sequence into protein/ligand sequences
			proteinSequence = KStar.Sequence.makeWildType(BBKStar.this.protein.confSpace);
			ligandSequence = KStar.Sequence.makeWildType(BBKStar.this.ligand.confSpace);
			for (SimpleConfSpace.Position pos : BBKStar.this.complex.confSpace.positions) {

				SimpleConfSpace.Position proteinPos = complexToProteinMap.get(pos);
				if (proteinPos != null) {
					proteinSequence.set(proteinPos.index, sequence.get(pos.index));
				}

				SimpleConfSpace.Position ligandPos = complexToLigandMap.get(pos);
				if (ligandPos != null) {
					ligandSequence.set(ligandPos.index, sequence.get(pos.index));
				}
			}

			this.score = 0;
		}

		@Override
		public int compareTo(Node other) {
			// negate for descending sort
			return -Double.compare(this.score, other.score);
		}

		public abstract void estimateScore();
	}

	public class MultiSequenceNode extends Node {

		public MultiSequenceNode(KStar.Sequence sequence) {
			super(sequence);
		}

		public List<Node> makeChildren() {

			List<Node> children = new ArrayList<>();

			List<SimpleConfSpace.Position> positions = complex.confSpace.positions;

			// pick the next design position
			// TODO: dynamic A*?
			int posIndex;
			for (posIndex = 0; posIndex< sequence.size(); posIndex++) {
				if (sequence.get(posIndex) == null) {
					break;
				}
			}
			if (posIndex >= sequence.size()) {
				throw new IllegalStateException("no design positions left to choose");
			}
			SimpleConfSpace.Position assignPos = positions.get(posIndex);

			// get the possible assignments
			Set<String> resTypes = new HashSet<>(assignPos.resFlex.resTypes);

			// add wild-type option if mutations are limited
			if (kstarSettings.maxSimultaneousMutations < positions.size()) {
				resTypes.add(assignPos.resFlex.wildType);
			}

			// for each assignment...
			for (String resType : resTypes) {

				// update the sequence with this assignment
				KStar.Sequence s = sequence.makeWithAssignment(assignPos.index, resType);

				if (s.isFullyAssigned()) {

					// fully assigned, make single sequence node
					children.add(new SingleSequenceNode(s));

				} else if (s.countMutations(complex.confSpace) == kstarSettings.maxSimultaneousMutations) {

					// mutation limit reached, fill unassigned positions with wild-type
					s.fillWildType(complex.confSpace);
					children.add(new SingleSequenceNode(s));

				} else {

					// still partial sequence, make multi-sequence node
					children.add(new MultiSequenceNode(s));
				}
			}

			return children;
		}

		@Override
		public void estimateScore() {

			// TODO: expose setting?
			// NOTE: for the correctness of the bounds, the number of confs must be the same for every node
			// meaning, it might not be sound to do epsilon-based iterative approximations here
			final int numConfs = 1000;

			if (MathTools.isFinite(protein.stabilityThreshold)) {

				// tank the sequence if the protein is unstable
				BigDecimal proteinUpperBound = calcUpperBound(protein, proteinSequence, numConfs);
				if (MathTools.isLessThan(proteinUpperBound, protein.stabilityThreshold)) {
					score = Double.NEGATIVE_INFINITY;
					isUnboundUnstable = true;
					return;
				}
			}

			BigDecimal proteinLowerBound = calcLowerBound(protein, proteinSequence, numConfs);

			// if the first few conf upper bound scores (for the pfunc lower bound) are too high,
			// then the K* upper bound is also too high
			if (MathTools.isZero(proteinLowerBound)) {
				score = Double.POSITIVE_INFINITY;
				isUnboundUnstable = false;
				return;
			}

			if (MathTools.isFinite(ligand.stabilityThreshold)) {

				// tank the sequence if the ligand is unstable
				BigDecimal ligandUpperBound = calcUpperBound(ligand, ligandSequence, numConfs);
				if (MathTools.isLessThan(ligandUpperBound, ligand.stabilityThreshold)) {
					score = Double.NEGATIVE_INFINITY;
					isUnboundUnstable = true;
					return;
				}
			}

			BigDecimal ligandLowerBound = calcLowerBound(ligand, ligandSequence, numConfs);

			// if the first few conf upper bound scores (for the pfunc lower bound) are too high,
			// then the K* upper bound is also too high
			if (MathTools.isZero(ligandLowerBound)) {
				score = Double.POSITIVE_INFINITY;
				isUnboundUnstable = false;
				return;
			}

			BigDecimal complexUpperBound = calcUpperBound(complex, sequence, numConfs);

			// compute the node score
			score = MathTools.bigDivideDivide(
				complexUpperBound,
				proteinLowerBound,
				ligandLowerBound,
				PartitionFunction.decimalPrecision
			).doubleValue();
			isUnboundUnstable = false;
		}

		private BigDecimal calcLowerBound(ConfSpaceInfo info, KStar.Sequence sequence, int numConfs) {

			// to compute lower bounds on pfuncs, we'll use the upper bound calculator,
			// but feed it upper-bound scores in order of greatest upper bound
			// instead of the usual lower-bound scores in order of smallest lower bound
			// which means we need to feed A* with a rigid, negated energy matrix
			// and then negate all the scores returned by A*
			Function<ConfSearch,ConfSearch> astarNegater = (confSearch) -> new ConfSearch() {
				@Override
				public ScoredConf nextConf() {
					ScoredConf conf = confSearch.nextConf();
					conf.setScore(-conf.getScore());
					return conf;
				}

				@Override
				public BigInteger getNumConformations() {
					return confSearch.getNumConformations();
				}

				@Override
				public List<ScoredConf> nextConfs(double maxEnergy) {
					throw new UnsupportedOperationException("some lazy programmer didn't implement this =P");
				}
			};

			SimplePartitionFunction.UpperBoundCalculator calc = new SimplePartitionFunction.UpperBoundCalculator(
				astarNegater.apply(confSearchFactory.make(info.rigidNegatedEmat, sequence.makeRCs(info.confSpace)))
			);
			calc.run(numConfs);
			return calc.totalBound;
		}

		private BigDecimal calcUpperBound(ConfSpaceInfo info, KStar.Sequence sequence, int numConfs) {

			// to compute upper bounds on pfuncs,
			// we'll use the upper bound calculator in the usual way

			SimplePartitionFunction.UpperBoundCalculator calc = new SimplePartitionFunction.UpperBoundCalculator(
				confSearchFactory.make(info.minimizedEmat, sequence.makeRCs(info.confSpace))
			);
			calc.run(numConfs);
			return calc.totalBound;
		}

		@Override
		public String toString() {
			return String.format("MultiSequenceNode[score=%12.6f, seq=%s]",
				score,
				sequence
			);
		}
	}

	public class SingleSequenceNode extends Node {

		public final PartitionFunction protein;
		public final PartitionFunction ligand;
		public final PartitionFunction complex;

		public SingleSequenceNode(KStar.Sequence sequence) {
			super(sequence);

			// make the partition functions
			this.protein = makePfunc(proteinPfuncs, BBKStar.this.protein, proteinSequence);
			this.ligand = makePfunc(ligandPfuncs, BBKStar.this.ligand, ligandSequence);
			this.complex = makePfunc(complexPfuncs, BBKStar.this.complex, sequence);
		}

		private PartitionFunction makePfunc(Map<KStar.Sequence,PartitionFunction> pfuncCache, ConfSpaceInfo info, KStar.Sequence sequence) {

			// first check the cache
			PartitionFunction pfunc = pfuncCache.get(sequence);
			if (pfunc != null) {
				return pfunc;
			}

			// cache miss, need to compute the partition function

			// make the partition function
			RCs rcs = sequence.makeRCs(info.confSpace);
			pfunc = new SimplePartitionFunction(confSearchFactory.make(info.minimizedEmat, rcs), info.minimizingConfEcalc);
			pfunc.setReportProgress(kstarSettings.showPfuncProgress);
			pfunc.init(kstarSettings.epsilon, info.stabilityThreshold);
			pfuncCache.put(sequence, pfunc);
			return pfunc;
		}

		@Override
		public void estimateScore() {

			// tank the sequence if either unbound strand is unstable
			// yeah, we haven't refined any pfuncs yet this estimation,
			// but since pfuncs get cached, check before we do any more estimation
			if (protein.getStatus() == PartitionFunction.Status.Unstable
				|| ligand.getStatus() == PartitionFunction.Status.Unstable) {
				score = Double.NEGATIVE_INFINITY;
				isUnboundUnstable = true;
				return;
			}

			// refine the pfuncs if needed
			if (protein.getStatus().canContinue()) {
				protein.compute(bbkstarSettings.numConfsPerBatch);

				// tank the sequence if the unbound protein is unstable
				if (protein.getStatus() == PartitionFunction.Status.Unstable) {
					score = Double.NEGATIVE_INFINITY;
					isUnboundUnstable = true;
					return;
				}
			}

			if (ligand.getStatus().canContinue()) {
				ligand.compute(bbkstarSettings.numConfsPerBatch);

				// tank the sequence if the unbound ligand is unstable
				if (ligand.getStatus() == PartitionFunction.Status.Unstable) {
					score = Double.NEGATIVE_INFINITY;
					isUnboundUnstable = true;
					return;
				}
			}

			if (complex.getStatus().canContinue()) {
				complex.compute(bbkstarSettings.numConfsPerBatch);
			}

			// update the score
			score = Math.log10(makeKStarScore().upperBound.doubleValue());
			isUnboundUnstable = false;
		}

		public KStarScore computeScore() {

			// refine the pfuncs until done
			while (protein.getStatus().canContinue()) {
				protein.compute(bbkstarSettings.numConfsPerBatch);
			}
			while (ligand.getStatus().canContinue()) {
				ligand.compute(bbkstarSettings.numConfsPerBatch);
			}
			while (complex.getStatus().canContinue()) {
				complex.compute(bbkstarSettings.numConfsPerBatch);
			}

			// update the score
			KStarScore kstarScore = makeKStarScore();
			score = Math.log10(kstarScore.upperBound.doubleValue());
			return kstarScore;
		}

		public KStarScore makeKStarScore() {
			return new KStarScore(protein.makeResult(), ligand.makeResult(), complex.makeResult());
		}

		public PfuncsStatus getStatus() {

			// aggregate pfunc statuses
			if (protein.getStatus() == PartitionFunction.Status.Estimated
				&& ligand.getStatus() == PartitionFunction.Status.Estimated
				&& complex.getStatus() == PartitionFunction.Status.Estimated) {
				return PfuncsStatus.Estimated;
			} else if (protein.getStatus() == PartitionFunction.Status.Estimating
				|| ligand.getStatus() == PartitionFunction.Status.Estimating
				|| complex.getStatus() == PartitionFunction.Status.Estimating) {
				return PfuncsStatus.Estimating;
			} else {
				return PfuncsStatus.Blocked;
			}
		}

		@Override
		public String toString() {
			return String.format("SingleSequenceNode[score=%12.6f, seq=%s, K*=%s]",
				score,
				sequence,
				makeKStarScore()
			);
		}
	}

	/** A configuration space containing just the protein strand */
	public final ConfSpaceInfo protein;

	/** A configuration space containing just the ligand strand */
	public final ConfSpaceInfo ligand;

	/** A configuration space containing both the protein and ligand strands */
	public final ConfSpaceInfo complex;

	/** Calculates the rigid-rotamer energy for a molecule */
	public final EnergyCalculator rigidEcalc;

	/** Calculates the continuous-rotamer (minimized) energy for a molecule */
	public final EnergyCalculator minimizingEcalc;

	/** A function that makes a ConfEnergyCalculator with the desired options */
	public final KStar.ConfEnergyCalculatorFactory confEcalcFactory;

	/** A function that makes a ConfSearchFactory (e.g, A* search) with the desired options */
	public final ConfSearchFactory confSearchFactory;

	/** Optional and overridable settings for BBK*, shared with K* */
	public final KStar.Settings kstarSettings;

	/** Optional and overridable settings for BBK* */
	public final Settings bbkstarSettings;

	private final Map<SimpleConfSpace.Position,SimpleConfSpace.Position> complexToProteinMap;
	private final Map<SimpleConfSpace.Position,SimpleConfSpace.Position> complexToLigandMap;

	// TODO: caching these will keep lots of A* trees in memory. is that a problem?
	private final Map<KStar.Sequence,PartitionFunction> proteinPfuncs;
	private final Map<KStar.Sequence,PartitionFunction> ligandPfuncs;
	private final Map<KStar.Sequence,PartitionFunction> complexPfuncs;

	public BBKStar(SimpleConfSpace protein, SimpleConfSpace ligand, SimpleConfSpace complex, EnergyCalculator rigidEcalc, EnergyCalculator minimizingEcalc, KStar.ConfEnergyCalculatorFactory confEcalcFactory, ConfSearchFactory confSearchFactory, KStar.Settings kstarSettings, Settings bbkstarSettings) {

		this.protein = new ConfSpaceInfo(
			KStar.ConfSpaceType.Protein,
			protein,
			confEcalcFactory.make(protein, rigidEcalc),
			confEcalcFactory.make(protein, minimizingEcalc)
		);
		this.ligand = new ConfSpaceInfo(
			KStar.ConfSpaceType.Ligand,
			ligand,
			confEcalcFactory.make(ligand, rigidEcalc),
			confEcalcFactory.make(ligand, minimizingEcalc)
		);
		this.complex = new ConfSpaceInfo(
			KStar.ConfSpaceType.Complex,
			complex,
			confEcalcFactory.make(complex, rigidEcalc),
			confEcalcFactory.make(complex, minimizingEcalc)
		);
		this.rigidEcalc = rigidEcalc;
		this.minimizingEcalc = minimizingEcalc;
		this.confEcalcFactory = confEcalcFactory;
		this.confSearchFactory = confSearchFactory;
		this.kstarSettings = kstarSettings;
		this.bbkstarSettings = bbkstarSettings;

		complexToProteinMap = this.complex.confSpace.mapPositionsTo(this.protein.confSpace);
		complexToLigandMap = this.complex.confSpace.mapPositionsTo(this.ligand.confSpace);

		proteinPfuncs = new HashMap<>();
		ligandPfuncs = new HashMap<>();
		complexPfuncs = new HashMap<>();
	}

	public List<KStar.ScoredSequence> run() {

		protein.calcEmatsIfNeeded();
		ligand.calcEmatsIfNeeded();
		complex.calcEmatsIfNeeded();

		List<KStar.ScoredSequence> scoredSequences = new ArrayList<>();

		// calculate wild-type first
		System.out.println("computing K* score for the wild-type sequence...");
		SingleSequenceNode wildTypeNode = new SingleSequenceNode(KStar.Sequence.makeWildType(complex.confSpace));
		KStarScore wildTypeScore = wildTypeNode.computeScore();
		kstarSettings.scoreWriters.writeScore(new KStarScoreWriter.ScoreInfo(
			-1,
			0,
			wildTypeNode.sequence,
			complex.confSpace,
			wildTypeScore
		));
		BigDecimal stabilityThresholdFactor = new BoltzmannCalculator().calc(kstarSettings.stabilityThreshold);
		protein.stabilityThreshold = wildTypeScore.protein.values.calcLowerBound().multiply(stabilityThresholdFactor);
		ligand.stabilityThreshold = wildTypeScore.ligand.values.calcLowerBound().multiply(stabilityThresholdFactor);

		// start the BBK* tree with the root node
		PriorityQueue<Node> tree = new PriorityQueue<>();
		tree.add(new MultiSequenceNode(new KStar.Sequence(complex.confSpace.positions.size())));

		// start searching the tree
		System.out.println("computing K* scores for the " + bbkstarSettings.numBestSequences + " best sequences to epsilon = " + kstarSettings.epsilon + " ...");
		kstarSettings.scoreWriters.writeHeader();
		while (!tree.isEmpty() && scoredSequences.size() < bbkstarSettings.numBestSequences) {

			// get the next node
			Node node = tree.poll();

			if (node instanceof SingleSequenceNode) {
				SingleSequenceNode ssnode = (SingleSequenceNode)node;

				// single-sequence node
				switch (ssnode.getStatus()) {
					case Estimated:

						// sequence is finished, return it!
						reportSequence(ssnode, scoredSequences);

					break;
					case Estimating:

						// needs more estimation, catch-and-release
						ssnode.estimateScore();
						if (!ssnode.isUnboundUnstable) {
							tree.add(ssnode);
						}

					break;
					case Blocked:

						// from here on out, it's all blocked sequences
						// so it's ok to put them in the sorted order now
						reportSequence(ssnode, scoredSequences);
				}

			} else if (node instanceof MultiSequenceNode) {
				MultiSequenceNode msnode = (MultiSequenceNode)node;

				// partial sequence, expand children
				// TODO: parallelize the multi-sequence node scoring here?
				for (Node child : msnode.makeChildren()) {
					child.estimateScore();
					if (!child.isUnboundUnstable) {
						tree.add(child);
					}
				}
			}
		}

		if (scoredSequences.size() < bbkstarSettings.numBestSequences) {
			if (tree.isEmpty()) {
				// all is well, we just don't have that many sequences in the design
				System.out.println("Tried to find " + bbkstarSettings.numBestSequences + " sequences,"
					+ " but design flexibility and sequence filters only allowed " + scoredSequences.size() + " sequences.");
			} else {
				throw new Error("BBK* ended, but the tree isn't empty and we didn't return enough sequences. This is a bug.");
			}
		}

		return scoredSequences;
	}

	private void reportSequence(SingleSequenceNode ssnode, List<KStar.ScoredSequence> scoredSequences) {

		KStarScore kstarScore = ssnode.makeKStarScore();
		scoredSequences.add(new KStar.ScoredSequence(ssnode.sequence, kstarScore));

		kstarSettings.scoreWriters.writeScore(new KStarScoreWriter.ScoreInfo(
			scoredSequences.size() - 1,
			bbkstarSettings.numBestSequences,
			ssnode.sequence,
			complex.confSpace,
			kstarScore
		));
	}
}