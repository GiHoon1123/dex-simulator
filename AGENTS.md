# dex-simulator-kotlin 작업 지침

> 이 파일이 모든 에이전트(Codex, Claude Code 등)의 공통 지침이다. `CLAUDE.md`는 이 파일을 불러오기만 한다. 지침은 여기서만 고친다.

원본 [GiHoon1123/Dex-Simulator](https://github.com/GiHoon1123/Dex-Simulator)(NestJS, TypeScript)를 Kotlin과 Spring Boot로 다시 만드는 저장소다.
목적은 두 가지다. Kotlin·Spring 실력을 보여 주는 공개 근거, 그리고 옮기면서 **금액 정확성과 동시성 설계**를 개선한 기록.

원본 로컬 경로: `~/Desktop/common/Dex-Simulator` (읽기 전용, 수정하지 않는다)

## 설계 결정 (요약, 이유는 docs/decisions.md)

- Kotlin 2.3.21, JDK 21, Gradle Kotlin DSL
- Spring Boot 4.1.2, Spring MVC (WebFlux 쓰지 않음). 3.x는 지원 종료라 쓰지 않는다
- 주기 작업(블록 생성, 거래 생성기, MEV 봇 등)은 코루틴
- 테스트: JUnit 5 + MockK. 품질: ktlint, GitHub Actions(빌드, 테스트)
- API 문서: springdoc-openapi
- **API 계약:** 원본과 같은 엔드포인트와 JSON 모양을 유지한다. 원본 설계가 명백히 잘못된 곳만 바꾸고 docs/decisions.md에 기록한다
- **금액:** 토큰 수량과 준비금은 최소 단위 정수(BigInteger). 가격 비율 같은 표시용 값만 BigDecimal. 원본은 전부 JS number(부동소수점)라 결과가 달라지는 곳은 문서와 테스트에 명시한다
- **동시성:** 시뮬레이션 상태(풀, 거래 대기열, 체인)는 단일 작성자. 상태를 바꾸는 것은 병렬도 1인 코루틴 하나뿐이고, 다른 쪽은 메시지로 요청한다. 락으로 여기저기 보호하지 않는다
- **이벤트:** 원본의 EventEmitter 이벤트(trade.executed, block.executed, mev.* 등)는 이름과 의미를 유지하되 Spring 방식으로 옮긴다. 방식은 docs/decisions.md에서 정한다
- **영속성:** 원본처럼 메모리 상태. DB는 추가하지 않는다

## 작업 규칙

- 모듈 단위로 진행한다. 순서: 공통 뼈대 → shared/pool(AMM) → shared/blockchain → shared/events → dex-simulation(market, lp, trader) → mev-simulation → contract-simulation
- 한 모듈이 테스트까지 끝나야 다음으로 간다
- 원본에 테스트가 있는 모듈(dex-simulation, http 예외 필터)은 원본 테스트를 먼저 Kotlin으로 옮겨 같은 동작을 확인한다. 테스트가 없는 모듈(shared, mev, contract)은 원본 동작을 읽고 핵심 경로 테스트를 새로 쓴다
- 마이그레이션 중에는 기능을 추가하지 않는다. 새 기능은 이전이 끝난 뒤 별도로
- 줄 단위 번역이 아니라 Kotlin답게 쓴다(data class, sealed class, null 안전성, 불변 우선)
- 원본과 동작이 달라지면 반드시 docs/decisions.md에 남긴다

## 커밋과 GitHub

- 커밋, push, PR 생성과 병합은 확인 없이 진행한다(2026-11-10 사용자 위임: "지침대로 자체검토해서 적용하고 빨리빨리 넘어가"). 대신 커밋 전에 빌드·ktlint·테스트 통과와 지침 준수를 스스로 검토한다
- 작성자: `GiHoon <85157729+GiHoon1123@users.noreply.github.com>`, gh 계정: GiHoon1123
- 작업 단위 커밋, Conventional Commits(`feat(pool): ...`, `test(dex): ...`), 영어 메시지
- AI 표기(Co-Authored-By 등)를 넣지 않는다
- 모듈마다 브랜치를 만들고 PR로 main에 합친다

## 문서

- 코드 주석은 영어, 문서(.md)는 한국어
- README: 무엇을 시뮬레이션하는지, 구조, 실행 방법, 원본과의 차이
- docs/decisions.md: 설계 결정과 이유, 원본과 달라진 점

## 현재 상태와 다음 할 일

> 작업을 이어받는 에이전트는 여기부터 읽는다. 단계가 끝날 때마다 이 칸을 갱신한다.

**2026-11-10 기준**
- `main`: 지침 문서 커밋 1개(`docs: add migration guidelines and decision log`), GitHub `GiHoon1123/dex-simulator-kotlin`(공개)에 push 완료
- 작업 브랜치: `chore/bootstrap` (아직 커밋 없음)
- `chore/bootstrap`에 만든 파일
  - Gradle: `build.gradle.kts`(Kotlin 2.3.21, Spring Boot 4.1.2, springdoc 3.2.0, MockK 1.14.12, ktlint 플러그인 14.2.0), `settings.gradle.kts`, Gradle 래퍼(9.8.2)
  - `src/main`: `DexSimulatorApplication.kt`, `application.yml`(포트 3000, Swagger UI `/api`), `common/error/ApiException.kt`, `common/error/GlobalExceptionHandler.kt`(원본 `HttpExceptionFilter` 이전), `common/config/ClockConfig.kt`
  - `src/test`: `GlobalExceptionHandlerTest.kt`(원본 테스트 8개를 옮김), `DexSimulatorApplicationTests.kt`
  - `.github/workflows/ci.yml`, `README.md`
- `./gradlew build` 통과(ktlint, 테스트 8개). 원본 예외 필터 테스트 8개 중 `null`, `undefined` 예외 2개는 JVM에 없어 "Exception이 아닌 Throwable" 1개로 합쳤고, 쿼리 문자열이 path에 남는지 확인하는 테스트 1개를 추가했다
- `bootRun` 확인: `/api`(Swagger UI)와 `/api-docs` 200, 없는 경로는 원본과 같은 형식의 404 JSON(메시지는 Spring 기본 문구라 원본 "Cannot GET /nope"와 다름)

**다음 할 일**
1. (완료) `./gradlew build` 통과
2. (완료) `.DS_Store`를 `.gitignore`에 추가했다. 지침 파일은 `AGENTS.md`로 옮겼고 `CLAUDE.md`는 `@AGENTS.md`만 담는다(아직 커밋 전)
3. 커밋하고 `chore/bootstrap`을 push, PR을 열어 CI 통과 확인 후 main에 합친다
4. 그다음 모듈: `shared/pool`(AMM). 원본 `src/shared/pool`을 읽고, 금액을 최소 단위 정수로 바꾸는 규칙을 docs/decisions.md 3번에 구체적으로 적은 뒤 시작한다

**남은 결정**
- docs/decisions.md 5번: 이벤트 전달 방식(Spring ApplicationEventPublisher 또는 코루틴 SharedFlow). shared/events 이전 때 정한다
- 원본 예외 처리는 서버 내부 오류도 400으로 돌려준다. 지금은 원본 동작을 그대로 옮겼다. 바꿀지 검토가 필요하다
- 원본 `src/common/guards/blockchain-prerequisites.guard.ts`는 shared/blockchain 이전 때 함께 옮긴다
- 원본은 개발 환경에서 블록 생성, 거래 생성기, MEV 봇을 자동 시작한다(`main.ts`). 각 모듈 이전 때 같은 동작을 넣는다
