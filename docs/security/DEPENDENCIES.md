# Dependencies and advisories

This file lists the third-party versions CareNexus ships, how they are scanned, and every known
advisory that is accepted rather than fixed. It is the reference for the launch blocker
"Unresolved exploitable dependency vulnerabilities".

Status (2026-11-11): the backend has been upgraded to Spring Boot 4.1.2 and scanned before and after
the upgrade. The OWASP dependency-check scan of the compile and runtime dependencies went from 156
findings (23 with CVSS 9 or higher) to none. A second scanner (OSV.dev, test scope included) went from
83 advisory matches to none. On the frontend, no patch-level update fixes any open advisory, so the
lock file is unchanged. The remaining npm advisories are accepted in section 4, each with its reason.

## 1. Resolved versions

### Backend (`backend/carenexus-api`)

Resolved with `./mvnw dependency:list` after the upgrade. Spring Boot's parent manages most versions.
`pom.xml` pins jjwt, springdoc, the LiveKit SDK, okhttp (through its BOM), Kotlin and the Swagger UI
webjar. Section 5 explains each pin.

| Component | Before | Now | How it gets in |
|---|---|---|---|
| Spring Boot (parent) | 3.3.13 | 4.1.2 | `spring-boot-starter-parent` |
| Spring Framework | 6.1.21 | 7.0.10 | managed by Boot |
| Spring Security | 6.3.10 | 7.1.2 | `spring-boot-starter-security` |
| Spring Data JPA / Commons | 3.3.13 | 4.1.2 | `spring-boot-starter-data-jpa` |
| Embedded Tomcat | 10.1.42 | 11.0.27 | `spring-boot-starter-webmvc` (was `-web`) |
| Hibernate ORM | 6.5.3.Final | 7.4.13.Final | `spring-boot-starter-data-jpa` |
| Hibernate Validator | 8.0.2.Final | 9.1.5.Final | `spring-boot-starter-validation` |
| Jackson 2 (`com.fasterxml`) databind | 2.17.3 | 2.21.7 | `spring-boot-jackson2`; the application's JSON mapper (section 5) |
| Jackson 3 (`tools.jackson`) databind | - | 3.1.7 | Boot 4 default; used by Boot internals only |
| Logback | 1.5.18 | 1.5.38 | managed by Boot |
| SnakeYAML | 2.2 | 2.6 | managed by Boot |
| Micrometer (Prometheus registry) | 1.13.15 | 1.17.2 | runtime, `/actuator/prometheus` |
| Flyway (core + PostgreSQL) | 10.10.0 | 12.4.0 | `spring-boot-starter-flyway` (was `flyway-core`) |
| HikariCP | 5.1.0 | 7.0.2 | managed by Boot |
| PostgreSQL JDBC | 42.7.7 | 42.7.14 | runtime |
| jjwt (api, impl, jackson) | 0.12.7 | 0.13.0 | `jjwt.version` |
| springdoc-openapi (webmvc-ui) | 2.4.0 | 3.2.0 | `springdoc.version` |
| Swagger UI webjar (bundles DOMPurify) | 5.11.8 (DOMPurify 3.0.9) | 5.34.1 (DOMPurify 3.4.17) | `swagger-ui.version` |
| LiveKit server SDK (`io.livekit:livekit-server`) | 0.16.0 | 0.17.0 | `livekit-server.version` |
| okhttp / retrofit / okio | 5.3.2 / 3.0.0 / 3.16.4 | 5.3.2 / 3.0.0 / 3.16.4 | LiveKit SDK; okhttp through `okhttp-bom` `${okhttp.version}` |
| protobuf-java (+ util) | 4.29.4 | 4.36.2 | LiveKit SDK, version managed by Boot |
| Kotlin stdlib | 2.3.0 | 2.4.21 | LiveKit SDK; `kotlin.version` |
| Lombok | 1.18.30 | 1.18.48 | `provided`, compile time only; now Boot's managed version |
| JUnit Jupiter / Mockito / AssertJ | 5.10.5 / 5.11.0 / 3.25.3 | 6.0.3 / 5.23.0 / 3.27.7 | test scope only |
| Testcontainers (PostgreSQL) | 1.21.4 | 2.0.5 | test scope only; artifact `testcontainers-postgresql` |

Spring Boot 4.1 is the newest generally available line. Open-source support for it runs until
2027-07-31; 4.0 ends on 2026-12-31 and 3.5 ended on 2026-06-30. 4.2 is at RC2 and not released yet.
Java stays at 17, which Boot 4.1 supports.

### Frontend (`frontend`)

Resolved from `frontend/package-lock.json` (lockfile version 3). Nothing changed in this step (section 3).

| Component | Version | Notes |
|---|---|---|
| Expo SDK (`expo`) | 56.0.24 | `package.json` range `^56.0.4`; Expo CLI 56.1.27. Newest 56.x patch. |
| expo-router | 56.2.22 | newest 56.x patch |
| React / React DOM | 19.2.3 | pinned exactly |
| React Native | 0.85.3 | pinned exactly |
| react-native-web | 0.21.2 | |
| react-native-reanimated / react-native-screens | 4.3.1 / 4.25.2 | |
| Metro (bundler) | 0.84.4 | through Expo |
| livekit-client | 2.23.0 | pinned exactly (video calls) |
| @livekit/components-react | 2.9.24 | pinned exactly |
| axios | 1.21.0 | |
| zod / react-hook-form | 4.6.7 / 7.77.0 | the app's own zod is the fixed 4.6.7 |
| qrcode-generator | 2.0.4 | MFA enrolment QR code |
| TypeScript | 6.0.3 | dev |
| ESLint | 9.39.5 | dev; see below |
| eslint-config-expo | 56.0.4 | dev; the Expo SDK 56 preset (eslint-plugin-react-hooks 7.1.1) |
| @playwright/test / @axe-core/playwright | 1.64.1 / 4.14.0 | dev, end-to-end and accessibility tests |

ESLint stays on the 9.x line on purpose. eslint-config-expo 56 brings eslint-plugin-react 7.37 and
eslint-plugin-import 2.32, whose peer ranges stop at ESLint 9. npm marks 9.39.5 as past its support
window, but it is a development tool and never reaches the shipped bundle. Move to ESLint 10 together
with the Expo SDK whose preset supports it.

## 2. What CI checks

All jobs are in `.github/workflows/ci.yml`.

| Area | Job | Blocking | Report only | When |
|---|---|---|---|---|
| npm packages (frontend) | Dependency audit (npm) | any critical advisory (`npm audit --audit-level=critical`) | high advisories (`--audit-level=high`) | push, pull request, nightly, manual |
| Maven dependencies (API) | Dependency scan (backend, OWASP dependency-check) | any compile or runtime dependency with CVSS 9.0 or higher | CVSS 7.0 to 8.9 (and unscored HIGH / CRITICAL), in the job summary; full HTML and JSON reports as the `backend-dependency-check-report` artifact | push, pull request, nightly, manual |
| Committed secrets | Secrets scan (gitleaks) | any finding not allow-listed in `.github/gitleaks.toml` | | push, pull request, manual |
| Frontend code | Frontend | `npm run lint` (ESLint, `--max-warnings 0`), `tsc --noEmit`, web export | | push, pull request, manual |
| Database migrations | Migrations | changed or deleted released files, duplicate versions, misnamed files, new versioned seed files (`scripts/check-migrations.sh`) | | push, pull request, manual |

The backend scan uses OWASP dependency-check 13.0.0. Its sources are the NVD (CVE data), the CISA
known-exploited list, and RetireJS for the Swagger UI scripts inside the springdoc webjar. Test-scope
dependencies are not scanned. Sonatype OSS Index is off because it needs an account.

NVD data source. The `dependency-check` profile reads the NVD's own JSON 2.0 data feeds
(`https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{year|modified}.json.gz`). These need no API key
and the NVD refreshes them several times a day. The NVD REST API is no longer the default because
dependency-check 13.0.0 sends an empty `apiKey` header when no key is configured, and the NVD now
answers that with `404 Invalid apiKey`. A scan without `NVD_API_KEY` therefore failed with "Invalid API
Key, length of 0". The `NVD_API_KEY` secret is no longer needed. To use the API anyway, run with
`-Ddependency-check.nvdDatafeedUrl=` (empty) and a key in `NVD_API_KEY`. The first feed download takes
about 3 to 8 minutes. After that, the daily cache in CI only fetches the changed files.

Unscored findings: RetireJS findings such as the DOMPurify ones inside Swagger UI often have only a
severity label and no CVSS score. The build gate counts them as 10.0. The baseline scan failed on
CVE-2026-116803 (DOMPurify 3.0.9), which RetireJS rates only HIGH. Without the webjar pin (section 5),
springdoc 3.2.0's own Swagger UI 5.34.0 (DOMPurify 3.4.16, CVE-2026-116803 and CVE-2026-116804) would
fail the gate the same way.

## 3. Running the scans locally

Backend (from `backend/carenexus-api`):

```sh
# Full scan, fails like CI on CVSS >= 9. Reports: target/dependency-check-report.html and .json.
# The vulnerability database lives in ~/.cache/dependency-check (first run: about 3 to 8 minutes).
./mvnw -B -ntp -Pdependency-check dependency-check:check

# Report everything without failing, or fail from CVSS 7 to see the high findings as failures.
./mvnw -B -ntp -Pdependency-check dependency-check:check -Ddependency-check.failBuildOnCVSS=11
./mvnw -B -ntp -Pdependency-check dependency-check:check -Ddependency-check.failBuildOnCVSS=7

# Use the NVD REST API instead of the data feeds (needs a key from https://nvd.nist.gov/developers/request-an-api-key).
NVD_API_KEY=<your key> ./mvnw -B -ntp -Pdependency-check dependency-check:check -Ddependency-check.nvdDatafeedUrl=

# Also query Sonatype OSS Index (needs a free OSS Index account).
./mvnw -B -ntp -Pdependency-check dependency-check:check -Ddependency-check.ossIndex=true \
  -DossIndexUsername=<email> -DossIndexPassword=<api token>

# The resolved versions that section 1 is taken from.
./mvnw -B -ntp dependency:list
```

A second opinion without any account: send the resolved Maven coordinates to OSV.dev
(`POST https://api.osv.dev/v1/querybatch`, ecosystem `Maven`). OSV covers GitHub's advisories (GHSA),
which sometimes list a fix before the NVD does. Section 4 includes that cross-check.

Frontend (from `frontend`):

```sh
npm ci
npm audit --audit-level=critical   # what CI blocks on
npm audit --audit-level=high       # what CI reports
npm audit --omit=dev               # without devDependencies (Expo build tooling is still included)
npm run lint                       # ESLint, max 0 warnings
```

Migrations (from anywhere in the repository): `scripts/check-migrations.sh`. It compares against the
merge base of `origin/main`; pass another ref as the first argument, or `--help` for details.

## 4. Scan results and accepted residual advisories

### Backend, before and after the upgrade (2026-11-11)

| Scanner | Scope | Before (Boot 3.3.13) | After (Boot 4.1.2) |
|---|---|---|---|
| OWASP dependency-check 13.0.0, NVD feeds of 2026-11-11 | compile + runtime | 156 findings: 23 at CVSS >= 9, 44 at 7.0 to 8.9, 71 at 4.0 to 6.9, 18 below 4 or unscored (one of them, DOMPurify 3.0.9 CVE-2026-116803, is unscored HIGH and the gate counts it as 10.0) | 0 findings; the CVSS 9 gate passes |
| OSV.dev (GHSA + Maven advisories) | compile + runtime + test | 83 advisory matches on 162 coordinates (11 at CVSS >= 9) | 0 matches on 189 coordinates |

What the upgrade fixed, by component (dependency-check, before → after):

| Component | Before: CVSS >= 9 / 7-8.9 / lower | Examples of what was fixed | Fixed by |
|---|---|---|---|
| Tomcat 10.1.42 | 13 / 18 / 10 | DIGEST and FORM authentication bypasses (CVE-2026-65905, CVE-2026-43512, CVE-2026-68525), constraint bypasses (CVE-2026-43515, CVE-2026-65182, CVE-2026-55276), HTTP/2 header validation (CVE-2026-41293), request smuggling (CVE-2026-24880), HTTP/2 DoS (CVE-2025-53506, CVE-2026-68763) | Tomcat 11.0.27 |
| Spring Framework 6.1.21 | 6 / 10 / 18 | XsltView SSRF/RCE (CVE-2026-47884, fixed only in 7.0.9, no 6.x fix), SpEL guard bypass and DoS (CVE-2026-59283, CVE-2026-41850), static resource DoS and path traversal (CVE-2026-41842, CVE-2026-41843), multipart smuggling (CVE-2026-41853) | Spring Framework 7.0.10 |
| Spring Boot 3.3.13 | 1 / 4 / 2 | Actuator CloudFoundry auth bypass (CVE-2026-22733), predictable temp directory (CVE-2026-40973), Cassandra TLS hostname check (CVE-2026-40974) | Spring Boot 4.1.2 |
| Spring Security 6.3.10 | 1 / 2 / 5 | security headers not written (CVE-2026-22732), X.509 principal extraction (CVE-2026-47838), open redirect (CVE-2026-41706) | Spring Security 7.1.2 |
| Kotlin stdlib 2.3.0 | 1 / 0 / 0 | CVE-2026-53914 (Kotlin before 2.4.20). The NVD matches it on the stdlib although the flaw is in the compiler's build cache | `kotlin.version` 2.4.21 |
| jackson-databind 2.17.3 | 0 / 2 / 2 | polymorphic type validator bypasses (CVE-2026-54512, CVE-2026-54513), DoS fixes in 2.21.7 | Jackson 2.21.7 |
| PostgreSQL JDBC 42.7.7 | 0 / 1 / 1 | SCRAM PBKDF2 CPU exhaustion (CVE-2026-42198), channel-binding downgrade (CVE-2026-54291) | pgjdbc 42.7.14 |
| log4j-api 2.23.1 | 0 / 1 / 2 | Log4j1XmlLayout escaping (CVE-2026-34479) | log4j-api 2.25.5 |
| Swagger UI 5.11.8 (DOMPurify 3.0.9), per bundle file | 1 unscored HIGH (gated as 10.0) / 1 / 21 | DOMPurify sanitizer bypasses | Swagger UI webjar 5.34.1 (DOMPurify 3.4.17) |
| commons-lang3 3.14.0, spring-data-jpa 3.3.13 | 0 / 0 / 2 | uncontrolled recursion (CVE-2025-48924), Spring Data DoS fixes | commons-lang3 3.20.0, Spring Data 2026.0.2 |

The before and after scans used the same vulnerability database (NVD feeds downloaded on
2026-11-11), so the difference comes only from the dependency versions.

### Accepted residual advisories

An advisory may stay open only when it is not exploitable in CareNexus or no fixed version exists yet.
Each one gets a row here with the reason, and is reviewed again by the date in the last column.

Backend: none. Both scanners report zero findings for the upgraded dependency set.

Frontend: `npm audit` on the lock file (2026-11-11) reports 0 critical, 7 high, 16 moderate and 2 low
packages. That is 25 packages but only 8 distinct advisories, because most packages are listed only
because they depend on one of the eight. No patch-level update (same major.minor) fixes any of them:
the affected packages are already on their newest patch, and every fix needs a new major version,
which mostly means Expo SDK 58. CI does not fail on them (no critical advisory).

| Advisory | Package and version | CVSS | Found by | Why it is not exploitable here, or what blocks the fix | Accepted by / on | Review by |
|---|---|---|---|---|---|---|
| GHSA-mrhx-c7xc-m3w5 (zod memory exhaustion on oversized constrained collections) | `zod` 3.25.76, only the copy nested under `expo/node_modules` (used by `@expo/cli`) | 7.5 | npm audit | Expo CLI uses it to validate the project's own config files at development and build time. It never sees user input and is not in the web bundle. The app's own `zod` is 4.6.7, which is fixed. Expo SDK 56's CLI pins zod 3, and the fix needs zod 4.6.6 (Expo SDK 58). | dependency upgrade (W8), owner to confirm / 2026-11-11 | 2027-02-11 or the Expo SDK 58 upgrade |
| GHSA-5p2g-fcmc-qvqq, GHSA-w3rx-r6r6-pgpr (image-size infinite loops in the JXL/HEIF and ICNS parsers) | `image-size` 1.2.1 (through `metro`) | 7.5 | npm audit | Metro reads the dimensions of the repository's own image assets while bundling. Uploaded files are handled by the API and never reach Metro. The fix is image-size 2.0.3 (a new major version), and Metro 0.84 requires ^1. | dependency upgrade (W8), owner to confirm / 2026-11-11 | 2027-02-11 or the Expo SDK 58 upgrade |
| GHSA-vcc3-ghjq-m6fr, GHSA-qr28-fpjm-wq7f (decode-uri-component DoS on malformed percent-encoding) | `decode-uri-component` 0.2.2 through `query-string` 7.1.3 through `expo-router` 56.2.22 | unscored (rated high) | npm audit | This one ships in the web bundle: expo-router parses the browser's own URL query with it. A crafted link can at most make the victim's own tab slow or hang. The API never runs this code, and no data is exposed. The fix is decode-uri-component 0.5.1 (not compatible with query-string 7's `^0.2.2`), which needs expo-router 58 (Expo SDK 58). | dependency upgrade (W8), owner to confirm / 2026-11-11 | 2027-02-11 or the Expo SDK 58 upgrade |
| GHSA-3g3m-2fjr-6pw4 (glob AbortSignal cancellation bypass) | `glob` 13.0.6 (through `@expo/config`, `@expo/config-plugins`, `@expo/fingerprint`, `@expo/metro-config`, `@expo/cli`) | unscored (moderate) | npm audit | Build tooling that globs the repository's own files. No untrusted pattern or time-limited glob is involved. Fixed only in glob 14.0.2 (a new major version, Expo SDK 58). | dependency upgrade (W8), owner to confirm / 2026-11-11 | 2027-02-11 or the Expo SDK 58 upgrade |
| GHSA-g3hv-75wg-m249 (minimatch DoS through glob-part amplification) | `minimatch` 10.2.6 (through `glob` and `@typescript-eslint` 8.72.2, dev) | unscored (moderate) | npm audit | Patterns come from the repository's own config and lint setup, never from users. Fixed only in minimatch 11.0.1 (a new major version; typescript-eslint 8.72.2 is the newest 8.x). | dependency upgrade (W8), owner to confirm / 2026-11-11 | 2027-02-11 |
| GHSA-gcqx-w48m-w2h4 (deepmerge prototype poisoning) | `deepmerge` 4.3.1 (through `@expo/config`) | 5.3 | npm audit | Expo merges the developer-written app config at build time; no request data is merged. 4.3.1 is the newest release, so no fixed version exists. | dependency upgrade (W8), owner to confirm / 2026-11-11 | 2027-02-11 |

Rules:

- CVSS 7.0 to 8.9 (or unscored HIGH): the CI job reports these but does not fail, so the row here is the record.
- CVSS 9.0 or higher (or unscored CRITICAL) fails the backend scan. Accepting one also needs a
  dependency-check suppression: a `dependency-check-suppressions.xml` next to `pom.xml`, referenced
  from the `dependency-check` profile with `<suppressionFiles>`. Each entry needs the advisory id, the
  exact package URL, a `<notes>` reason matching this table, and an `until` date. No suppression is
  needed today, so the file does not exist.
- npm critical advisories fail `npm audit`. npm has no suppression mechanism, so a critical advisory
  is always fixed (upgrade or `overrides` in `package.json`), never accepted.

## 5. The upgrade (be-dependency-upgrade, 2026-11-11)

### What changed in `pom.xml`

- Parent: `spring-boot-starter-parent` 3.3.13 → 4.1.2.
- Starters renamed or split in Boot 4: `spring-boot-starter-web` → `spring-boot-starter-webmvc`,
  `spring-boot-starter-aop` → `spring-boot-starter-aspectj`, and `flyway-core` → `spring-boot-starter-flyway`
  (without it, Boot 4 no longer runs Flyway). Test additions: `spring-boot-starter-webmvc-test`
  (`@AutoConfigureMockMvc`) and `spring-boot-micrometer-metrics-test` (`@AutoConfigureMetrics`). The
  Testcontainers artifact is now `testcontainers-postgresql`, and the 1.21.4 override was dropped
  because Boot manages 2.0.5.
- `spring-boot-jackson2`: keeps a Jackson 2 `ObjectMapper` bean, together with
  `spring.http.converters.preferred-json-mapper: jackson2` in `application.yml`. 27 main classes
  (including the strict request deserializers and their `@JsonDeserialize` annotations) and 92 test
  classes use Jackson 2 (`com.fasterxml.jackson.databind`). Boot 4 would otherwise serve HTTP bodies
  with Jackson 3, ignore those annotations, and have no `ObjectMapper` bean for the services. The
  wire format is unchanged (the whole suite passes).
- jjwt 0.12.7 → 0.13.0; springdoc 2.4.0 → 3.2.0 (the line built for Boot 4.1); livekit-server
  0.16.0 → 0.17.0. All three versions are now properties.
- `okhttp-bom` 5.3.2 imported: Boot 4 stopped managing okhttp, and Maven then resolved okhttp 4.12
  for retrofit next to the SDK's okhttp-jvm 5.3.2.
- `kotlin.version` 2.4.21 (see the Kotlin row in section 4) and `swagger-ui.version` 5.34.1 (the
  DOMPurify fix). springdoc 3.2.0 looks up its UI files under the webjar version it was built with
  (5.34.0), so `application.yml` passes the pinned version as `springdoc.swagger-ui.version:
  '@swagger-ui.version@'`, filled in by the Boot parent's resource filtering.
  `SwaggerUiWebjarTest` fails if the two ever disagree (every Swagger UI file would answer 404).
- Lombok: the explicit 1.18.30 was dropped in favour of Boot's managed 1.18.48 (the annotation
  processor already used the managed version).
- `dependency-check` profile: the NVD data feeds by default (section 2).

### Fallout fixed in the code (smallest change in each file)

| File | Change | Why |
|---|---|---|
| `src/main/java/com/carenexus/api/common/exception/GlobalExceptionHandler.java` | `getAllValidationResults()` → `getParameterValidationResults()` | removed in Spring Framework 7 (deprecated in 6.2) |
| `src/main/resources/application.yml` | `spring.http.converters.preferred-json-mapper: jackson2`; `springdoc.swagger-ui.version` | Jackson 2 on the HTTP path; serve the pinned Swagger UI webjar |
| `src/test/java/com/carenexus/api/BaseIntegrationTest.java` | `@AutoConfigureMockMvc` import from `org.springframework.boot.webmvc.test.autoconfigure` | moved in Boot 4 |
| `src/test/java/com/carenexus/api/common/web/ObservabilityIntegrationTest.java` | `@AutoConfigureObservability(tracing = false)` → `@AutoConfigureMetrics` | replaced in Boot 4 |
| `src/test/java/com/carenexus/api/releasegates/CommittedIntegrationTest.java` | `call(...)` takes `AbstractMockHttpServletRequestBuilder<?>` | in Spring 7 the multipart builder is no longer a `MockHttpServletRequestBuilder` |
| `src/test/java/com/carenexus/api/auth/controller/AuthControllerIntegrationTest.java` | the fixture user no longer sets `userId = 1` before `save` | since Hibernate 6.6, saving a new entity with a preset generated id is a merge of a missing row (`StaleObjectStateException`) |
| `src/test/java/com/carenexus/api/common/config/SwaggerUiWebjarTest.java` (new) | Swagger UI page, bundle and config answer 200 | guards the webjar pin and `springdoc.swagger-ui.version` |

Every `application.yml` key was checked against the configuration metadata of the Boot 4.1.2 jars.
None is unknown or deprecated.

### Test results

- Before the upgrade (Boot 3.3.13): `./mvnw test`, 2173 tests, 0 failures, 0 errors.
- After the upgrade (Boot 4.1.2): the whole suite, 2174 tests (the 2173 plus `SwaggerUiWebjarTest`),
  0 failures, 0 errors. The warnings in the test log are the same as before.

### Follow-ups (not needed for launch)

- Move the code to Jackson 3 (`tools.jackson.*`) and drop `spring-boot-jackson2` and the
  `preferred-json-mapper` setting. Both are deprecated in Boot 4 and will be removed in a later Boot
  line. The work is mechanical (imports, `JsonNode` accessors, the two strict deserializers). Set
  `spring.jackson.use-jackson2-defaults: true` to keep the wire format during the switch.
- Spring Boot 4.2 when it is released; move before 4.1's open-source support ends (2027-07-31).
- Expo SDK 58 clears every frontend row in section 4.
- When springdoc is next upgraded, remove `swagger-ui.version` and the `springdoc.swagger-ui.version`
  line once springdoc's bundled Swagger UI is 5.34.1 or newer. Otherwise the pin downgrades it.
