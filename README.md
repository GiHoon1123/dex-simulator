# dex-simulator

탈중앙 거래소(DEX)의 유동성 풀 가격 결정, 거래 대기열과 블록 생성, 거래 순서를 노린 공격(MEV)을 재현하는 시뮬레이터.
처음 NestJS로 만든 것을 Kotlin과 Spring Boot로 다시 만들고 있다. 원본 NestJS 저장소(`Dex-Simulator-nestjs`)는 비공개로 보관한다.

## 원본과 달라지는 점

- **금액:** 원본은 모든 수량과 가격을 JS 부동소수점으로 계산한다. 여기서는 토큰 수량과 준비금을 토큰 소수 자릿수에 맞춰 다루고, 풀 밖으로 나가는 양은 최소 단위에서 내림한다
- **동시성:** 원본은 Node 이벤트 루프 하나에 기대어 공유 상태를 바로 바꾼다. 여기서는 시뮬레이션 상태를 하나의 작성자만 바꾸도록 한다
- **원본 결함 수정:** 동작하지 않던 요청 검증, 풀을 등록하지 않던 오라클, 대기열에 들어가지 못하던 풀 스왑, 가짜 ABI 인코딩 등 ([decisions.md 9·10번](docs/decisions.md))

자세한 결정과 이유는 [docs/decisions.md](docs/decisions.md)에 있다.

## 진행 상황

| 모듈 | 상태 |
|---|---|
| 공통 뼈대 (예외 처리, API 문서, CI) | 완료 |
| shared/pool (AMM, 가격 오라클) | 완료 |
| shared/blockchain (거래 대기열, 블록 생성) | 완료 |
| shared/events (풀·블록체인 이벤트, 각 모듈과 함께 이전) | 완료 |
| dex-simulation (market, lp, trader) | 예정 |
| mev-simulation | 예정 |
| contract-simulation | 예정 |

## 실행

```bash
./gradlew bootRun
```

- 서버: http://localhost:3000
- API 문서: http://localhost:3000/api

## 기술 스택

Kotlin 2.3, Spring Boot 4.1 (Spring MVC), Kotlin Coroutines, JUnit 5, MockK, springdoc-openapi, ktlint, GitHub Actions
