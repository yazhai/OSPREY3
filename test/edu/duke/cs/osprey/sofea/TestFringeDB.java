package edu.duke.cs.osprey.sofea;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import edu.duke.cs.osprey.TestBase.TempFile;
import edu.duke.cs.osprey.confspace.MultiStateConfSpace;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.confspace.Strand;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.structure.PDBIO;
import edu.duke.cs.osprey.tools.MathTools;
import edu.duke.cs.osprey.tools.MathTools.BigDecimalBounds;
import org.junit.Test;

import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;


public class TestFringeDB {

	private static MathContext mathContext = new MathContext(16, RoundingMode.HALF_UP);

	@Test
	public void sizes() {
		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("fringe.db")) {

			// create a new FringeDB
			try (FringeDB db = FringeDB.create(confSpace, file, 1024, mathContext)) {
				assertThat(file.length(), is(1024L));
				assertThat(db.getNumNodes(), is(0L));
				assertThat(db.getCapacity(), is(21L));
			}

			// re-open the existing FringeDB
			try (FringeDB db = FringeDB.open(confSpace, file)) {
				assertThat(file.length(), is(1024L));
				assertThat(db.getNumNodes(), is(0L));
				assertThat(db.getCapacity(), is(21L));
			}
		}
	}

	@Test
	public void rootsSweepConsume() {

		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("fringe.db")) {
			try (FringeDB db = FringeDB.create(confSpace, file, 1024, mathContext)) {

				assertThat(db.getZMax(confSpace.states.get(0)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(1)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));

				// add the root nodes
				FringeDB.Roots roots = db.roots();
				roots.set(
					confSpace.states.get(0),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(0.0),
						MathTools.biggen(1024.5)
					),
					MathTools.biggen(4.2)
				);
				roots.set(
					confSpace.states.get(1),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(5.2),
						MathTools.biggen(10.4)
					),
					MathTools.biggen(1.3)
				);
				roots.set(
					confSpace.states.get(2),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(4.2),
						MathTools.biggen(7.3)
					),
					MathTools.biggen(3.6)
				);

				assertThat(roots.hasSpaceToCommit(), is(true));
				roots.commit();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(3L));

				// do a sweep and consume all the nodes
				FringeDB.Sweep sweep = db.sweep();
				while (!sweep.isEmpty()) {

					sweep.read();
					assertThat(sweep.state().index, is(0));
					assertThat(sweep.index().numDefined, is(0));
					assertThat(sweep.bounds(), is(new BigDecimalBounds(
						MathTools.biggen(0.0),
						MathTools.biggen(1024.5)
					)));
					assertThat(sweep.zpath(), is(MathTools.biggen(4.2)));
					sweep.commitAndAdvance();

					sweep.read();
					assertThat(sweep.state().index, is(1));
					assertThat(sweep.index().numDefined, is(0));
					assertThat(sweep.bounds(), is(new BigDecimalBounds(
						MathTools.biggen(5.2),
						MathTools.biggen(10.4)
					)));
					assertThat(sweep.zpath(), is(MathTools.biggen(1.3)));
					sweep.commitAndAdvance();

					sweep.read();
					assertThat(sweep.state().index, is(2));
					assertThat(sweep.index().numDefined, is(0));
					assertThat(sweep.bounds(), is(new BigDecimalBounds(
						MathTools.biggen(4.2),
						MathTools.biggen(7.3)
					)));
					assertThat(sweep.zpath(), is(MathTools.biggen(3.6)));
					sweep.commitAndAdvance();
				}
				sweep.finish();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(1)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));
				assertThat(db.getNumNodes(), is(0L));
			}
		}
	}

	@Test
	public void rootsSweepKeep() {

		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("fringe.db")) {
			try (FringeDB db = FringeDB.create(confSpace, file, 1024, mathContext)) {

				assertThat(db.getZMax(confSpace.states.get(0)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(1)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));

				// add the root nodes
				FringeDB.Roots roots = db.roots();
				roots.set(
					confSpace.states.get(0),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(0.0),
						MathTools.biggen(1024.5)
					),
					MathTools.biggen(4.2)
				);
				roots.set(
					confSpace.states.get(1),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(5.2),
						MathTools.biggen(10.4)
					),
					MathTools.biggen(1.3)
				);
				roots.set(
					confSpace.states.get(2),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(4.2),
						MathTools.biggen(7.3)
					),
					MathTools.biggen(3.6)
				);

				assertThat(roots.hasSpaceToCommit(), is(true));
				roots.commit();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(3L));

				// do a sweep and keep all the nodes
				FringeDB.Sweep sweep = db.sweep();

				sweep.read();
				assertThat(sweep.state().index, is(0));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(0.0),
					MathTools.biggen(1024.5)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(4.2)));
				sweep.rollbackAndAdvance();

				sweep.read();
				assertThat(sweep.state().index, is(1));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(5.2),
					MathTools.biggen(10.4)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(1.3)));
				sweep.rollbackAndAdvance();

				sweep.read();
				assertThat(sweep.state().index, is(2));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(4.2),
					MathTools.biggen(7.3)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(3.6)));
				sweep.rollbackAndAdvance();

				sweep.finish();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(3L));
			}
		}
	}

	@Test
	public void rootsResumeSweepConsume() {

		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("fringe.db")) {

			// make a new DB with root nodes
			try (FringeDB db = FringeDB.create(confSpace, file, 1024, mathContext)) {
				FringeDB.Roots roots = db.roots();
				roots.set(
					confSpace.states.get(0),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(0.0),
						MathTools.biggen(1024.5)
					),
					MathTools.biggen(4.2)
				);
				roots.set(
					confSpace.states.get(1),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(5.2),
						MathTools.biggen(10.4)
					),
					MathTools.biggen(1.3)
				);
				roots.set(
					confSpace.states.get(2),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(4.2),
						MathTools.biggen(7.3)
					),
					MathTools.biggen(3.6)
				);
				roots.commit();
			}

			// open the DB and consume all the nodes
			try (FringeDB db = FringeDB.open(confSpace, file)) {

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(3L));

				FringeDB.Sweep sweep = db.sweep();

				sweep.read();
				assertThat(sweep.state().index, is(0));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(0.0),
					MathTools.biggen(1024.5)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(4.2)));
				sweep.commitAndAdvance();

				sweep.read();
				assertThat(sweep.state().index, is(1));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(5.2),
					MathTools.biggen(10.4)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(1.3)));
				sweep.commitAndAdvance();

				sweep.read();
				assertThat(sweep.state().index, is(2));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(4.2),
					MathTools.biggen(7.3)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(3.6)));
				sweep.commitAndAdvance();

				sweep.finish();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(1)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));
				assertThat(db.getNumNodes(), is(0L));
			}

			// we should have an empty DB again
			try (FringeDB db = FringeDB.open(confSpace, file)) {
				assertThat(db.getZMax(confSpace.states.get(0)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(1)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));
				assertThat(db.getNumNodes(), is(0L));
			}
		}
	}

	@Test
	public void rootsSweepResume() {

		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("fringe.db")) {

			// make a new DB with root nodes
			try (FringeDB db = FringeDB.create(confSpace, file, 1024, mathContext)) {
				FringeDB.Roots roots = db.roots();
				roots.set(
					confSpace.states.get(0),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(0.0),
						MathTools.biggen(1024.5)
					),
					MathTools.biggen(4.2)
				);
				roots.set(
					confSpace.states.get(1),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(5.2),
						MathTools.biggen(10.4)
					),
					MathTools.biggen(1.3)
				);
				roots.set(
					confSpace.states.get(2),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(4.2),
						MathTools.biggen(7.3)
					),
					MathTools.biggen(3.6)
				);
				roots.commit();
			}

			// open the DB and consume one node
			try (FringeDB db = FringeDB.open(confSpace, file)) {

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(3L));

				FringeDB.Sweep sweep = db.sweep();
				assertThat(sweep.numNodesRemaining(), is(3L));

				sweep.read();
				assertThat(sweep.state().index, is(0));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(0.0),
					MathTools.biggen(1024.5)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(4.2)));
				sweep.commitAndAdvance();

				assertThat(sweep.numNodesRemaining(), is(2L));

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(2L));
			}

			// open the DB and rollback one node
			try (FringeDB db = FringeDB.open(confSpace, file)) {

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(2L));

				FringeDB.Sweep sweep = db.sweep();
				assertThat(sweep.numNodesRemaining(), is(2L));

				sweep.read();
				assertThat(sweep.state().index, is(1));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(5.2),
					MathTools.biggen(10.4)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(1.3)));
				sweep.rollbackAndAdvance();

				assertThat(sweep.numNodesRemaining(), is(1L));

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(2L));
			}

			// open the DB and consume one node
			try (FringeDB db = FringeDB.open(confSpace, file)) {

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(2L));

				FringeDB.Sweep sweep = db.sweep();
				assertThat(sweep.numNodesRemaining(), is(1L));

				sweep.read();
				assertThat(sweep.state().index, is(2));
				assertThat(sweep.index().numDefined, is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(4.2),
					MathTools.biggen(7.3)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(3.6)));
				sweep.commitAndAdvance();

				assertThat(sweep.numNodesRemaining(), is(0L));

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(1L));
			}

			// open the DB and finish the sweep
			try (FringeDB db = FringeDB.open(confSpace, file)) {

				FringeDB.Sweep sweep = db.sweep();
				assertThat(sweep.numNodesRemaining(), is(0L));
				sweep.finish();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));
				assertThat(db.getNumNodes(), is(1L));
			}

			// check the next sweep
			try (FringeDB db = FringeDB.open(confSpace, file)) {

				FringeDB.Sweep sweep = db.sweep();
				assertThat(sweep.numNodesRemaining(), is(1L));

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(nullValue()));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));
				assertThat(db.getNumNodes(), is(1L));
			}
		}
	}

	@Test
	public void twoLevels() {

		MultiStateConfSpace confSpace = makeConfSpace();

		try (TempFile file = new TempFile("fringe.db")) {
			try (FringeDB db = FringeDB.create(confSpace, file, 1024, mathContext)) {

				// add the root nodes
				FringeDB.Roots roots = db.roots();
				roots.set(
					confSpace.states.get(0),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(0.0),
						MathTools.biggen(1024.5)
					),
					MathTools.biggen(4.2)
				);
				roots.set(
					confSpace.states.get(1),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(5.2),
						MathTools.biggen(10.4)
					),
					MathTools.biggen(1.3)
				);
				roots.set(
					confSpace.states.get(2),
					new MathTools.BigDecimalBounds(
						MathTools.biggen(4.2),
						MathTools.biggen(7.3)
					),
					MathTools.biggen(3.6)
				);
				roots.commit();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(1024.5)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(10.4)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(MathTools.biggen(7.3)));
				assertThat(db.getNumNodes(), is(3L));

				// do a sweep and replace the roots with children
				FringeDB.Sweep sweep = db.sweep();
				while (!sweep.isEmpty()) {

					sweep.read();
					assertThat(sweep.state().index, is(0));
					sweep.addChild(
						sweep.index().assign(0, 0),
						new MathTools.BigDecimalBounds(
							MathTools.biggen(10.4),
							MathTools.biggen(35.2)
						),
						MathTools.biggen(19.9)
					);
					sweep.commitAndAdvance();

					sweep.read();
					assertThat(sweep.state().index, is(1));
					sweep.addChild(
						sweep.index().assign(0, 1),
						new MathTools.BigDecimalBounds(
							MathTools.biggen(93.8),
							MathTools.biggen(102.3)
						),
						MathTools.biggen(38.5)
					);
					sweep.addChild(
						sweep.index().assign(1, 0),
						new MathTools.BigDecimalBounds(
							MathTools.biggen(69.2),
							MathTools.biggen(74.1)
						),
						MathTools.biggen(20.8)
					);
					sweep.commitAndAdvance();

					sweep.read();
					assertThat(sweep.state().index, is(2));
					sweep.commitAndAdvance();
				}
				sweep.finish();

				// check db state
				assertThat(db.getZMax(confSpace.states.get(0)), is(MathTools.biggen(35.2)));
				assertThat(db.getZMax(confSpace.states.get(1)), is(MathTools.biggen(102.3)));
				assertThat(db.getZMax(confSpace.states.get(2)), is(nullValue()));
				assertThat(db.getNumNodes(), is(3L));

				// sweep the children and consume them
				sweep = db.sweep();

				sweep.read();
				assertThat(sweep.state().index, is(0));
				assertThat(sweep.index().numDefined, is(1));
				assertThat(sweep.index().definedPos[0], is(0));
				assertThat(sweep.index().definedRCs[0], is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(10.4),
					MathTools.biggen(35.2)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(19.9)));
				sweep.commitAndAdvance();

				sweep.read();
				assertThat(sweep.state().index, is(1));
				assertThat(sweep.index().numDefined, is(1));
				assertThat(sweep.index().definedPos[0], is(0));
				assertThat(sweep.index().definedRCs[0], is(1));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(93.8),
					MathTools.biggen(102.3)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(38.5)));
				sweep.commitAndAdvance();

				sweep.read();
				assertThat(sweep.state().index, is(1));
				assertThat(sweep.index().numDefined, is(1));
				assertThat(sweep.index().definedPos[0], is(1));
				assertThat(sweep.index().definedRCs[0], is(0));
				assertThat(sweep.bounds(), is(new BigDecimalBounds(
					MathTools.biggen(69.2),
					MathTools.biggen(74.1)
				)));
				assertThat(sweep.zpath(), is(MathTools.biggen(20.8)));
				sweep.commitAndAdvance();
			}
		}
	}

	private static MultiStateConfSpace makeConfSpace() {

		Molecule pdb = PDBIO.readResource("/1CC8.ss.pdb");

		Strand design = new Strand.Builder(pdb)
			.setResidues("A68", "A73")
			.build();
		for (String resNum : Arrays.asList("A68", "A69")) {
			design.flexibility.get(resNum)
				.setLibraryRotamers(Strand.WildType)
				.addWildTypeRotamers()
				.setContinuous();
		}

		Strand target = new Strand.Builder(pdb)
			.setResidues("A2", "A67")
			.build();
		for (String resNum : Arrays.asList("A5", "A6")) {
			target.flexibility.get(resNum)
				.setLibraryRotamers(Strand.WildType)
				.addWildTypeRotamers()
				.setContinuous();
		}

		// make a multi-state conf space
		return new MultiStateConfSpace
			.Builder("design", new SimpleConfSpace.Builder().addStrands(design).build())
			.addSequencedState("complex", new SimpleConfSpace.Builder().addStrands(design, target).build())
			.addState("target", new SimpleConfSpace.Builder().addStrands(target).build())
			.build();
	}
}
