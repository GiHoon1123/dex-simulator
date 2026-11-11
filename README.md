# dex-simulator

탈중앙 거래소(DEX)가 실제로 어떻게 움직이는지 재현하는 시뮬레이터.
유동성 풀의 가격 결정, 거래 대기열과 블록 생성, 거래 순서를 노린 공격(MEV), 여러 풀을 거치는 경로 스왑을 한 서버에서 돌린다.

처음 NestJS로 만든 것을 Kotlin과 Spring Boot로 다시 만들었다. 원본 NestJS 저장소(`Dex-Simulator-nestjs`)는 비공개로 보관한다.

## 무엇을 시뮬레이션하나

| 모듈 | 경로 | 내용 |
|---|---|---|
| pool | `/pool` | 상수곱(x·y=k) 풀 4개, 스왑·유동성 추가/제거, 가격 오라클(TWAP, 외부 가격 편차, MEV 기회 탐지) |
| blockchain | `/blockchain` | 거래 대기열(가스 가격 순), 12초 블록 생성, 가스 부족 거래 재시도, 무작위 사용자 거래 생성기 |
| dex | `/market`, `/lp`, `/trader` | ETH/BTC 시장 가격 변동, 유동성 공급자 지분·동적 수수료·보상 분배, 랜덤 거래와 아비트라지 |
| mev | `/mev` | 대기열의 큰 스왑을 찾아 프론트런·백런·샌드위치 전략을 고르는 MEV 봇 |
| contract | `/contract-simulation` | 모든 풀을 한 컨트랙트에 두는 싱글톤 구조와 최대 3홉 경로 탐색 라우터 |

실행하면 원본처럼 블록 자동 생성, 거래 생성기, MEV 봇이 함께 켜진다. 풀에서 실행한 스왑은 실제 ABI(`swap(address,bool,int256,uint160,bytes)`)로 인코딩되어 대기열과 블록으로 들어간다.

## 설계

- **단일 작성자:** 시뮬레이션 상태(풀, 대기열, 체인)는 스레드 하나만 바꾼다. 서비스의 공개 함수는 `suspend`이고 그 스레드에서 상태를 읽고 쓴다. 여러 단계에 걸친 변경(스왑 → 풀 → 이벤트, 멀티홉 스왑)이 다른 요청과 섞이지 않는다. 락은 쓰지 않는다
- **이벤트:** 원본의 EventEmitter를 코루틴 SharedFlow 이벤트 버스로 옮겼다. 상태를 바꾸는 반응은 같은 단계 안에서 직접 호출하고, 다른 모듈이 알기만 하면 되는 것만 이벤트로 보낸다
- **금액:** 토큰 수량과 준비금은 토큰 소수 자릿수(ETH 18, USDC 6, BTC 8)에 맞춘 BigDecimal이다. 풀 밖으로 나가는 양과 수수료를 뗀 투입량은 최소 단위에서 내림해서 반올림 오차가 항상 풀 쪽에 남는다. 가격, 비율, 통계는 Double이다
- **시간과 난수:** `Clock`과 `Random`을 주입받아 테스트에서 시간을 움직이고 난수를 정해 둔다

자세한 결정과 이유는 [docs/decisions.md](docs/decisions.md)에 있다.

## 원본과 달라진 점

API 경로, 상태 코드, JSON 필드는 원본과 같다. 원본에서 실제로 동작하지 않던 부분은 고치고 기록했다([decisions.md 9~14번](docs/decisions.md)). 주요한 것:

- 멀티홉 스왑이 원자적이지 않았다(중간 실패나 최소 출력량 미달에도 앞 홉의 풀 변경이 남았다) → 경로 전체를 검사한 뒤 함께 반영한다
- 요청 검증이 돌지 않았다(ValidationPipe 미등록) → Bean Validation으로 검증한다
- 오라클이 풀을 하나도 등록하지 않아 가격, TWAP, MEV 탐지가 비어 있었다
- 풀 스왑이 임의 nonce 때문에 대기열에서 대부분 거절됐고, call data는 0으로 채운 가짜였다
- 아비트라지가 가격 차이를 줄이는 대신 벌리는 방향으로 거래했고, 한 기회를 두 번 거래했다
- MEV 탐지가 같은 거래를 매초 새 기회로 쌓았고, 모든 토큰을 18자리 ETH로 읽었다

## 실행

JDK 21이 필요하다.

```bash
./gradlew bootRun
```

- 서버: http://localhost:3000
- API 문서(Swagger UI): http://localhost:3000/api

자동 시작을 끄려면 `--dex.simulation.auto-start=false`를 준다.

## 테스트

```bash
./gradlew build   # ktlint, 테스트 119개
```

- AMM 계산과 ABI 코덱은 따로 계산한 기댓값과 비교한다
- 서비스 테스트는 실제 단일 작성자 스레드 위에서 돌고, 동시 요청을 보내도 갱신이 사라지지 않는지 확인한다
- HTTP 테스트는 원본과 같은 경로, 상태 코드, JSON 필드를 확인한다
- 원본에 있던 dex-simulation 테스트와 예외 필터 테스트는 Kotlin으로 옮겼다

## 구조

```
src/main/kotlin/io/github/gihoon1123/dexsimulator/
├── common/      단일 작성자(SimulationContext), 이벤트 버스, 예외 처리, 금액 직렬화
├── shared/
│   ├── pool/        상수곱 풀, 가격 오라클
│   ├── blockchain/  대기열, 블록, 가스, 거래 생성기, ABI 코덱
│   └── events/      풀·블록체인 이벤트
├── dex/         market, lp, trader
├── mev/         탐지기, 전략, 봇
└── contract/    싱글톤 컨트랙트, 라우터
```

## 기술 스택

Kotlin 2.3, Spring Boot 4.1 (Spring MVC), Kotlin Coroutines, JUnit 5, springdoc-openapi, ktlint, GitHub Actions
