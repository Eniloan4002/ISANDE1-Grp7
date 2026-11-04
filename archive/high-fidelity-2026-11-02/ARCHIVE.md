# High-fidelity prototype archive

Captured 2026-11-02 from commit `4b8bf3b201b84359575c5dd55d1e0d32ffa6549c` with a clean working tree. The original HTML, CSS, JavaScript and demo guide are preserved byte for byte; MANIFEST.json records their SHA-256 checksums.

From the repository root: `python3 -m http.server 8080`, then visit `http://localhost:8080/archive/high-fidelity-2026-11-02/`. Select a role. Follow DEMO_SCENARIO.md for the cross-module walkthrough. External Google Fonts require internet; system fonts work offline.

This is a frozen design and behavior reference. It uses localStorage, seeded student records and role selection without authentication. Export buttons may only show notifications. Honor thresholds are illustrative and NOT approved ICI policy. Do not use its browser storage as production data. Browser storage is shared by origin with the original root demo; use a separate browser profile or port when comparing independent datasets.

New implementation lives in frontend/ and backend/. The original root demo remains available for existing links. Do not implement new features in the archived copy.
