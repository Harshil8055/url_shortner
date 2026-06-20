# URL Shortener

A small Java/Spring Boot service that turns long URLs into short codes and redirects
visitors to the original link.

This project is being built following the SDLC: requirements → HLD → LLD →
implementation → testing. Each phase's artifacts live in `docs/`.

## Docs
- [`docs/01-requirements.md`](docs/01-requirements.md) — Requirements (functional,
  non-functional, explicit assumptions and out-of-scope items)

## Tech Stack
- Java, Spring Boot, Maven
- In-memory storage (demo-scoped)
- JUnit (unit + integration tests)

## Status
🚧 Requirements, HLD, and LLD complete. Spring Boot project boilerplate scaffolded.
Business logic implementation in progress.

## Running locally
```bash
mvn spring-boot:run
```
Then check `GET http://localhost:8080/health` to confirm the app started correctly.
(This is a temporary verification endpoint — the real `/shorten` and `/{code}`
endpoints are added in the next implementation step, per `docs/03-lld.md`.)
