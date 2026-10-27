# Decision Log

Status: C = confirmed, P = provisional, U = unverified, X = conflict.

| ID | Status | Item | Notes |
|----|--------|------|-------|
| D-001 | P | Root manifest has six sections (logicalServices ... transformations) | From project prompt |
| D-002 | U | Placement of systemEonId and system name | Kept out of manifest model |
| D-003 | P | systemKey unique across the whole manifest | Pending official scope |
| D-004 | P | Enum spellings (Transaction/Reference, Yes/No, Third Party, DB type list) | Verify capitalization |
| D-005 | U | Approved PII names | Examples only; no catalog assumed |
| D-006 | U | Database logicalName (TAI) rules | Not enforced until confirmed |
| D-007 | U | sourcedFromColumns shape | Opaque maps, never synthesized |
| D-008 | P | Validation level | LOCAL only; no official schema or FDC acceptance evidence |
