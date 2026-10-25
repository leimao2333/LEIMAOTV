<#
.SYNOPSIS
    把数字小键盘映射成遥控器按键，转发给正在运行的 Android 模拟器。

.DESCRIPTION
    映射（NumLock 要打开）：
        小键盘 4 / 8 / 2 / 6  →  左 / 上 / 下 / 右
        小键盘 5              →  OK（DPAD_CENTER）
        Esc                   →  返回（BACK）

    只在模拟器窗口在前台时接管：否则 Esc 会被全局吃掉，别的程序里根本没法用。
    判定方式是「前台窗口属于 emulator.exe / qemu-system-* 进程」——模拟器窗口标题
    实测是空的，按标题匹配根本不会命中（第一版就是这个坑）；标题只作为兜底信号。

    按键走 adb server 的本地 TCP 协议（127.0.0.1:5037），一条常驻 shell 连接下发，
    单键约 1ms。不起 adb.exe 进程：那样一次几十上百毫秒，连按方向键会明显滞涩。

.EXAMPLE
    PowerShell 里执行（NumLock 打开，模拟器已启动）：
        powershell -ExecutionPolicy Bypass -File scripts\emu-keymap.ps1

.EXAMPLE
    # 只测通道：下发 下/右/OK/返回 四个键后退出（屏幕上应能看到焦点移动并退一页）
        powershell -ExecutionPolicy Bypass -File scripts\emu-keymap.ps1 -SelfTest
#>
param(
    [string]$Serial = "emulator-5554",
    [string[]]$WindowKeywords = @("emulator", "legado_test", "Android Emulator"),
    [switch]$SelfTest,
    # 打开后每拦一个键就在控制台打一行，用来确认映射确实在生效
    [switch]$LogKeys
)

$ErrorActionPreference = "Stop"
$AdbPort = 5037

# ---------------------------------------------------------------- adb 传输层

$script:tcp = $null
$script:stream = $null

function Write-AdbRequest {
    param($stream, [string]$payload)
    $bytes = [Text.Encoding]::ASCII.GetBytes($payload)
    $head = [Text.Encoding]::ASCII.GetBytes(("{0:X4}" -f $bytes.Length))
    $stream.Write($head, 0, 4)
    $stream.Write($bytes, 0, $bytes.Length)
    $stream.Flush()
}

function Read-AdbReply {
    param($stream)
    $buf = New-Object byte[] 4
    $n = $stream.Read($buf, 0, 4)
    if ($n -lt 4) { return "FAIL" }
    return [Text.Encoding]::ASCII.GetString($buf, 0, 4)
}

function Connect-Emulator {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $tcp.Connect("127.0.0.1", $AdbPort)
    $stream = $tcp.GetStream()
    Write-AdbRequest $stream "host:transport:$Serial"
    if ((Read-AdbReply $stream) -ne "OKAY") {
        $tcp.Close()
        throw "adb 不接受设备 $Serial，先确认 adb devices 里有它"
    }
    # 空命令 = 交互式 shell：后续每行 keyevent 复用这一条连接
    Write-AdbRequest $stream "shell:"
    if ((Read-AdbReply $stream) -ne "OKAY") {
        $tcp.Close()
        throw "无法打开 $Serial 的 shell"
    }
    $script:tcp = $tcp
    $script:stream = $stream
}

function Send-Key([int]$code) {
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        try {
            if ($null -eq $script:tcp -or -not $script:tcp.Connected) { Connect-Emulator }
            $line = [Text.Encoding]::ASCII.GetBytes("input keyevent $code`n")
            $script:stream.Write($line, 0, $line.Length)
            $script:stream.Flush()
            return
        } catch {
            if ($attempt -eq 1) { Write-Warning "按键下发失败：$($_.Exception.Message)"; return }
            try { if ($script:tcp) { $script:tcp.Close() } } catch { }
            $script:tcp = $null
        }
    }
}

try {
    Connect-Emulator
} catch {
    $adbExe = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $adbExe) {
        $cand = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
        if (Test-Path $cand) { $adbExe = $cand }
    }
    if ($adbExe) {
        & $adbExe start-server | Out-Null
        Start-Sleep -Milliseconds 800
        Connect-Emulator
    } else {
        throw "连不上 adb server，也找不到 adb.exe：先启动模拟器，并把 platform-tools 放进 PATH"
    }
}

if ($SelfTest) {
    Write-Output "通道自检：依次下发 下 / 右 / OK / 返回"
    foreach ($k in @(20, 22, 23, 4)) {
        Send-Key $k
        Start-Sleep -Milliseconds 250
    }
    Write-Output "自检完成"
    return
}

# ---------------------------------------------------------------- 键盘钩子

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class EmuKeys
{
    public delegate IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    public struct KBDLLHOOKSTRUCT
    {
        public uint vkCode;
        public uint scanCode;
        public uint flags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    public static extern IntPtr SetWindowsHookEx(int idHook, HookProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll", SetLastError = true)]
    public static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    public static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);

    public static string ForegroundTitle()
    {
        var sb = new StringBuilder(512);
        GetWindowText(GetForegroundWindow(), sb, sb.Capacity);
        return sb.ToString();
    }

    public static KBDLLHOOKSTRUCT ReadStruct(IntPtr lParam)
    {
        return (KBDLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(KBDLLHOOKSTRUCT));
    }
}
"@

$WH_KEYBOARD_LL = 13
$WM_KEYDOWN = 0x0100
$WM_SYSKEYDOWN = 0x0104

$map = @{
    0x64 = 21   # 小键盘 4 → 左
    0x68 = 19   # 小键盘 8 → 上
    0x62 = 20   # 小键盘 2 → 下
    0x66 = 22   # 小键盘 6 → 右
    0x65 = 23   # 小键盘 5 → OK
    0x1B = 4    # Esc      → 返回
}

$script:lastKey = 0
$script:lastAt = [DateTime]::MinValue

$handler = [EmuKeys+HookProc] {
    param($nCode, $wParam, $lParam)
    if ($nCode -ge 0) {
        $msg = [int]$wParam
        if ($msg -eq $WM_KEYDOWN -or $msg -eq $WM_SYSKEYDOWN) {
            $vk = [int]([EmuKeys]::ReadStruct($lParam).vkCode)
            if ($map.ContainsKey($vk)) {
                $isEmulator = $false
                # 先看前台窗口属于哪个进程：模拟器窗口标题是空的，靠标题判不出来
                try {
                    $fg = [EmuKeys]::GetForegroundWindow()
                    $fgPid = [uint32]0
                    [EmuKeys]::GetWindowThreadProcessId($fg, [ref]$fgPid) | Out-Null
                    if ($fgPid -ne 0) {
                        $pname = (Get-Process -Id $fgPid -ErrorAction Stop).ProcessName
                        if ($pname -match '^(emulator|qemu-system)') { $isEmulator = $true }
                    }
                } catch { }
                if (-not $isEmulator) {
                    $title = [EmuKeys]::ForegroundTitle()
                    foreach ($k in $WindowKeywords) {
                        if ($title -and $title.ToLower().Contains($k.ToLower())) { $isEmulator = $true; break }
                    }
                }
                if ($isEmulator) {
                    # 按住不放时 KeyDown 会连发，节流到 60ms 一档
                    $now = [DateTime]::UtcNow
                    if ($vk -eq $script:lastKey -and ($now - $script:lastAt).TotalMilliseconds -lt 60) {
                        return [IntPtr]1
                    }
                    $script:lastKey = $vk
                    $script:lastAt = $now
                    if ($LogKeys) {
                        $names = @{ 21 = '←左'; 19 = '↑上'; 20 = '↓下'; 22 = '→右'; 23 = 'OK'; 4 = '返回' }
                        Write-Host ("  按键 " + $names[$map[$vk]])
                    }
                    Send-Key $map[$vk]
                    return [IntPtr]1    # 拦下：否则 Esc 会让模拟器再处理一次返回
                }
            }
        }
    }
    return [EmuKeys]::CallNextHookEx([IntPtr]::Zero, $nCode, $wParam, $lParam)
}

$hookId = [EmuKeys]::SetWindowsHookEx($WH_KEYBOARD_LL, $handler, [IntPtr]::Zero, 0)
if ($hookId -eq [IntPtr]::Zero) {
    throw "键盘钩子安装失败（错误码 $([Runtime.InteropServices.Marshal]::GetLastWin32Error())）"
}

Write-Host "小键盘映射已启用（NumLock 请打开）：4/8/2/6 方向，5=OK，Esc=返回"
Write-Host "仅在模拟器（emulator/qemu 进程窗口）前台时接管；关掉本窗口即停止。"

try {
    Add-Type -AssemblyName System.Windows.Forms

    # 低级键盘钩子装在哪个线程就在哪个线程回调 —— 那个线程必须一直跑消息循环，
    # 用 Start-Sleep 死等是收不到事件的。这里借 WinForms 的消息泵。
    $watch = New-Object System.Windows.Forms.Timer
    $watch.Interval = 3000
    $watch.add_Tick({
        try {
            if ($null -eq $script:tcp -or -not $script:tcp.Connected) { Connect-Emulator }
        } catch {
            Write-Warning "adb 连接掉了：$($_.Exception.Message)"
        }
    })
    $watch.Start()

    [System.Windows.Forms.Application]::Run()
} finally {
    [EmuKeys]::UnhookWindowsHookEx($hookId) | Out-Null
    try { if ($script:tcp) { $script:tcp.Close() } } catch { }
    Write-Host "已停止。"
}
