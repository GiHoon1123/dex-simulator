# dex-simulator 작업 지침

> 이 파일이 모든 에이전트(Codex, Claude Code 등)의 공통 지침이다. `CLAUDE.md`는 이 파일을 불러오기만 한다. 지침은 여기서만 고친다.

원본 `GiHoon1123/Dex-Simulator-nestjs`(NestJS, TypeScript, 비공개)를 Kotlin과 Spring Boot로 다시 만드는 저장소다. 저장소는 `GiHoon1123/dex-simulator`(2026-11-11 `dex-simulator-kotlin`에서 이름 변경), 로컬 경로는 `~/Desktop/common/dex-simulator`.
목적은 두 가지다. Kotlin·Spring 실력을 보여 주는 공개 근거, 그리고 옮기면서 **금액 정확성과 동시성 설계**를 개선한 기록.

원본 로컬 경로: `~/Desktop/common/Dex-Simulator-nestjs` (읽기 전용, 수정하지 않는다)

## 설계 결정 (요약, 이유는 docs/decisions.md)

- Kotlin 2.3.21, JDK 21, Gradle Kotlin DSL
- Spring Boot 4.1.2, Spring MVC (WebFlux 쓰지 않음). 3.x는 지원 종료라 쓰지 않는다
- 주기 작업(블록 생성, 거래 생성기, MEV 봇 등)은 코루틴
- 테스트: JUnit 5 + MockK. 품질: ktlint, GitHub Actions(빌드, 테스트)
- API 문서: springdoc-openapi
- **API 계약:** 원본과 같은 엔드포인트와 JSON 모양을 유지한다. 원본 설계가 명백히 잘못된 곳만 바꾸고 docs/decisions.md에 기록한다
- **금액:** 토큰 수량과 준비금은 토큰 소수 자릿수에 맞춘 BigDecimal(최소 단위에서 내림). 가격, %, USD 추정 같은 표시용 값은 Double. 원본은 전부 JS number(부동소수점)라 결과가 달라지는 곳은 문서와 테스트에 명시한다
- **동시성:** 시뮬레이션 상태(풀, 거래 대기열, 체인)는 단일 작성자. 상태를 바꾸는 것은 병렬도 1인 코루틴 하나뿐이고, 다른 쪽은 메시지로 요청한다. 락으로 여기저기 보호하지 않는다
- **이벤트:** 원본의 EventEmitter 이벤트(trade.executed, block.executed, mev.* 등)는 이름과 의미를 유지하고, 코루틴 SharedFlow 기반 `EventBus`로 옮긴다(비동기, 발행 순서 유지)
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

**2026-11-11 기준**
- `main`: 공통 뼈대(PR #1), shared/pool(PR #2), shared/blockchain(PR #3), 저장소 이름 변경(PR #4), dex-simulation(PR #5), mev-simulation(PR #6)
- 공통 구조
  - `common/sim/SimulationContext`: 시뮬레이션 상태를 바꾸는 단일 스레드. 서비스의 공개 함수는 모두 `suspend`이고 `sim.run { }` 안에서 상태를 읽고 쓴다. 주기 작업은 `sim.scope`에서 돈다
  - `common/event/EventBus`: SharedFlow 이벤트 버스. 구독은 `subscribe<T>(scope, onSubscribed) { }`
  - `common/json/AmountJson`: BigDecimal을 끝의 0 없는 숫자로 직렬화
  - `common/config`: `Clock`, `Random` 빈. 시간과 난수는 반드시 주입받는다(테스트는 `MutableClock`, 시드 고정 Random)
  - 컨트롤러는 `suspend` 함수, POST는 `@ResponseStatus(CREATED)`(NestJS 기본 201)
  - 테스트 지원: `src/test/.../support`(`MutableClock`, `EventRecorder`). HTTP 테스트는 `@SpringBootTest` + MockMvc + `asyncDispatch`, 자동 시작은 `src/test/resources/application.properties`로 끈다
- shared/pool: `AmmMath`(순수 함수), `PoolService`, `PoolOracleService`, `PoolController`
- shared/blockchain: `TransactionPoolService`(대기열), `BlockService`(블록 생성·실행·자동 생성), `GasService`, `TransactionGeneratorService`, `TransactionConverterService`(풀 스왑 → 대기열), `SwapAbi`, `SimulationAutoStart`
- dex-simulation(`dex/`): `MarketService`, `LpService`, `TraderService`, 컨트롤러는 `DexControllers.kt`, 모델·이벤트는 `DexModels.kt`
- mev-simulation(`mev/`): `MevDetectorService`(대기열 스캔), `MevStrategyService`, `MevBotService`, `MevController`와 전제 조건 검사 `BlockchainPrerequisites`. 자동 시작에 MEV 봇 포함
- 이벤트: `shared/events/PoolEvents.kt`, `BlockchainEvents.kt`, `dex/DexModels.kt`
- **원칙:** 상태를 바꾸는 반응은 직접 호출(같은 시뮬레이션 단계), 알림만 필요한 것은 이벤트(decisions 11번)
- 테스트 106개(ktlint 포함 `./gradlew build` 통과). jar를 띄워 자동 블록 생성, 풀 스왑 → 대기열 → 블록, LP 초기화 → 거래 → 가격 변동 → 아비트라지 흐름, 자동 시작 상태에서 MEV 봇 공격을 확인했다. 테스트 지원에 `ScriptedRandom`(원본의 `Math.random` 모킹 대체)이 있다
- 원본에서 고친 문제는 docs/decisions.md 9번(pool), 10번(blockchain), 11번(dex), 13번(mev). suspend 핸들러 오류 형식은 12번

**다음 할 일**
1. `contract-simulation` 이전(마지막 모듈): 원본 `src/contract-simulation`(router, singleton 컨트랙트 시뮬레이션, 컨트롤러 1개)
2. 끝나면 README 진행 상황과 실행 안내를 마무리하고, 전체를 로컬에서 다시 확인한다

**이전이 모두 끝나면**
- 로컬 실행으로 전체 확인하고 README의 진행 상황을 마무리한다. 저장소 이름 변경과 원본 비공개 전환은 이미 끝났다(docs/decisions.md 8번)

**남은 결정**
- 원본 예외 처리는 서버 내부 오류도 400으로 돌려준다. 지금은 원본 동작을 그대로 옮겼다. 바꿀지 검토가 필요하다
- 원본은 개발 환경에서 블록 생성, 거래 생성기, MEV 봇을 자동 시작한다(`main.ts`). 각 모듈 이전 때 같은 동작을 넣는다
