/*
** This file is part of OSPREY 3.0
** 
** OSPREY Protein Redesign Software Version 3.0
** Copyright (C) 2001-2018 Bruce Donald Lab, Duke University
** 
** OSPREY is free software: you can redistribute it and/or modify
** it under the terms of the GNU General Public License version 2
** as published by the Free Software Foundation.
** 
** You should have received a copy of the GNU General Public License
** along with OSPREY.  If not, see <http://www.gnu.org/licenses/>.
** 
** OSPREY relies on grants for its development, and since visibility
** in the scientific literature is essential for our success, we
** ask that users of OSPREY cite our papers. See the CITING_OSPREY
** document in this distribution for more information.
** 
** Contact Info:
**    Bruce Donald
**    Duke University
**    Department of Computer Science
**    Levine Science Research Center (LSRC)
**    Durham
**    NC 27708-0129
**    USA
**    e-mail: www.cs.duke.edu/brd/
** 
** <signature of Bruce Donald>, Mar 1, 2018
** Bruce Donald, Professor of Computer Science
*/

package edu.duke.cs.osprey.ematrix;

import java.util.List;

import edu.duke.cs.osprey.confspace.ConfSpace;
import edu.duke.cs.osprey.energy.forcefield.ForcefieldParams;
import edu.duke.cs.osprey.structure.Residue;

public class ExampleParallelEmat {
	
	@SuppressWarnings("unused")
	private static EnergyMatrix calcEmat(int numThreads, ForcefieldParams ffparams, ConfSpace confSpace, List<Residue> shellResidues) {
		
		// make the energy matrix calculator
		SimpleEnergyMatrixCalculator.Cpu ematcalc = new SimpleEnergyMatrixCalculator.Cpu(numThreads, ffparams, confSpace, shellResidues);
		// NOTE: the super tiny energy matrix minimizations run slowly on the GPU, so just use the CPU for now
		
		// calculate the emat
		EnergyMatrix emat = ematcalc.calcEnergyMatrix();
		
		// don't forget to cleanup so we don't leave extra threads hanging around
		ematcalc.cleanup();
		
		return emat;
	}
}
