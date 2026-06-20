\# URL Shortener



A small Java/Spring Boot service that turns long URLs into short codes and redirects

visitors to the original link.



This project was built following the SDLC: requirements → HLD → LLD → implementation

→ testing. Each phase's design artifacts live in \[`docs/`](docs/) — start there if

you want to understand \*why\* things are built the way they are, not just \*what\* was

built. A one-page write-up on the development process is at

\[`docs/04-writeup.md`](docs/04-writeup.md).



\## Tech stack

\- Java 17, Spring Boot 3.3.4, Maven

\- In-memory storage (`ConcurrentHashMap`-based) — see `docs/01-requirements.md` §6

&#x20; for why this was chosen over a real database for this exercise

\- JUnit 5 + Mockito + MockMvc for testing



\## Prerequisites

\- \*\*JDK 17\*\* or later (\[Eclipse Temurin](https://adoptium.net/) recommended)

\- \*\*Maven 3.6+\*\* (or use the included `./mvnw` wrapper if present, or install via

&#x20; your package manager / IntelliJ's bundled Maven)



Verify your setup:

```bash

java -version    # should show 17 or higher

mvn -version

```



\## Install \& run



Clone the repository, then from the project root:



```bash

mvn clean install

mvn spring-boot:run

```



The service starts on \*\*`http://localhost:8080`\*\*.



Confirm it's up:

```bash

curl http://localhost:8080/health

\# {"status":"UP"}

```



\## API



\### `POST /shorten`

Shortens a URL. Accepts an optional custom alias.



\*\*Request:\*\*

```json

{

&#x20; "url": "https://example.com/some/very/long/path",

&#x20; "alias": "my-link"

}

```

`alias` is optional — omit it (or set to `null`) to get an auto-generated 7-character

code instead.



\*\*Response — `201 Created`:\*\*

```json

{

&#x20; "code": "my-link",

&#x20; "shortUrl": "http://localhost:8080/my-link",

&#x20; "originalUrl": "https://example.com/some/very/long/path"

}

```



\*\*Error responses:\*\*

| Status | Cause |

|---|---|

| `400 Bad Request` | URL is missing/malformed, or alias contains invalid characters |

| `409 Conflict` | Requested alias is already taken |



\### `GET /{code}`

Redirects to the original URL.



\- \*\*`301 Moved Permanently`\*\* with a `Location` header, if the code exists.

\- \*\*`404 Not Found`\*\* if the code is unknown.



\### Example usage (curl)

```bash

\# Shorten a URL (auto-generated code)

curl -i -X POST http://localhost:8080/shorten \\

&#x20; -H "Content-Type: application/json" \\

&#x20; -d '{"url":"https://example.com/some/long/path"}'



\# Shorten with a custom alias

curl -i -X POST http://localhost:8080/shorten \\

&#x20; -H "Content-Type: application/json" \\

&#x20; -d '{"url":"https://example.com/some/long/path","alias":"my-link"}'



\# Follow the redirect (replace CODE with the code you received above)

curl -i http://localhost:8080/CODE



\# Unknown code -> 404

curl -i http://localhost:8080/this-does-not-exist

```



\### Example usage (PowerShell)

```powershell

\# Shorten a URL

Invoke-RestMethod -Uri "http://localhost:8080/shorten" -Method Post `

&#x20; -ContentType "application/json" `

&#x20; -Body '{"url":"https://example.com/some/long/path"}'



\# Follow the redirect without auto-following it, to see the 301 + Location header

Invoke-WebRequest -Uri "http://localhost:8080/CODE" -MaximumRedirection 0 -ErrorAction SilentlyContinue

```



\## Running tests



```bash

mvn test

```



This runs the full suite: unit tests (validators, short code generator, repository,

service layer with Mockito) and integration tests (full HTTP stack via MockMvc,

real Spring context, real in-memory repository — no mocks). Includes a concurrency

stress test validating the repository's thread-safety design — see

`docs/03-lld.md` §9 for what that test does and does not guarantee.



To run a single test class from Maven:

```bash

mvn test -Dtest=UrlShortenerServiceTest

```



\## Project structure

```

src/main/java/com/example/urlshortener/

├── UrlShortenerApplication.java   Spring Boot entry point

├── controller/                    HTTP layer (UrlController, HealthController)

├── service/                       Business logic, ServiceResult, code generator

├── repository/                    Storage interface + in-memory implementation

├── dto/                           Request/response data carriers

└── validation/                    URL and alias validation



src/test/java/com/example/urlshortener/   Mirrors the structure above



docs/

├── 01-requirements.md             Functional requirements, explicit decisions

├── 02-hld.md                      Architecture, component design, concurrency model

├── 03-lld.md                      Class-level design, exact signatures

└── 04-writeup.md                  Process write-up (AI collaboration, trade-offs)

```



\## Key design decisions (see docs for full reasoning)

\- \*\*Short codes:\*\* random Base62, fixed 7 characters, collision-checked against the

&#x20; store before saving (with a capped retry loop) — collision-free by construction,

&#x20; not just statistically unlikely. See `docs/02-hld.md` §4.1.

\- \*\*Duplicate URLs (no alias):\*\* idempotent — shortening the same URL twice returns

&#x20; the same code. See `docs/01-requirements.md` §2.4.

\- \*\*Custom aliases:\*\* always create a new mapping, even if the URL was already

&#x20; shortened; aliases are unique system-wide (strict 1:1 with a URL). Taken alias →

&#x20; `409`. See `docs/01-requirements.md` §2.3.

\- \*\*Storage:\*\* in-memory only, behind a repository interface so a real database

&#x20; could be swapped in without touching the service layer. Data does not persist

&#x20; across restarts — a deliberate, documented tradeoff for this exercise.



\## Known limitations (deliberate, documented — not oversights)

\- No authentication, expiry/TTL, analytics, or rate limiting — explicitly out of

&#x20; scope; see `docs/01-requirements.md` §4.

\- A narrow TOCTOU race exists between checking code/alias availability and saving

&#x20; it; under heavy concurrent load this could theoretically mint more than one code

&#x20; for the same URL or race on a contested alias. Data integrity is never

&#x20; compromised (the two internal maps never end up inconsistent with each other),

&#x20; but the "exactly one winner" guarantee isn't atomic. See `docs/03-lld.md` §9 for

&#x20; the full reasoning and what a stricter fix would look like.

