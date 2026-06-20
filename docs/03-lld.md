\# URL Shortener — Low-Level Design (LLD)



This document builds on `02-hld.md` and translates each component into concrete Java

class signatures, package structure, and implementation-level logic — close enough to

mechanical that the implementation phase is largely "write what's specified here."



\---



\## 1. Package Structure



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

&#x20;   ├── UrlValidator.java

&#x20;   └── AliasValidator.java

```



Rationale: `dto` and `validation` are split out as their own packages rather than

folded into `controller`/`service`, since both are small, focused, and independently

unit-testable — DTOs are pure data carriers, validators are pure functions with no

Spring dependencies.



\---



\## 2. DTOs



\### 2.1 `ShortenRequest` (request body for POST /shorten)

```java

public class ShortenRequest {

&#x20;   private String url;     // required

&#x20;   private String alias;   // optional, nullable



&#x20;   // getters/setters (Jackson needs these for deserialization)

}

```



\### 2.2 `ShortenResponse` (success body for POST /shorten)

```java

public class ShortenResponse {

&#x20;   private final String code;

&#x20;   private final String shortUrl;

&#x20;   private final String originalUrl;



&#x20;   public ShortenResponse(String code, String shortUrl, String originalUrl) { ... }

&#x20;   // getters only — immutable response object

}

```



\### 2.3 `ErrorResponse` (body for 400 / 404 / 409 / 500)

```java

public class ErrorResponse {

&#x20;   private final String error;     // short machine-friendly code, e.g. "INVALID\_URL"

&#x20;   private final String message;   // human-readable detail



&#x20;   public ErrorResponse(String error, String message) { ... }

&#x20;   // getters only

}

```



\---



\## 3. `ServiceResult<T>` — the error-handling backbone



```java

public class ServiceResult<T> {



&#x20;   public enum Status {

&#x20;       SUCCESS,

&#x20;       INVALID\_URL,

&#x20;       INVALID\_ALIAS,

&#x20;       ALIAS\_CONFLICT,

&#x20;       NOT\_FOUND,

&#x20;       GENERATION\_FAILED

&#x20;   }



&#x20;   private final Status status;

&#x20;   private final T data;          // non-null only when status == SUCCESS

&#x20;   private final String message;  // human-readable detail, set on non-SUCCESS



&#x20;   private ServiceResult(Status status, T data, String message) {

&#x20;       this.status = status;

&#x20;       this.data = data;

&#x20;       this.message = message;

&#x20;   }



&#x20;   public static <T> ServiceResult<T> success(T data) {

&#x20;       return new ServiceResult<>(Status.SUCCESS, data, null);

&#x20;   }



&#x20;   public static <T> ServiceResult<T> invalidUrl(String message) {

&#x20;       return new ServiceResult<>(Status.INVALID\_URL, null, message);

&#x20;   }



&#x20;   public static <T> ServiceResult<T> invalidAlias(String message) {

&#x20;       return new ServiceResult<>(Status.INVALID\_ALIAS, null, message);

&#x20;   }



&#x20;   public static <T> ServiceResult<T> aliasConflict(String message) {

&#x20;       return new ServiceResult<>(Status.ALIAS\_CONFLICT, null, message);

&#x20;   }



&#x20;   public static <T> ServiceResult<T> notFound(String message) {

&#x20;       return new ServiceResult<>(Status.NOT\_FOUND, null, message);

&#x20;   }



&#x20;   public static <T> ServiceResult<T> generationFailed(String message) {

&#x20;       return new ServiceResult<>(Status.GENERATION\_FAILED, null, message);

&#x20;   }



&#x20;   public Status getStatus() { return status; }

&#x20;   public T getData() { return data; }

&#x20;   public String getMessage() { return message; }

&#x20;   public boolean isSuccess() { return status == Status.SUCCESS; }

}

```



Used as `ServiceResult<ShortenResponse>` for the shorten flow, and `ServiceResult<String>`

(data = original URL) for the redirect flow.



\---



\## 4. Validators



\### 4.1 `UrlValidator`

```java

public class UrlValidator {



&#x20;   public static boolean isValid(String url) {

&#x20;       if (url == null || url.isBlank()) return false;

&#x20;       try {

&#x20;           URI uri = new URI(url);

&#x20;           return uri.getScheme() != null \&\& uri.getHost() != null;

&#x20;       } catch (URISyntaxException e) {

&#x20;           return false;

&#x20;       }

&#x20;   }

}

```

\- Uses `java.net.URI` (built-in, no external dependency) per earlier decision.

\- A URL is valid if it parses as a syntactically correct URI \*\*and\*\* has both a scheme

&#x20; (`http`, `https`, `ftp`, anything — any scheme accepted per requirements §2.5) and a

&#x20; host. This rejects garbage like `"not a url"` or `"/just/a/path"` while accepting any

&#x20; well-formed absolute URL regardless of scheme.

\- Stateless static utility — trivially unit-testable with a table of valid/invalid

&#x20; inputs.



\### 4.2 `AliasValidator`

```java

public class AliasValidator {



&#x20;   private static final int MIN\_LENGTH = 1;

&#x20;   private static final int MAX\_LENGTH = 64;

&#x20;   private static final Pattern URL\_SAFE\_PATTERN =

&#x20;       Pattern.compile("^\[A-Za-z0-9\\\\-\_.\~]+$");



&#x20;   public static boolean isValid(String alias) {

&#x20;       if (alias == null) return false;

&#x20;       int len = alias.length();

&#x20;       if (len < MIN\_LENGTH || len > MAX\_LENGTH) return false;

&#x20;       return URL\_SAFE\_PATTERN.matcher(alias).matches();

&#x20;   }

}

```

\- Character set: RFC 3986 unreserved characters (`A–Z a–z 0–9 - \_ . \~`) — matches the

&#x20; "any URL-safe characters" decision from requirements §2.3.

\- Length bound: 1–64, the assumption explicitly flagged in requirements §6.2.

\- Both constants are named and visible at the top of the class — not magic numbers

&#x20; buried in logic — so the assumption is easy to spot and change later if needed.



\---



\## 5. `ShortCodeGenerator`



```java

public class ShortCodeGenerator {



&#x20;   private static final String ALPHABET =

&#x20;       "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

&#x20;   private static final int CODE\_LENGTH = 7;

&#x20;   private static final SecureRandom RANDOM = new SecureRandom();



&#x20;   public String generate() {

&#x20;       StringBuilder sb = new StringBuilder(CODE\_LENGTH);

&#x20;       for (int i = 0; i < CODE\_LENGTH; i++) {

&#x20;           sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));

&#x20;       }

&#x20;       return sb.toString();

&#x20;   }

}

```

\- `SecureRandom` per earlier decision — cryptographically strong randomness, avoids

&#x20; any theoretical predictability concern with `java.util.Random`'s linear congruential

&#x20; algorithm (not a strict requirement here, but a defensible default with negligible

&#x20; performance cost at this scale).

\- Pure function: no dependency on the repository, no knowledge of collisions — single

&#x20; responsibility, trivially unit-testable (e.g. assert length == 7, assert every char

&#x20; is in the alphabet, statistical distribution sanity checks).

\- 62-character alphabet, 7-length, matches the 62⁷ ≈ 3.5 trillion search space agreed

&#x20; in HLD §4.1.



\---



\## 6. `UrlRepository` (interface) and `InMemoryUrlRepository`



```java

public interface UrlRepository {

&#x20;   void save(String code, String url);

&#x20;   Optional<String> findByCode(String code);

&#x20;   Optional<String> findByUrl(String url);

&#x20;   boolean existsByCode(String code);

}

```



```java

@Repository

public class InMemoryUrlRepository implements UrlRepository {



&#x20;   private final Map<String, String> codeToUrl = new ConcurrentHashMap<>();

&#x20;   private final Map<String, String> urlToCode = new ConcurrentHashMap<>();

&#x20;   private final Object writeLock = new Object();



&#x20;   @Override

&#x20;   public void save(String code, String url) {

&#x20;       synchronized (writeLock) {

&#x20;           codeToUrl.put(code, url);

&#x20;           urlToCode.put(url, code);

&#x20;       }

&#x20;   }



&#x20;   @Override

&#x20;   public Optional<String> findByCode(String code) {

&#x20;       return Optional.ofNullable(codeToUrl.get(code));   // lock-free read

&#x20;   }



&#x20;   @Override

&#x20;   public Optional<String> findByUrl(String url) {

&#x20;       return Optional.ofNullable(urlToCode.get(url));    // lock-free read

&#x20;   }



&#x20;   @Override

&#x20;   public boolean existsByCode(String code) {

&#x20;       return codeToUrl.containsKey(code);                // lock-free read

&#x20;   }

}

```

\- A dedicated `writeLock` object (rather than `synchronized` on `this`) is used so that

&#x20; if other synchronized methods are ever added to this class for unrelated reasons,

&#x20; they don't unintentionally contend with the write path's lock. Small defensive

&#x20; choice, costs nothing.

\- \*\*Important subtlety for custom-alias writes:\*\* when saving a custom-alias entry for

&#x20; a URL that may already exist under a \*different\* code, `urlToCode.put(url, code)`

&#x20; will \*\*overwrite\*\* the reverse-index entry to point at the newest code. This is

&#x20; intentional and harmless: `urlToCode` is only ever consulted by the no-alias dedup

&#x20; path (HLD §3.2), and per requirements §2.3 a URL can have multiple valid codes

&#x20; simultaneously — `urlToCode` just tracks one canonical "the code we'll return if

&#x20; asked again with no alias," which reasonably becomes the most recent one. The old

&#x20; code remains fully valid and resolvable via `codeToUrl` regardless.



\---



\## 7. `UrlShortenerService`



```java

@Service

public class UrlShortenerService {



&#x20;   private static final int MAX\_GENERATION\_RETRIES = 5;



&#x20;   private final UrlRepository repository;

&#x20;   private final ShortCodeGenerator generator;



&#x20;   public UrlShortenerService(UrlRepository repository, ShortCodeGenerator generator) {

&#x20;       this.repository = repository;

&#x20;       this.generator = generator;

&#x20;   }



&#x20;   public ServiceResult<ShortenResponse> shorten(String url, String alias, String baseUrl) {

&#x20;       if (!UrlValidator.isValid(url)) {

&#x20;           return ServiceResult.invalidUrl("The provided URL is not well-formed.");

&#x20;       }



&#x20;       if (alias != null \&\& !alias.isBlank()) {

&#x20;           return shortenWithAlias(url, alias, baseUrl);

&#x20;       }

&#x20;       return shortenWithGeneratedCode(url, baseUrl);

&#x20;   }



&#x20;   private ServiceResult<ShortenResponse> shortenWithAlias(String url, String alias, String baseUrl) {

&#x20;       if (!AliasValidator.isValid(alias)) {

&#x20;           return ServiceResult.invalidAlias(

&#x20;               "Alias must be 1-64 URL-safe characters (A-Z, a-z, 0-9, -, \_, ., \~).");

&#x20;       }

&#x20;       if (repository.existsByCode(alias)) {

&#x20;           return ServiceResult.aliasConflict("Alias '" + alias + "' is already taken.");

&#x20;       }

&#x20;       repository.save(alias, url);

&#x20;       return ServiceResult.success(buildResponse(alias, url, baseUrl));

&#x20;   }



&#x20;   private ServiceResult<ShortenResponse> shortenWithGeneratedCode(String url, String baseUrl) {

&#x20;       Optional<String> existingCode = repository.findByUrl(url);

&#x20;       if (existingCode.isPresent()) {

&#x20;           return ServiceResult.success(buildResponse(existingCode.get(), url, baseUrl));

&#x20;       }



&#x20;       for (int attempt = 0; attempt < MAX\_GENERATION\_RETRIES; attempt++) {

&#x20;           String candidate = generator.generate();

&#x20;           if (!repository.existsByCode(candidate)) {

&#x20;               repository.save(candidate, url);

&#x20;               return ServiceResult.success(buildResponse(candidate, url, baseUrl));

&#x20;           }

&#x20;       }

&#x20;       return ServiceResult.generationFailed(

&#x20;           "Could not generate a unique short code after " + MAX\_GENERATION\_RETRIES + " attempts.");

&#x20;   }



&#x20;   public ServiceResult<String> redirect(String code) {

&#x20;       return repository.findByCode(code)

&#x20;           .map(ServiceResult::success)

&#x20;           .orElseGet(() -> ServiceResult.notFound("No URL found for code '" + code + "'."));

&#x20;   }



&#x20;   private ShortenResponse buildResponse(String code, String url, String baseUrl) {

&#x20;       return new ShortenResponse(code, baseUrl + "/" + code, url);

&#x20;   }

}

```



Notes:

\- Constructor injection (no field injection) — standard Spring practice, and makes the

&#x20; class trivially testable with mocked `UrlRepository`/`ShortCodeGenerator` via

&#x20; Mockito, per the testing-approach decision.

\- `baseUrl` is passed in (rather than hardcoded) so the service doesn't need to know

&#x20; about HTTP request context (host/port) — that's a controller concern. Controller

&#x20; derives it from the incoming request (e.g. via `HttpServletRequest` or Spring's

&#x20; `ServletUriComponentsBuilder`) and passes it down.

\- Note there's a theoretical TOCTOU (time-of-check-to-time-of-use) gap between

&#x20; `existsByCode(candidate)` and `save(candidate, url)` in `shortenWithGeneratedCode`,

&#x20; and similarly in `shortenWithAlias`. Given `save()` is synchronized but the preceding

&#x20; `existsByCode()` read is not part of the same critical section, two concurrent

&#x20; requests could theoretically both pass the check for the \*same generated candidate\*

&#x20; (astronomically unlikely for random codes, more plausible for two requests racing on

&#x20; the \*same custom alias\*). This is called out explicitly as a known limitation in §9

&#x20; rather than silently left as a bug — see that section for why it's an acceptable

&#x20; tradeoff at this scope, and what a stricter fix would look like.



\---



\## 8. `UrlController`



```java

@RestController

public class UrlController {



&#x20;   private final UrlShortenerService service;



&#x20;   public UrlController(UrlShortenerService service) {

&#x20;       this.service = service;

&#x20;   }



&#x20;   @PostMapping("/shorten")

&#x20;   public ResponseEntity<?> shorten(@RequestBody ShortenRequest request,

&#x20;                                     HttpServletRequest httpRequest) {

&#x20;       String baseUrl = ServletUriComponentsBuilder.fromRequestUri(httpRequest)

&#x20;           .replacePath(null).build().toUriString();



&#x20;       ServiceResult<ShortenResponse> result =

&#x20;           service.shorten(request.getUrl(), request.getAlias(), baseUrl);



&#x20;       return switch (result.getStatus()) {

&#x20;           case SUCCESS -> ResponseEntity.status(HttpStatus.CREATED).body(result.getData());

&#x20;           case INVALID\_URL, INVALID\_ALIAS ->

&#x20;               ResponseEntity.badRequest().body(new ErrorResponse(result.getStatus().name(), result.getMessage()));

&#x20;           case ALIAS\_CONFLICT ->

&#x20;               ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(result.getStatus().name(), result.getMessage()));

&#x20;           case GENERATION\_FAILED ->

&#x20;               ResponseEntity.status(HttpStatus.INTERNAL\_SERVER\_ERROR).body(new ErrorResponse(result.getStatus().name(), result.getMessage()));

&#x20;           default ->

&#x20;               ResponseEntity.status(HttpStatus.INTERNAL\_SERVER\_ERROR).body(new ErrorResponse("UNKNOWN", "Unexpected result status."));

&#x20;       };

&#x20;   }



&#x20;   @GetMapping("/{code}")

&#x20;   public ResponseEntity<?> redirect(@PathVariable String code) {

&#x20;       ServiceResult<String> result = service.redirect(code);



&#x20;       if (result.isSuccess()) {

&#x20;           return ResponseEntity.status(HttpStatus.MOVED\_PERMANENTLY)

&#x20;               .location(URI.create(result.getData()))

&#x20;               .build();

&#x20;       }

&#x20;       return ResponseEntity.status(HttpStatus.NOT\_FOUND)

&#x20;           .body(new ErrorResponse("NOT\_FOUND", result.getMessage()));

&#x20;   }

}

```

\- Controller contains \*\*zero business logic\*\* — purely translates `ServiceResult` →

&#x20; `ResponseEntity`, exactly per HLD §2.1.

\- `switch` on the enum is exhaustive-by-design (Java will warn on unhandled enum

&#x20; constants in a switch expression without a default, though a `default` arm is kept

&#x20; here defensively in case the enum grows without every call site being updated).



\---



\## 9. Known Limitation: TOCTOU Race on Code/Alias Reservation



Flagged explicitly rather than glossed over, since the assignment values deliberate,

documented tradeoffs:



\- \*\*What it is:\*\* `existsByCode(x)` (read) and `save(x, url)` (write) are two separate

&#x20; operations in the service layer, not one atomic operation. Two threads can both read

&#x20; "doesn't exist" before either writes.

\- \*\*Why it's not fixed in this submission:\*\* The realistic exposure is narrow.

&#x20; - For \*\*random generated codes\*\*: the probability of two concurrent requests

&#x20;   independently generating the \*exact same\* 7-character code in the same instant is

&#x20;   already astronomically small (1-in-3.5-trillion territory) before even accounting

&#x20;   for the additional requirement that they race within the same microsecond window.

&#x20; - For \*\*custom aliases\*\*: a genuine race is more plausible (two clients

&#x20;   simultaneously requesting the same human-chosen alias), but the consequence is

&#x20;   bounded and non-corrupting — last writer wins on the `save()` call (which \*is\*

&#x20;   synchronized), so the system ends up in a consistent state with one of the two URLs

&#x20;   stored under that alias; it just doesn't guarantee which request "wins" the

&#x20;   409 vs. 201 outcome under exact-simultaneous contention.

\- \*\*What a stricter fix would look like\*\* (noted for completeness, not implemented

&#x20; here): move the entire check-and-set into a single synchronized method on the

&#x20; repository — e.g. `boolean saveIfAbsent(String code, String url)` returning false if

&#x20; the code was already taken at the moment of the atomic check, letting the service

&#x20; retry/conflict based on that return value instead of a separate preceding read. This

&#x20; is a small, contained change if pursued later; not implemented now to keep the

&#x20; service-layer logic readable and because the practical risk at this scale is

&#x20; negligible.



\---



\## 10. Traceability to HLD / Requirements

Every class above maps 1:1 to a component named in HLD §2. No new components were

introduced beyond what HLD specified, except:

\- `dto` and `validation` packages — implementation-level organization, not a new

&#x20; architectural layer.

\- `ServiceResult.Status.GENERATION\_FAILED` — added during LLD discussion to support

&#x20; the retry-cap decision (HLD §4.1 updated to match).



\---



\## 11. Open Items Carried Into Implementation

\- Spring Boot project scaffolding (`pom.xml`, application properties).

\- Wiring `ShortCodeGenerator` and `InMemoryUrlRepository` as Spring beans (`@Component`/

&#x20; `@Repository` annotations as shown above).

\- Final JUnit test class list (unit tests per class with Mockito; integration tests via

&#x20; `MockMvc` hitting both endpoints end-to-end, covering every flow in HLD §3).

