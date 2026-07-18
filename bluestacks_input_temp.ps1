param(
    [string]$Keys,
    [int]$Wheel = 0,
    [int]$ClickX = -1,
    [int]$ClickY = -1,
    [int]$DragFromX = -1,
    [int]$DragFromY = -1,
    [int]$DragToX = -1,
    [int]$DragToY = -1
)
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class FocusWindow {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int command);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, int data, UIntPtr extraInfo);
}
"@
$player = Get-Process -Id 2132
[FocusWindow]::ShowWindow($player.MainWindowHandle, 3) | Out-Null
[FocusWindow]::SetForegroundWindow($player.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 300
if ($Keys) {
    [System.Windows.Forms.SendKeys]::SendWait($Keys)
}
if ($ClickX -ge 0 -and $ClickY -ge 0) {
    $rect = New-Object FocusWindow+RECT
    [FocusWindow]::GetWindowRect($player.MainWindowHandle, [ref]$rect) | Out-Null
    [FocusWindow]::SetCursorPos($rect.Left + $ClickX, $rect.Top + $ClickY) | Out-Null
    [FocusWindow]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    [FocusWindow]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
}
if ($Wheel -ne 0) {
    $rect = New-Object FocusWindow+RECT
    [FocusWindow]::GetWindowRect($player.MainWindowHandle, [ref]$rect) | Out-Null
    [FocusWindow]::SetCursorPos($rect.Left + 700, $rect.Top + 430) | Out-Null
    [FocusWindow]::mouse_event(0x0800, 0, 0, $Wheel, [UIntPtr]::Zero)
}
if ($DragFromX -ge 0 -and $DragFromY -ge 0 -and $DragToX -ge 0 -and $DragToY -ge 0) {
    $rect = New-Object FocusWindow+RECT
    [FocusWindow]::GetWindowRect($player.MainWindowHandle, [ref]$rect) | Out-Null
    [FocusWindow]::SetCursorPos($rect.Left + $DragFromX, $rect.Top + $DragFromY) | Out-Null
    [FocusWindow]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    for ($step = 1; $step -le 12; $step++) {
        $x = $rect.Left + $DragFromX + (($DragToX - $DragFromX) * $step / 12)
        $y = $rect.Top + $DragFromY + (($DragToY - $DragFromY) * $step / 12)
        [FocusWindow]::SetCursorPos([int]$x, [int]$y) | Out-Null
        Start-Sleep -Milliseconds 20
    }
    [FocusWindow]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
}
