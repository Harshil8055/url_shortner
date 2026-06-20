\# URL Shortener — High-Level Design (HLD)



This document builds directly on `01-requirements.md`. Every structural decision below

traces back to a specific requirement; where a tradeoff was made, the reasoning is

stated explicitly rather than left implicit.



\---



\## 1. Architecture Style



\*\*Layered architecture: Controller → Service → Repository\*\*, the standard Spring Boot

pattern. Chosen over a flatter Controller→Repository design because the business logic

here is non-trivial enough to deserve isolation:



\- URL validation

\- Custom-alias conflict checking

\- Duplicate-URL idempotency lookup

\- Short-code generation + collision retry



Keeping this logic in a `Service` class — rather than in the `Controller` or scattered

into the `Repository` — means each layer has one job: Controller translates HTTP ↔

DTOs, Service makes all the decisions, Repository only stores and retrieves.



```

Client

&#x20; │

&#x20; ▼

UrlController          (HTTP layer: request parsing, status codes, DTO mapping)

&#x20; │

&#x20; ▼

UrlShortenerService     (business logic: validation, dedup, alias, orchestration)

&#x20; │              │

&#x20; ▼              ▼

UrlRepository   ShortCodeGenerator

(interface)     (random base62 code generation)

&#x20; │

&#x20; ▼

InMemoryUrlRepository   (concrete impl: two ConcurrentHashMaps, synchronized writes)

```



\*(See the diagram rendered earlier in this conversation for the visual component view.)\*



\---



\## 2. Component Responsibilities



\### 2.1 `UrlController`

\- Exposes `POST /shorten` and `GET /{code}`.

\- Deserializes request JSON into a `ShortenRequest` DTO.

\- Delegates all logic to `UrlShortenerService`.

\- Maps service-layer outcomes (success / validation failure / alias conflict / not

&#x20; found) to HTTP status codes and response bodies.

\- Contains \*\*no business logic\*\* — if a decision requires an `if`, it almost certainly

&#x20; belongs in the service layer, not here.



\### 2.2 `UrlShortenerService`

The orchestrator. Returns a `ServiceResult<T>` from every public method — \*\*no

exceptions are thrown for expected failure cases\*\* (invalid URL, invalid alias, alias

conflict, not found). This keeps control flow explicit and linear: the Controller

inspects the result's status and maps it directly to an HTTP response, with no

try/catch or global exception handler involved. See §5 for the full Result contract.



For `POST /shorten`, in order:

1\. \*\*Validate\*\* the submitted URL (well-formed, has scheme + host). Reject early with a

&#x20;  validation failure if not — per requirements §2.5.

2\. \*\*Branch on whether a custom alias was supplied:\*\*

&#x20;  - \*\*Alias supplied\*\* → check `UrlRepository.existsByCode(alias)`.

&#x20;    - If taken → return a conflict outcome (controller maps to `409`).

&#x20;    - If free → validate alias characters (URL-safe set, length bound), then save

&#x20;      `(alias → url)` directly. \*\*No dedup check against existing URLs\*\* — per

&#x20;      requirements §2.3, custom-alias requests always create a new entry.

&#x20;  - \*\*No alias supplied\*\* → check `UrlRepository.findByUrl(url)`.

&#x20;    - If an entry already exists for this exact URL string → return that existing

&#x20;      code (idempotent path, per requirements §2.4).

&#x20;    - If not → generate a new code via `ShortCodeGenerator`, save it, return it.



For `GET /{code}`:

1\. Call `UrlRepository.findByCode(code)`.

2\. Found → return the original URL (controller issues `301` with `Location` header).

3\. Not found → return a not-found outcome (controller maps to `404`).



\### 2.3 `ShortCodeGenerator`

Single responsibility: produce a random 7-character Base62 string on request. \*\*Does

not\*\* know about the repository or about collision-checking — that orchestration lives

in the service layer, keeping the generator a pure, easily-unit-testable function:

`generate() -> String`.



\### 2.4 `UrlRepository` (interface)

Defines the storage contract, independent of implementation:

```java

public interface UrlRepository {

&#x20;   void save(String code, String url);

&#x20;   Optional<String> findByCode(String code);

&#x20;   Optional<String> findByUrl(String url);

&#x20;   boolean existsByCode(String code);

}

```

Putting this behind an interface — even though only one implementation exists — means

a future swap to Postgres/Redis/etc. touches only a new class implementing this

contract; the service layer is untouched. This was a deliberate choice from the

requirements discussion (§6.1), not default ceremony.



\### 2.5 `InMemoryUrlRepository`

Concrete implementation using \*\*two `ConcurrentHashMap`s\*\*:

\- `codeToUrl: Map<String, String>` — primary index, serves `findByCode` (the redirect

&#x20; path).

\- `urlToCode: Map<String, String>` — reverse index, serves `findByUrl` (the dedup

&#x20; check), avoiding an O(n) scan over all entries on every shorten request.



\*\*Concurrency design\*\* (see §4 below for full reasoning):

\- \*\*Reads are lock-free\*\* — `findByCode` and `findByUrl` are plain `ConcurrentHashMap`

&#x20; reads, no synchronization, full throughput.

\- \*\*Writes are synchronized\*\* — `save()` wraps updates to both maps in a single

&#x20; synchronized block, so a `(code, url)` pair is never partially visible (i.e., never a

&#x20; state where `codeToUrl` has the entry but `urlToCode` doesn't, or vice versa).



\---



\## 3. Request Flow Walkthroughs



\### 3.1 POST /shorten — no alias, new URL

1\. Client sends `{"url": "https://example.com/long/path"}`.

2\. Controller parses into DTO, calls `service.shorten(url, alias=null)`.

3\. Service validates URL → well-formed, passes.

4\. No alias given → service calls `repository.findByUrl(url)` → empty (not seen

&#x20;  before).

5\. Service calls `generator.generate()` → e.g. `"aZ3x9Qm"`.

6\. Service calls `repository.existsByCode("aZ3x9Qm")` → false (collision check).

7\. Service calls `repository.save("aZ3x9Qm", url)`.

8\. Controller returns `201 Created` with the code, short URL, and original URL.



\### 3.2 POST /shorten — no alias, duplicate URL (idempotent path)

1\. Client sends the same `url` as 3.1 again, no alias.

2\. Service validates → passes.

3\. `repository.findByUrl(url)` → \*\*found\*\*, returns existing code `"aZ3x9Qm"`.

4\. Service short-circuits: \*\*no new code generated, no new write\*\*.

5\. Controller returns `201 Created` with the \*same\* code as before.



\### 3.3 POST /shorten — custom alias, free

1\. Client sends `{"url": "...", "alias": "my-link"}`.

2\. Service validates URL, then validates alias characters/length.

3\. `repository.existsByCode("my-link")` → false.

4\. Service calls `repository.save("my-link", url)` — \*\*no dedup-by-URL check at all\*\*,

&#x20;  even if this exact URL already has another code from 3.1.

5\. Controller returns `201 Created` with `code = "my-link"`.



\### 3.4 POST /shorten — custom alias, taken

1\. Client sends `{"url": "...", "alias": "my-link"}` (already used above).

2\. `repository.existsByCode("my-link")` → true.

3\. Service returns a conflict outcome.

4\. Controller returns `409 Conflict`.



\### 3.5 GET /{code} — known code

1\. Client requests `GET /aZ3x9Qm`.

2\. Service calls `repository.findByCode("aZ3x9Qm")` → found.

3\. Controller returns `301 Moved Permanently`, `Location: https://example.com/long/path`.



\### 3.6 GET /{code} — unknown code

1\. Client requests `GET /doesNotExist`.

2\. `repository.findByCode(...)` → empty.

3\. Controller returns `404 Not Found` with a small JSON error body.



\---



\## 4. Collision \& Concurrency Design (the core "be ready to explain why" piece)



\### 4.1 Why short codes won't collide

\- Codes are random Base62 strings, fixed length 7 → search space = 62⁷ ≈ \*\*3.52

&#x20; trillion\*\* combinations.

\- \*\*Correctness does not rely on this number being large\*\* — it relies on the

&#x20; \*\*existence check before save\*\* (`existsByCode`) combined with \*\*retry-on-collision\*\*.

&#x20; If a freshly generated code happens to already exist, the service simply generates

&#x20; another and checks again. This is a `while (exists) { regenerate }` loop with a

&#x20; \*\*hard cap of 5 retries\*\* as a defensive measure against a pathological/misconfigured

&#x20; generator. If all 5 attempts collide (probability so close to zero it is not

&#x20; expected to ever trigger at this scale — see calculation below), the service returns

&#x20; `ServiceResult.Status.GENERATION\_FAILED`, which the controller maps to `500 Internal

&#x20; Server Error`. This cap exists purely as a defensive backstop, not because collisions

&#x20; are anticipated.

\- This means the claim isn't "collisions are statistically unlikely" — it's

&#x20; \*\*"collisions are structurally impossible to persist,"\*\* because the system never

&#x20; commits a code without first confirming it's free. The large search space just keeps

&#x20; the expected number of retries at effectively zero (probability of even one

&#x20; collision in 10 million stored codes ≈ 0.0000029, by the birthday-bound

&#x20; approximation) — the safety net (retry loop) is what makes the guarantee absolute,

&#x20; not the math making collisions merely improbable.



\### 4.2 Why the two-map write needs synchronization

\- A single `POST /shorten` (non-alias path) results in \*\*two writes\*\*:

&#x20; `codeToUrl.put(code, url)` and `urlToCode.put(url, code)`.

\- `ConcurrentHashMap` guarantees thread-safety \*\*per individual operation\*\*, but not

&#x20; atomicity \*\*across two separate maps\*\*. Without an explicit lock, two threads

&#x20; shortening the \*same\* URL concurrently could both pass the `findByUrl` check (seeing

&#x20; "not found" in both), both generate different codes, and both write — silently

&#x20; producing \*\*two different codes for the same URL\*\*, breaking the idempotency

&#x20; guarantee from requirements §2.4.

\- Fix: the entire \*\*check-then-act\*\* sequence (`findByUrl` → generate-if-absent →

&#x20; write-both-maps) for the no-alias path is wrapped in a single synchronized block in

&#x20; the repository/service. This makes writes serialize against each other, while reads

&#x20; (`findByCode` for the redirect path — the hot path of the whole system) remain

&#x20; completely lock-free.

\- This is a deliberate \*\*lock-free-reads, synchronized-writes\*\* tradeoff: writes are

&#x20; inherently less frequent than redirects in a URL shortener, and the critical section

&#x20; is tiny (in-memory map operations only, no I/O), so contention cost is negligible.



\---



\## 5. Error Handling Strategy



\*\*Result-object pattern, not exceptions.\*\* Every `UrlShortenerService` method returns a

generic `ServiceResult<T>` (status enum + optional data + message) instead of throwing.

The Controller inspects `result.getStatus()` and maps it directly to an HTTP response

via a `switch`. This keeps the failure paths as ordinary, traceable control flow rather

than exception-driven control flow, and avoids a global exception handler entirely —

there's nothing to intercept because nothing is thrown for expected business outcomes.



(Genuine unexpected errors — e.g. a bug causing a `NullPointerException` — are not

business outcomes and are not wrapped in `ServiceResult`; Spring's default error

handling covers those as a true exceptional case, separate from this contract.)



| Result Status | HTTP Status | Returned by |

|---|---|---|

| `SUCCESS` | 201 (shorten) / 301 (redirect) | Both shorten and redirect happy paths |

| `INVALID\_URL` | 400 | Malformed/invalid URL on shorten |

| `INVALID\_ALIAS` | 400 | Invalid alias characters or length |

| `ALIAS\_CONFLICT` | 409 | Alias already taken |

| `NOT\_FOUND` | 404 | Unknown code on redirect |

| `GENERATION\_FAILED` | 500 | Defensive: code generator exhausted its retry cap (see §4.1) |



\---



\## 6. Traceability to Requirements



| Requirement | HLD Component(s) |

|---|---|

| POST /shorten, GET /{code} only | `UrlController` |

| In-memory store | `InMemoryUrlRepository` |

| Custom alias support, 409 on conflict | `UrlShortenerService` §2.2, `ServiceResult.Status.ALIAS\_CONFLICT` |

| Idempotent duplicate-URL handling | `UrlShortenerService` §2.2 (no-alias branch), `urlToCode` reverse index |

| Collision-free short codes | `ShortCodeGenerator` + `existsByCode` retry loop, §4.1 |

| 404 for unknown codes | `UrlController` + `ServiceResult.Status.NOT\_FOUND` |

| URL validation (any scheme, well-formed) | `UrlShortenerService` validation step |

| Repository swappable for real DB later | `UrlRepository` interface |

| Thread-safety under concurrent requests | `InMemoryUrlRepository` synchronized writes, §4.2 |



\---



\## 7. Open Items Carried Into LLD

\- Exact method signatures, DTO field definitions, and exception class hierarchy.

\- Exact validation regex/library choice for URL well-formedness (e.g. Java's `java.net.URI`

&#x20; vs a stricter validator).

\- Exact alias character-validation logic and length-bound constant placement.

\- Package structure and class layout within the Maven project.

\- Specific JUnit test cases per component (unit) and per endpoint (integration).

