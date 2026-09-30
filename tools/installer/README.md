# Shark Hub Installer

A one-window Windows app for putting Shark Hub on a BYD Shark 6 for the first time, over Wi-Fi ADB.
It walks the owner through getting the car into ADB mode (portrait screen, Settings → System →
Version, tap the **Factory Reset** text about 10 times, top button on), finds the car on the network if they
don't know its IP, then installs the APK they choose.

- **One portable exe** (`dist\SharkHubInstaller.exe`, ~300 KB). No installer, no runtime to add:
  it's .NET Framework 4.8 / WPF, which every Windows 10 and 11 PC already has.
- **The app to install:** *Latest Shark Hub* downloads the newest release's APK from GitHub;
  *Choose APK file…* picks one on the PC. Dropping an `.apk` on the window, or on the exe, works too.
- **adb** is Google's own: the one next to the exe, the Android SDK's, one on `PATH`, or — if the PC
  has none — Android platform-tools downloaded from `dl.google.com` into
  `%LOCALAPPDATA%\SharkHubInstaller` on first use (~7 MB). Last IP and folder are remembered there too.
- **Find the car:** tries every address on the PC's own networks (a /24 at most per adapter;
  virtual adapters skipped) on port 5555, 64 at a time, and sends ADB's opening CNXN to anything that
  answers — a car with ADB on replies AUTH straight away. No key is offered, so the car never shows
  its Allow prompt for a scan. One hit fills the IP box; several show as tappable chips.
- **Install flow:** `adb connect <ip>:5555` → waits (up to 90 s) for the car's *Allow USB debugging?*
  → `adb install -r -g` → opens Shark Hub on the car. Common failures (signature mismatch, downgrade,
  storage, not an APK, unreachable car) get a plain-English line; *Details* shows the raw adb output.

## Build

```powershell
powershell -ExecutionPolicy Bypass -File tools\installer\build.ps1
```

Uses the C# compiler that ships with Windows (`%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe`,
so the source is C# 5: no string interpolation, no `?.`) and Python + Pillow for the icon
(`make_assets.py`: the fin logo and the launcher art with rounded transparent corners). The script
ends by rendering the window off-screen as a check. `build\` and `dist\` are gitignored.

The window is `Installer.xaml`, embedded and loaded at runtime, so all the styling is there;
`SharkHubInstaller.cs` wires it up by element name. The palette is Shark Hub's Deep Sea theme.

`SharkHubInstaller.exe --snapshot out.png [idle|ready|found|allow|busy|done] [some.apk]` renders the
window to a PNG and exits, for pictures and checks. `--candidates out.txt` lists the address ranges a
scan would cover (sends nothing) and `--probe <ip> out.txt` handshakes one address (adb / open / none).

## GitHub releases

*Latest Shark Hub* reads `https://api.github.com/repos/Peacemaker105/SharkHub/releases/latest` and
takes the `.apk` asset whose name contains "sharkhub" but not "installer", so the phone installer's
APK on the same release is skipped. It falls back to the first `.apk`. The repo is
`Store.DefaultRepo` in `SharkHubInstaller.cs`. A fork can point it elsewhere without a rebuild: put a
file `SharkHubInstaller.txt` next to the exe containing `repo=owner/name`. A pre-release doesn't
count: GitHub's `releases/latest` only returns full releases.

## Before handing it to other people

It isn't code-signed, so Windows SmartScreen shows *Windows protected your PC* on a downloaded copy
(More info → Run anyway), and a PC with Smart App Control on may refuse it. Signing it (for example
with Azure Trusted Signing) removes both.
