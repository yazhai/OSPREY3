package edu.duke.cs.osprey.gmec;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.PriorityQueue;

import edu.duke.cs.osprey.astar.conf.ConfAStarTree;
import edu.duke.cs.osprey.confspace.ConfSearch;
import edu.duke.cs.osprey.confspace.ConfSearch.EnergiedConf;
import edu.duke.cs.osprey.confspace.ConfSearch.ScoredConf;
import edu.duke.cs.osprey.confspace.SimpleConfSpace;
import edu.duke.cs.osprey.energy.ConfEnergyCalculator;
import edu.duke.cs.osprey.gmec.GMECFinder.ConfPruner;
import edu.duke.cs.osprey.tools.Progress;
import edu.duke.cs.osprey.tools.Stopwatch;

/**
 * Searches a conformation space for the single conformation that minimizes the
 * desired energy function, ie the Global Minimum Energy Conformation, or GMEC.
 * 
 * The search is performed by ordering all conformations in the conformation space
 * using A* search, and evaluating the energy of each conformation in order.
 * 
 * The search terminates returns the lowest-energy conformation found so far
 * when it can be proven that all remaining conformations in the conformation
 * space must have higher energy.
 */
public class SimpleGMECFinder {
	
	public static class Builder {
		
		private SimpleConfSpace space;
		
		/** A* implementation to sort conformations in the conformation space. */
		private ConfSearch search;
		
		/** Calculates the energy for a conformation. */
		private ConfEnergyCalculator.Async ecalc;
		private ConfPruner pruner;
		private ConfPrinter logPrinter;
		private ConfPrinter consolePrinter;
		
		/** True to print all conformations found during A* search to the console */ 
		private boolean printIntermediateConfsToConsole = false;
		
		public Builder(SimpleConfSpace space, ConfSearch search, ConfEnergyCalculator ecalc) {
			this(space, search, new ConfEnergyCalculator.Async.Adapter(ecalc));
		}
		
		public Builder(SimpleConfSpace space, ConfSearch search, ConfEnergyCalculator.Async ecalc) {
			this.space = space;
			this.search = search;
			this.ecalc = ecalc;
			this.pruner = null;
			this.logPrinter = new ConfPrinter.Nop();
			this.consolePrinter = new ConsoleConfPrinter();
		}
		
		/* TODO: integrate PartCR
		public Builder setConfPruner(ConfPruner val) {
			pruner = val;
			return this;
		}
		*/
		
		public Builder setLogPrinter(ConfPrinter val) {
			logPrinter = val;
			return this;
		}
		
		public Builder setConsolePrinter(ConfPrinter val) {
			consolePrinter = val;
			return this;
		}
		
		public Builder setPrintIntermediateConfsToConsole(boolean val) {
			printIntermediateConfsToConsole = val;
			return this;
		}
		
		public SimpleGMECFinder build() {
			return new SimpleGMECFinder(
				space,
				search,
				ecalc,
				pruner,
				logPrinter,
				consolePrinter,
				printIntermediateConfsToConsole
			);
		}
	}
	
	public final SimpleConfSpace space;
	public final ConfSearch search;
	public final ConfEnergyCalculator.Async ecalc;
	public final ConfPruner pruner;
	public final ConfPrinter logPrinter;
	public final ConfPrinter consolePrinter;
	public final boolean printIntermediateConfsToConsole;
	
	private SimpleGMECFinder(SimpleConfSpace space, ConfSearch search, ConfEnergyCalculator.Async ecalc, ConfPruner pruner, ConfPrinter logPrinter, ConfPrinter consolePrinter, boolean printIntermediateConfsToConsole) {
		this.space = space;
		this.search = search;
		this.ecalc = ecalc;
		this.pruner = pruner;
		this.logPrinter = logPrinter;
		this.consolePrinter = consolePrinter;
		this.printIntermediateConfsToConsole = printIntermediateConfsToConsole;
	}
	
	public EnergiedConf find() {
		
		Deque<EnergiedConf> confs = find(0);
		
		if (!confs.isEmpty()) {
			return confs.peek();
		}
		
		return null;
	}
	
	public Deque<EnergiedConf> find(double energyWindowSize) {
		
		// start searching for the min score conf
		System.out.println("Searching for min score conformation...");
		Stopwatch minScoreStopwatch = new Stopwatch().start();
		try {
			System.out.println("\t(among " + search.getNumConformations().floatValue() + " possibilities)");
		} catch (UnsupportedOperationException ex) {
			// conf search doesn't support it, no big deal
		}
		ScoredConf minScoreConf = search.nextConf();
		if (minScoreConf == null) {
			
			// no confs in the search space, can't recover, just bail
			return new ArrayDeque<>();
		}
		System.out.println("Found min score conformation in " + minScoreStopwatch.getTime(1));
		
		// evaluate the min score conf
		System.out.println("Computing energy of min score conf...");
		EnergiedConf eMinScoreConf = ecalc.calcEnergy(minScoreConf);
		logPrinter.print(eMinScoreConf, space);
		
		// peek ahead to the next conf
		ConfSearch.Splitter splitter = new ConfSearch.Splitter(search);
		ConfSearch unpeekedConfs = splitter.makeStream();
		ScoredConf peekedConf = splitter.makeStream().nextConf();
		
		// do we need to check more confs?
		if (peekedConf == null) {
			
			// nope, there's no more confs, so we already have the GMEC
			System.out.println("Found GMEC! (it's actually the only conformation allowed by the conf space!)");
			consolePrinter.print(eMinScoreConf, space);
			return new ArrayDeque<>(Arrays.asList(eMinScoreConf));
			
		} else if (peekedConf.getScore() > eMinScoreConf.getEnergy() && energyWindowSize <= 0) {
			
			// nope, no confs have lower energy and we're not doing an energy window
			System.out.println("Found GMEC! (it's actually the min score conformation too!)");
			consolePrinter.print(eMinScoreConf, space);
			return new ArrayDeque<>(Arrays.asList(eMinScoreConf));
		
		} else {
			
			// yup, need to keep searching for the GMEC, or to enumerate an energy window
			// but drop the min score conf on the console before moving on
			consolePrinter.print(eMinScoreConf, space);
			
			// estimate the top of our energy range
			// this is an upper bound for now, we'll refine it as we evaluate more structures
			final EnergyRange erange = new EnergyRange(eMinScoreConf.getEnergy(), energyWindowSize);
		
			// start the list of energied confs
			PriorityQueue<EnergiedConf> econfs = new PriorityQueue<EnergiedConf>(
				(EnergiedConf a, EnergiedConf b) -> Double.compare(a.getEnergy(), b.getEnergy())
			);
			econfs.add(eMinScoreConf);
		
			checkMoreConfs(unpeekedConfs, erange, econfs);
			
			// econfs is sorted now, so the first one is the GMEC
			EnergiedConf gmec = econfs.peek();
			System.out.println("\nFound GMEC!");
			consolePrinter.print(gmec, space);
			
			// return just the confs in the energy window
			Deque<EnergiedConf> econfsInRange = new ArrayDeque<>();
			while (!econfs.isEmpty()) {
				EnergiedConf econf = econfs.poll();
				if (erange.contains(econf.getEnergy())) {
					econfsInRange.add(econf);
				}
			}
			
			System.out.println(String.format("Found %d total conformations in energy window", econfsInRange.size()));
			
			return econfsInRange;
		}
	}
	
	private void checkMoreConfs(ConfSearch search, EnergyRange erange, PriorityQueue<EnergiedConf> econfs) {
		
		setErangeProgress(search, erange);
		
		// enumerate all confs in order of the scores, up to the estimate of the top of the energy window
		System.out.println("Enumerating other low-scoring conformations...");
        Deque<ScoredConf> otherLowEnergyConfs = new ArrayDeque<>();
        Stopwatch timingStopwatch = new Stopwatch().start();
        Stopwatch speculativeMinimizationStopwatch = new Stopwatch().start();
        while (true) {
            
            ScoredConf conf = search.nextConf();
            if (conf == null) {
                break;
            }
            otherLowEnergyConfs.add(conf);
            if (conf.getScore() >= erange.getMax()) {
                break;
            }
            
            // if we've been enumerating confs for a while, try a minimization to see if we get a smaller window
            if (speculativeMinimizationStopwatch.getTimeS() >= 10) {
                speculativeMinimizationStopwatch.stop();
                
                // save the conf and the energy for later
                EnergiedConf econf = ecalc.calcEnergy(otherLowEnergyConfs.removeFirst());
                handleEnergiedConf(econf, econfs, erange);
                
				boolean changed = erange.updateMin(econf.getEnergy());
				if (changed) {
                    System.out.println(String.format("Lower conformation energy updated energy window! remaining: %14.8f", erange.getMax() - conf.getScore()));
                    setErangeProgress(search, erange);
                }
                
                speculativeMinimizationStopwatch.start();
            }
        }
		
		// ConfSearch implementations can give one extra conf that's technically above the bound we gave to nextConfs()
		// that's because all ConfSearch implementations aren't smart enough (yet) to peek ahead
		// so prune the conf now because we definitely don't care about it
        if (!erange.contains(otherLowEnergyConfs.getLast().getScore())) {
        	otherLowEnergyConfs.removeLast();
        }
		
		System.out.println(String.format("\tFound %d more in %s", otherLowEnergyConfs.size(), timingStopwatch.getTime(1)));
		
		if (!otherLowEnergyConfs.isEmpty()) {
			
			/* TODO: integrate PartCR
			if (pruner != null) {
				pruner.prune(otherLowEnergyConfs, ecalc);
			}
			*/
			
			minimizeLowEnergyConfs(otherLowEnergyConfs, erange, econfs);
		}
	}

	private void minimizeLowEnergyConfs(Deque<ScoredConf> lowEnergyConfs, EnergyRange erange, PriorityQueue<EnergiedConf> econfs) {
		
		// calculate energy for each conf
		// this will probably take a while, so track progress
		Progress progress = new Progress(lowEnergyConfs.size());

		// what to do when we get a conf energy?
		ConfEnergyCalculator.Async.Listener ecalcListener = (econf) -> {
			
			handleEnergiedConf(econf, econfs, erange);
			progress.incrementProgress();

			// refine the estimate of the top of the energy window
			boolean changed = erange.updateMin(econf.getEnergy());
			if (changed) {

				int numLowEnergyConfsBefore = lowEnergyConfs.size();
				
				// prune conformations (in-place) with the new window
				for (int i=0; i<numLowEnergyConfsBefore; i++) {
					ScoredConf conf = lowEnergyConfs.poll();
					if (erange.containsOrBelow(conf.getScore())) {
						lowEnergyConfs.add(conf);
					}
				}

				// update progress
				System.out.println(String.format("\nNew lowest energy: %.6f", erange.getMin()));
				if (lowEnergyConfs.size() < numLowEnergyConfsBefore) {
					System.out.println(String.format("\tReduced to %d low-energy conformations", lowEnergyConfs.size()));
				}
				progress.setTotalWork(lowEnergyConfs.size());
			}
		};

		// calc the conf energy asynchronously
		System.out.println(String.format("\nComputing energies for %d conformations...", lowEnergyConfs.size()));
		while (!lowEnergyConfs.isEmpty()) {
			ecalc.calcEnergyAsync(lowEnergyConfs.remove(), ecalcListener);
		}
		ecalc.getTasks().waitForFinish();
	}
	
    private void setErangeProgress(ConfSearch confSearch, EnergyRange window) {
        
        // HACKHACK: set progress goal
        if (confSearch instanceof ConfAStarTree) {
            ConfAStarTree tree = (ConfAStarTree)confSearch;
            if (tree.getProgress() != null) {
                tree.getProgress().setGoalScore(window.getMax());
            }
        } else if (confSearch instanceof ConfSearch.Splitter.Stream) {
        	setErangeProgress(((ConfSearch.Splitter.Stream)confSearch).getSource(), window);
        }
    }
	
	private void handleEnergiedConf(EnergiedConf econf, PriorityQueue<EnergiedConf> econfs, EnergyRange erange) {
		
		// make sure the score was actually a lower bound
		if (econf.getScore() > econf.getEnergy() + 0.1) {
			throw new Error(String.format("Conformation score (%f) is not a lower bound on the energy (%f)! This is a serious bug.",
				econf.getScore(),
				econf.getEnergy()
			));
		}

		// save the conf and the energy for later
		econfs.add(econf);

		// immediately output the conf, in case the run aborts and we want to resume later
		logPrinter.print(econf, space);

		// log the conf to console too if desired
		if (printIntermediateConfsToConsole /* TEMP */ || true) {
			System.out.println();
			consolePrinter.print(econf, space, erange);
		}
	}
}
