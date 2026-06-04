"""
leads/example.py
Example usage of the lead import module.
Run directly:  python -m leads.example
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Allow running from inside the ai-python directory
sys.path.insert(0, str(Path(__file__).parent.parent))

from leads.importer import import_leads


def main() -> None:
    leads_file = Path(__file__).parent.parent.parent / "leads" / "leads.xlsx"

    print(f"\n{'─' * 55}")
    print(f"  Lead Import Example")
    print(f"  File: {leads_file}")
    print(f"{'─' * 55}\n")

    try:
        result = import_leads(leads_file)
    except FileNotFoundError as exc:
        print(f"[ERROR] {exc}")
        sys.exit(1)
    except ValueError as exc:
        print(f"[ERROR] {exc}")
        sys.exit(1)

    # ── Summary ──────────────────────────────────────────────────────────────
    print(f"Total rows read  : {result.total_rows}")
    print(f"Valid leads      : {result.valid_count}")
    print(f"Invalid rows     : {result.invalid_count}")
    print(f"Duplicates removed: {result.duplicate_count}")

    # ── Valid leads ───────────────────────────────────────────────────────────
    print(f"\nClean Lead List ({result.valid_count}):")
    print(json.dumps([lead.to_dict() for lead in result.leads], ensure_ascii=False, indent=2))

    # ── Invalid rows ──────────────────────────────────────────────────────────
    if result.invalid_rows:
        print(f"\nInvalid Rows ({result.invalid_count}):")
        for inv in result.invalid_rows:
            print(
                f"  Row {inv.row_number:>3} | "
                f"name={inv.raw_name!r:20} | "
                f"phone={inv.raw_phone!r:20} | "
                f"reason: {inv.reason}"
            )
    else:
        print("\nNo invalid rows found.")

    print(f"\n{'─' * 55}\n")


if __name__ == "__main__":
    main()
