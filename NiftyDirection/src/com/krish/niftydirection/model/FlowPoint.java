package com.krish.niftydirection.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** One intraday snapshot of the option chain and futures (taken on each update). */
public class FlowPoint {
    public int minute;                 // minutes since midnight IST
    public double spot, fut, futOi, basis, atmIv = Double.NaN;
    /** symbol → {oi, ltp, volume, iv}. */
    public Map<String, double[]> opt = new LinkedHashMap<>();
}
