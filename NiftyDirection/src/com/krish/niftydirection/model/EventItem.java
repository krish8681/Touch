package com.krish.niftydirection.model;

/** A scheduled market event (RBI policy, Fed decision, CPI, expiry…). */
public class EventItem {
    public String date = "";            // yyyy-MM-dd (IST day when it hits Indian markets)
    public String name = "", source = "";
    public int importance = 2;          // 1 low, 2 medium, 3 high

    public EventItem() {}
    public EventItem(String date, String name, int importance, String source) { this.date = date; this.name = name; this.importance = importance; this.source = source; }
}
