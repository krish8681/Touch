package com.krish.niftydirection.model;

/** One headline and how it was read. The reader never says buy or sell — only impact on the market. */
public class NewsItem {
    public String id = "", title = "", source = "", link = "";
    public long time;                   // published, epoch ms
    public boolean read;                // true once rated
    public String by = "";              // "Gemini" or "keywords"
    public double marketImpact, niftyImpact;   // -1 .. +1
    public String sector = "", severity = "LOW", horizon = "intraday", reason = "";
    public boolean scheduled;           // talks about a coming event (policy, data)
    public String eventDate = "", eventName = "";
    // verification
    public String topic = "";           // short key for the story (from Gemini or the words), to group duplicates
    public boolean speculative;         // "may", "could", "sources say" …
    public boolean official;            // from an official feed (RBI, SEBI)
    public int cluster = -1;            // same story group number
    public int publishers = 1;          // how many different publishers carry this story
    public String verification = "PROVISIONAL";   // PROVISIONAL / CORROBORATED / CONFIRMED / VERIFIED
    public String summary = "";         // feed description (used to spot agency copy)
    public int independent = 1;         // independent reports in the group (syndicated copies count once)
    public String agency = "";          // PTI / Reuters / … when the text says so
    public boolean lead = true;         // the one item that speaks for its group (others are duplicates)
    // audit log of the AI reading
    public String model = "", promptVersion = "";
    public long ratedAt;
    // filled by the engine: how the market has reacted since the news came out
    public String reaction = "";
    public double reactionWeight = 1;
}
