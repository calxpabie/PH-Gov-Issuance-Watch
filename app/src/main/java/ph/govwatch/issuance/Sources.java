package ph.govwatch.issuance;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The official pages that are checked. Edit this list to add or change a page. */
public final class Sources {

    public static final class Source {
        public final String id;
        public final String label;
        public final String url;

        Source(String id, String label, String url) {
            this.id = id;
            this.label = label;
            this.url = url;
        }
    }

    public static final List<Source> ALL = Collections.unmodifiableList(Arrays.asList(
            new Source("CSC", "CSC · 2026 Memorandum Circulars",
                    "https://www.csc.gov.ph/downloads/category/548-2026"),
            new Source("DBM-CL", "DBM · Circular Letters",
                    "https://www.dbm.gov.ph/index.php/circular-letters"),
            new Source("DBM-BC", "DBM · Budget Circulars",
                    "https://www.dbm.gov.ph/index.php/budget-circulars"),
            new Source("DBM-LBC", "DBM · Local Budget Circulars",
                    "https://www.dbm.gov.ph/index.php/local-budget-circulars"),
            new Source("DILG", "DILG · Website", "https://www.dilg.gov.ph/"),
            new Source("COA", "COA · Circulars", "https://www.coa.gov.ph/issuances/circulars/"),
            new Source("DOF", "DOF · Website", "https://www.dof.gov.ph/"),
            new Source("BIR", "BIR · 2026 Revenue Memorandum Circulars",
                    "https://www.bir.gov.ph/2026-Revenue-Memorandum-Circulars?label=2026&type=PAGE"),
            new Source("OG-PROC", "Gazette · Proclamations (holidays)",
                    "https://www.officialgazette.gov.ph/section/proclamations/"),
            new Source("OG-MC", "Gazette · Memorandum Circulars",
                    "https://www.officialgazette.gov.ph/section/executive-issuances/memorandum-circulars/"),
            new Source("OG-LAWS", "Gazette · Laws and issuances",
                    "https://www.officialgazette.gov.ph/section/laws/"),
            new Source("LAWPHIL", "LawPhil · 2026 Proclamations (backup)",
                    "https://lawphil.net/executive/proc/proc2026/proc2026.html")
    ));

    private Sources() {
    }
}
