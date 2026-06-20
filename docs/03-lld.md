# URL Shortener — Low-Level Design (LLD)

This document builds on `02-hld.md` and translates each component into concrete Java
class signatures, package structure, and implementation-level logic — close enough to
mechanical that the implementation phase is largely "write what's specified here."

---

## 1. Package Structure

```
com.example.urlshortener
├── UrlShortenerApplication.java        (Spring Boot entry point)
├── controller/
│   └── UrlController.java
├── service/
│   ├── UrlShortenerService.java
│   ├── ServiceResult.java
│   └── ShortCodeGenerator.java
├── repository/
│   ├── UrlRepository.java              (interface)
│   └── InMemoryUrlRepository.java
├── dto/
│   ├── ShortenRequest.java
│   ├── ShortenResponse.java
│   └── ErrorResponse.java
└── validation/
    ├── UrlValidator.java
    └── AliasValidator.java
```

Rationale: `dto` and `validation` are split out as their own packages rather than
folded into `controller`/`service`, since both are small, focused, and independently
unit-testable — DTOs are pure data carriers, validators are pure functions with no
Spring dependencies.

---

## 2. DTOs

### 2.1 `ShortenRequest` (request body for POST /shorten)
```java
public class ShortenRequest {
    private String url;     // required
    private String alias;   // optional, nullable

    // getters/setters (Jackson needs these for deserialization)
}
```

### 2.2 `ShortenResponse` (success body for POST /shorten)
```java
public class ShortenResponse {
    private final String code;
    private final String shortUrl;
    private final String originalUrl;

    public ShortenResponse(String code, String shortUrl, String originalUrl) { ... }
    // getters only — immutable response object
}
```

### 2.3 `ErrorResponse` (body for 400 / 404 / 409 / 500)
```java
public class ErrorResponse {
    private final String error;     // short machine-friendly code, e.g. "INVALID_URL"
    private final String message;   // human-readable detail

    public ErrorResponse(String error, String message) { ... }
    // getters only
}
```

---

## 3. `ServiceResult<T>` — the error-handling backbone

```java
public class ServiceResult<T> {

    public enum Status {
        SUCCESS,
        INVALID_URL,
        INVALID_ALIAS,
        ALIAS_CONFLICT,
        NOT_FOUND,
        GENERATION_FAILED
    }

    private final Status status;
    private final T data;          // non-null only when status == SUCCESS
    private final String message;  // human-readable detail, set on non-SUCCESS

    private ServiceResult(Status status, T data, String message) {
        this.status = status;
        this.data = data;
        this.message = message;
    }

    public static <T> ServiceResult<T> success(T data) {
        return new ServiceResult<>(Status.SUCCESS, data, null);
    }

    public static <T> ServiceResult<T> invalidUrl(String message) {
        return new ServiceResult<>(Status.INVALID_URL, null, message);
    }

    public static <T> ServiceResult<T> invalidAlias(String message) {
        return new ServiceResult<>(Status.INVALID_ALIAS, null, message);
    }

    public static <T> ServiceResult<T> aliasConflict(String message) {
        return new ServiceResult<>(Status.ALIAS_CONFLICT, null, message);
    }

    public static <T> ServiceResult<T> notFound(String message) {
        return new ServiceResult<>(Status.NOT_FOUND, null, message);
    }

    public static <T> ServiceResult<T> generationFailed(String message) {
        return new ServiceResult<>(Status.GENERATION_FAILED, null, message);
    }

    public Status getStatus() { return status; }
    public T getData() { return data; }
    public String getMessage() { return message; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
}
```

Used as `ServiceResult<ShortenResponse>` for the shorten flow, and `ServiceResult<String>`
(data = original URL) for the redirect flow.

---

## 4. Validators

### 4.1 `UrlValidator`
```java
public class UrlValidator {

    public static boolean isValid(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = new URI(url);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
```
- Uses `java.net.URI` (built-in, no external dependency) per earlier decision.
- A URL is valid if it parses as a syntactically correct URI **and** has both a scheme
  (`http`, `https`, `ftp`, anything — any scheme accepted per requirements §2.5) and a
  host. This rejects garbage like `"not a url"` or `"/just/a/path"` while accepting any
  well-formed absolute URL regardless of scheme.
- Stateless static utility — trivially unit-testable with a table of valid/invalid
  inputs.

### 4.2 `AliasValidator`
```java
public class AliasValidator {

    private static final int MIN_LENGTH = 1;
    private static final int MAX_LENGTH = 64;
    private static final Pattern URL_SAFE_PATTERN =
        Pattern.compile("^[A-Za-z0-9\\-_.~]+$");

    public static boolean isValid(String alias) {
        if (alias == null) return false;
        int len = alias.length();
        if (len < MIN_LENGTH || len > MAX_LENGTH) return false;
        return URL_SAFE_PATTERN.matcher(alias).matches();
    }
}
```
- Character set: RFC 3986 unreserved characters (`A–Z a–z 0–9 - _ . ~`) — matches the
  "any URL-safe characters" decision from requirements §2.3.
- Length bound: 1–64, the assumption explicitly flagged in requirements §6.2.
- Both constants are named and visible at the top of the class — not magic numbers
  buried in logic — so the assumption is easy to spot and change later if needed.

---

## 5. `ShortCodeGenerator`

```java
public class ShortCodeGenerator {

    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 7;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
```
- `SecureRandom` per earlier decision — cryptographically strong randomness, avoids
  any theoretical predictability concern with `java.util.Random`'s linear congruential
  algorithm (not a strict requirement here, but a defensible default with negligible
  performance cost at this scale).
- Pure function: no dependency on the repository, no knowledge of collisions — single
  responsibility, trivially unit-testable (e.g. assert length == 7, assert every char
  is in the alphabet, statistical distribution sanity checks).
- 62-character alphabet, 7-length, matches the 62⁷ ≈ 3.5 trillion search space agreed
  in HLD §4.1.

---

## 6. `UrlRepository` (interface) and `InMemoryUrlRepository`

```java
public interface UrlRepository {
    void save(String code, String url);
    Optional<String> findByCode(String code);
    Optional<String> findByUrl(String url);
    boolean existsByCode(String code);
}
```

```java
@Repository
public class InMemoryUrlRepository implements UrlRepository {

    private final Map<String, String> codeToUrl = new ConcurrentHashMap<>();
    private final Map<String, String> urlToCode = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    @Override
    public void save(String code, String url) {
        synchronized (writeLock) {
            codeToUrl.put(code, url);
            urlToCode.put(url, code);
        }
    }

    @Override
    public Optional<String> findByCode(String code) {
        return Optional.ofNullable(codeToUrl.get(code));   // lock-free read
    }

    @Override
    public Optional<String> findByUrl(String url) {
        return Optional.ofNullable(urlToCode.get(url));    // lock-free read
    }

    @Override
    public boolean existsByCode(String code) {
        return codeToUrl.containsKey(code);                // lock-free read
    }
}
```
- A dedicated `writeLock` object (rather than `synchronized` on `this`) is used so that
  if other synchronized methods are ever added to this class for unrelated reasons,
  they don't unintentionally contend with the write path's lock. Small defensive
  choice, costs nothing.
- **Important subtlety for custom-alias writes:** when saving a custom-alias entry for
  a URL that may already exist under a *different* code, `urlToCode.put(url, code)`
  will **overwrite** the reverse-index entry to point at the newest code. This is
  intentional and harmless: `urlToCode` is only ever consulted by the no-alias dedup
  path (HLD §3.2), and per requirements §2.3 a URL can have multiple valid codes
  simultaneously — `urlToCode` just tracks one canonical "the code we'll return if
  asked again with no alias," which reasonably becomes the most recent one. The old
  code remains fully valid and resolvable via `codeToUrl` regardless.

---

## 7. `UrlShortenerService`

```java
@Service
public class UrlShortenerService {

    private static final int MAX_GENERATION_RETRIES = 5;

    private final UrlRepository repository;
    private final ShortCodeGenerator generator;

    public UrlShortenerService(UrlRepository repository, ShortCodeGenerator generator) {
        this.repository = repository;
        this.generator = generator;
    }

    public ServiceResult<ShortenResponse> shorten(String url, String alias, String baseUrl) {
        if (!UrlValidator.isValid(url)) {
            return ServiceResult.invalidUrl("The provided URL is not well-formed.");
        }

        if (alias != null && !alias.isBlank()) {
            return shortenWithAlias(url, alias, baseUrl);
        }
        return shortenWithGeneratedCode(url, baseUrl);
    }

    private ServiceResult<ShortenResponse> shortenWithAlias(String url, String alias, String baseUrl) {
        if (!AliasValidator.isValid(alias)) {
            return ServiceResult.invalidAlias(
                "Alias must be 1-64 URL-safe characters (A-Z, a-z, 0-9, -, _, ., ~).");
        }
        if (repository.existsByCode(alias)) {
            return ServiceResult.aliasConflict("Alias '" + alias + "' is already taken.");
        }
        repository.save(alias, url);
        return ServiceResult.success(buildResponse(alias, url, baseUrl));
    }

    private ServiceResult<ShortenResponse> shortenWithGeneratedCode(String url, String baseUrl) {
        Optional<String> existingCode = repository.findByUrl(url);
        if (existingCode.isPresent()) {
            return ServiceResult.success(buildResponse(existingCode.get(), url, baseUrl));
        }

        for (int attempt = 0; attempt < MAX_GENERATION_RETRIES; attempt++) {
            String candidate = generator.generate();
            if (!repository.existsByCode(candidate)) {
                repository.save(candidate, url);
                return ServiceResult.success(buildResponse(candidate, url, baseUrl));
            }
        }
        return ServiceResult.generationFailed(
            "Could not generate a unique short code after " + MAX_GENERATION_RETRIES + " attempts.");
    }

    public ServiceResult<String> redirect(String code) {
        return repository.findByCode(code)
            .map(ServiceResult::success)
            .orElseGet(() -> ServiceResult.notFound("No URL found for code '" + code + "'."));
    }

    private ShortenResponse buildResponse(String code, String url, String baseUrl) {
        return new ShortenResponse(code, baseUrl + "/" + code, url);
    }
}
```

Notes:
- Constructor injection (no field injection) — standard Spring practice, and makes the
  class trivially testable with mocked `UrlRepository`/`ShortCodeGenerator` via
  Mockito, per the testing-approach decision.
- `baseUrl` is passed in (rather than hardcoded) so the service doesn't need to know
  about HTTP request context (host/port) — that's a controller concern. Controller
  derives it from the incoming request (e.g. via `HttpServletRequest` or Spring's
  `ServletUriComponentsBuilder`) and passes it down.
- Note there's a theoretical TOCTOU (time-of-check-to-time-of-use) gap between
  `existsByCode(candidate)` and `save(candidate, url)` in `shortenWithGeneratedCode`,
  and similarly in `shortenWithAlias`. Given `save()` is synchronized but the preceding
  `existsByCode()` read is not part of the same critical section, two concurrent
  requests could theoretically both pass the check for the *same generated candidate*
  (astronomically unlikely for random codes, more plausible for two requests racing on
  the *same custom alias*). This is called out explicitly as a known limitation in §9
  rather than silently left as a bug — see that section for why it's an acceptable
  tradeoff at this scope, and what a stricter fix would look like.

---

## 8. `UrlController`

```java
@RestController
public class UrlController {

    private final UrlShortenerService service;

    public UrlController(UrlShortenerService service) {
        this.service = service;
    }

    @PostMapping("/shorten")
    public ResponseEntity<?> shorten(@RequestBody ShortenRequest request,
                                      HttpServletRequest httpRequest) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)
            .replacePath(null).build().toUriString();

        ServiceResult<ShortenResponse> result =
            service.shorten(request.getUrl(), request.getAlias(), baseUrl);

        return switch (result.getStatus()) {
            case SUCCESS -> ResponseEntity.status(HttpStatus.CREATED).body(result.getData());
            case INVALID_URL, INVALID_ALIAS ->
                ResponseEntity.badRequest().body(new ErrorResponse(result.getStatus().name(), result.getMessage()));
            case ALIAS_CONFLICT ->
                ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(result.getStatus().name(), result.getMessage()));
            case GENERATION_FAILED ->
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(result.getStatus().name(), result.getMessage()));
            default ->
                ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse("UNKNOWN", "Unexpected result status."));
        };
    }

    @GetMapping("/{code}")
    public ResponseEntity<?> redirect(@PathVariable String code) {
        ServiceResult<String> result = service.redirect(code);

        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .location(URI.create(result.getData()))
                .build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(new ErrorResponse("NOT_FOUND", result.getMessage()));
    }
}
```
- Controller contains **zero business logic** — purely translates `ServiceResult` →
  `ResponseEntity`, exactly per HLD §2.1.
- `switch` on the enum is exhaustive-by-design (Java will warn on unhandled enum
  constants in a switch expression without a default, though a `default` arm is kept
  here defensively in case the enum grows without every call site being updated).

---

## 9. Known Limitation: TOCTOU Race on Code/Alias Reservation

Flagged explicitly rather than glossed over, since the assignment values deliberate,
documented tradeoffs:

- **What it is:** `existsByCode(x)` (read) and `save(x, url)` (write) are two separate
  operations in the service layer, not one atomic operation. Two threads can both read
  "doesn't exist" before either writes.
- **Why it's not fixed in this submission:** The realistic exposure is narrow.
  - For **random generated codes**: the probability of two concurrent requests
    independently generating the *exact same* 7-character code in the same instant is
    already astronomically small (1-in-3.5-trillion territory) before even accounting
    for the additional requirement that they race within the same microsecond window.
  - For **custom aliases**: a genuine race is more plausible (two clients
    simultaneously requesting the same human-chosen alias), but the consequence is
    bounded and non-corrupting — last writer wins on the `save()` call (which *is*
    synchronized), so the system ends up in a consistent state with one of the two URLs
    stored under that alias; it just doesn't guarantee which request "wins" the
    409 vs. 201 outcome under exact-simultaneous contention.
- **What a stricter fix would look like** (noted for completeness, not implemented
  here): move the entire check-and-set into a single synchronized method on the
  repository — e.g. `boolean saveIfAbsent(String code, String url)` returning false if
  the code was already taken at the moment of the atomic check, letting the service
  retry/conflict based on that return value instead of a separate preceding read. This
  is a small, contained change if pursued later; not implemented now to keep the
  service-layer logic readable and because the practical risk at this scale is
  negligible.

---

## 10. Traceability to HLD / Requirements
Every class above maps 1:1 to a component named in HLD §2. No new components were
introduced beyond what HLD specified, except:
- `dto` and `validation` packages — implementation-level organization, not a new
  architectural layer.
- `ServiceResult.Status.GENERATION_FAILED` — added during LLD discussion to support
  the retry-cap decision (HLD §4.1 updated to match).

---

## 11. Open Items Carried Into Implementation
- Spring Boot project scaffolding (`pom.xml`, application properties).
- Wiring `ShortCodeGenerator` and `InMemoryUrlRepository` as Spring beans (`@Component`/
  `@Repository` annotations as shown above).
- Final JUnit test class list (unit tests per class with Mockito; integration tests via
  `MockMvc` hitting both endpoints end-to-end, covering every flow in HLD §3).
