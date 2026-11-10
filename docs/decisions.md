# 설계 결정 기록

원본 Dex-Simulator(NestJS)를 Kotlin·Spring Boot로 옮기면서 내린 결정과 이유를 남긴다. 원본과 동작이 달라지는 곳은 반드시 여기에 적는다.

---

## 0. 원본 분석 (2026-11-10)

- 규모: TypeScript 소스 약 13,200줄(테스트 제외), 테스트 16개 파일 약 5,900줄
- 모듈: `shared`(pool, blockchain, events), `dex-simulation`(market, lp, trader), `mev-simulation`(detector, strategy, bot), `contract-simulation`(router, singleton), `common`(예외 필터)
- 의존 방향: `mev-simulation`과 `common`이 `shared`에 의존. 모듈 사이는 대부분 이벤트로 연결됨
- 이벤트: `trade.executed`, `arbitrage.opportunity`, `market.price.changed`, `transaction.pending/selected/executed/failed`, `block.executing/executed`, `mev.*` 등
- 테스트 범위: dex-simulation(market, lp, trader의 서비스, 컨트롤러, 타입)과 http 예외 필터, 앱 모듈. **shared(pool, blockchain), mev, contract에는 테스트가 없다**
- 숫자: 전부 JS number. `BigInt` 사용 없음. AMM 출력량 계산 `(amountInAfterFee * reserveOut) / (reserveIn + amountInAfterFee)`, 유동성 `Math.sqrt(a * b)` 모두 부동소수점
- 주기 작업과 상태: 블록 생성, 거래 생성기, MEV 봇, 트레이더, 시장 가격이 타이머와 이벤트로 공유 상태를 바꾼다. Node 이벤트 루프 하나라서 락 없이 동작한다

---

## 1. 기술 스택

- **결정:** Kotlin 최신 안정, JDK 21, Spring Boot 3.x 최신, Spring MVC, 코루틴, JUnit 5 + MockK, ktlint, springdoc-openapi, GitHub Actions
- **이유:** 국내 Kotlin·Spring 공고에서 가장 흔한 조합이다. 주기 작업이 많아서 코루틴이 Kotlin다운 방식이다. WebFlux는 이 프로젝트에 이점이 없다

## 2. API 계약

- **결정:** 원본과 같은 엔드포인트와 JSON 모양 유지
- **이유:** 같은 요청을 양쪽에 보내 결과를 비교할 수 있다
- **예외:** (원본 설계를 바꾼 곳이 생기면 여기에 추가)

## 3. 금액 표현

- **결정:** 토큰 수량과 준비금은 최소 단위 정수(BigInteger). 가격 비율 같은 표시용 값만 BigDecimal
- **이유:** 블록체인(wei)과 금융(원 단위) 모두 금액을 최소 단위 정수로 다룬다. 부동소수점 오차가 사라지고, AMM 계산을 실제 온체인 방식(정수 나눗셈, 내림)과 맞출 수 있다
- **원본과 달라지는 점:** (모듈을 옮기며 구체적으로 추가)

## 4. 동시성 모델

- **결정:** 시뮬레이션 상태는 단일 작성자. 병렬도 1인 코루틴 하나만 상태를 바꾸고, 컨트롤러와 주기 작업은 메시지로 요청한다
- **이유:** Spring MVC는 요청마다 스레드가 다르고 코루틴도 여러 스레드에서 돈다. 원본처럼 공유 상태를 바로 바꾸면 경합이 생긴다. 블록체인도 블록을 순서대로 하나씩 처리하므로 모델이 같고, 테스트가 결정적으로 돈다
- **대안과 버린 이유:** 상태마다 뮤텍스를 두는 방식은 여러 상태를 함께 바꾸는 거래(스왑 → 풀 → 이벤트)에서 락 순서와 범위를 관리해야 해서 단일 작성자보다 복잡하다

## 5. 이벤트 전달

- **결정:** (shared/events를 옮길 때 정한다. 후보: Spring ApplicationEventPublisher, 코루틴 SharedFlow)

## 6. 영속성

- **결정:** 원본처럼 메모리 상태. DB는 이전이 끝난 다음 단계로 미룬다
