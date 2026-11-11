#!/usr/bin/env python3
"""
Gov Issuance Watch (v2)

Scans configured Philippine government pages for the latest issuances
(circulars, memoranda, orders) and reports them newest-first.

What this version does differently from v1:
- Reads publication dates and issuance numbers (from the link, the table row
  or list item around it, and the URL) and sorts by date, not by title.
- Follows "next page" links (up to MAX_PAGES) so items beyond page 1 are seen.
- Uses an RSS/Atom feed when a page advertises one.
- Retries flaky servers with backoff.
- Ignores navigation links ("Circulars", "2025 Revenue Memorandum Circulars")
  by requiring an issuance number, a date, or a document file.
- Baselines each source separately, so adding a new source later does not
  flood the report.
- Reports "New since last run" and "Latest circulars (last N days)".

Important:
- This is a candidate-link monitor, not a guaranteed complete legal-issuance crawler.
- A page being reachable does not prove that every issuance was detected.
- Sources whose pages are rendered by JavaScript will show 0 candidates.
"""

from __future__ import annotations

import json
import re
import sys
import time
import xml.etree.ElementTree as ET
from datetime import date, datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import unquote, urldefrag, urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

BASE_DIR = Path(__file__).resolve().parent
STATE_PATH = BASE_DIR / "monitor_state.json"
REPORT_PATH = BASE_DIR / "daily-report.md"
NEW_ITEMS_PATH = BASE_DIR / "new-items.md"  # only exists when something new was found
CIRCULARS_PATH = BASE_DIR / "circulars.json"  # read by index.html

PHT = timezone(timedelta(hours=8), "PHT")  # Asia/Manila has no DST

CONNECT_TIMEOUT = 15
READ_TIMEOUT = 30
MAX_PAGES = 3              # listing pages followed per source
PAGE_DELAY_SECONDS = 1.0   # politeness delay between requests
RECENT_DAYS = 30           # window for the "Latest circulars" section
LATEST_PER_SOURCE = 10     # newest items shown per source
LATEST_OVERALL = 50
MAX_CIRCULARS_JSON = 400   # newest items published to circulars.json for the web app

# loose=True  : dedicated listing page; a link with a real (non-year) digit in
#               its title also counts as an issuance.
# paginate    : follow "next" links (default True).
# Year-specific URLs (CSC 548-2026, BIR 2026) must be updated each January.
SOURCES = [
    {"name": "CSC", "url": "https://www.csc.gov.ph/downloads/category/548-2026", "loose": True},
    {"name": "DBM Circular Letters", "url": "https://www.dbm.gov.ph/index.php/circular-letters", "loose": True},
    {"name": "DBM Budget Circulars", "url": "https://www.dbm.gov.ph/index.php/budget-circulars", "loose": True},
    {"name": "DBM Local Budget Circulars", "url": "https://www.dbm.gov.ph/index.php/local-budget-circulars", "loose": True},
    {"name": "DILG", "url": "https://www.dilg.gov.ph/", "paginate": False},
    {"name": "COA", "url": "https://www.coa.gov.ph/issuances/circulars/", "loose": True},
    {"name": "DOF", "url": "https://www.dof.gov.ph/", "paginate": False},
    {"name": "Official Gazette", "url": "https://www.officialgazette.gov.ph/", "paginate": False},
    {"name": "BIR 2026 RMC", "url": "https://www.bir.gov.ph/2026-Revenue-Memorandum-Circulars?label=2026&type=PAGE", "loose": True},
]

KEYWORDS = re.compile(
    r"circular|memorandum|issuance|revenue regulation|administrative order|"
    r"department order|executive order|proclamation|republic act|joint circular|budget circular|"
    r"\b(?:mc|rmc|rmo|rr|jmc|dbc|lbc|cl)\s*(?:no\.?|number|#)?\s*\d",
    re.IGNORECASE,
)

NUMBER_RE = re.compile(
    r"\b(?:(?:rmc|rmo|rr|jmc|mc|dbc|lbc|cl)\s*(?:no\.?|#)?\s*|nos?\.?\s*|number\s+)"
    r"(\d{1,4}(?:\s?[-–/]\s?\d{1,4}){0,2}[A-Za-z]?)\b",
    re.IGNORECASE,
)

_MONTH_NAMES = (
    "january|february|march|april|may|june|july|august|september|october|"
    "november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"
)
MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}
DATE_PATTERNS = [
    # November 5, 2026 / Nov. 5th 2026
    re.compile(
        rf"\b(?P<mon>{_MONTH_NAMES})\.?\s+(?P<day>\d{{1,2}})(?:st|nd|rd|th)?,?\s+(?P<year>20\d{{2}})\b",
        re.IGNORECASE,
    ),
    # 5 November 2026
    re.compile(
        rf"\b(?P<day>\d{{1,2}})(?:st|nd|rd|th)?\s+(?P<mon>{_MONTH_NAMES})\.?,?\s+(?P<year>20\d{{2}})\b",
        re.IGNORECASE,
    ),
    # 2026-11-05, 2026/11/05 (also WordPress-style /2026/11/05/ URLs)
    re.compile(r"\b(?P<year>20\d{2})[-/.](?P<mnum>\d{1,2})[-/.](?P<day>\d{1,2})\b"),
    # 11/05/2026 (month first, as is usual in Philippine documents)
    re.compile(r"\b(?P<mnum>\d{1,2})[-/](?P<day>\d{1,2})[-/](?P<year>20\d{2})\b"),
]

FILE_EXTS = (".pdf", ".doc", ".docx", ".xls", ".xlsx")
GENERIC_LABELS = {
    "download", "view", "read more", "more", "details", "click here",
    "pdf", "link", "open", "file", "download pdf", "view file",
}
NEXT_TEXTS = {
    "next", "next page", "next »", "next ›", "next →", "»", "›", ">", ">>",
    "older", "older posts",
}

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0 Safari/537.36 GovIssuanceWatch/2.0"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-PH,en;q=0.9",
}


# ---------------------------------------------------------------- helpers

def clean(text):
    return " ".join((text or "").split())


def normalize_url(url):
    return urldefrag(url.strip())[0]


def is_same_site(candidate_url, source_url):
    candidate_host = (urlparse(candidate_url).hostname or "").lower()
    source_host = (urlparse(source_url).hostname or "").lower()
    return bool(candidate_host and source_host and (
        candidate_host == source_host or candidate_host.endswith("." + source_host)
    ))


def find_date(text, today):
    """First plausible date in text as a date, or None. Ignores dates >7 days ahead."""
    if not text:
        return None
    for pattern in DATE_PATTERNS:
        for match in pattern.finditer(text):
            parts = match.groupdict()
            try:
                year = int(parts["year"])
                day = int(parts["day"])
                month = MONTHS[parts["mon"][:3].lower()] if parts.get("mon") else int(parts["mnum"])
                found = date(year, month, day)
            except (KeyError, ValueError):
                continue
            if found <= today + timedelta(days=7):
                return found
    return None


def find_number(text):
    if not text:
        return None
    match = NUMBER_RE.search(text)
    return re.sub(r"\s+", "", match.group(1)) if match else None


def format_date(iso):
    try:
        return date.fromisoformat(iso).strftime("%b %d, %Y")
    except (TypeError, ValueError):
        return ""


def build_session():
    session = requests.Session()
    retry = Retry(
        total=3, connect=3, read=3, status=3,
        backoff_factor=2,
        status_forcelist=(429, 500, 502, 503, 504),
        allowed_methods=frozenset(["GET"]),
        raise_on_status=False,
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    session.headers.update(HEADERS)
    return session


def fetch(session, url):
    response = session.get(url, timeout=(CONNECT_TIMEOUT, READ_TIMEOUT))
    response.raise_for_status()
    return response


def looks_like_html(response):
    content_type = response.headers.get("Content-Type", "").lower()
    return "html" in content_type or "<html" in response.text[:1000].lower()


# ------------------------------------------------------------- state

def empty_state():
    return {"version": 2, "baselined_sources": [], "items": {}}


def load_state():
    if not STATE_PATH.exists():
        return empty_state()
    try:
        data = json.loads(STATE_PATH.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError("State file is not an object")
    except Exception:
        # Preserve a broken state file for diagnosis rather than crashing.
        backup = STATE_PATH.with_suffix(".corrupt.json")
        if not backup.exists():
            STATE_PATH.replace(backup)
        return empty_state()

    if data.get("version") == 2 and isinstance(data.get("items"), dict):
        data.setdefault("baselined_sources", [])
        return data

    # Migrate v1 ({"initialized": bool, "seen": [urls]}). Old links keep
    # first_seen=None so they count as "known" but never as "new".
    seen = data.get("seen", [])
    seen = [u for u in seen if isinstance(u, str)] if isinstance(seen, list) else []
    state = empty_state()
    if data.get("initialized"):
        state["baselined_sources"] = [s["name"] for s in SOURCES]
    for url in seen:
        state["items"][url] = {
            "agency": None, "title": "", "number": None,
            "published": None, "first_seen": None,
        }
    return state


def save_state(state, now_utc):
    out = {
        "version": 2,
        "last_checked_utc": now_utc.isoformat(),
        "baselined_sources": sorted(state["baselined_sources"]),
        "items": state["items"],
    }
    STATE_PATH.write_text(
        json.dumps(out, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )


# ---------------------------------------------------------- scanning

def make_candidate(source, url, title, context, hint_iso, today):
    """Return a candidate dict if this link looks like an issuance, else None."""
    path = unquote(urlparse(url).path)
    if not KEYWORDS.search(f"{title} {context} {path}"):
        return None

    number = find_number(title) or find_number(context) or find_number(path)
    published = (
        find_date(title, today) or find_date(context, today)
        or (date.fromisoformat(hint_iso) if hint_iso else None)
        or find_date(unquote(url), today)
    )
    is_file = path.lower().endswith(FILE_EXTS)

    qualifies = bool(number or published or is_file)
    if not qualifies and source.get("loose"):
        # A digit that is more than a bare year ("2025 Revenue Memorandum Circulars").
        qualifies = bool(re.search(r"\d", re.sub(r"\b(?:19|20)\d{2}\b", "", title)))
    if not qualifies:
        return None

    return {
        "url": url,
        "title": title[:250],
        "number": number,
        "published": published.isoformat() if published else None,
    }


def merge_candidate(found, cand):
    old = found.get(cand["url"])
    if old is None:
        found[cand["url"]] = cand
        return
    better = (bool(cand["number"]), bool(cand["published"]), len(cand["title"])) > (
        bool(old["number"]), bool(old["published"]), len(old["title"])
    )
    best, other = (cand, old) if better else (old, cand)
    for key in ("number", "published"):
        if not best[key]:
            best[key] = other[key]
    found[cand["url"]] = best


def context_text(anchor):
    """Text of the table row / list item / article holding the link (to find dates)."""
    node = anchor.find_parent(["tr", "li", "article"])
    if node is None:
        # Bare link: only borrow the parent's text if the link is its sole link,
        # otherwise unrelated neighbours (e.g. nav links) would leak into this item.
        parent = anchor.parent
        if parent is None or parent.name in ("body", "html", "nav") or len(parent.find_all("a")) != 1:
            return ""
        node = parent
    text = clean(node.get_text(" ", strip=True))
    return text if len(text) <= 400 else ""


def find_next_page(soup, base_url, source_url):
    link = soup.find("link", rel="next")
    if link is not None and link.get("href"):
        return normalize_url(urljoin(base_url, link["href"]))
    for anchor in soup.find_all("a", href=True):
        href = anchor["href"].strip()
        if not href or href.startswith(("#", "javascript:", "mailto:")):
            continue
        text = clean(anchor.get_text(" ", strip=True)).lower()
        hint = (anchor.get("aria-label") or anchor.get("title") or "").strip().lower()
        if "next" in (anchor.get("rel") or []) or text in NEXT_TEXTS or hint in {"next", "next page"}:
            url = normalize_url(urljoin(base_url, href))
            if is_same_site(url, source_url):
                return url
    return None


def find_feed_url(soup, base_url):
    for link in soup.find_all("link", href=True):
        rel = link.get("rel") or []
        kind = (link.get("type") or "").lower()
        if "alternate" in rel and ("rss" in kind or "atom" in kind):
            return normalize_url(urljoin(base_url, link["href"]))
    return None


def parse_stamp(stamp):
    if not stamp:
        return None
    try:
        return parsedate_to_datetime(stamp).astimezone(PHT).date().isoformat()
    except (TypeError, ValueError):
        pass
    try:
        return datetime.fromisoformat(stamp.replace("Z", "+00:00")).date().isoformat()
    except ValueError:
        return None


def parse_feed(text):
    """RSS/Atom -> [(title, link, iso_date_or_None)]."""
    if len(text) > 2_000_000:
        return []
    root = ET.fromstring(text)
    entries = []
    for element in root.iter():
        if not isinstance(element.tag, str) or element.tag.split("}")[-1] not in ("item", "entry"):
            continue
        title = link = stamp = ""
        for child in element:
            tag = child.tag.split("}")[-1] if isinstance(child.tag, str) else ""
            if tag == "title":
                title = clean(child.text)
            elif tag == "link" and not link:
                href = child.get("href")
                if href and child.get("rel") in (None, "alternate"):
                    link = href.strip()
                elif child.text and child.text.strip():
                    link = child.text.strip()
            elif tag in ("pubDate", "published", "updated", "date") and not stamp:
                stamp = (child.text or "").strip()
        if title and link:
            entries.append((title, link, parse_stamp(stamp)))
    return entries


def scan_source(session, source, today):
    name, page_url = source["name"], source["url"]
    result = {
        "name": name, "url": page_url, "status": "unverified",
        "detail": "", "candidates": [], "pages": 0, "feed": None,
    }
    found = {}
    errors = []
    feed_url = None
    visited = set()
    max_pages = MAX_PAGES if source.get("paginate", True) else 1
    current = page_url

    for page_no in range(max_pages):
        if current in visited:
            break
        visited.add(current)
        try:
            response = fetch(session, current)
        except requests.RequestException as exc:
            errors.append(f"page {page_no + 1}: {type(exc).__name__}: {exc}")
            break
        if not looks_like_html(response):
            content_type = response.headers.get("Content-Type", "") or "unknown type"
            errors.append(f"page {page_no + 1}: content did not appear to be HTML ({content_type})")
            break

        soup = BeautifulSoup(response.text, "html.parser")
        result["pages"] += 1

        for anchor in soup.find_all("a", href=True):
            href = anchor["href"].strip()
            if not href or href.startswith(("#", "mailto:", "tel:", "javascript:")):
                continue
            url = normalize_url(urljoin(response.url, href))
            if urlparse(url).scheme not in ("http", "https") or not is_same_site(url, page_url):
                continue
            if url in (normalize_url(current), normalize_url(page_url)):
                continue
            label = clean(anchor.get_text(" ", strip=True)) or clean(anchor.get("title"))
            context = context_text(anchor)
            if (not label or label.lower() in GENERIC_LABELS) and context:
                # Generic link text ("Download"): use the row text, minus that label.
                title = context[len(label):].strip() if label and context.lower().startswith(label.lower()) else context
                title = title[:200]
            else:
                title = label
            if not title:
                continue
            cand = make_candidate(source, url, title, context, None, today)
            if cand:
                merge_candidate(found, cand)

        if page_no == 0:
            feed_url = find_feed_url(soup, response.url)

        nxt = find_next_page(soup, response.url, page_url)
        if not nxt:
            break
        current = nxt
        time.sleep(PAGE_DELAY_SECONDS)

    feed_note = ""
    if feed_url:
        try:
            feed_response = fetch(session, feed_url)
            for title, link, stamp in parse_feed(feed_response.text):
                link = normalize_url(urljoin(feed_url, link))
                if not is_same_site(link, page_url):
                    continue
                cand = make_candidate(source, link, title, "", stamp, today)
                if cand:
                    merge_candidate(found, cand)
            result["feed"] = feed_url
            feed_note = " Feed used."
        except Exception as exc:  # feeds are a bonus; never fail the source over one
            feed_note = f" Feed found but not usable ({type(exc).__name__})."

    result["candidates"] = list(found.values())

    if result["pages"] == 0 and not result["feed"]:
        result["detail"] = "Could not verify source: " + ("; ".join(errors) or "no page loaded")
        return result

    dated = sum(1 for c in result["candidates"] if c["published"])
    result["status"] = "accessible"
    detail = (
        f"Scanned {result['pages']} page(s); found {len(found)} candidate link(s), "
        f"{dated} with a publication date.{feed_note}"
    )
    if errors:
        detail += " Partial scan: " + "; ".join(errors) + "."
    if not found:
        detail += (
            " No issuance-like links found; the layout may have changed or the page "
            "may load its content with JavaScript. Check this source manually."
        )
    detail += " This does not confirm complete coverage."
    result["detail"] = detail
    return result


# ----------------------------------------------------------- reporting

def sort_key(item):
    return (item.get("published") or item.get("first_seen") or "", item["title"].lower())


def format_item(item, with_agency=True):
    title = item["title"].replace("[", "(").replace("]", ")")
    line = f"[{title}]({item['url']})"
    if with_agency:
        line = f"**{item['agency']}** — {line}"
    meta = []
    if item.get("number"):
        meta.append(f"No. {item['number']}")
    if item.get("published"):
        meta.append(format_date(item["published"]))
    if meta:
        line += " · " + " · ".join(meta)
    return line


def write_report(results, new_items, newly_baselined, latest, now):
    reachable = sum(1 for r in results if r["status"] == "accessible")
    lines = [
        "# Gov Issuance Watch — Daily Report",
        "",
        f"- Checked: {now.strftime('%Y-%m-%d %H:%M:%S')} {now.tzname()}",
        "- Schedule target: 9:00 AM Asia/Manila",
        f"- Sources reachable: {reachable} of {len(results)}",
        "",
        "> Coverage limitation: this report lists candidate links found on the configured pages. "
        "An accessible page is not proof that every issuance was detected. "
        "Sources marked unverified require manual checking.",
        "",
    ]

    lines.extend(["## New since last run", ""])
    if newly_baselined:
        lines.extend([
            f"Baseline established for: {', '.join(newly_baselined)}. Existing links were saved "
            "and are not reported as new.",
            "",
        ])
    if new_items:
        lines.extend(f"- {format_item(item)}" for item in sorted(new_items, key=sort_key, reverse=True))
    else:
        lines.append("No new candidate links were detected during this run.")
    lines.append("")

    lines.extend([f"## Latest circulars (last {RECENT_DAYS} days)", ""])
    if latest:
        lines.extend(f"- {format_item(item)}" for item in latest[:LATEST_OVERALL])
        if len(latest) > LATEST_OVERALL:
            lines.append(f"- …and {len(latest) - LATEST_OVERALL} more")
    else:
        lines.append("No dated or newly found candidates in this window.")
    lines.append("")

    lines.extend(["## Source-by-source check", ""])
    for result in results:
        lines.append(f"### {result['name']}")
        lines.append(f"- Page: {result['url']}")
        lines.append(f"- Status: **{result['status']}**")
        lines.append(f"- Note: {result['detail']}")
        candidates = sorted(result["candidates"], key=sort_key, reverse=True)
        if candidates:
            lines.append(f"- Newest candidates (up to {LATEST_PER_SOURCE}):")
            lines.extend(f"  - {format_item(c, with_agency=False)}" for c in candidates[:LATEST_PER_SOURCE])
            hidden = len(candidates) - LATEST_PER_SOURCE
            if hidden > 0:
                lines.append(f"  - …and {hidden} older candidate(s) not shown")
        lines.append("")

    REPORT_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_new_items_file(new_items, now):
    """Body for the GitHub issue the workflow opens. Removed when nothing is new."""
    if not new_items:
        NEW_ITEMS_PATH.unlink(missing_ok=True)
        return
    lines = [f"New issuances detected on {now.strftime('%Y-%m-%d')}:", ""]
    lines.extend(f"- {format_item(item)}" for item in sorted(new_items, key=sort_key, reverse=True))
    lines.extend(["", "See `daily-report.md` for the full report."])
    NEW_ITEMS_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_circulars_json(state, results, now):
    """Newest-first list of every known circular, for the web app (index.html)."""
    entries = []
    for url, item in state["items"].items():
        if not item.get("title") or not item.get("agency"):
            continue  # legacy v1 entries without metadata fill in once seen again
        path = unquote(urlparse(url).path).lower()
        entries.append({
            "agency": item["agency"],
            "title": item["title"],
            "number": item.get("number"),
            "published": item.get("published"),
            "first_seen": item.get("first_seen"),
            "url": url,
            "file": path.endswith(FILE_EXTS),
        })
    entries.sort(key=sort_key, reverse=True)
    payload = {
        "generated": now.isoformat(),
        "sources": [{"name": r["name"], "url": r["url"], "status": r["status"]} for r in results],
        "items": entries[:MAX_CIRCULARS_JSON],
    }
    CIRCULARS_PATH.write_text(
        json.dumps(payload, indent=1, ensure_ascii=False) + "\n", encoding="utf-8"
    )


# ---------------------------------------------------------------- main

def main():
    now = datetime.now(PHT)
    today = now.date()
    state = load_state()
    items = state["items"]
    baselined = set(state["baselined_sources"])

    session = build_session()
    results = []
    for index, source in enumerate(SOURCES):
        if index:
            time.sleep(PAGE_DELAY_SECONDS)
        try:
            results.append(scan_source(session, source, today))
        except Exception as exc:
            results.append({
                "name": source["name"], "url": source["url"], "status": "unverified",
                "detail": f"Scanning error: {type(exc).__name__}: {exc}",
                "candidates": [], "pages": 0, "feed": None,
            })

    new_items = []
    newly_baselined = []
    for result in results:
        name = result["name"]
        was_baselined = name in baselined
        for cand in result["candidates"]:
            cand["agency"] = name
            known = items.get(cand["url"])
            if known is None:
                cand["first_seen"] = today.isoformat() if was_baselined else None
                items[cand["url"]] = {
                    "agency": name, "title": cand["title"], "number": cand["number"],
                    "published": cand["published"], "first_seen": cand["first_seen"],
                }
                if was_baselined:
                    new_items.append(cand)
            else:
                known["agency"] = known.get("agency") or name
                known["title"] = cand["title"] or known.get("title", "")
                known["number"] = cand["number"] or known.get("number")
                known["published"] = cand["published"] or known.get("published")
                cand["first_seen"] = known.get("first_seen")
                cand["published"] = known["published"]
        if result["status"] == "accessible" and not was_baselined:
            baselined.add(name)
            newly_baselined.append(name)

    cutoff = (today - timedelta(days=RECENT_DAYS)).isoformat()
    latest_by_url = {}
    for result in results:
        for cand in result["candidates"]:
            effective = cand.get("published") or cand.get("first_seen")
            if effective and effective >= cutoff:
                latest_by_url.setdefault(cand["url"], cand)
    latest = sorted(latest_by_url.values(), key=sort_key, reverse=True)

    state["baselined_sources"] = sorted(baselined)
    save_state(state, datetime.now(timezone.utc))
    write_report(results, new_items, newly_baselined, latest, now)
    write_new_items_file(new_items, now)
    write_circulars_json(state, results, now)

    print(f"Checked {len(results)} configured sources.")
    print(f"New candidate links: {len(new_items)}")
    print(f"Latest (last {RECENT_DAYS} days): {len(latest)}")
    print(f"Report written to: {REPORT_PATH.name}")

    if all(r["status"] != "accessible" for r in results):
        print("ERROR: no source could be reached.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
