package edu.duke.cs.osprey.kstar;

import static edu.duke.cs.osprey.TestBase.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import edu.duke.cs.osprey.astar.conf.ConfAStarTree;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.confspace.Strand;
import edu.duke.cs.osprey.ematrix.SimpleReferenceEnergies;
import edu.duke.cs.osprey.ematrix.SimplerEnergyMatrixCalculator;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.energy.EnergyCalculator;
import edu.duke.cs.osprey.energy.forcefield.ForcefieldParams;
import edu.duke.cs.osprey.gmec.ConfSearchFactory;
import edu.duke.cs.osprey.kstar.pfunc.PartitionFunction;
import edu.duke.cs.osprey.parallelism.Parallelism;
import edu.duke.cs.osprey.restypes.ResidueTemplateLibrary;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBIO;
import org.junit.Test;

import java.io.File;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

public class TestKStar {

	private static class Strands {
		public Strand protein;
		public Strand ligand;
	}

	private static Strands make2RL0(ForcefieldParams ffparams) {

		// choose a molecule
		Molecule mol = PDBIO.readFile("examples/python.KStar/2RL0.min.reduce.pdb");

		// make sure all strands share the same template library
		ResidueTemplateLibrary templateLib = new ResidueTemplateLibrary.Builder(ffparams.forcefld)
				.addMoleculeForWildTypeRotamers(mol)
				.build();

		Strands strands = new Strands();

		// define the protein strand
		strands.protein = new Strand.Builder(mol)
				.setTemplateLibrary(templateLib)
				.setResidues(648, 654)
				.build();
		strands.protein.flexibility.get(649).setLibraryRotamers("TYR", "ALA", "VAL", "ILE", "LEU").addWildTypeRotamers().setContinuous();
		strands.protein.flexibility.get(650).setLibraryRotamers("ASP", "GLU").addWildTypeRotamers().setContinuous();
		strands.protein.flexibility.get(651).setLibraryRotamers("GLU", "ASP").addWildTypeRotamers().setContinuous();
		strands.protein.flexibility.get(654).setLibraryRotamers("THR", "SER", "ASN", "GLN").addWildTypeRotamers().setContinuous();

		// define the ligand strand
		strands.ligand = new Strand.Builder(mol)
				.setTemplateLibrary(templateLib)
				.setResidues(155, 194)
				.build();
		strands.ligand.flexibility.get(156).setLibraryRotamers("PHE", "TYR", "ALA", "VAL", "ILE", "LEU").addWildTypeRotamers().setContinuous();
		strands.ligand.flexibility.get(172).setLibraryRotamers("LYS", "ASP", "GLU").addWildTypeRotamers().setContinuous();
		strands.ligand.flexibility.get(192).setLibraryRotamers("ILE", "ALA", "VAL", "LEU", "PHE", "TYR").addWildTypeRotamers().setContinuous();
		strands.ligand.flexibility.get(193).setLibraryRotamers("THR", "SER", "ASN").addWildTypeRotamers().setContinuous();

		return strands;
	}

	private static KStar runKStar(ForcefieldParams ffparams, Strands strands, double epsilon) {

		AtomicReference<KStar> kstarRef = new AtomicReference<>(null);

		// make the conf space
		SimpleConfSpace confSpace = new SimpleConfSpace.Builder()
			.addStrand(strands.protein)
			.addStrand(strands.ligand)
			.build();

		// TEMP
		Parallelism parallelism = Parallelism.makeCpu(4);
		//Parallelism parallelism = Parallelism.make(4, 1, 4);

		// how should we compute energies of molecules?
		new EnergyCalculator.Builder(confSpace, ffparams)
			.setParallelism(parallelism)
			.use((ecalc) -> {

				// how should we define energies of conformations?
				KStar.ConfEnergyCalculatorFactory confEcalcFactory = (confSpaceArg, ecalcArg) -> {
					SimpleReferenceEnergies eref = new SimplerEnergyMatrixCalculator.Builder(confSpaceArg, ecalcArg)
						.build()
						.calcReferenceEnergies();
					return new ConfEnergyCalculator.Builder(confSpaceArg, ecalcArg)
						.setReferenceEnergies(eref)
						.build();
				};

				// how should confs be ordered and searched?
				ConfSearchFactory confSearchFactory = (emat, pmat) -> {
					return new ConfAStarTree.Builder(emat, pmat)
						.setTraditional()
						.build();
				};

				// run K*
				KStar.Settings settings = new KStar.Settings.Builder()
					.setEpsilon(epsilon)
					.addScoreConsoleWriter()
					.addScoreFileWriter(new File("kstar.scores.tsv"))
					.build();
				KStar kstar = new KStar(strands.protein, strands.ligand, confSpace, ecalc, confEcalcFactory, confSearchFactory, settings);
				kstar.run();

				// pass back the ref
				kstarRef.set(kstar);
			});

		return kstarRef.get();
	}

	@Test
	public void test2RL0() {

		ForcefieldParams ffparams = new ForcefieldParams();
		Strands strands = make2RL0(ffparams);
		double epsilon = 0.99;
		KStar kstar = runKStar(ffparams, strands, epsilon);

		// check the results (e = 0.683)
		// TODO: get real numbers from the old K* code
		assertSequence(kstar, 0, "PHE ASP GLU THR", "PHE LYS ILE THR", "PHE ASP GLU THR PHE LYS ILE THR", 2.748776e+04, 4.007259e+30, 4.138330e+50, 3.756975e+15, epsilon);
	}

	private void assertSequence(KStar kstar, int sequenceIndex, String proteinSequence, String ligandSequence, String complexSequence, double proteinQStar, double ligandQStar, double complexQStar, Double kstarScore, double epsilon) {

		// check the sequences
		assertThat(kstar.proteinInfo.sequences.get(sequenceIndex), is(new KStar.Sequence(proteinSequence)));
		assertThat(kstar.ligandInfo.sequences.get(sequenceIndex), is(new KStar.Sequence(ligandSequence)));
		assertThat(kstar.complexInfo.sequences.get(sequenceIndex), is(new KStar.Sequence(complexSequence)));

		PartitionFunction.Result proteinResult = kstar.proteinInfo.pfuncResults.get(kstar.proteinInfo.sequences.get(sequenceIndex));
		PartitionFunction.Result ligandResult = kstar.ligandInfo.pfuncResults.get(kstar.ligandInfo.sequences.get(sequenceIndex));
		PartitionFunction.Result complexResult = kstar.complexInfo.pfuncResults.get(kstar.complexInfo.sequences.get(sequenceIndex));

		// check q* values
		assertQStar(proteinResult.values, proteinQStar, epsilon);
		assertQStar(ligandResult.values, ligandQStar, epsilon);
		assertQStar(complexResult.values, complexQStar, epsilon);

		// check K* score
		if (kstarScore != null) {
			assertThat(proteinResult.status, is(PartitionFunction.Status.Estimated));
			assertThat(ligandResult.status, is(PartitionFunction.Status.Estimated));
			assertThat(complexResult.status, is(PartitionFunction.Status.Estimated));
			assertThat(PartitionFunction.Result.calcKStarScore(proteinResult, ligandResult, complexResult).doubleValue(), isRelatively(kstarScore, 0.1));
		} else {
			assertThat(proteinResult.status == PartitionFunction.Status.Estimated
				&& ligandResult.status == PartitionFunction.Status.Estimated
				&& complexResult.status == PartitionFunction.Status.Estimated,
				is(false));
		}
	}

	private void assertQStar(PartitionFunction.Values values, double proteinQStar, double epsilon) {

		// check epsilon
		assertThat(values.getEffectiveEpsilon(), lessThanOrEqualTo(epsilon));

		// q* should be within epsilon of the target (a lower-epsilon calculation of q*)
		double minQStar = new BigDecimal(proteinQStar).doubleValue()*(1.0 - epsilon);
		assertThat(values.qstar.doubleValue(), greaterThanOrEqualTo(minQStar));
	}
}
