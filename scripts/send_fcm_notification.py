#!/usr/bin/env python3
"""Send a topic notification when monitor.py creates new-items.md."""
import json
import os
import sys
from pathlib import Path

import requests
from google.auth.transport.requests import Request
from google.oauth2 import service_account

PROJECT_ID = "ph-gov-issuance-watch"
TOPIC = "issuance-updates"
SCOPES = ["https://www.googleapis.com/auth/firebase.messaging"]


def main() -> int:
    report_path = Path("new-items.md")
    if not report_path.is_file():
        print("No new-items.md file; no notification to send.")
        return 0

    raw_credentials = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw_credentials:
        print("FIREBASE_SERVICE_ACCOUNT_JSON secret is not configured.", file=sys.stderr)
        return 2

    try:
        info = json.loads(raw_credentials)
        credentials = service_account.Credentials.from_service_account_info(
            info, scopes=SCOPES
        )
        if info.get("project_id") != PROJECT_ID:
            raise ValueError("Service account project_id does not match the Firebase project.")
        credentials.refresh(Request())
    except Exception as exc:
        print(f"Could not prepare Firebase credentials: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2

    lines = [line.strip() for line in report_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    count = sum(1 for line in lines if line.startswith("- "))
    title = "New government issuances detected"
    body = f"{count} new item(s) found. Open PH-Gov Issuance Watch to review the report."
    payload = {
        "message": {
            "topic": TOPIC,
            "notification": {"title": title, "body": body},
            "data": {"title": title, "body": body, "type": "new_issuances"},
            "android": {"priority": "HIGH", "notification": {"channel_id": "issuance_updates"}},
        }
    }
    endpoint = f"https://fcm.googleapis.com/v1/projects/{PROJECT_ID}/messages:send"
    response = requests.post(
        endpoint,
        headers={
            "Authorization": f"Bearer {credentials.token}",
            "Content-Type": "application/json; charset=UTF-8",
        },
        json=payload,
        timeout=30,
    )
    if not response.ok:
        print(f"Firebase returned HTTP {response.status_code}: {response.text[:1000]}", file=sys.stderr)
        return 1

    print("Firebase notification sent to topic issuance-updates.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
