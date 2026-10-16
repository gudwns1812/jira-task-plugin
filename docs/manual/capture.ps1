<#
.SYNOPSIS
    사용 설명서(index.html)의 캡처 슬롯을 채운다.

.DESCRIPTION
    슬롯 번호를 주면 images\<번호>.png 로 화면을 저장한다. 설명서는 그 파일을 자동으로
    그 자리에 넣는다 — HTML을 고칠 필요가 없다.

    기본은 5초 뒤 촬영이다. 명령을 실행한 뒤 찍고 싶은 창을 앞으로 가져다 두면 된다.

.EXAMPLE
    .\capture.ps1 -List
    슬롯 번호와 각 슬롯에 무엇을 담아야 하는지 전부 보여준다.

.EXAMPLE
    .\capture.ps1 14
    5초 뒤 화면 전체를 찍어 images\14.png 로 저장한다.

.EXAMPLE
    .\capture.ps1 14 -Window -Delay 8
    8초 뒤, 그때 앞에 있는 창만 찍는다. 다이얼로그를 담을 때 쓴다.
#>
[CmdletBinding(DefaultParameterSetName = 'Capture')]
param(
    [Parameter(ParameterSetName = 'Capture', Position = 0, Mandatory = $true)]
    [ValidateRange(1, 19)]
    [int]$Slot,

    # 촬영까지 기다리는 시간(초). 그 사이에 찍을 창을 앞으로 가져다 둔다.
    [Parameter(ParameterSetName = 'Capture')]
    [ValidateRange(0, 60)]
    [int]$Delay = 5,

    # 화면 전체 대신 그때 앞에 있는 창만 찍는다.
    [Parameter(ParameterSetName = 'Capture')]
    [switch]$Window,

    # 슬롯 목록만 보여주고 끝낸다.
    [Parameter(ParameterSetName = 'List', Mandatory = $true)]
    [switch]$List
)

$ErrorActionPreference = 'Stop'

# 설명서의 슬롯과 같은 목록이다. 한쪽만 고치면 서로 어긋난다.
$slots = [ordered]@{
    '01' = '터미널에서 claude --version 또는 codex --version 이 버전을 답하는 화면'
    '02' = 'Settings > Plugins > 톱니바퀴 > Install Plugin from Disk 메뉴'
    '03' = 'Tools > Jira Task 메뉴가 펼쳐진 화면(항목 6개)'
    '04' = '설정 마법사 1단계 - Jira 연결 정보 창'
    '05' = '설정 마법사 마지막 - 설정 내용 확인 창'
    '06' = 'Settings | Tools | Jira Task 설정 화면 전체'
    '07' = 'GitLab 연결 테스트를 눌러 성공 문구가 뜬 상태'
    '08' = 'AI 초안 그룹 - 체크박스 2개, 사용할 AI 콤보와 탐지 결과, 모델·경로 칸'
    '09' = 'Start Jira Task 창 - 현재 스프린트 작업 목록'
    '10' = '상태 표시줄의 현재 작업 위젯'
    '11' = '오른쪽 Jira Task 툴 윈도우 - 현재 작업/스프린트/설정 요약'
    '12' = '커밋 창을 연 순간, 메시지 칸에 이슈 키가 이미 붙어 있는 모습'
    '13' = '커밋 메시지 칸 옆 툴바의 AI 아이콘'
    '14' = 'AI 초안이 채워진 커밋 메시지 - [키] type: 한 줄'
    '15' = 'Tools > Jira Task > Commit with Jira Task 창과 AI로 쓰기 버튼'
    '16' = 'Create Merge Request 창 - 대상 브랜치/템플릿/제목/본문'
    '17' = 'AI로 다시 쓰기를 누른 뒤 바뀐 MR 제목과 본문'
    '18' = 'MR을 만들었습니다 알림과 열기 버튼'
    '19' = 'Search Jira Issues 창에서 이슈를 찾아 제목/설명을 고치는 화면'
}

$imageDirectory = Join-Path $PSScriptRoot 'images'

if ($List) {
    Write-Output ''
    Write-Output '  슬롯  상태   담을 화면'
    Write-Output '  ----  -----  ------------------------------------------------------------'
    foreach ($number in $slots.Keys) {
        $file = Join-Path $imageDirectory "$number.png"
        $state = if (Test-Path $file) { '있음 ' } else { '비었음' }
        Write-Output ('  {0,4}  {1}  {2}' -f $number, $state, $slots[$number])
    }
    $filled = @($slots.Keys | Where-Object { Test-Path (Join-Path $imageDirectory "$_.png") }).Count
    Write-Output ''
    Write-Output ('  {0}/{1} 채움. 비어 있는 슬롯은 설명서에 "캡처 필요"로 보입니다.' -f $filled, $slots.Count)
    Write-Output ''
    return
}

# 슬롯 번호는 '01' 같은 두 자리 문자열이 정본이다 — 파일 이름과 같은 모양이어야 헷갈리지 않는다.
$key = '{0:d2}' -f $Slot
if (-not $slots.Contains($key)) {
    throw "슬롯 $key 은 설명서에 없습니다. .\capture.ps1 -List 로 목록을 보세요."
}

if (-not (Test-Path $imageDirectory)) {
    New-Item -ItemType Directory -Path $imageDirectory | Out-Null
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;

public class JiraTaskCapture {

    [StructLayout(LayoutKind.Sequential)]
    public struct Rect { public int Left; public int Top; public int Right; public int Bottom; }

    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr handle, out Rect rect);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
'@

# 디스플레이 배율(125%, 150%)을 켜 둔 화면에서 흐릿하게 찍히지 않게 한다.
[void][JiraTaskCapture]::SetProcessDPIAware()

Write-Output ''
Write-Output ("  슬롯 {0} : {1}" -f $key, $slots[$key])
if ($Delay -gt 0) {
    Write-Output ("  {0}초 뒤에 찍습니다. 지금 찍을 화면을 앞으로 가져다 두세요." -f $Delay)
    for ($remaining = $Delay; $remaining -gt 0; $remaining--) {
        Write-Host ("`r  {0,2} ..." -f $remaining) -NoNewline
        Start-Sleep -Seconds 1
    }
    Write-Host "`r        `r" -NoNewline
}

if ($Window) {
    $handle = [JiraTaskCapture]::GetForegroundWindow()
    $rect = New-Object JiraTaskCapture+Rect
    if (-not [JiraTaskCapture]::GetWindowRect($handle, [ref]$rect)) {
        throw '앞에 있는 창의 크기를 읽지 못했습니다. -Window 없이 화면 전체로 찍어 보세요.'
    }
    $x = $rect.Left
    $y = $rect.Top
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
} else {
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $x = $bounds.X
    $y = $bounds.Y
    $width = $bounds.Width
    $height = $bounds.Height
}

if ($width -le 0 -or $height -le 0) {
    throw "찍을 영역이 비어 있습니다($width x $height)."
}

$bitmap = New-Object System.Drawing.Bitmap $width, $height
try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($x, $y, 0, 0, $bitmap.Size)
    } finally {
        $graphics.Dispose()
    }

    $path = Join-Path $imageDirectory "$key.png"
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $bitmap.Dispose()
}

Write-Output ("  저장했습니다: {0}  ({1} x {2})" -f $path, $width, $height)
Write-Output '  index.html 을 새로 고치면 그 자리에 들어가 있습니다.'
Write-Output ''
