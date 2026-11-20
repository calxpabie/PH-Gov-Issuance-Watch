package ph.govwatch.issuance;

/** One link found on an official web page. */
public final class Link {
    public final String title;
    public final String url;

    public Link(String title, String url) {
        this.title = title;
        this.url = url;
    }
}
