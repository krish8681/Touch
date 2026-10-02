package com.krish.niftydirection.model;

/** One strike of the option chain: call side and put side. prev* = values at the previous session close (-1 = unknown). */
public class OptionRow {
    public double strike;
    public String ceSymbol = "", peSymbol = "";
    public long ceToken, peToken;
    public double ceOi, cePrevOi = -1, ceLtp, cePrevLtp, ceVol;
    public double peOi, pePrevOi = -1, peLtp, pePrevLtp, peVol;
    public double ceIv = Double.NaN, peIv = Double.NaN;   // implied volatility in %, worked out from the price
    public double ceBid, ceAsk, peBid, peAsk;              // best bid / offer (0 = none)

    public double ceDoi() { return cePrevOi >= 0 ? ceOi - cePrevOi : 0; }
    public double peDoi() { return pePrevOi >= 0 ? peOi - pePrevOi : 0; }
    public double ceDp() { return cePrevLtp > 0 ? ceLtp - cePrevLtp : 0; }
    public double peDp() { return pePrevLtp > 0 ? peLtp - pePrevLtp : 0; }
    public boolean hasPrev() { return cePrevOi >= 0 && pePrevOi >= 0; }
}
