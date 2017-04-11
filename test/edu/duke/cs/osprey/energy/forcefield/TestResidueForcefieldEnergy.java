package edu.duke.cs.osprey.energy.forcefield;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import edu.duke.cs.osprey.TestBase;
import edu.duke.cs.osprey.dof.ProlinePucker;
import edu.duke.cs.osprey.dof.ResidueTypeDOF;
import edu.duke.cs.osprey.energy.ResidueInteractions;
import edu.duke.cs.osprey.energy.forcefield.TestForcefieldEnergy.Residues;
import edu.duke.cs.osprey.parallelism.Parallelism;
import edu.duke.cs.osprey.structure.AtomConnectivity;
import edu.duke.cs.osprey.structure.Residue;

public class TestResidueForcefieldEnergy extends TestBase {
	
	private static enum EfuncType {
		
		AllPairs {
			@Override
			public ResidueForcefieldEnergy makeff(List<Residue> residues, ForcefieldParams ffparams, AtomConnectivity connectivity) {
				ResidueInteractions inters = new ResidueInteractions();
				for (int pos1=0; pos1<residues.size(); pos1++) {
					inters.addSingle(residues.get(pos1).getPDBResNumber());
					for (int pos2=0; pos2<pos1; pos2++) {
						inters.addPair(residues.get(pos1).getPDBResNumber(), residues.get(pos2).getPDBResNumber());
					}
				}
				return new ResidueForcefieldEnergy(ffparams, inters, residues, connectivity);
			}
		},
		SingleAndShell {
			@Override
			public ResidueForcefieldEnergy makeff(List<Residue> residues, ForcefieldParams ffparams, AtomConnectivity connectivity) {
				ResidueInteractions inters = new ResidueInteractions();
				inters.addSingle(residues.get(0).getPDBResNumber());
				for (int pos1=1; pos1<residues.size(); pos1++) {
					inters.addPair(residues.get(0).getPDBResNumber(), residues.get(pos1).getPDBResNumber());
				}
				return new ResidueForcefieldEnergy(ffparams, inters, residues, connectivity);
			}
		};
		
		public abstract ResidueForcefieldEnergy makeff(List<Residue> residues, ForcefieldParams ffparams, AtomConnectivity connectivity);
	}
	
	private void checkEnergies(Residues r, List<Residue> residues, double allPairsEnergy, double singleAndShellEnergy) {
		
		Map<EfuncType,Double> expectedEnergies = new EnumMap<>(EfuncType.class);
		expectedEnergies.put(EfuncType.AllPairs, allPairsEnergy);
		expectedEnergies.put(EfuncType.SingleAndShell, singleAndShellEnergy);
		
		checkEnergies(r, residues, expectedEnergies);
	}
	
	private void checkEnergies(Residues r, List<Residue> residues, Map<EfuncType,Double> expectedEnergies) {
		
		ForcefieldParams ffparams = new ForcefieldParams();
		AtomConnectivity connectivity = new AtomConnectivity.Builder()
			.setResidues(residues)
			.setParallelism(Parallelism.makeCpu(4))
			.build();
		
		for (EfuncType type : EfuncType.values()) {
			ResidueForcefieldEnergy efunc = type.makeff(residues, ffparams, connectivity);
			assertThat(type.toString(), efunc.getEnergy(), isAbsolutely(expectedEnergies.get(type), 1e-10));
		}
	}
	
	@Test
	public void testSingleGly() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly15 );
		checkEnergies(r, residues, -4.572136255843063, -4.572136255843063);
	}
	
	@Test
	public void testGlyPair() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly06, r.gly15 );
		checkEnergies(r, residues, -9.17380398335906, -4.601667727515996);
	}
	
	@Test
	public void testGlySerPair() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly15, r.ser17 );
		checkEnergies(r, residues, -9.48559560659799, -2.6911081922156552);
	}
	
	@Test
	public void testTrpPair() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.trp18, r.trp25 );
		checkEnergies(r, residues, -12.625574526252965, -6.218018599252964);
	}
	
	@Test
	public void test4Residues() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly15, r.ser17, r.trp18, r.trp25 );
		checkEnergies(r, residues, -23.31199205572296, -2.756905624257449);
	}
	
	@Test
	public void test6Residues() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly15, r.ser17, r.trp18, r.trp25, r.arg22, r.ala24 );
		checkEnergies(r, residues, -52.316176530733166, -2.7906943839799343);
	}
	
	@Test
	public void test10Residues() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly15, r.ser17, r.trp18, r.trp25, r.arg22, r.ala24, r.ile26, r.phe31, r.arg32, r.glu34 );
		checkEnergies(r, residues, -93.33337795127768, -2.7991581273906516);
	}
	
	@Test
	public void test14Residues() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly15, r.ser17, r.trp18, r.trp25, r.arg22, r.ala24, r.ile26, r.phe31, r.arg32, r.glu34, r.val36, r.leu39, r.trp47, r.leu48 );
		checkEnergies(r, residues, -112.44246575304817, -2.799964297346741);
	}
	
	@Test
	public void test24Residues() {
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList(
			r.gly06, r.gly15, r.ser17, r.trp18, r.trp25, r.arg22, r.ala24, r.ile26, r.phe31, r.arg32, r.glu34, r.val36,
			r.leu39, r.trp47, r.leu48, r.ile53, r.arg55, r.val56, r.leu57, r.ile59, r.val62, r.leu64, r.val65, r.met66
		);
		checkEnergies(r, residues, -163.74206898485193, -4.612537058185951);
	}
	
	@Test
	public void testBrokenProline() {
		
		Residues r = new Residues();
		List<Residue> residues = Arrays.asList( r.gly15 );
		
		// mutate to a proline, which will be broken at this pos
		Residue res = r.gly15;
		res.pucker = new ProlinePucker(r.strand.templateLib, res);
		ResidueTypeDOF.switchToTemplate(r.strand.templateLib, res, "PRO");
		
		assertThat(res.confProblems.size(), is(1));
		checkEnergies(r, residues, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
	}
	
	private double calcEnergy(List<Residue> residues, ResidueInteractions inters) {
		
		AtomConnectivity connectivity = new AtomConnectivity.Builder()
			.setResidues(residues)
			.build();
		
		return new ResidueForcefieldEnergy(new ForcefieldParams(), inters, residues, connectivity).getEnergy();
	}
	
	@Test
	public void oneIntraWeight() {
		
		Residues r = new Residues();
		ResidueInteractions inters;
		
		// check base value
		final double baseEnergy = -4.5721362558430645;
		inters = new ResidueInteractions();
		inters.addSingle(r.gly15.getPDBResNumber(), 1, 0);
		assertThat(calcEnergy(Arrays.asList(r.gly15), inters), isAbsolutely(baseEnergy));
		
		// test weight
		for (double weight : Arrays.asList(-0.5, -2.0, -1.0, 0.0, 0.5, 2.0)) {
			inters = new ResidueInteractions();
			inters.addSingle(r.gly15.getPDBResNumber(), weight, 0);
			assertThat("weight: " + weight, calcEnergy(Arrays.asList(r.gly15), inters), isAbsolutely(baseEnergy*weight));
		}
	}
	
	@Test
	public void oneIntraOffset() {
		
		Residues r = new Residues();
		ResidueInteractions inters;
		
		// check base value
		final double baseEnergy = -4.5721362558430645;
		inters = new ResidueInteractions();
		inters.addSingle(r.gly15.getPDBResNumber(), 1, 0);
		assertThat(calcEnergy(Arrays.asList(r.gly15), inters), isAbsolutely(baseEnergy));
		
		// test weight
		for (double offset : Arrays.asList(-0.5, -2.0, -1.0, 0.0, 0.5, 2.0)) {
			inters = new ResidueInteractions();
			inters.addSingle(r.gly15.getPDBResNumber(), 1, offset);
			assertThat("offset: " + offset, calcEnergy(Arrays.asList(r.gly15), inters), isAbsolutely(baseEnergy + offset));
		}
	}
}
