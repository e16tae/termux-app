# Changelog

Changes in the e16tae fork on top of [termux/termux-app](https://github.com/termux/termux-app).
Each release is tagged `v<upstream version>-e16tae.<n>`.

이 포크가 [termux/termux-app](https://github.com/termux/termux-app) 위에 더한 변경 사항입니다.
릴리스 태그는 `v<원본 버전>-e16tae.<번호>` 형식입니다.

## Unreleased

### English
- **Korean/CJK input:** the IME's in-progress composition is drawn at the terminal cursor (based on termux/termux-app#5242). Committed text stays on screen until the remote echo arrives, so typing over SSH no longer flickers.
- **Copying from mouse-tracking apps** (tmux-like multiplexers, full-screen TUIs): long-press and drag selects through the app, so the copy leaves out pane borders. Selection snaps to words, shows a magnifier, and has handles and a Copy button. Tapping elsewhere cancels.
- **Shift+Enter** sends a line feed instead of Enter, so apps that treat it as "new line" get it.
- **Space-bar cursor control** of keyboards such as Samsung Keyboard moves the terminal cursor.
- **Update button in the drawer:** checks this fork's releases, downloads the APK, verifies its checksum and opens Android's installer.
- **Korean translation** of the app's interface.
- Removed the swipe-in text input page of the extra keys bar.

### 한국어
- **한글 입력:** 조합 중인 글자를 터미널 커서 위치에 바로 보여 줍니다(termux/termux-app#5242 기반). 확정한 글자는 원격 응답이 올 때까지 화면에 남겨 두어 SSH에서 입력할 때 깜빡이지 않습니다.
- **마우스를 쓰는 앱에서 복사**(herdr 같은 멀티플렉서, 전체 화면 TUI): 길게 눌러 끌면 앱이 직접 선택하므로 패널 테두리 없이 복사됩니다. 단어 단위로 맞춰지고, 돋보기와 조절 손잡이, 복사 버튼이 있습니다. 다른 곳을 누르면 취소됩니다.
- **Shift+Enter**는 Enter 대신 줄바꿈 문자를 보냅니다. 줄바꿈으로 받는 앱에서 줄이 바뀝니다.
- 삼성 키보드 같은 키보드의 **스페이스바 커서 이동**으로 터미널 커서가 움직입니다.
- **서랍의 업데이트 버튼:** 이 포크의 릴리스를 확인하고, APK를 받아 체크섬을 확인한 뒤 Android 설치 화면을 엽니다.
- 앱 화면 **한국어 번역**.
- 보조키 줄을 밀면 나오던 글자 입력칸을 없앴습니다.
