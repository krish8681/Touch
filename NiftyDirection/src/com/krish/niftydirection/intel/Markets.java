package com.krish.niftydirection.intel;

/**
 * The daily world-market series the engines learn from (Yahoo Finance daily closes, keyed by the IST date of each session).
 * Names are the keys of History.global. The first eight are the inputs of the original forecaster and keep their names.
 */
public final class Markets {
    private Markets() {}

    public static final String SP = "S&P 500", NASDAQ = "Nasdaq", NIKKEI = "Nikkei", HSI = "Hang Seng", BRENT = "Brent crude",
            USDINR = "USD/INR", US10Y = "US 10Y yield", DXY = "Dollar index",
            DOW = "Dow", RUSSELL = "Russell 2000", USVIX = "US VIX", USFUT = "US futures", DAX = "DAX", FTSE = "FTSE 100", CAC = "CAC 40",
            KOSPI = "Kospi", SHANGHAI = "Shanghai", ASX = "ASX 200", TAIWAN = "Taiwan",
            EURUSD = "EUR/USD", USDJPY = "USD/JPY", USDCNY = "USD/CNY",
            US13W = "US 13W yield", US5Y = "US 5Y yield", US30Y = "US 30Y yield",
            WTI = "WTI crude", GOLD = "Gold", SILVER = "Silver", COPPER = "Copper";

    /** {name, Yahoo symbol}. */
    public static final String[][] YAHOO = {
            {SP, "^GSPC"}, {NASDAQ, "^IXIC"}, {NIKKEI, "^N225"}, {HSI, "^HSI"}, {BRENT, "BZ=F"}, {USDINR, "INR=X"}, {US10Y, "^TNX"}, {DXY, "DX-Y.NYB"},
            {DOW, "^DJI"}, {RUSSELL, "^RUT"}, {USVIX, "^VIX"}, {USFUT, "ES=F"}, {DAX, "^GDAXI"}, {FTSE, "^FTSE"}, {CAC, "^FCHI"},
            {KOSPI, "^KS11"}, {SHANGHAI, "000001.SS"}, {ASX, "^AXJO"}, {TAIWAN, "^TWII"},
            {EURUSD, "EURUSD=X"}, {USDJPY, "JPY=X"}, {USDCNY, "CNY=X"},
            {US13W, "^IRX"}, {US5Y, "^FVX"}, {US30Y, "^TYX"},
            {WTI, "CL=F"}, {GOLD, "GC=F"}, {SILVER, "SI=F"}, {COPPER, "HG=F"}};

    /** Yields: changes are measured in points (× 10 = basis points / 10), not percent. */
    public static boolean isYield(String name) { return name.endsWith("yield"); }
}
