# 스마트 GIF 안드로이드 앱

[스마트 GIF 생성기](https://gum456757.github.io/gif-creator/)를 전체 화면 웹뷰로 여는 안드로이드 앱입니다. 동영상 선택, 구간 미리보기, GIF 저장이 앱 안에서 동작합니다.

컴퓨터에 안드로이드 스튜디오가 없어도 GitHub Actions가 설치용 APK를 만듭니다.

## APK 받는 방법

1. 이 폴더를 GitHub 저장소에 올립니다. 기본 브랜치 이름은 무엇이든 괜찮습니다.
2. GitHub 저장소의 **Actions** 탭을 엽니다.
3. **Android APK** 워크플로가 끝날 때까지 기다립니다.
4. 완료된 실행을 열고, 아래 Artifacts에서 **smart-gif**를 받습니다.
5. 압축을 풀면 `smart-gif.apk`가 있습니다. 휴대폰으로 옮겨 설치합니다.
6. 설치가 막히면 설정에서 이 출처의 앱 설치를 허용합니다.

코드를 푸시할 때마다 같은 과정으로 새 APK가 만들어집니다. Actions 탭의 **Run workflow**로 직접 다시 빌드할 수도 있습니다.

## 앱에 들어 있는 것

- 시작 주소: `https://gum456757.github.io/gif-creator/`
- 동영상 파일 선택
- 사이트에서 만든 GIF를 다운로드 폴더에 저장
- 같은 사이트 안의 이동은 앱에 두고, 한국해양대학교 홈페이지 같은 바깥 링크는 브라우저로 엽니다
- 화면을 아래로 당기면 새로고침, 뒤로 가면 웹뷰 이전 페이지로 이동합니다

## 아이콘

런처 아이콘은 동영상 프레임, 필름, 반복 화살표를 겹친 그림입니다. 원본은 `design/app-icon-source.jpg`, 스토어용 512px 이미지는 `design/play-store-icon-512.png`입니다.

## 서명

릴리스 APK는 `keystore/gif-creator-release.p12`로 서명됩니다. 비밀번호는 `keystore.properties`에 있습니다. 이 키는 GitHub Actions가 비밀 설정 없이 설치 가능한 APK를 만들도록 저장소에 포함되어 있습니다. 저장소를 공개하면 서명 키도 함께 공개됩니다. Play 스토어에 올릴 앱이라면 이 키 대신 비공개 키를 새로 만드세요.
