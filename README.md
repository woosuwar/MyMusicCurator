# My Music Curator

안드로이드 폰에서 재생한 음악을 자동으로 기록해서 **재생 횟수·재생 주기**를 정리하고,
**장르 / BPM / 분위기 / 가수 / 앨범 / 발매 시기** 메타데이터를 모아 **좋아하는 가수와 장르를 추천**하는 앱입니다.

## 동작 방식

| 단계 | 구현 |
|---|---|
| 재생 감지 | `NotificationListenerService` + `MediaSessionManager` → 유튜브 뮤직, 스포티파이, 멜론, 삼성 뮤직 등 MediaSession 을 쓰는 모든 앱 |
| 재생 1회 판정 | 실제 재생 시간 30초 이상 + (곡 길이의 절반 또는 4분 이상) — Last.fm 스크로블 규칙. 일시정지 시간은 빼고, 한 곡 반복도 횟수로 셈 |
| 로컬 메타데이터 | MediaStore 에서 파일을 찾아 ID3v2(mp3)·Vorbis comment(FLAC) 태그를 직접 파싱 → `TBPM`, `TMOO`, `TXXX:MOOD`, `TCON`, `TDOR/TDRC`, `TCOM` |
| 외부 API | **Last.fm** (장르·분위기 태그, 비슷한 가수, 장르별 인기 가수) · **MusicBrainz** (원 발매연도, 키 불필요) · **GetSongBPM** (BPM, 선택) |
| 통계 | 기간별(7일/30일/1년/전체) 많이 들은 곡·가수·장르·분위기·BPM 분포·연대·시간대·요일 |
| 재생 주기 | 곡별 평균/중앙값 재생 간격, 다음 예상 재생 시점, "다시 들을 때가 된 애청곡" |
| 추천 | 최근 재생일수록 가중치를 주는 선호 점수(반감기 30일) × Last.fm 유사도 → 아직 안 들어본 가수, 그 가수들에서 나온 새 장르 |

## 설정

1. `local.properties.example` 을 참고해 `local.properties` 에 API 키 입력
   - `LASTFM_API_KEY` — https://www.last.fm/api/account/create (무료, 추천 기능에 필요)
   - `GETSONGBPM_API_KEY` — https://getsongbpm.com/api (선택)
2. Android Studio 로 열거나 `./gradlew assembleDebug`
3. 앱 실행 후 **설정 → 알림 접근 허용**, (선택) **음악 파일 접근 허용**

> MusicBrainz 는 연락 가능한 User-Agent 를 요구합니다. 포크해서 배포한다면 `data/remote/Clients.kt` 의 `USER_AGENT` 를 본인 저장소 주소로 바꾸세요.

## 구조

```
app/src/main/java/com/mymusiccurator/
├─ service/MediaListenerService.kt   MediaSession 감시
├─ domain/PlaybackTracker.kt         재생 시간 누적·1회 판정
├─ domain/Stats.kt                   통계·재생 주기·선호 점수
├─ domain/Recommendations.kt         추천 점수 계산 + Last.fm 연동
├─ data/db/                          Room (tracks, play_events, recommendations)
├─ data/metadata/                    ID3/FLAC 파서, 태그 → 장르/분위기 분류
├─ data/remote/                      Last.fm, MusicBrainz, GetSongBPM
├─ data/repository/                  재생 저장, 메타데이터 보강
├─ work/Workers.kt                   WorkManager (메타데이터 수집, 일일 추천 갱신)
└─ ui/                               Jetpack Compose 화면 (통계 / 곡 목록 / 곡 상세 / 추천 / 설정)
```

## 데이터 출처

- BPM data provided by [GetSongBPM](https://getsongbpm.com)
- 태그·유사 가수 데이터: [Last.fm](https://www.last.fm)
- 발매연도 데이터: [MusicBrainz](https://musicbrainz.org)

## 테스트

```bash
./gradlew :app:testDebugUnitTest
```
태그 파서, 재생 판정(일시정지·건너뛰기·반복), 통계·주기, 추천 점수 계산을 검증합니다.
