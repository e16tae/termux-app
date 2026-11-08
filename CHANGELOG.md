# Changelog

Changes in the e16tae fork on top of [termux/termux-app](https://github.com/termux/termux-app).
Each release is tagged `v<upstream version>-e16tae.<n>`.

이 포크가 [termux/termux-app](https://github.com/termux/termux-app) 위에 더한 변경 사항입니다.
릴리스 태그는 `v<원본 버전>-e16tae.<번호>` 형식입니다.

## v0.118.0-e16tae.1 (2026-11-08)

### English
- **Korean/CJK input:** the IME's in-progress composition is drawn at the terminal cursor (based on termux/termux-app#5242). Committed text stays on screen until the remote echo arrives, so typing over SSH no longer flickers, including when Enter is pressed.
- **Copying from mouse-tracking apps** (tmux-like multiplexers, full-screen TUIs): long-press and drag selects through the app, so the copy leaves out pane borders. Selection snaps to words, shows a magnifier, and has handles and a Copy button. Tapping elsewhere cancels. Selection handles take touches in a wider margin around them.
- **Shift+Enter** sends a line feed instead of Enter, so apps that treat it as "new line" get it.
- **Space-bar cursor control** of keyboards such as Samsung Keyboard moves the terminal cursor.
- **Update button in the drawer:** checks this fork's releases, downloads the APK, verifies its checksum and opens Android's installer. Long-press it to install the latest development build.
- **Drawer:** a menu button opens the terminal menu (keep screen on, reset, share, …), which long press no longer reaches while a program tracks the mouse.
- **Attach:** the `ATTACH` extra key picks files, copies them to `~/downloads/attach` and pastes their paths. If `~/bin/termux-attach-hook` exists, it runs with each file and the last line it prints is pasted instead, e.g. the path after uploading the file to the machine you are connected to. Pasting attaches the files on the clipboard (e.g. a copied screenshot) the same way, and so do images inserted from the keyboard, e.g. from its clipboard history.
- **Korean translation** of the app's interface.
- Removed the swipe-in text input page of the extra keys bar.

### 한국어
- **한글 입력:** 조합 중인 글자를 터미널 커서 위치에 바로 보여 줍니다(termux/termux-app#5242 기반). 확정한 글자는 원격 응답이 올 때까지 화면에 남겨 두어 SSH에서 입력하거나 Enter를 누를 때 깜빡이지 않습니다.
- **마우스를 쓰는 앱에서 복사**(herdr 같은 멀티플렉서, 전체 화면 TUI): 길게 눌러 끌면 앱이 직접 선택하므로 패널 테두리 없이 복사됩니다. 단어 단위로 맞춰지고, 돋보기와 조절 손잡이, 복사 버튼이 있습니다. 다른 곳을 누르면 취소됩니다. 선택 손잡이는 주변 여백까지 터치를 받아 잡기 쉽습니다.
- **Shift+Enter**는 Enter 대신 줄바꿈 문자를 보냅니다. 줄바꿈으로 받는 앱에서 줄이 바뀝니다.
- 삼성 키보드 같은 키보드의 **스페이스바 커서 이동**으로 터미널 커서가 움직입니다.
- **서랍의 업데이트 버튼:** 이 포크의 릴리스를 확인하고, APK를 받아 체크섬을 확인한 뒤 Android 설치 화면을 엽니다. 길게 누르면 최신 개발용 시험판을 설치합니다.
- **서랍:** 메뉴 버튼으로 터미널 메뉴(화면 켜짐 유지, 초기화, 공유 등)를 엽니다. 마우스를 쓰는 앱에서는 길게 눌러도 이 메뉴가 열리지 않기 때문입니다.
- **첨부:** 보조키 `ATTACH`로 파일을 고르면 `~/downloads/attach`에 복사하고 경로를 붙여 넣습니다. `~/bin/termux-attach-hook`이 있으면 파일마다 실행하고, 그 출력의 마지막 줄을 대신 붙여 넣습니다. 예를 들어 접속한 머신에 파일을 올리고 그 경로를 출력하게 할 수 있습니다. 클립보드에 파일(복사한 스크린샷 등)이 있을 때 붙여 넣거나, 키보드에서 이미지를 넣을 때(키보드 클립보드 기록 등)도 같은 방식으로 첨부합니다.
- 앱 화면 **한국어 번역**.
- 보조키 줄을 밀면 나오던 글자 입력칸을 없앴습니다.
