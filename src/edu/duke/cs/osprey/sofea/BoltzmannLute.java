package edu.duke.cs.osprey.sofea;

import edu.duke.cs.osprey.kstar.pfunc.BoltzmannCalculator;
import edu.duke.cs.osprey.lute.LUTEConfEnergyCalculator;

import java.math.BigDecimal;
import java.math.MathContext;


public class BoltzmannLute {

	public final LUTEConfEnergyCalculator luteEcalc;

	public final BigDecimal factor;
	private final BigDecimal[] values;

	public BoltzmannLute(LUTEConfEnergyCalculator luteEcalc, MathContext mathContext) {

		this.luteEcalc = luteEcalc;
		this.values = new BigDecimal[luteEcalc.tuples.size()];

		// pre-calculate all the boltzmann-weighted values
		BoltzmannCalculator bcalc = new BoltzmannCalculator(mathContext);
		this.factor = bcalc.calcPrecise(luteEcalc.state.tupleEnergyOffset);
		for (int t=0; t<luteEcalc.tuples.size(); t++) {
			this.values[t] = bcalc.calcPrecise(luteEcalc.state.tupleEnergies[t]);
		}
	}

	public boolean has(int pos, int rc) {
		return luteEcalc.hasTuple(pos, rc);
	}

	public boolean has(int pos1, int rc1, int pos2, int rc2) {
		return luteEcalc.hasTuple(pos1, rc1, pos2, rc2);
	}

	public BigDecimal get(int pos, int rc) {
		return get(luteEcalc.tuples.getIndex(pos, rc));
	}

	public BigDecimal get(int pos1, int rc1, int pos2, int rc2) {
		return get(luteEcalc.tuples.getIndex(pos1, rc1, pos2, rc2));
	}

	public BigDecimal get(int pos1, int rc1, int pos2, int rc2, int pos3, int rc3) {
		return get(luteEcalc.tuples.getIndex(pos1, rc1, pos2, rc2, pos3, rc3));
	}

	private BigDecimal get(Integer t) {
		if (t == null) {
			return null;
		} else {
			return values[t];
		}
	}
}
