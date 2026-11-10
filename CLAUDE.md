# dex-simulator-kotlin 작업 지침

원본 [GiHoon1123/Dex-Simulator](https://github.com/GiHoon1123/Dex-Simulator)(NestJS, TypeScript)를 Kotlin과 Spring Boot로 다시 만드는 저장소다.
목적은 두 가지다. Kotlin·Spring 실력을 보여 주는 공개 근거, 그리고 옮기면서 **금액 정확성과 동시성 설계**를 개선한 기록.

원본 로컬 경로: `~/Desktop/common/Dex-Simulator` (읽기 전용, 수정하지 않는다)

## 설계 결정 (요약, 이유는 docs/decisions.md)

- Kotlin 최신 안정 버전, JDK 21, Gradle Kotlin DSL
- Spring Boot 3.x 최신, Spring MVC (WebFlux 쓰지 않음)
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

- 커밋과 push는 할 때마다 사용자에게 먼저 확인받는다
- 작성자: `GiHoon <85157729+GiHoon1123@users.noreply.github.com>`, gh 계정: GiHoon1123
- 작업 단위 커밋, Conventional Commits(`feat(pool): ...`, `test(dex): ...`), 영어 메시지
- AI 표기(Co-Authored-By 등)를 넣지 않는다
- 모듈마다 브랜치를 만들고 PR로 main에 합친다

## 문서

- 코드 주석은 영어, 문서(.md)는 한국어
- README: 무엇을 시뮬레이션하는지, 구조, 실행 방법, 원본과의 차이
- docs/decisions.md: 설계 결정과 이유, 원본과 달라진 점
