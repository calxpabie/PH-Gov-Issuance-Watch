package ph.govwatch.issuance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Works out which links are new compared with what was seen before. */
public final class Diff {

    private Diff() {
    }

    public static List<Link> fresh(List<Link> current, Set<String> seenUrls) {
        List<Link> out = new ArrayList<>();
        for (Link l : current) {
            if (!seenUrls.contains(l.url)) {
                out.add(l);
            }
        }
        return out;
    }
}
