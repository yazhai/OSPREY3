package edu.duke.cs.osprey.minimization;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import edu.duke.cs.osprey.TestBase;
import edu.duke.cs.osprey.astar.conf.ConfAStarTree;
import edu.duke.cs.osprey.astar.conf.RCs;
import edu.duke.cs.osprey.astar.conf.order.AStarOrder;
import edu.duke.cs.osprey.astar.conf.order.StaticScoreHMeanAStarOrder;
import edu.duke.cs.osprey.astar.conf.scoring.AStarScorer;
import edu.duke.cs.osprey.astar.conf.scoring.MPLPPairwiseHScorer;
import edu.duke.cs.osprey.astar.conf.scoring.PairwiseGScorer;
import edu.duke.cs.osprey.astar.conf.scoring.mplp.NodeUpdater;
import edu.duke.cs.osprey.confspace.ConfSearch.EnergiedConf;
import edu.duke.cs.osprey.confspace.ConfSearch.ScoredConf;
import edu.duke.cs.osprey.confspace.SearchProblem;
import edu.duke.cs.osprey.control.EnvironmentVars;
import edu.duke.cs.osprey.dof.deeper.DEEPerSettings;
import edu.duke.cs.osprey.ematrix.EnergyMatrix;
import edu.duke.cs.osprey.ematrix.SimpleEnergyCalculator;
import edu.duke.cs.osprey.ematrix.SimpleEnergyMatrixCalculator;
import edu.duke.cs.osprey.ematrix.epic.EPICSettings;
import edu.duke.cs.osprey.energy.EnergyFunction;
import edu.duke.cs.osprey.energy.EnergyFunctionGenerator;
import edu.duke.cs.osprey.energy.GpuEnergyFunctionGenerator;
import edu.duke.cs.osprey.energy.MultiTermEnergyFunction;
import edu.duke.cs.osprey.gpu.GpuQueuePool;
import edu.duke.cs.osprey.parallelism.ThreadPoolTaskExecutor;
import edu.duke.cs.osprey.pruning.PruningMatrix;
import edu.duke.cs.osprey.structure.Molecule;
import edu.duke.cs.osprey.tools.Factory;
import edu.duke.cs.osprey.tools.ObjectIO;
import edu.duke.cs.osprey.tools.Stopwatch;
import edu.duke.cs.osprey.tupexp.LUTESettings;

@SuppressWarnings("unused")
public class BenchmarkMinimization extends TestBase {
	
	public static void main(String[] args)
	throws Exception {
		
		initDefaultEnvironment();
		
		// for these small problems, more than one thread is actually slower
		MultiTermEnergyFunction.setNumThreads(1);
		
		// make a search problem
		System.out.println("Building search problem...");
		
		ResidueFlexibility resFlex = new ResidueFlexibility();
		resFlex.addMutable("39 43", "ALA");
		resFlex.addFlexible("40 41 42 44 45");
		boolean doMinimize = true;
		boolean addWt = true;
		boolean useEpic = false;
		boolean useTupleExpansion = false;
		boolean useEllipses = false;
		boolean useERef = false;
		boolean addResEntropy = false;
		boolean addWtRots = false;
		ArrayList<String[]> moveableStrands = new ArrayList<String[]>();
		ArrayList<String[]> freeBBZones = new ArrayList<String[]>();
		SearchProblem search = new SearchProblem(
			"test", "test/1CC8/1CC8.ss.pdb", 
			resFlex.flexResList, resFlex.allowedAAs, addWt, doMinimize, useEpic, new EPICSettings(), useTupleExpansion, new LUTESettings(),
			new DEEPerSettings(), moveableStrands, freeBBZones, useEllipses, useERef, addResEntropy, addWtRots, null,
			false, new ArrayList<>()
		);
		
		// calc the energy matrix
		File ematFile = new File("/tmp/benchmarMinimization.emat.dat");
		if (ematFile.exists()) {
			search.emat = (EnergyMatrix)ObjectIO.readObject(ematFile.getAbsolutePath(), false);
		} else {
			ThreadPoolTaskExecutor tasks = new ThreadPoolTaskExecutor();
			tasks.start(2);
			SimpleEnergyCalculator ecalc = new SimpleEnergyCalculator(EnvironmentVars.curEFcnGenerator, search.confSpace, search.shellResidues);
			search.emat = new SimpleEnergyMatrixCalculator(ecalc).calcEnergyMatrix(tasks);
			tasks.stop();
			ObjectIO.writeObject(search.emat, ematFile.getAbsolutePath());
		}
		
		// settings
		final int numConfs = 8;//64;//512;
		
		// get a few arbitrary conformations
		search.pruneMat = new PruningMatrix(search.confSpace, 1000);
		RCs rcs = new RCs(search.pruneMat);
		AStarOrder order = new StaticScoreHMeanAStarOrder();
		AStarScorer hscorer = new MPLPPairwiseHScorer(new NodeUpdater(), search.emat, 4, 0.0001);
		ConfAStarTree tree = new ConfAStarTree(order, new PairwiseGScorer(search.emat), hscorer, rcs);
		List<ScoredConf> confs = new ArrayList<>();
		for (int i=0; i<numConfs; i++) {
			confs.add(tree.nextConf());
		}
		
		//benchmarkParallelism(search, confs);
		benchmarkGpu(search, confs);
	}
	
	private static void benchmarkParallelism(SearchProblem search, List<ScoredConf> confs)
	throws Exception {
		
		// settings
		final int[] numThreadsList = { 1, 2, 4, 8 };//, 16 };
		final boolean useGpu = false;
		
		int maxNumThreads = numThreadsList[numThreadsList.length - 1];
		
		// get the energy function generator
		final EnergyFunctionGenerator egen;
		if (useGpu) {
			GpuQueuePool gpuPool = new GpuQueuePool(maxNumThreads, 1);
			//GpuQueuePool gpuPool = new GpuQueuePool(1, maxNumThreads);
			egen = new GpuEnergyFunctionGenerator(makeDefaultFFParams(), gpuPool);
		} else {
			egen = EnvironmentVars.curEFcnGenerator;
		}
		
		// make the energy function factory
		Factory<EnergyFunction,Molecule> efuncs = new Factory<EnergyFunction,Molecule>() {
			@Override
			public EnergyFunction make(Molecule mol) {
				return egen.fullConfEnergy(search.confSpace, search.shellResidues, mol);
			}
		};
		
		List<EnergiedConf> minimizedConfs;
		
		// benchmark base minimization
		System.out.println("\nBenchmarking main thread...");
		Stopwatch baseStopwatch = new Stopwatch().start();
		minimizedConfs = new ConfMinimizer().minimize(confs, efuncs, search.confSpace);
		baseStopwatch.stop();
		System.out.println("precise timing: " + baseStopwatch.getTime(TimeUnit.MILLISECONDS));
		checkEnergies(minimizedConfs);
		
		// benchmark parallel minimization
		ThreadPoolTaskExecutor tasks = new ThreadPoolTaskExecutor();
		
		for (int numThreads : numThreadsList) {
			
			tasks.start(numThreads);
			
			System.out.println("\nBenchmarking " + numThreads + " task thread(s)...");
			Stopwatch taskStopwatch = new Stopwatch().start();
			minimizedConfs = new ConfMinimizer().minimize(confs, efuncs, search.confSpace, tasks);
			taskStopwatch.stop();
			System.out.println("precise timing: " + taskStopwatch.getTime(TimeUnit.MILLISECONDS));
			System.out.println(String.format("Speedup: %.2fx", (float)baseStopwatch.getTimeNs()/taskStopwatch.getTimeNs()));
			checkEnergies(minimizedConfs);
			
			tasks.stopAndWait(10000);
		}
	}

	private static void benchmarkGpu(SearchProblem search, List<ScoredConf> confs)
	throws Exception {
		
		// make minimizer factories
		Factory<Minimizer,MoleculeModifierAndScorer> fastMinimizers = new Factory<Minimizer,MoleculeModifierAndScorer>() {
			@Override
			public Minimizer make(MoleculeModifierAndScorer mof) {
				return new CCDMinimizer(mof, true);
			}
		};
		Factory<Minimizer,MoleculeModifierAndScorer> simpleMinimizers = new Factory<Minimizer,MoleculeModifierAndScorer>() {
			@Override
			public Minimizer make(MoleculeModifierAndScorer mof) {
				return new SimpleCCDMinimizer(mof, new Factory<LineSearcher,Void>() {
					@Override
					public LineSearcher make(Void context) {
						return new GpuStyleSurfingLineSearcher();
					}
				});
			}
		};
		Factory<Minimizer,MoleculeModifierAndScorer> gpuSimpleMinimizers = new Factory<Minimizer,MoleculeModifierAndScorer>() {
			@Override
			public Minimizer make(MoleculeModifierAndScorer mof) {
				return new SimpleCCDMinimizer(mof, new Factory<LineSearcher,Void>() {
					@Override
					public LineSearcher make(Void context) {
						return new GpuSurfingLineSearcher();
					}
				});
			}
		};
		
		// make efuncs
		Factory<EnergyFunction,Molecule> efuncs = new Factory<EnergyFunction,Molecule>() {
			@Override
			public EnergyFunction make(Molecule mol) {
				return EnvironmentVars.curEFcnGenerator.fullConfEnergy(search.confSpace, search.shellResidues, mol);
			}
		};
		GpuQueuePool gpuPool = new GpuQueuePool(1);
		GpuEnergyFunctionGenerator gpuegen = new GpuEnergyFunctionGenerator(makeDefaultFFParams(), gpuPool);
		Factory<EnergyFunction,Molecule> gpuefuncs = new Factory<EnergyFunction,Molecule>() {
			@Override
			public EnergyFunction make(Molecule mol) {
				return gpuegen.fullConfEnergy(search.confSpace, search.shellResidues, mol);
			}
		};
		
		List<EnergiedConf> minimizedConfs;
		
		/*
		System.out.println("\nbenchmarking CPU fast...");
		Stopwatch cpuFastStopwatch = new Stopwatch().start();
		minimizedConfs = new ConfMinimizer(fastMinimizers).minimize(confs, efuncs, search.confSpace);
		System.out.println("precise timing: " + cpuFastStopwatch.stop().getTime(TimeUnit.MILLISECONDS));
		checkEnergies(minimizedConfs);
		*/
		//Stopwatch cpuFastStopwatch = new Stopwatch().start().stop();
		
		/*
		System.out.println("\nbenchmarking CPU simple...");
		Stopwatch cpuSimpleStopwatch = new Stopwatch().start();
		minimizedConfs = new ConfMinimizer(simpleMinimizers).minimize(confs, efuncs, search.confSpace);
		System.out.print("precise timing: " + cpuSimpleStopwatch.stop().getTime(TimeUnit.MILLISECONDS));
		System.out.println(String.format(", speedup: %.2fx", (double)cpuFastStopwatch.getTimeNs()/cpuSimpleStopwatch.getTimeNs()));
		checkEnergies(minimizedConfs);
		*/
		
		System.out.println("\nbenchmarking GPU fast...");
		Stopwatch gpuFastStopwatch = new Stopwatch().start();
		minimizedConfs = new ConfMinimizer(fastMinimizers).minimize(confs, gpuefuncs, search.confSpace);
		System.out.print("precise timing: " + gpuFastStopwatch.stop().getTime(TimeUnit.MILLISECONDS));
		//System.out.println(String.format(", speedup: %.2fx", (double)cpuFastStopwatch.getTimeNs()/gpuFastStopwatch.getTimeNs()));
		System.out.println();
		checkEnergies(minimizedConfs);
		
		System.out.println("\nbenchmarking GPU simple...");
		Stopwatch gpuSimpleStopwatch = new Stopwatch().start();
		minimizedConfs = new ConfMinimizer(gpuSimpleMinimizers).minimize(confs, gpuefuncs, search.confSpace);
		System.out.print("precise timing: " + gpuSimpleStopwatch.stop().getTime(TimeUnit.MILLISECONDS));
		//System.out.println(String.format(", speedup: %.2fx", (double)cpuFastStopwatch.getTimeNs()/gpuSimpleStopwatch.getTimeNs()));
		System.out.println(String.format(", speedup: %.2fx", (double)gpuFastStopwatch.getTimeNs()/gpuSimpleStopwatch.getTimeNs()));
		checkEnergies(minimizedConfs);
	}
	
	private static void checkEnergies(List<EnergiedConf> minimizedConfs) {
		
		// what do we expect the energies to be?
		final double[] expectedEnergies = {
			-107.01471465433335, -107.14427781940432, -106.79145713231975, -106.92139365967053, -106.28769308885211, -107.11801397703762,
			-106.67892206113300, -106.41908247351522, -106.89600279606412, -106.74468003314176, -106.45689550906734, -106.95533592350961,
			-106.43786020587382, -106.52194038276753, -106.39164304147317, -106.72599111242266, -106.10055039136107, -106.73371625732581,
			-106.45590591321724, -105.95183095436968, -106.40589873963415, -106.16492362939529, -106.50474766966147, -105.73721952508399,
			-105.82709235989205, -106.01931524286667, -106.23489219140693, -106.38648146252073, -106.23062800511192, -106.01634808496966,
			-106.51221811051244, -106.13624568652024, -105.51519524725856, -105.58610640846234, -106.16496203741923, -105.99987206614541,
			-105.72752981857495, -105.98691660302913, -106.18174504161567, -105.79283093027898, -106.28946765141372, -105.72932809359553,
			-106.31993398348527, -105.46572215870616, -105.37426766628305, -105.64106898231132, -106.15810456445854, -105.00808312851092,
			-105.50648038730101, -105.76577751349954, -105.95687878293921, -105.30211014747410, -106.10563929270654, -106.34665109876666,
			-104.96296983624227, -105.24942719384953, -105.65936824595236, -105.80929936537325, -106.04288152341317, -105.28676430493509,
			-105.78599675179457, -105.60879921653074, -105.59757048659445, -105.93244312434278, -104.85689138106054, -105.82782195449379,
			-105.52743754622844, -106.07292813638603, -104.76277348796116, -105.70198170069450, -105.41906775530121, -105.59810317503754,
			-105.57514289492345, -105.00078589960478, -105.25982046176493, -105.45769078867225, -105.69570611182525, -105.81569226997661,
			-105.37345135437695, -105.32516577883564, -105.18453541903449, -104.77552773763776, -105.66366244374360, -104.74544304009927,
			-105.20104157038789, -105.62118651281193, -105.94380707127172, -105.47715148871868, -105.19874481396897, -105.68525855743960,
			-105.25536940872452, -106.00921382366042, -105.42817910733990, -105.37039080855324, -104.92177476888057, -105.12020000808083,
			-105.08078289833018, -105.10353140273351, -104.54518052211290, -105.42372792818857, -104.36275996097521, -105.57048229185690,
			-104.25303198823977, -105.44707827350183, -105.85164997702210, -105.31075273360257, -105.97179065419242, -104.91206291319261,
			-105.69003300796533, -105.51826984417780, -104.98031924936627, -105.57272360260767, -104.69168823691530, -104.40887082877894,
			-104.86724943217820, -104.93313570047883, -105.42707048179973, -105.34975963008013, -104.90747507078802, -105.62328510744953,
			-105.75073803047596, -104.86824231252481, -105.33459153227595, -104.57528843368355, -105.69902440188027, -104.64177288680466,
			-105.30313041655097, -105.00374191781812, -105.54711439324967, -104.08473536768880, -105.57243628154440, -104.97243136318652,
			-104.69486696747346, -105.43236944932737, -105.00083384185085, -105.71047072209598, -104.82613740183368, -105.57489565381286,
			-104.05917939095565, -105.31305290546224, -105.41318537681416, -104.32261731867930, -104.59963047550923, -105.91694984304478,
			-105.33273014643979, -105.42364290540462, -104.14833050083541, -105.78969828281687, -104.52076106775769, -104.37539516504394,
			-106.21454802160396, -105.17643884002453, -104.41988170514084, -104.65436687119288, -106.16362270858143, -105.40771531317080,
			-104.77866456898530, -104.83517966058959, -104.09812405452149, -105.19231554889792, -104.81302275781590, -105.06326550017315,
			-104.85752681349028, -104.47460561498100, -105.48509376108684, -103.97419266631252, -104.72747357244805, -106.26512235263604,
			-105.30567884244734, -104.62741585743903, -104.39180316394315, -106.19581403199717, -104.90730331825398, -104.58659215819033,
			-104.00093262535539, -105.29750351050679, -105.16157335628849, -104.44814827154117, -104.90140765200064, -104.89764418139825,
			-106.10554279963003, -105.94295560942044, -105.92238331331627, -103.77998290187057, -105.24487450328840, -105.04573511167207,
			-104.12204194163733, -104.32166837831235, -104.94327437357629, -104.89190813419374, -104.46913816692250, -104.62824592676805,
			-105.26879506817085, -105.54998441970992, -105.53386331060860, -105.73441659514394, -105.25574154613450, -105.67291477100300,
			-105.41589676227450, -103.86876914069020, -105.21164833250174, -105.41904595302559, -104.38504849225677, -105.31640069919560,
			-106.02276867191723, -105.95464612203685, -104.61106374951828, -104.99480985375133, -106.19872916409350, -104.55644206920498,
			-105.90533069229794, -105.24132844084920, -105.93190379268083, -105.13953338072118, -105.09303969950356, -103.82124786030334,
			-105.03716089785990, -104.52406041578820, -104.86147570208132, -104.53266558383702, -103.67840037673506, -104.67901036037097,
			-104.68040152599038, -104.21567111696235, -104.38923669073810, -105.61715762522093, -104.90348502355474, -105.84652954050975,
			-104.82413092403296, -105.19333542008505, -105.75282471862891, -105.24102208246916, -104.91840781845195, -106.01403592689245,
			-105.23197089444734, -105.37357915834045, -106.14724853812857, -103.86535166200228, -103.91555847979969, -104.49897275837901,
			-106.07518358129548, -105.94806024005838, -104.30302435709363, -104.59024790837603, -104.83172271319961, -105.61273760784785,
			-105.51815962893006, -104.27625893126400, -105.04124665995161, -104.12456912697934, -105.29785199682765, -103.72015324953097,
			-104.89607505044538, -104.67573396997192, -104.78811528253772, -104.71149976152795, -105.87035999108284, -105.84762147807616,
			-105.68179468413993, -104.69340267062613, -105.66191308486190, -104.24750500479568, -103.56984042839480, -105.21929010198568,
			-105.05832095703352, -106.00743343063917, -105.22588653387828, -106.04529158570351, -104.29767216706364, -105.80277172614801,
			-103.97067152782465, -104.70848407755854, -105.18342756945046, -105.47542108372234, -104.11761581397184, -105.04748899558011,
			-105.15495227717840, -104.53489689605946, -104.41934878610024, -105.76330046618455, -105.05066331473932, -104.62281614065553,
			-103.58467568999096, -104.93472969259594, -105.22648463775461, -105.11741439568057, -105.57723191303745, -105.80719506140456,
			-105.30531427765396, -105.53320092422744, -105.79239314546436, -105.94794643829802, -104.88643091430363, -104.27875943483156,
			-104.40041941781607, -104.47377097700347, -105.69522705296282, -105.77509010959030, -105.68835339396445, -104.96501297004177,
			-104.38773855880581, -104.31448593576498, -104.88554116132197, -104.70543995056714, -105.71326400013693, -104.81727208183081,
			-104.97054243724433, -104.86973190300350, -105.22250402869321, -103.81162959511003, -104.01189844484831, -104.39772571903661,
			-105.35627530857573, -104.06748409151103, -105.58894508758749, -105.10373348590008, -105.64456235652620, -104.48261240919449,
			-104.78291045929600, -104.13952332489644, -105.96432457362549, -105.11633746889557, -104.39375276774112, -104.02391484618519,
			-105.89835982265500, -104.10632510995578, -105.36469996870215, -104.48490208510638, -105.27148292582207, -105.72982082588818,
			-104.53570484321187, -104.59116448662883, -105.17515979923381, -105.50925854594503, -105.82474704643090, -104.46387649961031,
			-104.22298138713809, -105.37495107790130, -105.25545098077250, -105.61261838259020, -103.84627447018269, -104.27031234914722,
			-105.02228370999000, -104.58632668902754, -104.50911180351240, -104.44824389158293, -104.77173804525134, -105.52635255759170,
			-104.85799838020820, -105.04932522340577, -103.69016499330387, -104.59907047311509, -105.47460553258144, -105.86176465809880,
			-105.75862857571850, -104.97198153326910, -105.78504182990523, -104.05651403319169, -105.18786845670388, -104.22098730839413,
			-104.75867480809602, -105.64419104264566, -104.41135936752582, -105.68427975496215, -105.55254379819490, -105.38938995200158,
			-103.60623205237732, -104.79947790009862, -104.57373871384992, -104.69697189623449, -105.42314431051686, -104.63471022082042,
			-105.01220867334017, -104.90717139592785, -104.01272182379302, -104.08383256501590, -105.00902267975476, -105.85542114101023,
			-104.25958824863419, -104.85824147708330, -105.27871666820383, -104.96442223694078, -105.31422426647634, -103.30763048868492,
			-104.44213135097021, -104.18386553844773, -105.55427284966686, -105.56036159018530, -103.26573648364447, -105.51338717824433,
			-105.42364514109464, -104.00868455340091, -104.51369643677870, -105.42929037233523, -103.51125857492502, -105.43790692033804,
			-104.31533024033062, -104.73171053250476, -104.90668138856618, -104.92397467051906, -103.81728787858303, -104.12504515164963,
			-103.64114769538745, -104.58350331975522, -103.74426565224336, -104.17700946595662, -105.67849079623580, -105.28966134727051,
			-105.23019541933029, -104.96449161719234, -105.59582873884301, -104.23501739344756, -103.64451602125037, -104.83028122991722,
			-104.55165836269897, -103.35551689460812, -104.84949134762982, -104.71942179938688, -104.58411260889237, -105.52227005869089,
			-104.41376786106554, -105.07321582084670, -104.36140508223801, -103.51534095237623, -105.72193913792043, -104.04811078871408,
			-105.70474112546088, -105.78139453827595, -103.46899558974302, -105.71801705136740, -104.57657552125370, -104.55575126196399,
			-105.23711890021850, -105.61761141034097, -105.20443651959967, -104.68221833110920, -104.23517044684162, -103.87549980755733,
			-104.53129326949984, -105.47034819948514, -104.55359456967783, -105.47811227377183, -104.58913442583211, -104.58980147760894,
			-105.36469868293183, -103.99164115377137, -103.30322980052173, -105.64193163798137, -104.91632481990297, -103.31155618843906,
			-105.09298854602737, -104.39088216665911, -105.20160368508715, -104.01993207325103, -105.18027766314324, -105.71343357608269,
			-104.17508957851729, -104.23766919536361, -105.38252175273936, -105.45698096947353, -104.72166153418730, -103.96136323877008,
			-104.75472767975450, -103.79367608199645, -105.02842416792730, -104.19690339663867, -104.73556368358547, -105.28419391252349,
			-105.25400978357557, -104.20635766918751, -105.08270172425375, -105.16080847392138, -105.61122408708295, -104.51336812297585,
			-103.37272129160175, -104.22993205026660, -103.95561851690990, -104.17356343912405, -105.17688871333444, -105.27633510848447,
			-104.81903390719096, -105.31811468696134, -104.60243562858571, -104.99362619688151, -104.49966947179584, -105.19768428892269,
			-105.55459990631216, -104.90093763839960, -103.91989469881723, -104.03881080618366, -105.28254959151224, -103.34927592816312,
			-104.68122506530622, -104.71244829110928, -103.55478842686453, -103.72416145021363, -104.20282449075565, -105.25543358397891,
			-103.88610034965609, -105.60990206965060, -104.28012067535394, -104.49125515105347, -104.28410459224443, -105.25510890768993,
			-105.42461876753079, -103.88791784728554, -103.49204285472620, -104.74731777802437, -103.80032545111749, -105.59180680749536,
			-103.20811235101901, -103.32709352530163
		};
		
		final double Epsilon = 1e-3;
		
		int n = minimizedConfs.size();
		for (int i=0; i<n; i++) {
			
			double energy = minimizedConfs.get(i).getEnergy();
			
			if (i < expectedEnergies.length) {
				
				double absErr = energy - expectedEnergies[i];
				if (absErr > Epsilon) {
					System.out.println(String.format("\tWARNING: low precision energy: exp:%12.8f  obs:%12.8f  absErr:%12.8f",
						expectedEnergies[i], energy, absErr
					));
				}
				
			} else {
				
				System.out.println("\tNew energy: " + energy);
			}
		}
	}
}
