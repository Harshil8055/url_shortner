# URL Shortener — Requirements Document

## 1. Overview
A minimal, single-instance URL shortening service built in **Java (Spring Boot, Maven)**.
It exposes two HTTP endpoints — one to create a short code for a long URL, and one to
redirect a short code back to its original URL. Mappings are persisted in an **in-memory
hash map** (explicitly chosen for this demo; see §6 Assumptions & Limitations).

This document captures every functional decision needed before design begins. Every
open question from the assignment brief has been resolved deliberately below — nothing
is left to implicit assumption.

---

## 2. Functional Requirements

### 2.1 POST /shorten
Accepts a long URL and returns a short code that redirects to it.

**Request body (JSON):**
```json
{
  "url": "https://example.com/some/very/long/path?with=params",
  "alias": "my-custom-code"
}
```
- `url` — **required**, string. Must be a well-formed absolute URL (see §2.4 Validation).
- `alias` — **optional**, string. If provided, used as the short code instead of generating
  one. See §2.3 Custom Alias Behavior.

**Success response — `201 Created`:**
```json
{
  "code": "my-custom-code",
  "shortUrl": "http://localhost:8080/my-custom-code",
  "originalUrl": "https://example.com/some/very/long/path?with=params"
}
```

**Error responses:**
| Status | Condition |
|---|---|
| `400 Bad Request` | `url` missing, malformed, or not well-formed; `alias` contains invalid characters |
| `409 Conflict` | requested `alias` already maps to an existing entry |

### 2.2 GET /{code}
Redirects to the original URL for a known code.

- **`301 Moved Permanently`**, with `Location` header set to the original URL — if `code` exists.
- **`404 Not Found`**, with a small JSON error body — if `code` does not exist.

There is no concept of "expired" in this version (see §6), so every code is either
known (301) or unknown (404) — no third state.

### 2.3 Custom Alias Behavior (deliberate design decision)
- If the caller supplies `alias`:
  - If the alias is **already taken** (by any prior entry, regardless of what URL it
    points to) → `409 Conflict`. The system never silently overwrites or falls back to
    a random code.
  - If the alias is free → a **new entry is created** using that alias as the code,
    **even if the same URL already has a different code** from a previous request.
    Custom-alias requests always create a new, separate mapping; they never reuse or
    merge with an existing auto-generated entry for the same URL.
- If the caller does **not** supply `alias` → see §2.4 Duplicate URL Behavior.

**Allowed alias characters:** any URL-safe character (per RFC 3986 unreserved set:
`A–Z a–z 0–9 - _ . ~`). Anything outside this set → `400 Bad Request`.

**Alias length:** no hard limit specified by stakeholder ("no limit, within reason").
Default assumption for implementation: **1–64 characters**. This bound will be stated
explicitly in code/config so it's a visible decision, not a hidden constant.

**Case sensitivity:** aliases and generated codes are **case-sensitive**
(`AbC123` ≠ `abc123`), consistent with standard URL-shortener conventions.

### 2.4 Duplicate URL Behavior (deliberate design decision)
Applies **only when no custom alias is given** in the request.

- If the exact same `url` string has been shortened before (via an auto-generated
  code) → return the **existing code**, idempotently. No new entry is created.
  Response is still `201 Created` with the existing code's data (this is a deliberate
  simplification — see §6 for the alternative of returning `200 OK` instead).
- "Same URL" means **exact string match** — no normalization (no trailing-slash
  trimming, no scheme/host case-folding, no query-param reordering). This is an
  explicit decision, not an oversight: `https://example.com/page` and
  `https://example.com/page/` are treated as **different** URLs.
- This idempotency check is bypassed entirely whenever a custom alias is supplied
  (see §2.3) — custom-alias requests always create a new entry.

### 2.5 URL Validation
A submitted `url` is considered valid if:
- It is a well-formed absolute URL (parses successfully as a URI with a scheme and
  host).
- **Any scheme is accepted** (not restricted to http/https) — only structural
  well-formedness is checked, per stakeholder decision.

Invalid input → `400 Bad Request` with an error message indicating why.

### 2.6 Short Code Generation (deliberate design decision)
- Codes are generated as **random Base62 strings** (`A–Z`, `a–z`, `0–9`), **fixed
  length of 7 characters**.
- Search space: 62⁷ ≈ 3.5 trillion possible codes.
- **Collision handling:** uniqueness is enforced by checking the datastore for
  existence before committing a new code; on collision, regenerate and retry. This
  makes the system **provably collision-free at the data level** (not just
  "statistically unlikely") — the probability calculation only affects how often a
  retry is needed, not correctness.
- Full rationale and alternatives considered are documented in the HLD (§ Short Code
  Generator design).

---

## 3. Non-Functional Requirements
Per stakeholder direction, this assignment optimizes for **functional correctness and
clarity of design reasoning**, not scale/performance/rate-limiting. These are
explicitly out of scope but noted as future-scope considerations (§6).

---

## 4. Out of Scope for This Submission (documented, not overlooked)
The following were discussed and deliberately deferred:
- **Authentication / authorization** — no login, API keys, or ownership model. Anyone
  can shorten any URL or access any code. Captured here so it's visible as a conscious
  choice for a future iteration, not a gap.
- **Expiry / TTL on links** — originally considered mandatory, then explicitly moved to
  future scope. In this submission, **links never expire**.
- **Analytics** (click counts, timestamps, referrers) — not implemented.
- **DELETE / metadata endpoints** — only the two required endpoints (`POST /shorten`,
  `GET /{code}`) are implemented.
- **Rate limiting / abuse prevention** — not implemented.
- **URL normalization** for duplicate detection — exact string match only.

---

## 5. Tech Stack
| Concern | Choice |
|---|---|
| Language | Java |
| Framework | Spring Boot |
| Build tool | Maven |
| Datastore | In-memory `ConcurrentHashMap` (see §6) |
| Testing | JUnit (unit + integration tests) |

---

## 6. Assumptions & Limitations (explicit)
1. **In-memory storage** means all mappings are lost on application restart. This is an
   acceptable, deliberate tradeoff for a demo service — explicitly chosen over
   SQLite/Postgres/Redis for simplicity. The HLD will isolate this behind a repository
   interface so swapping in a real datastore later is a contained change.
2. **Alias max length of 64 characters** is an implementation default, not a
   stakeholder-specified number — flagged here as an assumption.
3. **POST /shorten on duplicate URL returns 201**, not 200, even though no new resource
   was technically created. Alternative considered: return `200 OK` to signal "this
   already existed." **Confirmed decision: `201`**, for response-shape consistency (the
   body always represents a successfully shortened URL, and the caller doesn't need to
   branch on status code to get the data it wants). Worth a one-line mention in
   submission notes as a conscious tradeoff over strict REST semantics.
4. Single-instance, single-process service — no concerns about distributed ID
   generation or multi-node coordination (would matter if the in-memory store were ever
   swapped for a clustered setup).

---

## 7. Open Items Carried Into HLD
None outstanding — all design forks identified during requirements gathering have been
resolved above. HLD will focus on component structure, the short-code generator's
internal design, and how validation/dedup/alias logic compose around the storage layer.
